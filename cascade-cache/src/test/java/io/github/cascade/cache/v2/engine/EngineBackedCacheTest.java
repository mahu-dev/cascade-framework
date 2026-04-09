package io.github.cascade.cache.v2.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cascade.cache.v2.consistency.LocalVersionManager;
import io.github.cascade.cache.v2.consistency.VersionManager;
import io.github.cascade.cache.v2.loader.DistLockCoordinator;
import io.github.cascade.cache.v2.store.model.CacheRecord;
import io.github.cascade.cache.v2.consistency.InvalidationEvent;
import io.github.cascade.cache.v2.policy.CachePolicy;
import io.github.cascade.cache.v2.policy.LockFailureStrategy;
import io.github.cascade.cache.v2.policy.RefreshExecutionOptions;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
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
    void shouldBoundLocalVersionSizeForHighCardinalityKeys() throws Exception {
        CachePolicy policy = CachePolicy.builder()
                .l1Enabled(true)
                .l2Enabled(false)
                .autoRefreshEnabled(false)
                .syncMode(SyncMode.NONE)
                .maxTrackedKeys(1)
                .build();
        CaffeineL1Store<String, String> l1 = new CaffeineL1Store<>(100_000, false);
        VersionManager<String> versionManager = new LocalVersionManager<>();
        TestBus<String> bus = new TestBus<>();

        EngineBackedCache<String, String> cache = new EngineBackedCache<>(
                "user", policy, l1, null, null, bus, versionManager, DistLockCoordinator.noop(), "node-1"
        );
        try {
            for (int i = 0; i < 25_000; i++) {
                cache.put("lv-" + i, "v-" + i);
            }
            com.github.benmanes.caffeine.cache.Cache<String, Long> localVersion = localVersionCache(cache);
            localVersion.cleanUp();

            assertTrue(localVersion.estimatedSize() <= 10_000L,
                    "高基数key下localVersion应被容量限制，不应线性增长");
        } finally {
            cache.close();
        }
    }

    @Test
    void shouldPreferFallbackLoaderOnGetOrLoadMiss() {
        CachePolicy policy = CachePolicy.builder()
                .l1Enabled(true)
                .l2Enabled(false)
                .autoRefreshEnabled(false)
                .syncMode(SyncMode.NONE)
                .build();
        CaffeineL1Store<String, String> l1 = new CaffeineL1Store<>(100, false);
        VersionManager<String> versionManager = new LocalVersionManager<>();
        TestBus<String> bus = new TestBus<>();
        AtomicInteger builtinCalls = new AtomicInteger(0);
        AtomicInteger fallbackCalls = new AtomicInteger(0);

        EngineBackedCache<String, String> cache = new EngineBackedCache<>(
                "user", policy, l1, null, key -> "builtin-" + builtinCalls.incrementAndGet(),
                bus, versionManager, DistLockCoordinator.noop(), "node-1"
        );
        try {
            String loaded = cache.getOrLoad("fallback-key", key -> {
                fallbackCalls.incrementAndGet();
                return "fallback-" + key;
            });
            assertEquals("fallback-fallback-key", loaded, "miss时应优先使用fallback loader");
            assertEquals(0, builtinCalls.get(), "fallback loader存在时不应触发内置loader");
            assertEquals(1, fallbackCalls.get(), "fallback loader应仅执行一次");

            assertEquals("fallback-fallback-key", cache.get("fallback-key").orElse(null), "fallback值应被写回缓存");
            assertEquals(0, builtinCalls.get(), "命中fallback回填值时不应触发内置loader");
        } finally {
            cache.close();
        }
    }

    @Test
    void shouldUseBuiltinLoaderWhenGetOrLoadFallbackIsNull() {
        CachePolicy policy = CachePolicy.builder()
                .l1Enabled(true)
                .l2Enabled(false)
                .autoRefreshEnabled(false)
                .syncMode(SyncMode.NONE)
                .build();
        CaffeineL1Store<String, String> l1 = new CaffeineL1Store<>(100, false);
        VersionManager<String> versionManager = new LocalVersionManager<>();
        TestBus<String> bus = new TestBus<>();
        AtomicInteger builtinCalls = new AtomicInteger(0);

        EngineBackedCache<String, String> cache = new EngineBackedCache<>(
                "user", policy, l1, null, key -> "builtin-" + builtinCalls.incrementAndGet(),
                bus, versionManager, DistLockCoordinator.noop(), "node-1"
        );
        try {
            assertEquals("builtin-1", cache.getOrLoad("builtin-key", null), "fallback为空时应走内置loader");
            assertEquals(1, builtinCalls.get());
            assertEquals("builtin-1", cache.getOrLoad("builtin-key", null), "命中后不应重复加载");
            assertEquals(1, builtinCalls.get());
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

    @Test
    void shouldRunRefreshSeriallyWhenAllowConcurrentRefreshDisabled() throws Exception {
        RefreshExecutionOptions options = new RefreshExecutionOptions(4, 16, false, 5, 0, 0, true, 1);
        CachePolicy policy = CachePolicy.builder()
                .l1Enabled(true)
                .l2Enabled(false)
                .autoRefreshEnabled(true)
                .syncMode(SyncMode.NONE)
                .refreshExecutionOptions(options)
                .build();
        CaffeineL1Store<String, String> l1 = new CaffeineL1Store<>(100, false);
        VersionManager<String> versionManager = new LocalVersionManager<>();
        TestBus<String> bus = new TestBus<>();
        AtomicInteger inFlight = new AtomicInteger(0);
        AtomicInteger maxInFlight = new AtomicInteger(0);
        CountDownLatch loaderStarted = new CountDownLatch(1);
        CountDownLatch unblock = new CountDownLatch(1);

        EngineBackedCache<String, String> cache = new EngineBackedCache<>(
                "user",
                policy,
                l1,
                null,
                key -> {
                    loaderStarted.countDown();
                    int now = inFlight.incrementAndGet();
                    maxInFlight.updateAndGet(prev -> Math.max(prev, now));
                    try {
                        unblock.await(1, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        inFlight.decrementAndGet();
                    }
                    return "value-" + key;
                },
                bus,
                versionManager,
                DistLockCoordinator.noop(),
                "node-1"
        );
        try {
            invokeTriggerRefresh(cache, "k-1");
            assertTrue(loaderStarted.await(500, TimeUnit.MILLISECONDS), "首个刷新任务应已开始执行");

            invokeTriggerRefresh(cache, "k-2");
            Thread.sleep(150L);
            assertEquals(1, maxInFlight.get(), "allowConcurrentRefresh=false时刷新应串行执行");

            unblock.countDown();
            assertTrue(waitUntil(() -> {
                try {
                    return refreshingKeyCount(cache) == 0;
                } catch (Exception e) {
                    return false;
                }
            }, 2000L), "刷新任务应在超时前结束");
        } finally {
            cache.close();
        }
    }

    @Test
    void shouldRunRefreshConcurrentlyWhenAllowConcurrentRefreshEnabled() throws Exception {
        RefreshExecutionOptions options = new RefreshExecutionOptions(2, 16, true, 5, 0, 0, true, 1);
        CachePolicy policy = CachePolicy.builder()
                .l1Enabled(true)
                .l2Enabled(false)
                .autoRefreshEnabled(true)
                .syncMode(SyncMode.NONE)
                .refreshExecutionOptions(options)
                .build();
        CaffeineL1Store<String, String> l1 = new CaffeineL1Store<>(100, false);
        VersionManager<String> versionManager = new LocalVersionManager<>();
        TestBus<String> bus = new TestBus<>();
        AtomicInteger inFlight = new AtomicInteger(0);
        AtomicInteger maxInFlight = new AtomicInteger(0);
        CountDownLatch bothStarted = new CountDownLatch(2);
        CountDownLatch unblock = new CountDownLatch(1);

        EngineBackedCache<String, String> cache = new EngineBackedCache<>(
                "user",
                policy,
                l1,
                null,
                key -> {
                    int now = inFlight.incrementAndGet();
                    maxInFlight.updateAndGet(prev -> Math.max(prev, now));
                    bothStarted.countDown();
                    try {
                        unblock.await(1, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        inFlight.decrementAndGet();
                    }
                    return "value-" + key;
                },
                bus,
                versionManager,
                DistLockCoordinator.noop(),
                "node-1"
        );
        try {
            invokeTriggerRefresh(cache, "k-1");
            invokeTriggerRefresh(cache, "k-2");

            assertTrue(bothStarted.await(800, TimeUnit.MILLISECONDS),
                    "allowConcurrentRefresh=true且线程池足够时应并发执行多个key刷新");
            assertTrue(waitUntil(() -> maxInFlight.get() >= 2, 400L), "并发刷新时同时在执行的任务数应>=2");

            unblock.countDown();
            assertTrue(waitUntil(() -> {
                try {
                    return refreshingKeyCount(cache) == 0;
                } catch (Exception e) {
                    return false;
                }
            }, 2000L), "刷新任务应在超时前结束");
        } finally {
            cache.close();
        }
    }

    @Test
    void shouldDeduplicateRefreshForSameKeyEvenWhenConcurrentRefreshEnabled() throws Exception {
        RefreshExecutionOptions options = new RefreshExecutionOptions(2, 16, true, 5, 0, 0, true, 1);
        CachePolicy policy = CachePolicy.builder()
                .l1Enabled(true)
                .l2Enabled(false)
                .autoRefreshEnabled(true)
                .syncMode(SyncMode.NONE)
                .refreshExecutionOptions(options)
                .build();
        CaffeineL1Store<String, String> l1 = new CaffeineL1Store<>(100, false);
        VersionManager<String> versionManager = new LocalVersionManager<>();
        TestBus<String> bus = new TestBus<>();
        AtomicInteger attempts = new AtomicInteger(0);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch unblock = new CountDownLatch(1);

        EngineBackedCache<String, String> cache = new EngineBackedCache<>(
                "user",
                policy,
                l1,
                null,
                key -> {
                    int current = attempts.incrementAndGet();
                    if (current == 1) {
                        firstStarted.countDown();
                        try {
                            unblock.await(1, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    return "value-" + current;
                },
                bus,
                versionManager,
                DistLockCoordinator.noop(),
                "node-1"
        );
        try {
            invokeTriggerRefresh(cache, "dup-key");
            assertTrue(firstStarted.await(500, TimeUnit.MILLISECONDS), "首个同key刷新任务应已开始执行");

            invokeTriggerRefresh(cache, "dup-key");
            Thread.sleep(120L);
            assertEquals(1, attempts.get(), "同key刷新应去重，不应并发重复执行");

            unblock.countDown();
            assertTrue(waitUntil(() -> {
                try {
                    return refreshingKeyCount(cache) == 0;
                } catch (Exception e) {
                    return false;
                }
            }, 2000L), "刷新完成后应释放同key刷新状态");
            assertEquals(1, attempts.get(), "同key去重应保证一次刷新周期内仅执行一次加载");
        } finally {
            cache.close();
        }
    }

    @Test
    void shouldRetryRefreshUntilSuccessWithinMaxRetries() throws Exception {
        RefreshExecutionOptions options = new RefreshExecutionOptions(1, 16, true, 2, 2, 0, true, 1);
        CachePolicy policy = CachePolicy.builder()
                .l1Enabled(true)
                .l2Enabled(false)
                .autoRefreshEnabled(true)
                .syncMode(SyncMode.NONE)
                .refreshExecutionOptions(options)
                .build();
        CaffeineL1Store<String, String> l1 = new CaffeineL1Store<>(100, false);
        VersionManager<String> versionManager = new LocalVersionManager<>();
        TestBus<String> bus = new TestBus<>();
        AtomicInteger attempts = new AtomicInteger(0);

        EngineBackedCache<String, String> cache = new EngineBackedCache<>(
                "user",
                policy,
                l1,
                null,
                key -> {
                    int current = attempts.incrementAndGet();
                    if (current < 3) {
                        throw new IllegalStateException("mock refresh failure " + current);
                    }
                    return "retry-" + current;
                },
                bus,
                versionManager,
                DistLockCoordinator.noop(),
                "node-1"
        );
        try {
            invokeTriggerRefresh(cache, "rk");
            assertTrue(waitUntil(() -> attempts.get() >= 3, 2000L), "刷新应在重试上限内完成重试");
            assertTrue(waitUntil(() -> {
                try {
                    return refreshingKeyCount(cache) == 0;
                } catch (Exception e) {
                    return false;
                }
            }, 2000L), "重试结束后刷新状态应释放");
            assertEquals("retry-3", cache.get("rk").orElse(null), "重试成功后应完成回填");
        } finally {
            cache.close();
        }
    }

    @Test
    void shouldStartRefreshSchedulerLazilyWhenStartOnInitDisabled() throws Exception {
        RefreshExecutionOptions options = new RefreshExecutionOptions(1, 16, false, 2, 0, 0, false, 1);
        CachePolicy policy = CachePolicy.builder()
                .l1Enabled(true)
                .l2Enabled(false)
                .autoRefreshEnabled(true)
                .syncMode(SyncMode.NONE)
                .refreshExecutionOptions(options)
                .build();
        CaffeineL1Store<String, String> l1 = new CaffeineL1Store<>(100, false);
        VersionManager<String> versionManager = new LocalVersionManager<>();
        TestBus<String> bus = new TestBus<>();

        EngineBackedCache<String, String> cache = new EngineBackedCache<>(
                "user", policy, l1, null, null, bus, versionManager, DistLockCoordinator.noop(), "node-1"
        );
        try {
            assertFalse(refreshSchedulerStarted(cache), "startOnInit=false时初始化阶段不应启动刷新调度");
            cache.get("lazy-key");
            assertTrue(waitUntil(() -> {
                try {
                    return refreshSchedulerStarted(cache);
                } catch (Exception e) {
                    return false;
                }
            }, 1000L), "首次访问后应延迟启动刷新调度");
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

    @SuppressWarnings("unchecked")
    private static com.github.benmanes.caffeine.cache.Cache<String, Long> localVersionCache(
            EngineBackedCache<String, String> cache
    ) throws Exception {
        Field field = EngineBackedCache.class.getDeclaredField("localVersion");
        field.setAccessible(true);
        return (com.github.benmanes.caffeine.cache.Cache<String, Long>) field.get(cache);
    }

    private static void invokeRefreshTrackedKeys(EngineBackedCache<?, ?> cache) throws Exception {
        Method method = EngineBackedCache.class.getDeclaredMethod("refreshTrackedKeys");
        method.setAccessible(true);
        method.invoke(cache);
    }

    private static void invokeTriggerRefresh(EngineBackedCache<?, ?> cache, Object key) throws Exception {
        Method method = EngineBackedCache.class.getDeclaredMethod("triggerRefresh", Object.class);
        method.setAccessible(true);
        method.invoke(cache, key);
    }

    @SuppressWarnings("unchecked")
    private static int refreshingKeyCount(EngineBackedCache<?, ?> cache) throws Exception {
        Field field = EngineBackedCache.class.getDeclaredField("refreshingKeys");
        field.setAccessible(true);
        return ((Set<Object>) field.get(cache)).size();
    }

    private static boolean refreshSchedulerStarted(EngineBackedCache<?, ?> cache) throws Exception {
        Field field = EngineBackedCache.class.getDeclaredField("refreshStarted");
        field.setAccessible(true);
        return ((java.util.concurrent.atomic.AtomicBoolean) field.get(cache)).get();
    }

    private static boolean waitUntil(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(20L);
        }
        return condition.getAsBoolean();
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
