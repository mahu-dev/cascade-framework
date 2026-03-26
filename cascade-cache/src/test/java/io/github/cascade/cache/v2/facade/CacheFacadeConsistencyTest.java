package io.github.cascade.cache.v2.facade;

import io.github.cascade.cache.v2.api.Cache;
import io.github.cascade.cache.v2.api.annotations.CacheEvict;
import io.github.cascade.cache.v2.api.annotations.Cacheable;
import io.github.cascade.cache.v2.facade.CacheAspect;
import io.github.cascade.cache.configuration.CascadeCacheProperties;
import io.github.cascade.cache.v2.facade.FunctionalCacheManager;
import io.github.cascade.cache.v2.api.annotations.CascadeCached;
import io.github.cascade.cache.v2.loader.CacheLoaderResolver;
import io.github.cascade.cache.v2.policy.SyncMode;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

            proxy.evictAll();
            assertEquals(0L, snapshotSize(cacheAspect), "clear(allEntries) 后应清理该cache全部快照");
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
}
