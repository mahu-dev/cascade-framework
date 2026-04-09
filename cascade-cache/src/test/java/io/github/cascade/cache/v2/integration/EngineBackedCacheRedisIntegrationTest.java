package io.github.cascade.cache.v2.integration;

import io.github.cascade.cache.v2.api.Cache;
import io.github.cascade.cache.v2.consistency.RedisVersionManager;
import io.github.cascade.cache.v2.engine.EngineBackedCache;
import io.github.cascade.cache.v2.loader.DistLockCoordinator;
import io.github.cascade.cache.v2.store.model.CacheRecord;
import io.github.cascade.cache.v2.observability.CacheMetricsCollector;
import io.github.cascade.cache.v2.policy.CachePolicy;
import io.github.cascade.cache.v2.policy.SyncMode;
import io.github.cascade.cache.v2.store.l1.CaffeineL1Store;
import io.github.cascade.cache.v2.store.l2.RedissonL2Store;
import io.github.cascade.cache.v2.consistency.RedisInvalidationBus;
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

        Cache<String, String> nodeA = createCache(
                clientA, cacheName, keyPrefix, topicPrefix, SyncMode.INVALIDATE, "node-A", String.class
        );
        Cache<String, String> nodeB = createCache(
                clientB, cacheName, keyPrefix, topicPrefix, SyncMode.INVALIDATE, "node-B", String.class
        );
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

        Cache<String, String> nodeA = createCache(
                clientA, cacheName, keyPrefix, topicPrefix, SyncMode.UPDATE, "node-A", String.class
        );
        Cache<String, String> nodeB = createCache(
                clientB, cacheName, keyPrefix, topicPrefix, SyncMode.UPDATE, "node-B", String.class
        );
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

    @Test
    void shouldSyncPojoValueInUpdateModeWithoutClassCastException() {
        String cacheName = "it-update-pojo-" + UUID.randomUUID();
        String keyPrefix = "it:cascade:" + UUID.randomUUID() + ":";
        String topicPrefix = "it:cascade:sync:" + UUID.randomUUID() + ":";

        RedissonClient clientA = newClient();
        RedissonClient clientB = newClient();
        closers.add(clientA::shutdown);
        closers.add(clientB::shutdown);

        Cache<String, UserProfile> nodeA = createCache(
                clientA, cacheName, keyPrefix, topicPrefix, SyncMode.UPDATE, "node-A", UserProfile.class
        );
        Cache<String, UserProfile> nodeB = createCache(
                clientB, cacheName, keyPrefix, topicPrefix, SyncMode.UPDATE, "node-B", UserProfile.class
        );
        closers.add(nodeA::close);
        closers.add(nodeB::close);

        nodeA.put("u1", new UserProfile("u1", "Alice-v1"));
        assertTrue(waitUntil(
                () -> "Alice-v1".equals(nodeB.get("u1").map(UserProfile::name).orElse(null)),
                Duration.ofMillis(500)
        ), "POJO 初次写入未在500ms内同步");

        nodeA.put("u1", new UserProfile("u1", "Alice-v2"));
        assertTrue(waitUntil(
                () -> "Alice-v2".equals(nodeB.get("u1").map(UserProfile::name).orElse(null)),
                Duration.ofMillis(500)
        ), "UPDATE 模式下 POJO 跨节点同步失败或出现类型转换异常");
    }

    @Test
    void shouldBackfillL1AfterBatchGetAllFromRedisL2() {
        String cacheName = "it-batch-getall-" + UUID.randomUUID();
        String keyPrefix = "it:cascade:" + UUID.randomUUID() + ":";
        String topicPrefix = "it:cascade:sync:" + UUID.randomUUID() + ":";

        RedissonClient writerClient = newClient();
        RedissonClient readerClient = newClient();
        closers.add(writerClient::shutdown);
        closers.add(readerClient::shutdown);

        EngineBackedCache<String, String> writer = createCache(
                writerClient, cacheName, keyPrefix, topicPrefix, SyncMode.NONE, "node-writer", String.class
        );
        EngineBackedCache<String, String> reader = createCache(
                readerClient, cacheName, keyPrefix, topicPrefix, SyncMode.NONE, "node-reader", String.class
        );
        closers.add(writer::close);
        closers.add(reader::close);

        writer.put("k1", "v1");
        writer.put("k2", "v2");

        assertEquals(java.util.Map.of("k1", "v1", "k2", "v2"), reader.getAll(List.of("k1", "k2")));
        assertEquals(0L, reader.statsSnapshot().l1Hit(), "首次批量读取前L1为空，不应计L1命中");
        assertEquals(2L, reader.statsSnapshot().l2Hit(), "首次批量读取应命中L2");
        assertEquals(2L, reader.statsSnapshot().backfillL1(), "首次批量读取命中L2后应回填L1");

        assertEquals(java.util.Map.of("k1", "v1", "k2", "v2"), reader.getAll(List.of("k1", "k2")));
        assertEquals(2L, reader.statsSnapshot().l1Hit(), "二次批量读取应直接命中L1");
        assertEquals(2L, reader.statsSnapshot().l2Hit(), "二次批量读取不应新增L2命中");
    }

    private <V> EngineBackedCache<String, V> createCache(RedissonClient client,
                                                         String cacheName,
                                                         String keyPrefix,
                                                         String topicPrefix,
                                                         SyncMode syncMode,
                                                         String nodeId,
                                                         Class<V> valueType) {
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
                new CaffeineL1Store<String, V>(10_000, false),
                new RedissonL2Store<String, V>(cacheName, client, keyPrefix),
                null,
                new RedisInvalidationBus<String>(client, topicPrefix),
                new RedisVersionManager<String>(cacheName, client, keyPrefix),
                DistLockCoordinator.noop(),
                nodeId,
                valueType,
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

    private record UserProfile(String id, String name) {
    }
}
