package io.github.cascade.cache.v2.facade;

import io.github.cascade.cache.api.Cache;
import io.github.cascade.cache.aspect.CacheAspect;
import io.github.cascade.cache.configuration.CascadeCacheProperties;
import io.github.cascade.cache.core.functional.FunctionalCacheManager;
import io.github.cascade.cache.v2.api.annotations.CascadeCached;
import io.github.cascade.cache.v2.policy.SyncMode;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

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
}

