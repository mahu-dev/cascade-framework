package io.github.cascade.cache.v2.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cascade.cache.v2.consistency.LocalVersionManager;
import io.github.cascade.cache.v2.consistency.VersionManager;
import io.github.cascade.cache.v2.loader.DistLockCoordinator;
import io.github.cascade.cache.v2.store.model.CacheRecord;
import io.github.cascade.cache.v2.consistency.InvalidationEvent;
import io.github.cascade.cache.v2.policy.CachePolicy;
import io.github.cascade.cache.v2.policy.LockFailureStrategy;
import io.github.cascade.cache.v2.policy.SyncMode;
import io.github.cascade.cache.v2.store.l1.CaffeineL1Store;
import io.github.cascade.cache.v2.store.l2.L2CacheStore;
import io.github.cascade.cache.v2.consistency.InvalidationBus;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineBackedCacheTest {

    @Test
    void shouldBackfillL1WhenL2Hit() {
        CachePolicy policy = CachePolicy.builder()
                .l1Enabled(true)
                .l2Enabled(true)
                .autoRefreshEnabled(false)
                .syncMode(SyncMode.NONE)
                .build();
        CaffeineL1Store<String, String> l1 = new CaffeineL1Store<>(100, false);
        InMemoryL2Store<String, String> l2 = new InMemoryL2Store<>();
        VersionManager<String> versionManager = new LocalVersionManager<>();
        TestBus<String> bus = new TestBus<>();

        long now = System.currentTimeMillis();
        l2.put("k1", new CacheRecord<>("v1", 1L, now, now + 60_000, now + 60_000, "seed"), 60);

        EngineBackedCache<String, String> cache = new EngineBackedCache<>(
                "user", policy, l1, l2, null, bus, versionManager, DistLockCoordinator.noop(), "node-1"
        );
        try {
            Optional<String> value = cache.get("k1");
            assertTrue(value.isPresent());
            assertEquals("v1", value.get());
            assertTrue(l1.get("k1").isPresent(), "L2命中后必须回填L1");
        } finally {
            cache.close();
        }
    }

    @Test
    void shouldFallbackToL2WhenL1RecordHardExpired() {
        CachePolicy policy = CachePolicy.builder()
                .l1Enabled(true)
                .l2Enabled(true)
                .autoRefreshEnabled(false)
                .syncMode(SyncMode.NONE)
                .build();
        CaffeineL1Store<String, String> l1 = new CaffeineL1Store<>(100, false);
        InMemoryL2Store<String, String> l2 = new InMemoryL2Store<>();
        VersionManager<String> versionManager = new LocalVersionManager<>();
        TestBus<String> bus = new TestBus<>();

        long now = System.currentTimeMillis();
        l1.put("k-expired", new CacheRecord<>("stale", 1L, now - 30_000, now - 20_000, now - 1, "node-1"));
        l2.put("k-expired", new CacheRecord<>("fresh", 2L, now, now + 60_000, now + 60_000, "seed"), 60);

        EngineBackedCache<String, String> cache = new EngineBackedCache<>(
                "user", policy, l1, l2, null, bus, versionManager, DistLockCoordinator.noop(), "node-1"
        );
        try {
            Optional<String> value = cache.get("k-expired");
            assertTrue(value.isPresent());
            assertEquals("fresh", value.get());
            assertEquals("fresh", l1.get("k-expired").map(CacheRecord::getValue).orElse(null),
                    "L1硬过期后应回退读取L2并回填L1");
        } finally {
            cache.close();
        }
    }

    @Test
    void shouldWriteBackL1AndL2WhenLoadedFromSource() {
        CachePolicy policy = CachePolicy.builder()
                .l1Enabled(true)
                .l2Enabled(true)
                .autoRefreshEnabled(false)
                .syncMode(SyncMode.NONE)
                .build();
        CaffeineL1Store<String, String> l1 = new CaffeineL1Store<>(100, false);
        InMemoryL2Store<String, String> l2 = new InMemoryL2Store<>();
        VersionManager<String> versionManager = new LocalVersionManager<>();
        TestBus<String> bus = new TestBus<>();

        EngineBackedCache<String, String> cache = new EngineBackedCache<>(
                "user", policy, l1, l2, key -> "load-" + key, bus, versionManager, DistLockCoordinator.noop(), "node-1"
        );
        try {
            Optional<String> value = cache.get("k2");
            assertTrue(value.isPresent());
            assertEquals("load-k2", value.get());
            assertEquals("load-k2", l1.get("k2").map(CacheRecord::getValue).orElse(null));
            assertEquals("load-k2", l2.get("k2").map(CacheRecord::getValue).orElse(null));
            assertEquals(1L, cache.statsSnapshot().backfillL2(), "源加载后应写回L2");
        } finally {
            cache.close();
        }
    }

    @Test
    void shouldCoalesceConcurrentLoadsWithSingleFlight() {
        CachePolicy policy = CachePolicy.builder()
                .l1Enabled(true)
                .l2Enabled(false)
                .autoRefreshEnabled(false)
                .singleFlightEnabled(true)
                .distributedLockEnabled(false)
                .syncMode(SyncMode.NONE)
                .build();
        CaffeineL1Store<String, String> l1 = new CaffeineL1Store<>(100, false);
        VersionManager<String> versionManager = new LocalVersionManager<>();
        TestBus<String> bus = new TestBus<>();
        AtomicInteger sourceCalls = new AtomicInteger(0);

        EngineBackedCache<String, String> cache = new EngineBackedCache<>(
                "user",
                policy,
                l1,
                null,
                key -> {
                    try {
                        Thread.sleep(80L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return "v-" + sourceCalls.incrementAndGet();
                },
                bus,
                versionManager,
                DistLockCoordinator.noop(),
                "node-1"
        );
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<CompletableFuture<String>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                futures.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return cache.get("hot-key").orElse(null);
                }, pool));
            }
            start.countDown();

            List<String> values = futures.stream().map(CompletableFuture::join).toList();
            assertTrue(values.stream().allMatch("v-1"::equals), "并发请求应返回同一加载结果");
            assertEquals(1, sourceCalls.get(), "singleflight应将并发加载合并为一次");
        } finally {
            pool.shutdownNow();
            cache.close();
        }
    }

    @Test
    void shouldIgnoreOutOfOrderInvalidationEvent() {
        CachePolicy policy = CachePolicy.builder()
                .l1Enabled(true)
                .l2Enabled(false)
                .autoRefreshEnabled(false)
                .syncMode(SyncMode.INVALIDATE)
                .build();
        CaffeineL1Store<String, String> l1 = new CaffeineL1Store<>(100, false);
        VersionManager<String> versionManager = new LocalVersionManager<>();
        TestBus<String> bus = new TestBus<>();

        long now = System.currentTimeMillis();
        l1.put("k3", new CacheRecord<>("new-value", 10L, now, now + 60_000, now + 60_000, "node-1"));

        EngineBackedCache<String, String> cache = new EngineBackedCache<>(
                "user", policy, l1, null, null, bus, versionManager, DistLockCoordinator.noop(), "node-1"
        );
        try {
            bus.emitInvalidation("user", "k3", 5L, "node-2");
            assertTrue(l1.get("k3").isPresent(), "旧版本事件不应淘汰新值");

            bus.emitInvalidation("user", "k3", 11L, "node-2");
            assertFalse(l1.get("k3").isPresent(), "新版本事件应淘汰本地L1");
        } finally {
            cache.close();
        }
    }

    @Test
    void shouldApplyUpdateEventWhenSyncModeIsUpdate() {
        CachePolicy policy = CachePolicy.builder()
                .l1Enabled(true)
                .l2Enabled(false)
                .autoRefreshEnabled(false)
                .syncMode(SyncMode.UPDATE)
                .syncUpdateEnabled(true)
                .build();
        CaffeineL1Store<String, String> l1 = new CaffeineL1Store<>(100, false);
        VersionManager<String> versionManager = new LocalVersionManager<>();
        TestBus<String> bus = new TestBus<>();

        EngineBackedCache<String, String> cache = new EngineBackedCache<>(
                "user", policy, l1, null, null, bus, versionManager, DistLockCoordinator.noop(), "node-1"
        );
        try {
            CacheRecord<String> oldRecord = new CacheRecord<>("old", 1L, 1L, 100L, 100L, "node-2");
            l1.put("k4", oldRecord);

            CacheRecord<String> newRecord = new CacheRecord<>(
                    "new", 2L, System.currentTimeMillis(), System.currentTimeMillis() + 60_000,
                    System.currentTimeMillis() + 120_000, "node-2"
            );
            bus.emitUpdate("user", "k4", newRecord, String.class.getName(), "node-2");

            assertEquals("new", l1.get("k4").map(CacheRecord::getValue).orElse(null));
        } finally {
            cache.close();
        }
    }

    @Test
    void shouldApplyUpdateEventForPojoWithoutClassCastException() {
        CachePolicy policy = CachePolicy.builder()
                .l1Enabled(true)
                .l2Enabled(false)
                .autoRefreshEnabled(false)
                .syncMode(SyncMode.UPDATE)
                .syncUpdateEnabled(true)
                .build();
        CaffeineL1Store<String, UserProfile> l1 = new CaffeineL1Store<>(100, false);
        VersionManager<String> versionManager = new LocalVersionManager<>();
        TestBus<String> bus = new TestBus<>();

        EngineBackedCache<String, UserProfile> cache = new EngineBackedCache<>(
                "user", policy, l1, null, null, bus, versionManager, DistLockCoordinator.noop(), "node-1",
                UserProfile.class, null
        );
        try {
            long now = System.currentTimeMillis();
            CacheRecord<UserProfile> newRecord = new CacheRecord<>(
                    new UserProfile("u1", "Alice-v2"),
                    2L,
                    now,
                    now + 60_000,
                    now + 120_000,
                    "node-2"
            );
            bus.emitUpdate("user", "u1", newRecord, UserProfile.class.getName(), "node-2");

            UserProfile value = l1.get("u1").map(CacheRecord::getValue).orElse(null);
            assertEquals("Alice-v2", value != null ? value.name() : null);
        } finally {
            cache.close();
        }
    }

    @Test
    void shouldFallbackToInvalidateWhenUpdateTypeMismatch() {
        CachePolicy policy = CachePolicy.builder()
                .l1Enabled(true)
                .l2Enabled(false)
                .autoRefreshEnabled(false)
                .syncMode(SyncMode.UPDATE)
                .syncUpdateEnabled(true)
                .build();
        CaffeineL1Store<String, String> l1 = new CaffeineL1Store<>(100, false);
        VersionManager<String> versionManager = new LocalVersionManager<>();
        TestBus<String> bus = new TestBus<>();

        EngineBackedCache<String, String> cache = new EngineBackedCache<>(
                "user", policy, l1, null, null, bus, versionManager, DistLockCoordinator.noop(), "node-1",
                String.class, null
        );
        try {
            long now = System.currentTimeMillis();
            l1.put("k5", new CacheRecord<>("old", 1L, now, now + 60_000, now + 60_000, "node-1"));
            CacheRecord<String> update = new CacheRecord<>("new", 2L, now, now + 60_000, now + 60_000, "node-2");

            bus.emitUpdate("user", "k5", update, Integer.class.getName(), "node-2");

            assertFalse(l1.get("k5").isPresent(), "UPDATE类型不匹配时应降级为失效");
            assertEquals(1L, cache.statsSnapshot().syncUpdateFallback());
        } finally {
            cache.close();
        }
    }

    @Test
    void shouldFallbackToInvalidateWhenUpdatePayloadTooLarge() {
        CachePolicy policy = CachePolicy.builder()
                .l1Enabled(true)
                .l2Enabled(false)
                .autoRefreshEnabled(false)
                .syncMode(SyncMode.UPDATE)
                .syncUpdateEnabled(true)
                .syncUpdateMaxPayloadBytes(4)
                .build();
        CaffeineL1Store<String, String> l1 = new CaffeineL1Store<>(100, false);
        VersionManager<String> versionManager = new LocalVersionManager<>();
        TestBus<String> bus = new TestBus<>();

        EngineBackedCache<String, String> cache = new EngineBackedCache<>(
                "user", policy, l1, null, null, bus, versionManager, DistLockCoordinator.noop(), "node-1",
                String.class, null
        );
        try {
            cache.put("k6", "payload-too-large");
            assertEquals(InvalidationEvent.Operation.INVALIDATE, bus.lastPublishedOperation,
                    "超限payload应降级为INVALIDATE事件");
        } finally {
            cache.close();
        }
    }

    @Test
    void shouldNotLoadWhenLockUnavailableAndStrictStrategy() {
        CachePolicy policy = CachePolicy.builder()
                .l1Enabled(true)
                .l2Enabled(false)
                .autoRefreshEnabled(false)
                .syncMode(SyncMode.NONE)
                .singleFlightEnabled(false)
                .distributedLockEnabled(true)
                .lockFailureStrategy(LockFailureStrategy.STRICT)
                .build();
        CaffeineL1Store<String, String> l1 = new CaffeineL1Store<>(100, false);
        VersionManager<String> versionManager = new LocalVersionManager<>();
        TestBus<String> bus = new TestBus<>();
        AtomicInteger sourceCalls = new AtomicInteger(0);
        DistLockCoordinator<String> denyLock = new DistLockCoordinator<>() {
            @Override
            public <T> LockResult<T> withLock(String cacheName,
                                              String key,
                                              long waitMs,
                                              long leaseMs,
                                              java.util.function.Supplier<T> supplier) {
                return LockResult.notAcquired();
            }
        };

        EngineBackedCache<String, String> cache = new EngineBackedCache<>(
                "user", policy, l1, null, k -> "v-" + sourceCalls.incrementAndGet(), bus, versionManager, denyLock, "node-1"
        );
        try {
            assertTrue(cache.get("k7").isEmpty(), "STRICT策略下锁失败应放弃加载");
            assertEquals(0, sourceCalls.get(), "STRICT策略下不应执行加载器");
        } finally {
            cache.close();
        }
    }

    @Test
    void shouldReuseNamespaceVersionWhenL2ClearAlreadyBumped() {
        CachePolicy policy = CachePolicy.builder()
                .l1Enabled(true)
                .l2Enabled(true)
                .autoRefreshEnabled(false)
                .syncMode(SyncMode.INVALIDATE)
                .build();
        CaffeineL1Store<String, String> l1 = new CaffeineL1Store<>(100, false);
        TrackingVersionManager<String> versionManager = new TrackingVersionManager<>();
        TestBus<String> bus = new TestBus<>();
        InMemoryL2Store<String, String> l2 = new InMemoryL2Store<>() {
            @Override
            public void clear() {
                super.clear();
                versionManager.bumpClearVersionExternally();
            }
        };

        EngineBackedCache<String, String> cache = new EngineBackedCache<>(
                "user", policy, l1, l2, null, bus, versionManager, DistLockCoordinator.noop(), "node-1"
        );
        try {
            cache.clear();
            assertEquals(1L, versionManager.currentClearVersion(), "clear后应沿用命名空间版本");
            assertEquals(0, versionManager.nextClearCalls.get(), "L2已完成命名空间bump时不应再次nextClearVersion");
            assertEquals(1L, bus.lastClearVersion, "发布的CLEAR事件版本应与命名空间版本一致");
        } finally {
            cache.close();
        }
    }

    @Test
    void shouldPruneStaleTrackedKeysSoNewHotKeyCanBeTracked() throws Exception {
        CachePolicy policy = CachePolicy.builder()
                .l1Enabled(true)
                .l2Enabled(false)
                .autoRefreshEnabled(true)
                .refreshIntervalSeconds(1)
                .syncMode(SyncMode.NONE)
                .hotKeyAccessThreshold(1)
                .maxTrackedKeys(1)
                .build();
        CaffeineL1Store<String, String> l1 = new CaffeineL1Store<>(100, false);
        VersionManager<String> versionManager = new LocalVersionManager<>();
        TestBus<String> bus = new TestBus<>();

        EngineBackedCache<String, String> cache = new EngineBackedCache<>(
                "user", policy, l1, null, null, bus, versionManager, DistLockCoordinator.noop(), "node-1"
        );
        try {
            cache.get("ghost-1");
            invokeRefreshTrackedKeys(cache);
            cache.get("ghost-2");

            Set<String> tracked = trackedKeys(cache);
            assertEquals(1, tracked.size(), "陈旧key应被及时移出追踪集合");
            assertTrue(tracked.contains("ghost-2"), "新热点key应可进入追踪集合");
            assertFalse(tracked.contains("ghost-1"), "无记录的陈旧key不应长期占用追踪槽位");
        } finally {
            cache.close();
        }
    }

    @SuppressWarnings("unchecked")
    private static Set<String> trackedKeys(EngineBackedCache<String, String> cache) throws Exception {
        Field field = EngineBackedCache.class.getDeclaredField("trackedKeys");
        field.setAccessible(true);
        return (Set<String>) field.get(cache);
    }

    private static void invokeRefreshTrackedKeys(EngineBackedCache<?, ?> cache) throws Exception {
        Method method = EngineBackedCache.class.getDeclaredMethod("refreshTrackedKeys");
        method.setAccessible(true);
        method.invoke(cache);
    }

    private static class InMemoryL2Store<K, V> implements L2CacheStore<K, V> {
        private final ConcurrentMap<K, CacheRecord<V>> data = new ConcurrentHashMap<>();
        private final ConcurrentMap<K, AtomicLong> versions = new ConcurrentHashMap<>();

        @Override
        public Optional<CacheRecord<V>> get(K key) {
            return Optional.ofNullable(data.get(key));
        }

        @Override
        public void put(K key, CacheRecord<V> record, long ttlSeconds) {
            data.put(key, record);
            versions.computeIfAbsent(key, ignored -> new AtomicLong(0L))
                    .updateAndGet(current -> Math.max(current, record.getVersion()));
        }

        @Override
        public void evict(K key) {
            data.remove(key);
        }

        @Override
        public void clear() {
            data.clear();
        }

        @Override
        public long size() {
            return data.size();
        }

        @Override
        public long nextVersion(K key) {
            return versions.computeIfAbsent(key, ignored -> new AtomicLong(0L)).incrementAndGet();
        }

        @Override
        public void close() {
            data.clear();
            versions.clear();
        }
    }

    private static final class TestBus<K> implements InvalidationBus<K> {

        private static final String UPDATE_PAYLOAD_CODEC = "jackson-json-v1";

        private final Map<String, Consumer<InvalidationEvent<K>>> handlers = new ConcurrentHashMap<>();
        private final ObjectMapper objectMapper = new ObjectMapper();
        private volatile InvalidationEvent.Operation lastPublishedOperation;
        private volatile long lastClearVersion;

        @Override
        public CompletableFuture<Void> publishInvalidation(String cacheName, K key, long version, String nodeId) {
            lastPublishedOperation = InvalidationEvent.Operation.INVALIDATE;
            Consumer<InvalidationEvent<K>> handler = handlers.get(cacheName);
            if (handler != null) {
                handler.accept(InvalidationEvent.invalidate(cacheName, key, version, nodeId));
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> publishUpdate(String cacheName,
                                                     K key,
                                                     CacheRecord<?> record,
                                                     String valueTypeName,
                                                     String nodeId) {
            lastPublishedOperation = InvalidationEvent.Operation.UPDATE;
            Consumer<InvalidationEvent<K>> handler = handlers.get(cacheName);
            if (handler != null) {
                InvalidationEvent.UpdatePayload payload = toUpdatePayload(record);
                handler.accept(InvalidationEvent.update(
                        cacheName,
                        key,
                        record != null ? record.getVersion() : 0L,
                        payload,
                        valueTypeName,
                        nodeId
                ));
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> publishClear(String cacheName, long version, String nodeId) {
            lastPublishedOperation = InvalidationEvent.Operation.CLEAR;
            lastClearVersion = version;
            Consumer<InvalidationEvent<K>> handler = handlers.get(cacheName);
            if (handler != null) {
                handler.accept(InvalidationEvent.clear(cacheName, version, nodeId));
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void subscribe(String cacheName, Consumer<InvalidationEvent<K>> handler) {
            handlers.put(cacheName, handler);
        }

        @Override
        public void unsubscribe(String cacheName) {
            handlers.remove(cacheName);
        }

        @Override
        public void start() {
            // no-op
        }

        @Override
        public void stop() {
            handlers.clear();
        }

        @Override
        public boolean isRunning() {
            return true;
        }

        private void emitInvalidation(String cacheName, K key, long version, String nodeId) {
            Consumer<InvalidationEvent<K>> handler = handlers.get(cacheName);
            if (handler != null) {
                handler.accept(InvalidationEvent.invalidate(cacheName, key, version, nodeId));
            }
        }

        private void emitUpdate(String cacheName,
                                K key,
                                CacheRecord<?> record,
                                String valueTypeName,
                                String nodeId) {
            Consumer<InvalidationEvent<K>> handler = handlers.get(cacheName);
            if (handler != null) {
                InvalidationEvent.UpdatePayload payload = toUpdatePayload(record);
                handler.accept(InvalidationEvent.update(
                        cacheName,
                        key,
                        record != null ? record.getVersion() : 0L,
                        payload,
                        valueTypeName,
                        nodeId
                ));
            }
        }

        private InvalidationEvent.UpdatePayload toUpdatePayload(CacheRecord<?> record) {
            if (record == null) {
                return null;
            }
            try {
                return new InvalidationEvent.UpdatePayload(
                        UPDATE_PAYLOAD_CODEC,
                        objectMapper.writeValueAsString(record.getValue()),
                        record.getWriteTimeMs(),
                        record.getSoftExpireAtMs(),
                        record.getHardExpireAtMs(),
                        record.getSourceNodeId()
                );
            } catch (Exception e) {
                throw new IllegalStateException("构建测试UPDATE事件payload失败", e);
            }
        }
    }

    private static final class TrackingVersionManager<K> implements VersionManager<K> {
        private final ConcurrentMap<K, AtomicLong> keyVersions = new ConcurrentHashMap<>();
        private final AtomicLong clearVersion = new AtomicLong(0L);
        private final AtomicInteger nextClearCalls = new AtomicInteger(0);

        @Override
        public long nextVersion(K key) {
            return keyVersions.computeIfAbsent(key, ignored -> new AtomicLong(0L)).incrementAndGet();
        }

        @Override
        public long currentVersion(K key) {
            AtomicLong version = keyVersions.get(key);
            return version == null ? 0L : version.get();
        }

        @Override
        public long nextClearVersion() {
            nextClearCalls.incrementAndGet();
            return clearVersion.incrementAndGet();
        }

        @Override
        public long currentClearVersion() {
            return clearVersion.get();
        }

        private void bumpClearVersionExternally() {
            clearVersion.incrementAndGet();
        }
    }

    private record UserProfile(String id, String name) {
    }
}
