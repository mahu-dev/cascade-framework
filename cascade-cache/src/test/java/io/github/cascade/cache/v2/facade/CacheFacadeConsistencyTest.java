package io.github.cascade.cache.v2.facade;

import io.github.cascade.cache.v2.api.Cache;
import io.github.cascade.cache.v2.api.annotations.CacheEvict;
import io.github.cascade.cache.v2.api.annotations.CachePut;
import io.github.cascade.cache.v2.api.annotations.Cacheable;
import io.github.cascade.cache.v2.facade.CacheAspect;
import io.github.cascade.cache.configuration.CascadeCacheProperties;
import io.github.cascade.cache.v2.consistency.InvalidationBus;
import io.github.cascade.cache.v2.consistency.InvalidationEvent;
import io.github.cascade.cache.v2.consistency.LocalVersionManager;
import io.github.cascade.cache.v2.common.exception.CacheConfigurationException;
import io.github.cascade.cache.v2.engine.EngineBackedCache;
import io.github.cascade.cache.v2.facade.FunctionalCacheManager;
import io.github.cascade.cache.v2.api.annotations.CascadeCached;
import io.github.cascade.cache.v2.loader.DistLockCoordinator;
import io.github.cascade.cache.v2.loader.CacheLoaderResolver;
import io.github.cascade.cache.v2.policy.CachePolicy;
import io.github.cascade.cache.v2.policy.SyncMode;
import io.github.cascade.cache.v2.store.l1.CaffeineL1Store;
import io.github.cascade.cache.v2.store.model.CacheRecord;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CacheFacadeConsistencyTest {

    @Test
    void shouldKeepAutoRefreshBehaviorConsistentAcrossProgrammaticAndAnnotation() throws Exception {
        CascadeCacheProperties defaults = CascadeCacheProperties.defaults();
        defaults.getL1().setEnabled(true);
        defaults.getL2().setEnabled(false);
        defaults.getSync().setEnabled(false);
        defaults.getRefresh().setEnabled(true);
        defaults.getRefresh().setDefaultRefreshIntervalSeconds(1);
        defaults.getProtection().setSingleFlightEnabled(true);
        defaults.getProtection().setDistributedLockEnabled(false);

        FunctionalCacheManager manager = new FunctionalCacheManager(null, defaults, null);
        try {
            verifyProgrammaticBehavior(manager);
            verifyAnnotationBehavior(manager, defaults);
        } finally {
            manager.close();
        }
    }

    @Test
    void shouldApplyRefreshExecutionOptionsConsistentlyAcrossProgrammaticAndAnnotation() {
        CascadeCacheProperties defaults = CascadeCacheProperties.defaults();
        defaults.getL1().setEnabled(true);
        defaults.getL2().setEnabled(false);
        defaults.getSync().setEnabled(false);
        defaults.getRefresh().setEnabled(true);
        defaults.getRefresh().setDefaultRefreshIntervalSeconds(1);
        defaults.getRefresh().setThreadPoolSize(4);
        defaults.getRefresh().setQueueCapacity(32);
        defaults.getRefresh().setAllowConcurrentRefresh(false);
        defaults.getRefresh().setRefreshTimeoutSeconds(2);
        defaults.getRefresh().setMaxRetries(1);
        defaults.getRefresh().setRetryIntervalSeconds(0);
        defaults.getRefresh().setStartOnInit(false);
        defaults.getRefresh().setShutdownTimeoutSeconds(1);
        defaults.getProtection().setSingleFlightEnabled(true);
        defaults.getProtection().setDistributedLockEnabled(false);

        FunctionalCacheManager manager = new FunctionalCacheManager(null, defaults, null);
        try {
            Cache<String, String> programmatic = manager.newCache("programmatic-refresh-options")
                    .keyType(String.class)
                    .valueType(String.class)
                    .loader(key -> "P")
                    .syncMode(SyncMode.NONE)
                    .autoRefresh(true)
                    .build();
            programmatic.get("k1");

            DemoService target = new DemoService();
            CacheAspect cacheAspect = new CacheAspect(manager, defaults);
            AspectJProxyFactory proxyFactory = new AspectJProxyFactory(target);
            proxyFactory.addAspect(cacheAspect);
            DemoService proxy = proxyFactory.getProxy();
            proxy.load("u1");

            Map<String, Object> programmaticDiagnostics = manager.diagnostics("programmatic-refresh-options");
            Map<String, Object> annotationDiagnostics = manager.diagnostics("annotation-refresh");

            assertEquals(4, programmaticDiagnostics.get("policy.refresh.threadPoolSize"));
            assertEquals(4, annotationDiagnostics.get("policy.refresh.threadPoolSize"));
            assertEquals(1, programmaticDiagnostics.get("policy.refresh.effectiveThreadPoolSize"));
            assertEquals(1, annotationDiagnostics.get("policy.refresh.effectiveThreadPoolSize"));
            assertEquals(false, programmaticDiagnostics.get("policy.refresh.startOnInit"));
            assertEquals(false, annotationDiagnostics.get("policy.refresh.startOnInit"));
            assertEquals(32, programmaticDiagnostics.get("policy.refresh.queueCapacity"));
            assertEquals(32, annotationDiagnostics.get("policy.refresh.queueCapacity"));
        } finally {
            manager.close();
        }
    }

    @Test
    void shouldDisableRefreshWhenEitherCacheableAliasFlagIsFalse() throws Exception {
        CascadeCacheProperties defaults = CascadeCacheProperties.defaults();
        defaults.getL1().setEnabled(true);
        defaults.getL2().setEnabled(false);
        defaults.getSync().setEnabled(false);
        defaults.getRefresh().setEnabled(true);
        defaults.getRefresh().setDefaultRefreshIntervalSeconds(1);
        defaults.getProtection().setSingleFlightEnabled(true);
        defaults.getProtection().setDistributedLockEnabled(false);

        FunctionalCacheManager manager = new FunctionalCacheManager(null, defaults, null);
        try {
            RefreshAliasService target = new RefreshAliasService();
            CacheAspect cacheAspect = new CacheAspect(manager, defaults);
            AspectJProxyFactory proxyFactory = new AspectJProxyFactory(target);
            proxyFactory.addAspect(cacheAspect);
            RefreshAliasService proxy = proxyFactory.getProxy();

            assertEquals("enable-off-1", proxy.loadEnableOff("u1"));
            assertEquals("auto-off-1", proxy.loadAutoOff("u2"));

            Map<String, Object> enableOffDiagnostics = manager.diagnostics("annotation-refresh-enable-off");
            Map<String, Object> autoOffDiagnostics = manager.diagnostics("annotation-refresh-auto-off");
            assertEquals(false, enableOffDiagnostics.get("policy.autoRefresh"));
            assertEquals(false, autoOffDiagnostics.get("policy.autoRefresh"));

            Thread.sleep(1100L);
            proxy.loadEnableOff("u1");
            proxy.loadAutoOff("u2");

            boolean enableOffRefreshed = waitUntil(() -> {
                proxy.loadEnableOff("u1");
                return target.enableOffCalls.get() > 1;
            }, Duration.ofSeconds(2));
            boolean autoOffRefreshed = waitUntil(() -> {
                proxy.loadAutoOff("u2");
                return target.autoOffCalls.get() > 1;
            }, Duration.ofSeconds(2));

            assertEquals(false, enableOffRefreshed, "enableRefresh=false 时不应触发自动刷新");
            assertEquals(false, autoOffRefreshed, "autoRefresh=false 时不应触发自动刷新");
        } finally {
            manager.close();
        }
    }

    @Test
    void shouldKeepConditionUnlessSemanticsConsistentAcrossProgrammaticAndAnnotation() {
        CascadeCacheProperties defaults = CascadeCacheProperties.defaults();
        defaults.getL1().setEnabled(true);
        defaults.getL2().setEnabled(false);
        defaults.getSync().setEnabled(false);
        defaults.getRefresh().setEnabled(false);
        defaults.getProtection().setSingleFlightEnabled(true);
        defaults.getProtection().setDistributedLockEnabled(false);

        FunctionalCacheManager manager = new FunctionalCacheManager(null, defaults, null);
        try {
            verifyProgrammaticConditionUnlessBehavior(manager);
            verifyAnnotationConditionUnlessBehavior(manager, defaults);
        } finally {
            manager.close();
        }
    }

    @Test
    void shouldCleanupInvocationSnapshotsOnEvictAndClear() throws Exception {
        CascadeCacheProperties defaults = CascadeCacheProperties.defaults();
        defaults.getL1().setEnabled(true);
        defaults.getL2().setEnabled(false);
        defaults.getSync().setEnabled(false);
        defaults.getRefresh().setEnabled(false);
        defaults.getProtection().setSingleFlightEnabled(true);
        defaults.getProtection().setDistributedLockEnabled(false);

        FunctionalCacheManager manager = new FunctionalCacheManager(null, defaults, null);
        try {
            SnapshotCleanupService target = new SnapshotCleanupService();
            CacheAspect cacheAspect = new CacheAspect(manager, defaults);
            AspectJProxyFactory proxyFactory = new AspectJProxyFactory(target);
            proxyFactory.addAspect(cacheAspect);
            SnapshotCleanupService proxy = proxyFactory.getProxy();

            proxy.load("u1");
            proxy.load("u2");
            assertEquals(2L, snapshotSize(cacheAspect), "应记录两个方法快照");

            proxy.evictOne("u1");
            assertEquals(1L, snapshotSize(cacheAspect), "evict(key) 后应清理对应快照");

            proxy.load("u1");
            assertEquals(2L, snapshotSize(cacheAspect), "再次访问后应恢复对应快照");

            proxy.evictMany();
            assertEquals(0L, snapshotSize(cacheAspect), "evict(multi-key) 后应逐个清理对应快照");

            proxy.load("u3");
            assertEquals(1L, snapshotSize(cacheAspect), "新访问后应重新建立快照");

            proxy.evictAll();
            assertEquals(0L, snapshotSize(cacheAspect), "clear(allEntries) 后应清理该cache全部快照");
        } finally {
            manager.close();
        }
    }

    @Test
    void shouldEvictEveryKeyWhenEvictExpressionReturnsCollection() {
        CascadeCacheProperties defaults = CascadeCacheProperties.defaults();
        defaults.getL1().setEnabled(true);
        defaults.getL2().setEnabled(false);
        defaults.getSync().setEnabled(false);
        defaults.getRefresh().setEnabled(false);
        defaults.getProtection().setSingleFlightEnabled(true);
        defaults.getProtection().setDistributedLockEnabled(false);

        FunctionalCacheManager manager = new FunctionalCacheManager(null, defaults, null);
        try {
            MultiKeyEvictService target = new MultiKeyEvictService();
            CacheAspect cacheAspect = new CacheAspect(manager, defaults);
            AspectJProxyFactory proxyFactory = new AspectJProxyFactory(target);
            proxyFactory.addAspect(cacheAspect);
            MultiKeyEvictService proxy = proxyFactory.getProxy();

            assertEquals("v-1", proxy.load("k1"));
            assertEquals("v-2", proxy.load("k2"));
            assertEquals("v-1", proxy.load("k1"));
            assertEquals("v-2", proxy.load("k2"));
            assertEquals(2, target.sourceCalls.get(), "驱逐前命中缓存不应重复加载");

            proxy.evictBatch(java.util.List.of("k1", "k2"));

            assertEquals("v-3", proxy.load("k1"));
            assertEquals("v-4", proxy.load("k2"));
            assertEquals(4, target.sourceCalls.get(), "多key驱逐后每个key都应重新加载");
        } finally {
            manager.close();
        }
    }

    @Test
    void shouldTreatInvalidUnlessAsFalseAndStillCache() {
        CascadeCacheProperties defaults = CascadeCacheProperties.defaults();
        defaults.getL1().setEnabled(true);
        defaults.getL2().setEnabled(false);
        defaults.getSync().setEnabled(false);
        defaults.getRefresh().setEnabled(false);
        defaults.getProtection().setSingleFlightEnabled(true);
        defaults.getProtection().setDistributedLockEnabled(false);

        FunctionalCacheManager manager = new FunctionalCacheManager(null, defaults, null);
        try {
            InvalidUnlessService target = new InvalidUnlessService();
            CacheAspect cacheAspect = new CacheAspect(manager, defaults);
            AspectJProxyFactory proxyFactory = new AspectJProxyFactory(target);
            proxyFactory.addAspect(cacheAspect);
            InvalidUnlessService proxy = proxyFactory.getProxy();

            assertEquals("legacy-1", proxy.loadLegacy("u1"));
            assertEquals("legacy-1", proxy.loadLegacy("u1"));
            assertEquals(1, target.legacyCalls.get(), "@Cacheable 无效unless应按false处理并正常缓存");

            assertEquals("cascade-1", proxy.loadCascade("u2"));
            assertEquals("cascade-1", proxy.loadCascade("u2"));
            assertEquals(1, target.cascadeCalls.get(), "@CascadeCached 无效unless应按false处理并正常缓存");
        } finally {
            manager.close();
        }
    }

    @Test
    void shouldFailFastOnAnnotationCacheDefinitionConflict() {
        CascadeCacheProperties defaults = CascadeCacheProperties.defaults();
        defaults.getL1().setEnabled(true);
        defaults.getL2().setEnabled(false);
        defaults.getSync().setEnabled(false);
        defaults.getRefresh().setEnabled(false);
        defaults.getProtection().setSingleFlightEnabled(true);
        defaults.getProtection().setDistributedLockEnabled(false);

        FunctionalCacheManager manager = new FunctionalCacheManager(null, defaults, null);
        try {
            manager.getOrCreateCache("annotation-definition-conflict", String.class, Long.class);

            AnnotationDefinitionConflictService target = new AnnotationDefinitionConflictService();
            CacheAspect cacheAspect = new CacheAspect(manager, defaults);
            AspectJProxyFactory proxyFactory = new AspectJProxyFactory(target);
            proxyFactory.addAspect(cacheAspect);
            AnnotationDefinitionConflictService proxy = proxyFactory.getProxy();

            assertThrows(CacheConfigurationException.class, () -> proxy.load("u1"));
            assertEquals(0, target.calls.get(), "定义冲突时应fail-fast，不应静默降级为直调方法");
        } finally {
            manager.close();
        }
    }

    @Test
    void shouldPreferRegisteredLoaderOverSnapshotLoaderInAnnotationPath() {
        CascadeCacheProperties defaults = CascadeCacheProperties.defaults();
        defaults.getL1().setEnabled(true);
        defaults.getL2().setEnabled(false);
        defaults.getSync().setEnabled(false);
        defaults.getRefresh().setEnabled(false);
        defaults.getProtection().setSingleFlightEnabled(true);
        defaults.getProtection().setDistributedLockEnabled(false);
        defaults.getLoader().setAutoDiscover(false);

        CacheLoaderResolver resolver = new CacheLoaderResolver();
        FunctionalCacheManager manager = new FunctionalCacheManager(null, defaults, resolver);
        try {
            manager.registerLoader(
                    "annotation-loader-priority",
                    String.class,
                    String.class,
                    key -> "registered-" + key
            );

            AnnotationLoaderPriorityService target = new AnnotationLoaderPriorityService();
            CacheAspect cacheAspect = new CacheAspect(manager, defaults);
            AspectJProxyFactory proxyFactory = new AspectJProxyFactory(target);
            proxyFactory.addAspect(cacheAspect);
            AnnotationLoaderPriorityService proxy = proxyFactory.getProxy();

            assertEquals("registered-u1", proxy.load("u1"));
            assertEquals("registered-u1", proxy.load("u1"));
            assertEquals(0, target.methodCalls.get(), "注册loader应优先于snapshot loader");
        } finally {
            manager.close();
        }
    }

    @Test
    void shouldAllowLateRegisteredLoaderToTakeOverSnapshotFallback() {
        CascadeCacheProperties defaults = CascadeCacheProperties.defaults();
        defaults.getL1().setEnabled(true);
        defaults.getL2().setEnabled(false);
        defaults.getSync().setEnabled(false);
        defaults.getRefresh().setEnabled(false);
        defaults.getProtection().setSingleFlightEnabled(true);
        defaults.getProtection().setDistributedLockEnabled(false);
        defaults.getLoader().setAutoDiscover(false);

        CacheLoaderResolver resolver = new CacheLoaderResolver();
        FunctionalCacheManager manager = new FunctionalCacheManager(null, defaults, resolver);
        try {
            AnnotationLoaderPriorityService target = new AnnotationLoaderPriorityService();
            CacheAspect cacheAspect = new CacheAspect(manager, defaults);
            AspectJProxyFactory proxyFactory = new AspectJProxyFactory(target);
            proxyFactory.addAspect(cacheAspect);
            AnnotationLoaderPriorityService proxy = proxyFactory.getProxy();

            assertEquals("snapshot-1", proxy.load("u3"));
            assertEquals(1, target.methodCalls.get(), "首次应由snapshot loader兜底");

            manager.registerLoader(
                    "annotation-loader-priority",
                    String.class,
                    String.class,
                    key -> "registered-" + key
            );
            Cache<String, String> cache = manager.getCache("annotation-loader-priority");
            assertTrue(cache != null);
            cache.evict("u3");

            assertEquals("registered-u3", proxy.load("u3"));
            assertEquals(1, target.methodCalls.get(), "晚注册loader应接管并阻止写回放方法再次执行");
        } finally {
            manager.close();
        }
    }

    @Test
    void shouldFallbackToSnapshotLoaderWhenNoRegisteredLoaderInAnnotationPath() {
        CascadeCacheProperties defaults = CascadeCacheProperties.defaults();
        defaults.getL1().setEnabled(true);
        defaults.getL2().setEnabled(false);
        defaults.getSync().setEnabled(false);
        defaults.getRefresh().setEnabled(false);
        defaults.getProtection().setSingleFlightEnabled(true);
        defaults.getProtection().setDistributedLockEnabled(false);
        defaults.getLoader().setAutoDiscover(false);

        CacheLoaderResolver resolver = new CacheLoaderResolver();
        FunctionalCacheManager manager = new FunctionalCacheManager(null, defaults, resolver);
        try {
            AnnotationLoaderPriorityService target = new AnnotationLoaderPriorityService();
            CacheAspect cacheAspect = new CacheAspect(manager, defaults);
            AspectJProxyFactory proxyFactory = new AspectJProxyFactory(target);
            proxyFactory.addAspect(cacheAspect);
            AnnotationLoaderPriorityService proxy = proxyFactory.getProxy();

            assertEquals("snapshot-1", proxy.load("u2"));
            assertEquals("snapshot-1", proxy.load("u2"));
            assertEquals(1, target.methodCalls.get(), "无注册loader时应回退到snapshot loader并缓存");
        } finally {
            manager.close();
        }
    }

    @Test
    void shouldNotReplayCachePutMethodOnProgrammaticMiss() {
        CascadeCacheProperties defaults = CascadeCacheProperties.defaults();
        defaults.getL1().setEnabled(true);
        defaults.getL2().setEnabled(false);
        defaults.getSync().setEnabled(false);
        defaults.getRefresh().setEnabled(false);
        defaults.getProtection().setSingleFlightEnabled(true);
        defaults.getProtection().setDistributedLockEnabled(false);

        FunctionalCacheManager manager = new FunctionalCacheManager(null, defaults, null);
        try {
            CachePutOnlyService target = new CachePutOnlyService();
            CacheAspect cacheAspect = new CacheAspect(manager, defaults);
            AspectJProxyFactory proxyFactory = new AspectJProxyFactory(target);
            proxyFactory.addAspect(cacheAspect);
            CachePutOnlyService proxy = proxyFactory.getProxy();

            assertEquals("put-1", proxy.put("u1"));
            assertEquals(1, target.putCalls.get());

            Cache<String, String> cache = manager.getCache("annotation-cacheput-only");
            assertTrue(cache != null);
            cache.evict("u1");

            assertTrue(cache.get("u1").isEmpty(), "miss时不应通过@CachePut快照回放写方法");
            assertEquals(1, target.putCalls.get(), "@CachePut方法被miss链路错误回放");
        } finally {
            manager.close();
        }
    }

    @Test
    void shouldNotReplayCachePutMethodDuringAutoRefreshOnSharedCache() throws Exception {
        CascadeCacheProperties defaults = CascadeCacheProperties.defaults();
        defaults.getL1().setEnabled(true);
        defaults.getL2().setEnabled(false);
        defaults.getSync().setEnabled(false);
        defaults.getRefresh().setEnabled(true);
        defaults.getRefresh().setDefaultRefreshIntervalSeconds(1);
        defaults.getProtection().setSingleFlightEnabled(true);
        defaults.getProtection().setDistributedLockEnabled(false);
        defaults.getProtection().setHotKeyAccessThreshold(1);

        FunctionalCacheManager manager = new FunctionalCacheManager(null, defaults, null);
        try {
            SharedCachePutRefreshService target = new SharedCachePutRefreshService();
            CacheAspect cacheAspect = new CacheAspect(manager, defaults);
            AspectJProxyFactory proxyFactory = new AspectJProxyFactory(target);
            proxyFactory.addAspect(cacheAspect);
            SharedCachePutRefreshService proxy = proxyFactory.getProxy();

            assertEquals("read-1", proxy.read("u1"));
            assertEquals("read-1", proxy.read("u1"));
            assertEquals(1, target.readCalls.get());

            assertEquals("put-1", proxy.put("u1"));
            assertEquals(1, target.putCalls.get());

            boolean refreshed = waitUntil(
                    () -> target.readCalls.get() >= 2,
                    Duration.ofSeconds(4)
            );
            assertTrue(refreshed, "应由读方法快照完成自动刷新");
            assertEquals(1, target.putCalls.get(), "@CachePut不应被自动刷新链路回放");
        } finally {
            manager.close();
        }
    }

    @Test
    void shouldShareDefaultGeneratedKeyAcrossCacheablePutAndEvict() {
        CascadeCacheProperties defaults = CascadeCacheProperties.defaults();
        defaults.getL1().setEnabled(true);
        defaults.getL2().setEnabled(false);
        defaults.getSync().setEnabled(false);
        defaults.getRefresh().setEnabled(false);
        defaults.getProtection().setSingleFlightEnabled(true);
        defaults.getProtection().setDistributedLockEnabled(false);

        FunctionalCacheManager manager = new FunctionalCacheManager(null, defaults, null);
        try {
            DefaultKeyInteropService target = new DefaultKeyInteropService();
            CacheAspect cacheAspect = new CacheAspect(manager, defaults);
            AspectJProxyFactory proxyFactory = new AspectJProxyFactory(target);
            proxyFactory.addAspect(cacheAspect);
            DefaultKeyInteropService proxy = proxyFactory.getProxy();

            assertEquals("read-1", proxy.read("u1"));
            assertEquals("read-1", proxy.read("u1"));
            assertEquals(1, target.readCalls.get(), "首次写回后应命中缓存");

            assertEquals("put-1", proxy.put("u1"));
            assertEquals(1, target.putCalls.get(), "@CachePut 应执行一次并更新缓存");
            assertEquals("put-1", proxy.read("u1"), "@CachePut 与 @Cacheable 默认key应互通");
            assertEquals(1, target.readCalls.get(), "@CachePut 写入后读取应直接命中，不应回放读方法");

            proxy.evict("u1");
            assertEquals("read-2", proxy.read("u1"), "@CacheEvict 与 @Cacheable 默认key应互通");
            assertEquals(2, target.readCalls.get(), "驱逐后应触发读方法重新加载");
        } finally {
            manager.close();
        }
    }

    @Test
    void shouldReadAnnotationCachedValueViaProgrammaticApiByIdKey() {
        CascadeCacheProperties defaults = CascadeCacheProperties.defaults();
        defaults.getL1().setEnabled(true);
        defaults.getL2().setEnabled(false);
        defaults.getSync().setEnabled(false);
        defaults.getRefresh().setEnabled(false);
        defaults.getProtection().setSingleFlightEnabled(true);
        defaults.getProtection().setDistributedLockEnabled(false);

        FunctionalCacheManager manager = new FunctionalCacheManager(null, defaults, null);
        try {
            AnnotationProgrammaticInteropService target = new AnnotationProgrammaticInteropService();
            CacheAspect cacheAspect = new CacheAspect(manager, defaults);
            AspectJProxyFactory proxyFactory = new AspectJProxyFactory(target);
            proxyFactory.addAspect(cacheAspect);
            AnnotationProgrammaticInteropService proxy = proxyFactory.getProxy();

            assertEquals("u1-v1", proxy.load("u1"));
            assertEquals(1, target.calls.get());

            Cache<String, String> cache = manager.getOrCreateCache(
                    "annotation-programmatic-interop",
                    String.class,
                    String.class
            );
            assertEquals("u1-v1", cache.get("u1").orElse(null), "编程式API应能按id命中注解写入的缓存值");
            assertTrue(cache.get("u2").isEmpty());
            assertEquals(1, target.calls.get(), "编程式读取命中缓存不应触发方法回放");
        } finally {
            manager.close();
        }
    }

    @Test
    void shouldDegradeToSyncPathWhenAsyncLoadMethodReturnsPrimitive() throws Throwable {
        CascadeCacheProperties defaults = CascadeCacheProperties.defaults();
        defaults.getL1().setEnabled(true);
        defaults.getL2().setEnabled(false);
        defaults.getSync().setEnabled(false);
        defaults.getRefresh().setEnabled(false);
        defaults.getProtection().setSingleFlightEnabled(true);
        defaults.getProtection().setDistributedLockEnabled(false);

        FunctionalCacheManager manager = new FunctionalCacheManager(null, defaults, null);
        try {
            AsyncPrimitiveService target = new AsyncPrimitiveService();
            CacheAspect cacheAspect = new CacheAspect(manager, defaults);
            AspectJProxyFactory proxyFactory = new AspectJProxyFactory(target);
            proxyFactory.addAspect(cacheAspect);
            AsyncPrimitiveService proxy = proxyFactory.getProxy();

            assertEquals(1, proxy.load("p1"), "primitive返回类型应降级为同步执行，避免返回null触发拆箱异常");
            assertEquals(1, target.calls.get());
            assertEquals(1, proxy.load("p1"));
            assertEquals(1, target.calls.get(), "命中缓存后不应重复执行方法");
        } finally {
            manager.close();
        }
    }

    @Test
    void shouldReturnNullOnAsyncLoadMissAndBackfillLater() throws Exception {
        CascadeCacheProperties defaults = CascadeCacheProperties.defaults();
        defaults.getL1().setEnabled(true);
        defaults.getL2().setEnabled(false);
        defaults.getSync().setEnabled(false);
        defaults.getRefresh().setEnabled(false);
        defaults.getProtection().setSingleFlightEnabled(true);
        defaults.getProtection().setDistributedLockEnabled(false);

        FunctionalCacheManager manager = new FunctionalCacheManager(null, defaults, null);
        try {
            AsyncLoadService target = new AsyncLoadService();
            CacheAspect cacheAspect = new CacheAspect(manager, defaults);
            AspectJProxyFactory proxyFactory = new AspectJProxyFactory(target);
            proxyFactory.addAspect(cacheAspect);
            AsyncLoadService proxy = proxyFactory.getProxy();

            assertEquals(null, proxy.load("u-async"), "asyncLoad=true 的miss应立即返回null");
            assertTrue(target.firstLoadDone.await(2, java.util.concurrent.TimeUnit.SECONDS), "应触发异步加载");
            Cache<String, String> cache = manager.getCache("annotation-async-load");
            assertTrue(waitUntil(
                            () -> cache != null && cache.containsKey("u-async"),
                            Duration.ofSeconds(2)
                    ),
                    "异步加载完成后应完成缓存回填");
            assertEquals("async-1", proxy.load("u-async"), "异步加载完成后应命中回填值");
            assertEquals(1, target.calls.get(), "回填后再次读取不应重复执行方法");
        } finally {
            manager.close();
        }
    }

    @Test
    void shouldHandlePrimitiveReturnTypeOnSnapshotLoaderMissPath() {
        CascadeCacheProperties defaults = CascadeCacheProperties.defaults();
        defaults.getL1().setEnabled(true);
        defaults.getL2().setEnabled(false);
        defaults.getSync().setEnabled(false);
        defaults.getRefresh().setEnabled(false);
        defaults.getProtection().setSingleFlightEnabled(true);
        defaults.getProtection().setDistributedLockEnabled(false);

        FunctionalCacheManager manager = new FunctionalCacheManager(null, defaults, null);
        try {
            PrimitiveReturnService target = new PrimitiveReturnService();
            CacheAspect cacheAspect = new CacheAspect(manager, defaults);
            AspectJProxyFactory proxyFactory = new AspectJProxyFactory(target);
            proxyFactory.addAspect(cacheAspect);
            PrimitiveReturnService proxy = proxyFactory.getProxy();

            assertEquals(1, proxy.load("k1"));
            assertEquals(1, proxy.load("k1"));
            assertEquals(1, target.calls.get(), "缓存命中时不应重复调用方法");

            Cache<String, Integer> cache = manager.getCache("annotation-primitive-return");
            assertTrue(cache != null);
            cache.evict("k1");

            assertEquals(2, proxy.load("k1"), "primitive返回值在miss链路应正常通过loader类型校验");
            assertEquals(2, target.calls.get(), "miss后应仅回放一次方法，不应因类型误判触发额外降级调用");
        } finally {
            manager.close();
        }
    }

    @Test
    void shouldHonorCacheEvictSyncFlag() {
        CascadeCacheProperties defaults = CascadeCacheProperties.defaults();
        defaults.getL1().setEnabled(true);
        defaults.getL2().setEnabled(false);
        defaults.getSync().setEnabled(false);
        defaults.getRefresh().setEnabled(false);
        defaults.getProtection().setSingleFlightEnabled(true);
        defaults.getProtection().setDistributedLockEnabled(false);

        FunctionalCacheManager manager = new FunctionalCacheManager(null, defaults, null);
        CountingInvalidationBus bus = new CountingInvalidationBus();
        TrackingL2Store<String, String> l2 = new TrackingL2Store<>();
        CachePolicy policy = CachePolicy.builder()
                .l1Enabled(true)
                .l2Enabled(true)
                .autoRefreshEnabled(false)
                .syncMode(SyncMode.INVALIDATE)
                .build();
        EngineBackedCache<String, String> cache = new EngineBackedCache<>(
                "annotation-evict-sync",
                policy,
                new CaffeineL1Store<>(100, false),
                l2,
                null,
                bus,
                new LocalVersionManager<>(),
                DistLockCoordinator.noop(),
                "node-1"
        );
        try {
            manager.registerCache("annotation-evict-sync", cache);
            CacheEvictSyncService target = new CacheEvictSyncService();
            CacheAspect cacheAspect = new CacheAspect(manager, defaults);
            AspectJProxyFactory proxyFactory = new AspectJProxyFactory(target);
            proxyFactory.addAspect(cacheAspect);
            CacheEvictSyncService proxy = proxyFactory.getProxy();

            cache.put("k1", "v1");
            assertTrue(l2.get("k1").isPresent(), "预热写入后L2应存在k1");
            int baselineAfterSeed = bus.invalidatePublishCount.get();
            long l2EvictBaseline = l2.evictCount.get();
            proxy.evictLocal("k1");
            assertEquals(baselineAfterSeed, bus.invalidatePublishCount.get(), "sync=false 不应发布同步事件");
            assertEquals(l2EvictBaseline, l2.evictCount.get(), "sync=false 不应驱逐共享L2");
            assertTrue(l2.get("k1").isPresent(), "sync=false 仅应影响本地，不应删除共享L2键");

            cache.put("k2", "v2");
            assertTrue(l2.get("k2").isPresent(), "预热写入后L2应存在k2");
            int baselineBeforeSyncEvict = bus.invalidatePublishCount.get();
            long l2EvictBeforeSync = l2.evictCount.get();
            proxy.evictSync("k2");
            assertEquals(baselineBeforeSyncEvict + 1, bus.invalidatePublishCount.get(), "sync=true 应发布同步事件");
            assertEquals(l2EvictBeforeSync + 1, l2.evictCount.get(), "sync=true 应驱逐共享L2");
            assertTrue(l2.get("k2").isEmpty(), "sync=true 后L2中的k2应被删除");
        } finally {
            manager.close();
        }
    }

    @Test
    void shouldKeepCacheEvictBeforeInvocationDefaultFalse() throws Exception {
        assertEquals(false, CacheEvict.class.getMethod("beforeInvocation").getDefaultValue());

        CascadeCacheProperties defaults = CascadeCacheProperties.defaults();
        defaults.getL1().setEnabled(true);
        defaults.getL2().setEnabled(false);
        defaults.getSync().setEnabled(false);
        defaults.getRefresh().setEnabled(false);
        defaults.getProtection().setSingleFlightEnabled(true);
        defaults.getProtection().setDistributedLockEnabled(false);

        FunctionalCacheManager manager = new FunctionalCacheManager(null, defaults, null);
        try {
            CacheEvictBeforeInvocationService target = new CacheEvictBeforeInvocationService();
            CacheAspect cacheAspect = new CacheAspect(manager, defaults);
            AspectJProxyFactory proxyFactory = new AspectJProxyFactory(target);
            proxyFactory.addAspect(cacheAspect);
            CacheEvictBeforeInvocationService proxy = proxyFactory.getProxy();

            assertEquals("v-1", proxy.load("u1"));
            assertThrows(IllegalStateException.class, () -> proxy.evictAfterAndFail("u1"));
            assertEquals("v-1", proxy.load("u1"), "beforeInvocation 默认 false，异常时不应提前驱逐");
            assertEquals(1, target.loadCalls.get(), "默认后置驱逐在异常路径不应触发缓存删除");

            assertThrows(IllegalStateException.class, () -> proxy.evictBeforeAndFail("u1"));
            assertEquals("v-2", proxy.load("u1"), "beforeInvocation=true 时应先驱逐，即使方法抛异常");
            assertEquals(2, target.loadCalls.get(), "前置驱逐后应触发重新加载");
        } finally {
            manager.close();
        }
    }

    private void verifyProgrammaticBehavior(FunctionalCacheManager manager) throws InterruptedException {
        AtomicInteger sourceCounter = new AtomicInteger(0);
        Cache<String, String> cache = manager.newCache("programmatic-refresh")
                .keyType(String.class)
                .valueType(String.class)
                .loader(key -> "P-" + sourceCounter.incrementAndGet())
                .ttlSeconds(30)
                .softTtlSeconds(1)
                .syncMode(SyncMode.NONE)
                .autoRefresh(true)
                .build();

        assertEquals("P-1", cache.get("u1").orElse(null));
        assertEquals("P-1", cache.get("u1").orElse(null));
        assertEquals(1, sourceCounter.get(), "命中缓存时不应重复调用加载器");

        Thread.sleep(1100L);
        assertEquals("P-1", cache.get("u1").orElse(null), "softTTL到期后先返回旧值");

        boolean refreshed = waitUntil(
                () -> "P-2".equals(cache.get("u1").orElse(null)),
                Duration.ofSeconds(2)
        );
        assertTrue(refreshed, "编程式入口未按预期自动刷新并写回");
    }

    private void verifyAnnotationBehavior(FunctionalCacheManager manager,
                                          CascadeCacheProperties defaults) throws InterruptedException {
        DemoService target = new DemoService();
        CacheAspect cacheAspect = new CacheAspect(manager, defaults);
        AspectJProxyFactory proxyFactory = new AspectJProxyFactory(target);
        proxyFactory.addAspect(cacheAspect);
        DemoService proxy = proxyFactory.getProxy();

        assertEquals("A-1", proxy.load("u2"));
        assertEquals("A-1", proxy.load("u2"));
        assertEquals(1, target.sourceCalls.get(), "命中缓存时不应重复执行方法");

        Thread.sleep(1100L);
        assertEquals("A-1", proxy.load("u2"), "softTTL到期后先返回旧值");

        boolean refreshed = waitUntil(
                () -> "A-2".equals(proxy.load("u2")),
                Duration.ofSeconds(2)
        );
        assertTrue(refreshed, "注解入口未按预期自动刷新并写回");
    }

    private static boolean waitUntil(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(20L);
        }
        return condition.getAsBoolean();
    }

    private void verifyProgrammaticConditionUnlessBehavior(FunctionalCacheManager manager) {
        AtomicInteger sourceCalls = new AtomicInteger(0);
        Cache<String, String> cache = manager.newCache("programmatic-condition-unless")
                .keyType(String.class)
                .valueType(String.class)
                .syncMode(SyncMode.NONE)
                .autoRefresh(false)
                .build();

        assertEquals("keep-1", programmaticLoad(cache, sourceCalls, "cache-keep"));
        assertEquals("keep-1", programmaticLoad(cache, sourceCalls, "cache-keep"));
        assertEquals(1, sourceCalls.get(), "condition=true且unless=false时应命中缓存");

        assertEquals("skip-2", programmaticLoad(cache, sourceCalls, "cache-skip"));
        assertEquals("skip-3", programmaticLoad(cache, sourceCalls, "cache-skip"));
        assertEquals(3, sourceCalls.get(), "unless=true时不应写缓存");

        assertEquals("keep-4", programmaticLoad(cache, sourceCalls, "nocache"));
        assertEquals("keep-5", programmaticLoad(cache, sourceCalls, "nocache"));
        assertEquals(5, sourceCalls.get(), "condition=false时应绕过缓存");
    }

    private void verifyAnnotationConditionUnlessBehavior(FunctionalCacheManager manager,
                                                         CascadeCacheProperties defaults) {
        ConditionUnlessService target = new ConditionUnlessService();
        CacheAspect cacheAspect = new CacheAspect(manager, defaults);
        AspectJProxyFactory proxyFactory = new AspectJProxyFactory(target);
        proxyFactory.addAspect(cacheAspect);
        ConditionUnlessService proxy = proxyFactory.getProxy();

        assertEquals("keep-1", proxy.loadCascade("cache-keep"));
        assertEquals("keep-1", proxy.loadCascade("cache-keep"));
        assertEquals(1, target.cascadeCalls.get(), "@CascadeCached condition/unless 语义不符合预期");

        assertEquals("skip-2", proxy.loadCascade("cache-skip"));
        assertEquals("skip-3", proxy.loadCascade("cache-skip"));
        assertEquals(3, target.cascadeCalls.get(), "@CascadeCached unless=true 应不缓存");

        assertEquals("keep-4", proxy.loadCascade("nocache"));
        assertEquals("keep-5", proxy.loadCascade("nocache"));
        assertEquals(5, target.cascadeCalls.get(), "@CascadeCached condition=false 应绕过缓存");

        assertEquals("keep-1", proxy.loadLegacy("cache-keep"));
        assertEquals("keep-1", proxy.loadLegacy("cache-keep"));
        assertEquals(1, target.legacyCalls.get(), "@Cacheable condition/unless 语义不符合预期");

        assertEquals("skip-2", proxy.loadLegacy("cache-skip"));
        assertEquals("skip-3", proxy.loadLegacy("cache-skip"));
        assertEquals(3, target.legacyCalls.get(), "@Cacheable unless=true 应不缓存");
    }

    private static String programmaticLoad(Cache<String, String> cache, AtomicInteger counter, String key) {
        // condition 仅前置：不满足时直接绕过缓存链路
        if (!key.startsWith("cache")) {
            return sourceValue(counter, key);
        }
        String cached = cache.get(key).orElse(null);
        if (cached != null) {
            return cached;
        }
        String loaded = sourceValue(counter, key);
        // unless 后置：结果命中条件则不回填缓存
        if (!loaded.startsWith("skip-")) {
            cache.put(key, loaded);
        }
        return loaded;
    }

    private static String sourceValue(AtomicInteger counter, String key) {
        int seq = counter.incrementAndGet();
        return key.contains("skip") ? "skip-" + seq : "keep-" + seq;
    }

    private static long snapshotSize(CacheAspect cacheAspect) throws Exception {
        Field field = CacheAspect.class.getDeclaredField("invocationSnapshots");
        field.setAccessible(true);
        Object cache = field.get(cacheAspect);
        if (!(cache instanceof com.github.benmanes.caffeine.cache.Cache<?, ?> caffeineCache)) {
            return -1L;
        }
        return caffeineCache.estimatedSize();
    }

    static class DemoService {
        private final AtomicInteger sourceCalls = new AtomicInteger(0);

        @CascadeCached(
                name = "annotation-refresh",
                ttl = "30s",
                softTtl = "1s",
                syncMode = SyncMode.NONE,
                autoRefresh = true
        )
        public String load(String userId) {
            return "A-" + sourceCalls.incrementAndGet();
        }
    }

    static class RefreshAliasService {
        private final AtomicInteger enableOffCalls = new AtomicInteger(0);
        private final AtomicInteger autoOffCalls = new AtomicInteger(0);

        @Cacheable(
                value = "annotation-refresh-enable-off",
                key = "#id",
                enableL1 = true,
                enableL2 = false,
                enableSync = false,
                enableRefresh = false,
                autoRefresh = true,
                refreshInterval = 1
        )
        public String loadEnableOff(String id) {
            return "enable-off-" + enableOffCalls.incrementAndGet();
        }

        @Cacheable(
                value = "annotation-refresh-auto-off",
                key = "#id",
                enableL1 = true,
                enableL2 = false,
                enableSync = false,
                enableRefresh = true,
                autoRefresh = false,
                refreshInterval = 1
        )
        public String loadAutoOff(String id) {
            return "auto-off-" + autoOffCalls.incrementAndGet();
        }
    }

    static class ConditionUnlessService {
        private final AtomicInteger cascadeCalls = new AtomicInteger(0);
        private final AtomicInteger legacyCalls = new AtomicInteger(0);

        @CascadeCached(
                name = "annotation-condition-unless-cascade",
                key = "#userId",
                condition = "#userId.startsWith('cache')",
                unless = "#result.startsWith('skip-')",
                syncMode = SyncMode.NONE,
                autoRefresh = false
        )
        public String loadCascade(String userId) {
            int seq = cascadeCalls.incrementAndGet();
            return userId.contains("skip") ? "skip-" + seq : "keep-" + seq;
        }

        @Cacheable(
                value = "annotation-condition-unless-legacy",
                key = "#userId",
                condition = "#userId.startsWith('cache')",
                unless = "#result.startsWith('skip-')",
                enableL1 = true,
                enableL2 = false,
                enableSync = false,
                enableRefresh = false,
                autoRefresh = false
        )
        public String loadLegacy(String userId) {
            int seq = legacyCalls.incrementAndGet();
            return userId.contains("skip") ? "skip-" + seq : "keep-" + seq;
        }
    }

    static class SnapshotCleanupService {
        private final AtomicInteger sourceCalls = new AtomicInteger(0);

        @Cacheable(
                value = "annotation-snapshot-cleanup",
                key = "#id",
                enableL1 = true,
                enableL2 = false,
                enableSync = false,
                enableRefresh = false,
                autoRefresh = false
        )
        public String load(String id) {
            return "v-" + sourceCalls.incrementAndGet();
        }

        @CacheEvict(value = "annotation-snapshot-cleanup", key = "#id")
        public void evictOne(String id) {
            // no-op
        }

        @CacheEvict(value = "annotation-snapshot-cleanup", allEntries = true)
        public void evictAll() {
            // no-op
        }

        @CacheEvict(value = "annotation-snapshot-cleanup", key = "{'u1', 'u2'}")
        public void evictMany() {
            // no-op
        }
    }

    static class MultiKeyEvictService {
        private final AtomicInteger sourceCalls = new AtomicInteger(0);

        @Cacheable(
                value = "annotation-evict-multi-keys",
                key = "#id",
                enableL1 = true,
                enableL2 = false,
                enableSync = false,
                enableRefresh = false,
                autoRefresh = false
        )
        public String load(String id) {
            return "v-" + sourceCalls.incrementAndGet();
        }

        @CacheEvict(value = "annotation-evict-multi-keys", key = "#ids")
        public void evictBatch(java.util.List<String> ids) {
            // no-op
        }
    }

    static class InvalidUnlessService {
        private final AtomicInteger legacyCalls = new AtomicInteger(0);
        private final AtomicInteger cascadeCalls = new AtomicInteger(0);

        @Cacheable(
                value = "invalid-unless-legacy",
                key = "#id",
                unless = "invalid-spel(",
                enableL1 = true,
                enableL2 = false,
                enableSync = false,
                enableRefresh = false,
                autoRefresh = false
        )
        public String loadLegacy(String id) {
            return "legacy-" + legacyCalls.incrementAndGet();
        }

        @CascadeCached(
                name = "invalid-unless-cascade",
                key = "#id",
                unless = "invalid-spel(",
                syncMode = SyncMode.NONE,
                autoRefresh = false,
                enableL1 = true,
                enableL2 = false
        )
        public String loadCascade(String id) {
            return "cascade-" + cascadeCalls.incrementAndGet();
        }
    }

    static class AnnotationLoaderPriorityService {
        private final AtomicInteger methodCalls = new AtomicInteger(0);

        @Cacheable(
                value = "annotation-loader-priority",
                key = "#id",
                enableL1 = true,
                enableL2 = false,
                enableSync = false,
                enableRefresh = false,
                autoRefresh = false
        )
        public String load(String id) {
            return "snapshot-" + methodCalls.incrementAndGet();
        }
    }

    static class CachePutOnlyService {
        private final AtomicInteger putCalls = new AtomicInteger(0);

        @CachePut(
                value = "annotation-cacheput-only",
                key = "#id",
                enableL1 = true,
                enableL2 = false,
                sync = false,
                autoRefresh = false
        )
        public String put(String id) {
            return "put-" + putCalls.incrementAndGet();
        }
    }

    static class SharedCachePutRefreshService {
        private final AtomicInteger readCalls = new AtomicInteger(0);
        private final AtomicInteger putCalls = new AtomicInteger(0);

        @Cacheable(
                value = "annotation-cacheput-shared",
                key = "#id",
                enableL1 = true,
                enableL2 = false,
                enableSync = false,
                enableRefresh = true,
                autoRefresh = true,
                refreshInterval = 1
        )
        public String read(String id) {
            return "read-" + readCalls.incrementAndGet();
        }

        @CachePut(
                value = "annotation-cacheput-shared",
                key = "#id",
                enableL1 = true,
                enableL2 = false,
                sync = false,
                autoRefresh = true,
                refreshInterval = 1
        )
        public String put(String id) {
            return "put-" + putCalls.incrementAndGet();
        }
    }

    static class DefaultKeyInteropService {
        private final AtomicInteger readCalls = new AtomicInteger(0);
        private final AtomicInteger putCalls = new AtomicInteger(0);

        @Cacheable(
                value = "annotation-default-key-interop",
                enableL1 = true,
                enableL2 = false,
                enableSync = false,
                enableRefresh = false,
                autoRefresh = false
        )
        public String read(String id) {
            return "read-" + readCalls.incrementAndGet();
        }

        @CachePut(
                value = "annotation-default-key-interop",
                enableL1 = true,
                enableL2 = false,
                sync = false,
                autoRefresh = false
        )
        public String put(String id) {
            return "put-" + putCalls.incrementAndGet();
        }

        @CacheEvict(value = "annotation-default-key-interop")
        public void evict(String id) {
            // no-op
        }
    }

    static class AnnotationProgrammaticInteropService {
        private final AtomicInteger calls = new AtomicInteger(0);

        @Cacheable(
                value = "annotation-programmatic-interop",
                key = "#id",
                enableL1 = true,
                enableL2 = false,
                enableSync = false,
                enableRefresh = false,
                autoRefresh = false
        )
        public String load(String id) {
            return id + "-v" + calls.incrementAndGet();
        }
    }

    static class AsyncLoadService {
        private final AtomicInteger calls = new AtomicInteger(0);
        private final CountDownLatch firstLoadDone = new CountDownLatch(1);

        @Cacheable(
                value = "annotation-async-load",
                key = "#id",
                asyncLoad = true,
                enableL1 = true,
                enableL2 = false,
                enableSync = false,
                enableRefresh = false,
                autoRefresh = false
        )
        public String load(String id) {
            int seq = calls.incrementAndGet();
            try {
                Thread.sleep(80L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                firstLoadDone.countDown();
            }
            return "async-" + seq;
        }
    }

    static class AsyncPrimitiveService {
        private final AtomicInteger calls = new AtomicInteger(0);

        @Cacheable(
                value = "annotation-async-primitive",
                key = "#id",
                asyncLoad = true,
                enableL1 = true,
                enableL2 = false,
                enableSync = false,
                enableRefresh = false,
                autoRefresh = false
        )
        public int load(String id) {
            return calls.incrementAndGet();
        }
    }

    static class CacheEvictSyncService {
        @CacheEvict(value = "annotation-evict-sync", key = "#id", sync = false)
        public void evictLocal(String id) {
            // no-op
        }

        @CacheEvict(value = "annotation-evict-sync", key = "#id", sync = true)
        public void evictSync(String id) {
            // no-op
        }
    }

    static class CacheEvictBeforeInvocationService {
        private final AtomicInteger loadCalls = new AtomicInteger(0);

        @Cacheable(
                value = "annotation-evict-before-invocation",
                key = "#id",
                enableL1 = true,
                enableL2 = false,
                enableSync = false,
                enableRefresh = false,
                autoRefresh = false
        )
        public String load(String id) {
            return "v-" + loadCalls.incrementAndGet();
        }

        @CacheEvict(value = "annotation-evict-before-invocation", key = "#id")
        public void evictAfterAndFail(String id) {
            throw new IllegalStateException("boom-after");
        }

        @CacheEvict(value = "annotation-evict-before-invocation", key = "#id", beforeInvocation = true)
        public void evictBeforeAndFail(String id) {
            throw new IllegalStateException("boom-before");
        }
    }

    static class PrimitiveReturnService {
        private final AtomicInteger calls = new AtomicInteger(0);

        @Cacheable(
                value = "annotation-primitive-return",
                key = "#id",
                enableL1 = true,
                enableL2 = false,
                enableSync = false,
                enableRefresh = false,
                autoRefresh = false
        )
        public int load(String id) {
            return calls.incrementAndGet();
        }
    }

    static class AnnotationDefinitionConflictService {
        private final AtomicInteger calls = new AtomicInteger(0);

        @Cacheable(
                value = "annotation-definition-conflict",
                key = "#id",
                enableL1 = true,
                enableL2 = false,
                enableSync = false,
                enableRefresh = false,
                autoRefresh = false
        )
        public String load(String id) {
            return "direct-" + calls.incrementAndGet();
        }
    }

    static class CountingInvalidationBus implements InvalidationBus<String> {
        private final AtomicInteger invalidatePublishCount = new AtomicInteger(0);

        @Override
        public CompletableFuture<Void> publishInvalidation(String cacheName, String key, long version, String nodeId) {
            invalidatePublishCount.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> publishUpdate(String cacheName,
                                                     String key,
                                                     CacheRecord<?> record,
                                                     String valueTypeName,
                                                     String nodeId) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> publishClear(String cacheName, long version, String nodeId) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void subscribe(String cacheName, java.util.function.Consumer<InvalidationEvent<String>> handler) {
            // no-op
        }

        @Override
        public void unsubscribe(String cacheName) {
            // no-op
        }

        @Override
        public void start() {
            // no-op
        }

        @Override
        public void stop() {
            // no-op
        }

        @Override
        public boolean isRunning() {
            return true;
        }
    }

    static class TrackingL2Store<K, V> implements io.github.cascade.cache.v2.store.l2.L2CacheStore<K, V> {
        private final java.util.concurrent.ConcurrentMap<K, CacheRecord<V>> data = new java.util.concurrent.ConcurrentHashMap<>();
        private final java.util.concurrent.ConcurrentMap<K, java.util.concurrent.atomic.AtomicLong> versions =
                new java.util.concurrent.ConcurrentHashMap<>();
        private final java.util.concurrent.atomic.AtomicLong evictCount = new java.util.concurrent.atomic.AtomicLong(0L);

        @Override
        public java.util.Optional<CacheRecord<V>> get(K key) {
            return java.util.Optional.ofNullable(data.get(key));
        }

        @Override
        public void put(K key, CacheRecord<V> record, long ttlSeconds) {
            data.put(key, record);
            versions.computeIfAbsent(key, ignored -> new java.util.concurrent.atomic.AtomicLong(0L))
                    .updateAndGet(current -> Math.max(current, record.getVersion()));
        }

        @Override
        public void evict(K key) {
            evictCount.incrementAndGet();
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
            return versions.computeIfAbsent(key, ignored -> new java.util.concurrent.atomic.AtomicLong(0L))
                    .incrementAndGet();
        }

        @Override
        public void close() {
            data.clear();
            versions.clear();
        }
    }
}
