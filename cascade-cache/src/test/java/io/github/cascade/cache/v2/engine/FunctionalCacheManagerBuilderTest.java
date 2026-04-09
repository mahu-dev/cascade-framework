package io.github.cascade.cache.v2.engine;

import io.github.cascade.cache.v2.api.Cache;
import io.github.cascade.cache.v2.api.CacheLoader;
import io.github.cascade.cache.v2.api.annotations.CacheLoaderBinding;
import io.github.cascade.cache.v2.common.exception.CacheConfigurationException;
import io.github.cascade.cache.configuration.CascadeCacheProperties;
import io.github.cascade.cache.v2.facade.FunctionalCacheManager;
import io.github.cascade.cache.v2.loader.CacheLoaderResolver;
import io.github.cascade.cache.v2.policy.SyncMode;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FunctionalCacheManagerBuilderTest {

    @Test
    void shouldBuildCacheWithProgrammaticBuilder() {
        CascadeCacheProperties config = CascadeCacheProperties.defaults();
        config.getL1().setEnabled(true);
        config.getL2().setEnabled(false);
        config.getRefresh().setEnabled(true);
        config.getSync().setEnabled(false);

        FunctionalCacheManager manager = new FunctionalCacheManager(null, config, null);
        try {
            Cache<String, String> cache = manager.newCache("user")
                    .keyType(String.class)
                    .valueType(String.class)
                    .loader(id -> "user-" + id)
                    .ttlSeconds(60)
                    .softTtlSeconds(10)
                    .syncMode(SyncMode.NONE)
                    .autoRefresh(true)
                    .build();

            assertNotNull(cache);
            assertEquals("user-100", cache.get("100").orElse(null));
            assertNotNull(manager.getCache("user"));
            Map<String, Object> diagnostics = manager.diagnostics("user");
            assertEquals(true, diagnostics.get("exists"));
            assertTrue(diagnostics.containsKey("stats.miss"));
        } finally {
            manager.close();
        }
    }

    @Test
    void shouldApplyL1ExpireAfterWriteConfigWhenCreatingCache() throws InterruptedException {
        CascadeCacheProperties config = CascadeCacheProperties.defaults();
        config.getL1().setEnabled(true);
        config.getL2().setEnabled(false);
        config.getSync().setEnabled(false);
        config.getRefresh().setEnabled(false);
        config.getL1().setExpireAfterWriteSeconds(1);
        config.getL1().setExpireAfterAccessSeconds(-1);

        FunctionalCacheManager manager = new FunctionalCacheManager(null, config, null);
        try {
            Cache<String, String> cache = manager.getOrCreateCache("l1-expire-after-write", String.class, String.class, config);
            cache.put("k", "v");
            assertEquals("v", cache.get("k").orElse(null));

            Thread.sleep(1200L);

            assertTrue(cache.get("k").isEmpty(), "L1写后过期配置应被下发到Caffeine并生效");
        } finally {
            manager.close();
        }
    }

    @Test
    void shouldLoadWithRegisteredLoaderWhenNotProvidedInCacheCreation() {
        CascadeCacheProperties config = CascadeCacheProperties.defaults();
        config.getL1().setEnabled(true);
        config.getL2().setEnabled(false);
        config.getSync().setEnabled(false);

        CacheLoaderResolver resolver = new CacheLoaderResolver();
        FunctionalCacheManager manager = new FunctionalCacheManager(null, config, resolver);
        try {
            manager.registerLoader("registered-users", String.class, String.class, key -> "user-" + key);
            Cache<String, String> cache = manager.getOrCreateCache("registered-users", String.class, String.class);
            assertEquals("user-42", cache.get("42").orElse(null));
        } finally {
            manager.close();
        }
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void shouldNormalizePrimitiveAndWrapperTypesAcrossRegisterAndGet() {
        CascadeCacheProperties config = CascadeCacheProperties.defaults();
        config.getL1().setEnabled(true);
        config.getL2().setEnabled(false);
        config.getSync().setEnabled(false);

        CacheLoaderResolver resolver = new CacheLoaderResolver();
        FunctionalCacheManager manager = new FunctionalCacheManager(null, config, resolver);
        try {
            Class<Integer> primitiveIntType = (Class<Integer>) (Class) int.class;
            manager.registerLoader("primitive-users", String.class, primitiveIntType, key -> 42);

            Cache<String, Integer> cache = manager.getOrCreateCache("primitive-users", String.class, Integer.class);
            assertEquals(42, cache.get("u1").orElse(null));

            Cache<String, Integer> sameCache = manager.getOrCreateCache("primitive-users", String.class, primitiveIntType);
            assertSame(cache, sameCache);
        } finally {
            manager.close();
        }
    }

    @Test
    void shouldFailWhenRegisterLoaderWithoutResolver() {
        CascadeCacheProperties config = CascadeCacheProperties.defaults();
        config.getL1().setEnabled(true);
        config.getL2().setEnabled(false);
        config.getSync().setEnabled(false);

        FunctionalCacheManager manager = new FunctionalCacheManager(null, config, null);
        try {
            assertThrows(CacheConfigurationException.class,
                    () -> manager.registerLoader("users", String.class, String.class, key -> "u-" + key));
        } finally {
            manager.close();
        }
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void shouldFailFastWhenRegisteredLoaderValueTypeMismatched() {
        CascadeCacheProperties config = CascadeCacheProperties.defaults();
        config.getL1().setEnabled(true);
        config.getL2().setEnabled(false);
        config.getSync().setEnabled(false);

        CacheLoaderResolver resolver = new CacheLoaderResolver();
        FunctionalCacheManager manager = new FunctionalCacheManager(null, config, resolver);
        try {
            io.github.cascade.cache.v2.api.CacheLoader<String, String> wrong =
                    (io.github.cascade.cache.v2.api.CacheLoader<String, String>) (io.github.cascade.cache.v2.api.CacheLoader)
                            ((io.github.cascade.cache.v2.api.CacheLoader<String, Object>) key -> 123L);
            manager.registerLoader("broken-users", String.class, String.class, wrong);

            Cache<String, String> cache = manager.getOrCreateCache("broken-users", String.class, String.class);
            CompletionException ex = assertThrows(CompletionException.class, () -> cache.get("1"));
            assertTrue(ex.getCause() instanceof CacheConfigurationException);
        } finally {
            manager.close();
        }
    }

    @Test
    void shouldNotUseDiscoveredLoaderWhenAutoDiscoverDisabled() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(AutoDiscoverLoaderConfig.class)) {
            CacheLoaderResolver resolver = new CacheLoaderResolver();
            resolver.setApplicationContext(context);
            resolver.clearCache();

            CascadeCacheProperties config = CascadeCacheProperties.defaults();
            config.getL1().setEnabled(true);
            config.getL2().setEnabled(false);
            config.getSync().setEnabled(false);
            config.getLoader().setAutoDiscover(false);

            FunctionalCacheManager manager = new FunctionalCacheManager(null, config, resolver);
            try {
                Cache<String, String> cache = manager.getOrCreateCache("discover-users", String.class, String.class, config);
                assertTrue(cache.get("1").isEmpty());
                CacheLoaderResolver.ResolverStats stats = resolver.getStats();
                assertFalse(stats.autoDiscoveryInitialized(), "cache-level autoDiscover=false 不应触发自动发现初始化");
                assertEquals(0, stats.discoveredLoaderCount(), "cache-level autoDiscover=false 不应填充发现注册表");
            } finally {
                manager.close();
            }
        }
    }

    @Test
    void shouldUseDiscoveredLoaderWhenAutoDiscoverEnabled() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(AutoDiscoverLoaderConfig.class)) {
            CacheLoaderResolver resolver = new CacheLoaderResolver();
            resolver.setApplicationContext(context);

            CascadeCacheProperties config = CascadeCacheProperties.defaults();
            config.getL1().setEnabled(true);
            config.getL2().setEnabled(false);
            config.getSync().setEnabled(false);
            config.getLoader().setAutoDiscover(true);

            FunctionalCacheManager manager = new FunctionalCacheManager(null, config, resolver);
            try {
                Cache<String, String> cache = manager.getOrCreateCache("discover-users", String.class, String.class, config);
                assertEquals("discovered-1", cache.get("1").orElse(null));
            } finally {
                manager.close();
            }
        }
    }

    @Test
    void shouldFailFastWhenSameCacheNameHasDifferentKeyType() {
        CascadeCacheProperties config = CascadeCacheProperties.defaults();
        config.getL1().setEnabled(true);
        config.getL2().setEnabled(false);
        config.getSync().setEnabled(false);

        FunctionalCacheManager manager = new FunctionalCacheManager(null, config, null);
        try {
            manager.getOrCreateCache("conflict-type", String.class, String.class);
            assertThrows(CacheConfigurationException.class,
                    () -> manager.getOrCreateCache("conflict-type", Long.class, String.class));
        } finally {
            manager.close();
        }
    }

    @Test
    void shouldFailFastWhenSameCacheNameHasDifferentPolicy() {
        CascadeCacheProperties config = CascadeCacheProperties.defaults();
        config.getL1().setEnabled(true);
        config.getL2().setEnabled(false);
        config.getSync().setEnabled(false);
        config.getRefresh().setEnabled(true);
        config.getRefresh().setDefaultRefreshIntervalSeconds(5);

        FunctionalCacheManager manager = new FunctionalCacheManager(null, config, null);
        try {
            manager.getOrCreateCache("conflict-policy", String.class, String.class, config);

            CascadeCacheProperties changed = CascadeCacheProperties.defaults();
            changed.getL1().setEnabled(true);
            changed.getL2().setEnabled(false);
            changed.getSync().setEnabled(false);
            changed.getRefresh().setEnabled(false); // 改变策略，触发指纹冲突
            changed.getRefresh().setDefaultRefreshIntervalSeconds(5);

            assertThrows(CacheConfigurationException.class,
                    () -> manager.getOrCreateCache("conflict-policy", String.class, String.class, changed));
        } finally {
            manager.close();
        }
    }

    @Test
    void shouldFailFastWhenSameCacheNameHasDifferentL1MaximumSize() {
        CascadeCacheProperties config = CascadeCacheProperties.defaults();
        config.getL1().setEnabled(true);
        config.getL2().setEnabled(false);
        config.getSync().setEnabled(false);
        config.getRefresh().setEnabled(false);
        config.getL1().setMaximumSize(128);

        FunctionalCacheManager manager = new FunctionalCacheManager(null, config, null);
        try {
            manager.getOrCreateCache("conflict-l1-max-size", String.class, String.class, config);

            CascadeCacheProperties changed = CascadeCacheProperties.defaults();
            changed.getL1().setEnabled(true);
            changed.getL2().setEnabled(false);
            changed.getSync().setEnabled(false);
            changed.getRefresh().setEnabled(false);
            changed.getL1().setMaximumSize(256);

            assertThrows(CacheConfigurationException.class,
                    () -> manager.getOrCreateCache("conflict-l1-max-size", String.class, String.class, changed));
        } finally {
            manager.close();
        }
    }

    @Test
    void shouldFailFastWhenSameCacheNameHasDifferentL1ExpireAfterWrite() {
        CascadeCacheProperties config = CascadeCacheProperties.defaults();
        config.getL1().setEnabled(true);
        config.getL2().setEnabled(false);
        config.getSync().setEnabled(false);
        config.getRefresh().setEnabled(false);
        config.getL1().setExpireAfterWriteSeconds(60);
        config.getL1().setExpireAfterAccessSeconds(-1);

        FunctionalCacheManager manager = new FunctionalCacheManager(null, config, null);
        try {
            manager.getOrCreateCache("conflict-l1-expire-write", String.class, String.class, config);

            CascadeCacheProperties changed = CascadeCacheProperties.defaults();
            changed.getL1().setEnabled(true);
            changed.getL2().setEnabled(false);
            changed.getSync().setEnabled(false);
            changed.getRefresh().setEnabled(false);
            changed.getL1().setExpireAfterWriteSeconds(120);
            changed.getL1().setExpireAfterAccessSeconds(-1);

            assertThrows(CacheConfigurationException.class,
                    () -> manager.getOrCreateCache("conflict-l1-expire-write", String.class, String.class, changed));
        } finally {
            manager.close();
        }
    }

    @Test
    void shouldFailFastWhenSameCacheNameHasDifferentL1ExpireAfterAccess() {
        CascadeCacheProperties config = CascadeCacheProperties.defaults();
        config.getL1().setEnabled(true);
        config.getL2().setEnabled(false);
        config.getSync().setEnabled(false);
        config.getRefresh().setEnabled(false);
        config.getL1().setExpireAfterWriteSeconds(-1);
        config.getL1().setExpireAfterAccessSeconds(30);

        FunctionalCacheManager manager = new FunctionalCacheManager(null, config, null);
        try {
            manager.getOrCreateCache("conflict-l1-expire-access", String.class, String.class, config);

            CascadeCacheProperties changed = CascadeCacheProperties.defaults();
            changed.getL1().setEnabled(true);
            changed.getL2().setEnabled(false);
            changed.getSync().setEnabled(false);
            changed.getRefresh().setEnabled(false);
            changed.getL1().setExpireAfterWriteSeconds(-1);
            changed.getL1().setExpireAfterAccessSeconds(45);

            assertThrows(CacheConfigurationException.class,
                    () -> manager.getOrCreateCache("conflict-l1-expire-access", String.class, String.class, changed));
        } finally {
            manager.close();
        }
    }

    @Test
    void shouldFailFastWhenRefreshExecutionConfigInvalid() {
        CascadeCacheProperties config = CascadeCacheProperties.defaults();
        config.getL1().setEnabled(true);
        config.getL2().setEnabled(false);
        config.getSync().setEnabled(false);
        config.getRefresh().setThreadPoolSize(0);

        assertThrows(CacheConfigurationException.class, () -> new FunctionalCacheManager(null, config, null));
    }

    @Test
    void shouldFailFastOnRegisterCacheConflict() {
        CascadeCacheProperties config = CascadeCacheProperties.defaults();
        config.getL1().setEnabled(true);
        config.getL2().setEnabled(false);
        config.getSync().setEnabled(false);

        FunctionalCacheManager manager = new FunctionalCacheManager(null, config, null);
        try {
            assertTrue(manager.registerCache("manual-cache", new DummyCacheA()));
            assertThrows(CacheConfigurationException.class,
                    () -> manager.registerCache("manual-cache", new DummyCacheB()));
        } finally {
            manager.close();
        }
    }

    @Test
    void shouldNotCloseCacheWhenRegisteringSameInstanceRepeatedly() {
        CascadeCacheProperties config = CascadeCacheProperties.defaults();
        config.getL1().setEnabled(true);
        config.getL2().setEnabled(false);
        config.getSync().setEnabled(false);

        FunctionalCacheManager manager = new FunctionalCacheManager(null, config, null);
        try {
            DummyCacheA cache = new DummyCacheA();
            assertTrue(manager.registerCache("idempotent-cache", cache));
            assertFalse(cache.isClosed());

            assertFalse(manager.registerCache("idempotent-cache", cache));
            assertFalse(cache.isClosed(), "同一实例幂等注册不应关闭当前缓存");
            assertSame(cache, manager.getCache("idempotent-cache"));
        } finally {
            manager.close();
        }
    }

    private static class DummyCacheA implements Cache<String, String> {
        private boolean closed;

        @Override
        public Optional<String> get(String key) {
            return Optional.empty();
        }

        @Override
        public String getOrLoad(String key, Function<String, String> loader) {
            return null;
        }

        @Override
        public CompletableFuture<Optional<String>> getAsync(String key) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        @Override
        public void put(String key, String value) {
        }

        @Override
        public void put(String key, String value, long ttlSeconds) {
        }

        @Override
        public CompletableFuture<Void> putAsync(String key, String value) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void evict(String key) {
        }

        @Override
        public void clear() {
        }

        @Override
        public Map<String, String> getAll(Iterable<String> keys) {
            return Map.of();
        }

        @Override
        public void putAll(Map<String, String> entries) {
        }

        @Override
        public boolean containsKey(String key) {
            return false;
        }

        @Override
        public long size() {
            return 0;
        }

        @Override
        public String getName() {
            return "dummy-a";
        }

        @Override
        public void close() {
            closed = true;
        }

        @Override
        public boolean isClosed() {
            return closed;
        }
    }

    private static final class DummyCacheB extends DummyCacheA {
        @Override
        public String getName() {
            return "dummy-b";
        }
    }

    @Configuration
    static class AutoDiscoverLoaderConfig {
        @Bean
        CacheLoader<String, String> discoveredUsersLoader() {
            return new DiscoveredUsersLoader();
        }
    }

    @CacheLoaderBinding(cacheName = "discover-users", keyType = String.class, valueType = String.class)
    static class DiscoveredUsersLoader implements CacheLoader<String, String> {
        @Override
        public String apply(String key) {
            return "discovered-" + key;
        }
    }
}
