package io.github.cascade.cache.v2.integration;

import io.github.cascade.cache.api.Cache;
import io.github.cascade.cache.v2.consistency.RedisVersionManager;
import io.github.cascade.cache.v2.core.EngineBackedCache;
import io.github.cascade.cache.v2.loader.DistLockCoordinator;
import io.github.cascade.cache.v2.model.CacheRecord;
import io.github.cascade.cache.v2.observability.CacheMetricsCollector;
import io.github.cascade.cache.v2.policy.CachePolicy;
import io.github.cascade.cache.v2.policy.SyncMode;
import io.github.cascade.cache.v2.store.CaffeineL1Store;
import io.github.cascade.cache.v2.store.RedissonL2Store;
import io.github.cascade.cache.v2.sync.RedisInvalidationBus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class EngineBackedCacheRedisIntegrationTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.2-alpine"))
            .withExposedPorts(6379);

    private final List<AutoCloseable> closers = new ArrayList<>();

    @AfterEach
    void cleanup() throws Exception {
        for (AutoCloseable closer : closers) {
            closer.close();
        }
        closers.clear();
    }

    @Test
    void shouldConvergeWithin500msAfterPut() {
        String cacheName = "it-user-" + UUID.randomUUID();
        String keyPrefix = "it:cascade:" + UUID.randomUUID() + ":";
        String topicPrefix = "it:cascade:sync:" + UUID.randomUUID() + ":";

        RedissonClient clientA = newClient();
        RedissonClient clientB = newClient();
        closers.add(clientA::shutdown);
        closers.add(clientB::shutdown);

        Cache<String, String> nodeA = createCache(clientA, cacheName, keyPrefix, topicPrefix, SyncMode.INVALIDATE, "node-A");
        Cache<String, String> nodeB = createCache(clientB, cacheName, keyPrefix, topicPrefix, SyncMode.INVALIDATE, "node-B");
        closers.add(nodeA::close);
        closers.add(nodeB::close);

        nodeA.put("k1", "v1");
        assertEquals("v1", nodeB.get("k1").orElse(null));

        long start = System.nanoTime();
        nodeA.put("k1", "v2");

        boolean converged = waitUntil(() -> "v2".equals(nodeB.get("k1").orElse(null)), Duration.ofMillis(500));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(converged, "500ms内未收敛到新值，elapsed=" + elapsedMs + "ms");
    }

    @Test
    void shouldIgnoreOutOfOrderUpdateEvent() {
        String cacheName = "it-update-" + UUID.randomUUID();
        String keyPrefix = "it:cascade:" + UUID.randomUUID() + ":";
        String topicPrefix = "it:cascade:sync:" + UUID.randomUUID() + ":";

        RedissonClient clientA = newClient();
        RedissonClient clientB = newClient();
        closers.add(clientA::shutdown);
        closers.add(clientB::shutdown);

        Cache<String, String> nodeA = createCache(clientA, cacheName, keyPrefix, topicPrefix, SyncMode.UPDATE, "node-A");
        Cache<String, String> nodeB = createCache(clientB, cacheName, keyPrefix, topicPrefix, SyncMode.UPDATE, "node-B");
        closers.add(nodeA::close);
        closers.add(nodeB::close);

        nodeA.put("k2", "seed");
        assertEquals("seed", nodeB.get("k2").orElse(null));

        RedisInvalidationBus<String> externalBus = new RedisInvalidationBus<>(clientA, topicPrefix);
        externalBus.start();
        closers.add(externalBus::stop);

        long now = System.currentTimeMillis();
        CacheRecord<String> fresh = new CacheRecord<>("fresh", 20L, now, now + 60_000, now + 120_000, "external");
        CacheRecord<String> stale = new CacheRecord<>("stale", 10L, now, now + 60_000, now + 120_000, "external");

        externalBus.publishUpdate(cacheName, "k2", fresh, String.class.getName(), "external").join();
        assertTrue(waitUntil(() -> "fresh".equals(nodeB.get("k2").orElse(null)), Duration.ofMillis(500)));

        externalBus.publishUpdate(cacheName, "k2", stale, String.class.getName(), "external").join();
        Thread.yield();
        assertEquals("fresh", nodeB.get("k2").orElse(null), "乱序旧事件不应覆盖新版本");
    }

    private Cache<String, String> createCache(RedissonClient client,
                                              String cacheName,
                                              String keyPrefix,
                                              String topicPrefix,
                                              SyncMode syncMode,
                                              String nodeId) {
        CachePolicy policy = CachePolicy.builder()
                .l1Enabled(true)
                .l2Enabled(true)
                .hardTtlSeconds(1800)
                .softTtlSeconds(300)
                .autoRefreshEnabled(false)
                .syncMode(syncMode)
                .syncUpdateEnabled(syncMode == SyncMode.UPDATE)
                .singleFlightEnabled(true)
                .distributedLockEnabled(false)
                .build();

        return new EngineBackedCache<>(
                cacheName,
                policy,
                new CaffeineL1Store<>(10_000, false),
                new RedissonL2Store<>(cacheName, client, keyPrefix),
                null,
                new RedisInvalidationBus<>(client, topicPrefix),
                new RedisVersionManager<>(cacheName, client, keyPrefix),
                DistLockCoordinator.noop(),
                nodeId,
                CacheMetricsCollector.create(cacheName, null)
        );
    }

    private RedissonClient newClient() {
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        return Redisson.create(config);
    }

    private boolean waitUntil(BooleanSupplier condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return condition.getAsBoolean();
            }
        }
        return condition.getAsBoolean();
    }
}
