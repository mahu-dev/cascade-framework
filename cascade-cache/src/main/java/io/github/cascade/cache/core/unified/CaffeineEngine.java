package io.github.cascade.cache.core.unified;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import io.github.cascade.cache.api.CacheLoader;
import io.github.cascade.cache.api.CacheStats;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 基于Caffeine的缓存引擎实现
 * 专注于本地缓存功能，去除继承层次
 *
 * @author cascade
 */
@Slf4j
public class CaffeineEngine<K, V> implements CacheEngine<K, V> {

    private final com.github.benmanes.caffeine.cache.Cache<K, V> cache;
    private final LoadingCache<K, V> loadingCache;
    private final boolean useLoadingCache;
    private final String name;

    /**
     * 构造器 - 使用配置对象
     */
    public CaffeineEngine(String name, CaffeineConfig config) {
        this.name = name;
        Caffeine<Object, Object> builder = Caffeine.newBuilder();
        // 应用配置
        if (config.maximumSize > 0) {
            builder.maximumSize(config.maximumSize);
        }
        if (config.expireAfterWrite != null) {
            builder.expireAfterWrite(config.expireAfterWrite.toMillis(), TimeUnit.MILLISECONDS);
        }
        if (config.expireAfterAccess != null) {
            builder.expireAfterAccess(config.expireAfterAccess.toMillis(), TimeUnit.MILLISECONDS);
        }
        if (config.refreshAfterWrite != null) {
            builder.refreshAfterWrite(config.refreshAfterWrite.toMillis(), TimeUnit.MILLISECONDS);
        }
        if (config.recordStats) {
            builder.recordStats();
        }
        if (config.initialCapacity > 0) {
            builder.initialCapacity(config.initialCapacity);
        }


        // 决定使用LoadingCache还是普通Cache
        this.useLoadingCache = config.refreshAfterWrite != null && config.cacheLoader != null;

        if (useLoadingCache) {
            // 创建Caffeine CacheLoader适配器
            com.github.benmanes.caffeine.cache.CacheLoader<K, V> caffeineCacheLoader = key -> (V) config.cacheLoader.load(key);
            this.loadingCache = builder.build(caffeineCacheLoader);
            this.cache = loadingCache; // LoadingCache实现了Cache接口
            log.debug("Created LoadingCache engine: {}", name);
        } else {
            this.cache = builder.build();
            this.loadingCache = null;
            log.debug("Created Cache engine: {}", name);
        }
    }

    @Override
    public V get(K key) {
        if (key == null) return null;

        V value;
        if (useLoadingCache) {
            // 使用LoadingCache的自动加载功能
            value = loadingCache.get(key);
        } else {
            value = cache.getIfPresent(key);
        }

        log.debug("CaffeineEngine get: key={}, hit={}", key, value != null);
        return value;
    }

    @Override
    public Map<K, V> getAll(Set<K> keys) {
        if (keys == null || keys.isEmpty()) return Map.of();

        Map<K, V> result;
        if (useLoadingCache) {
            result = loadingCache.getAll(keys);
        } else {
            result = cache.getAllPresent(keys);
        }

        log.debug("Cache getAll: keys={}, hits={}", keys.size(), result.size());
        return result;
    }

    @Override
    public void put(K key, V value) {
        if (key == null || value == null) return;

        cache.put(key, value);
        log.debug("Cache put: key={}", key);
    }

    @Override
    public void put(K key, V value, Duration ttl) {
        // Caffeine不支持单独设置TTL，使用全局配置
        put(key, value);
        log.debug("Cache put with TTL: key={}, ttl={}", key, ttl.toSeconds());
    }

    @Override
    public void putAll(Map<K, V> map) {
        if (map == null || map.isEmpty()) return;

        cache.putAll(map);
        log.debug("Cache putAll: size={}", map.size());
    }

    @Override
    public void evict(K key) {
        if (key == null) return;

        cache.invalidate(key);
        log.debug("Cache evict: key={}", key);
    }

    @Override
    public void evictAll(Set<K> keys) {
        if (keys == null || keys.isEmpty()) return;

        cache.invalidateAll(keys);
        log.debug("Cache evictAll: size={}", keys.size());
    }

    @Override
    public void clear() {
        cache.invalidateAll();
        log.debug("Cache cleared: {}", name);
    }

    @Override
    public boolean containsKey(K key) {
        if (key == null) return false;
        return cache.getIfPresent(key) != null;
    }

    @Override
    public long size() {
        return cache.estimatedSize();
    }

    @Override
    public CacheStats getStats() {
        com.github.benmanes.caffeine.cache.stats.CacheStats caffeineStats = cache.stats();
        return new CaffeineStatsAdapter(caffeineStats);
    }

    @Override
    public void cleanUp() {
        cache.cleanUp();
    }

    /**
     * 配置类
     */
    @Data
    @Accessors(chain = true)
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CaffeineConfig<K, V> {
        private long maximumSize = 10000;
        private Duration expireAfterWrite;
        private Duration expireAfterAccess;
        private Duration refreshAfterWrite;
        private boolean recordStats = false;
        private int initialCapacity;
        private int concurrencyLevel;
        private boolean weakKeys;
        private boolean weakValues;
        private boolean softValues;
        private CacheLoader<K, V> cacheLoader;

    }

    /**
     * Caffeine统计信息适配器
     */
    private static class CaffeineStatsAdapter implements CacheStats {
        private final com.github.benmanes.caffeine.cache.stats.CacheStats stats;

        public CaffeineStatsAdapter(com.github.benmanes.caffeine.cache.stats.CacheStats stats) {
            this.stats = stats;
        }

        @Override
        public long hitCount() {
            return stats.hitCount();
        }

        @Override
        public long missCount() {
            return stats.missCount();
        }

        @Override
        public double hitRate() {
            return stats.hitRate();
        }

        @Override
        public double missRate() {
            return stats.missRate();
        }

        @Override
        public long loadCount() {
            return stats.loadCount();
        }

        @Override
        public double averageLoadPenalty() {
            return stats.averageLoadPenalty();
        }

        @Override
        public long evictionCount() {
            return stats.evictionCount();
        }

        @Override
        public long evictionWeight() {
            return stats.evictionWeight();
        }

        @Override
        public long requestCount() {
            return stats.requestCount();
        }

        @Override
        public long loadExceptionCount() {
            return stats.loadCount() - stats.loadSuccessCount();
        }

        @Override
        public long totalLoadTime() {
            return stats.totalLoadTime();
        }

        @Override
        public void reset() {
            // Caffeine不支持重置统计，这是一个只读实现
        }
    }
}