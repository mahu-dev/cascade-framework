package io.github.cascade.cache.v2.facade;

import io.github.cascade.cache.configuration.CascadeCacheProperties;
import io.github.cascade.cache.v2.api.Cache;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CacheAspectSupportTest {

    @Test
    void shouldUseGetIfPresentWhenSkipLoadOnMissEnabled() {
        ProbeCache cache = new ProbeCache();
        cache.getIfPresentResult = Optional.of("present");
        CacheAspectSupport support = new CacheAspectSupport(
                null,
                CascadeCacheProperties.defaults(),
                new CacheInvocationSnapshotSupport()
        );

        Optional<Object> value = support.readFromCache(cast(cache), "k1", true);

        assertEquals(Optional.of("present"), value);
        assertEquals(1, cache.getIfPresentCalls.get());
        assertEquals(0, cache.getCalls.get());
        assertEquals(0, cache.containsKeyCalls.get());
    }

    @Test
    void shouldUseGetWhenSkipLoadOnMissDisabled() {
        ProbeCache cache = new ProbeCache();
        cache.getResult = Optional.of("loaded");
        CacheAspectSupport support = new CacheAspectSupport(
                null,
                CascadeCacheProperties.defaults(),
                new CacheInvocationSnapshotSupport()
        );

        Optional<Object> value = support.readFromCache(cast(cache), "k2", false);

        assertEquals(Optional.of("loaded"), value);
        assertEquals(1, cache.getCalls.get());
        assertEquals(0, cache.getIfPresentCalls.get());
        assertEquals(0, cache.containsKeyCalls.get());
    }

    @SuppressWarnings("unchecked")
    private static Cache<Object, Object> cast(Cache<?, ?> cache) {
        return (Cache<Object, Object>) cache;
    }

    private static final class ProbeCache implements Cache<Object, Object> {
        private final AtomicInteger getCalls = new AtomicInteger();
        private final AtomicInteger getIfPresentCalls = new AtomicInteger();
        private final AtomicInteger containsKeyCalls = new AtomicInteger();
        private Optional<Object> getResult = Optional.empty();
        private Optional<Object> getIfPresentResult = Optional.empty();

        @Override
        public Optional<Object> get(Object key) {
            getCalls.incrementAndGet();
            return getResult;
        }

        @Override
        public Optional<Object> getIfPresent(Object key) {
            getIfPresentCalls.incrementAndGet();
            return getIfPresentResult;
        }

        @Override
        public Object getOrLoad(Object key, Function<Object, Object> loader) {
            return null;
        }

        @Override
        public CompletableFuture<Optional<Object>> getAsync(Object key) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        @Override
        public void put(Object key, Object value) {
        }

        @Override
        public void put(Object key, Object value, long ttlSeconds) {
        }

        @Override
        public CompletableFuture<Void> putAsync(Object key, Object value) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void evict(Object key) {
        }

        @Override
        public void clear() {
        }

        @Override
        public Map<Object, Object> getAll(Iterable<Object> keys) {
            return Map.of();
        }

        @Override
        public void putAll(Map<Object, Object> entries) {
        }

        @Override
        public boolean containsKey(Object key) {
            containsKeyCalls.incrementAndGet();
            return false;
        }

        @Override
        public long size() {
            return 0;
        }

        @Override
        public String getName() {
            return "probe";
        }

        @Override
        public void close() {
        }

        @Override
        public boolean isClosed() {
            return false;
        }
    }
}
