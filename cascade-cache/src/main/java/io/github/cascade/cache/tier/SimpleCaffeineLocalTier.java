package io.github.cascade.cache.tier;

import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.cascade.cache.api.CacheLoader;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

/**
 * 基于Caffeine的简化本地缓存层实现
 */
public class SimpleCaffeineLocalTier<K, V> extends AbstractLocalTier<K, V> {

    private final com.github.benmanes.caffeine.cache.Cache<K, V> caffeineCache;
    private final com.github.benmanes.caffeine.cache.LoadingCache<K, V> caffeineLoadingCache;
    private final boolean useLoadingCache;

    public SimpleCaffeineLocalTier(String name, LocalTierConfig config) {
        super(name);

        // 设置基类配置
        if (config.getMaximumSize() > 0) {
            setMaximumSize(config.getMaximumSize());
        }
        if (config.getExpireAfterWrite() != null) {
            setExpireAfterWrite(config.getExpireAfterWrite());
        }
        if (config.getExpireAfterAccess() != null) {
            setExpireAfterAccess(config.getExpireAfterAccess());
        }
        if (config.getRefreshAfterWrite() != null) {
            setRefreshAfterWrite(config.getRefreshAfterWrite());
        }

        Caffeine<Object, Object> builder = Caffeine.newBuilder();

        if (config.getMaximumSize() > 0) {
            builder.maximumSize(config.getMaximumSize());
        }

        if (config.getExpireAfterWrite() != null) {
            builder.expireAfterWrite(config.getExpireAfterWrite().toMillis(), TimeUnit.MILLISECONDS);
        }

        if (config.getExpireAfterAccess() != null) {
            builder.expireAfterAccess(config.getExpireAfterAccess().toMillis(), TimeUnit.MILLISECONDS);
        }

        if (config.getRefreshAfterWrite() != null) {
            builder.refreshAfterWrite(config.getRefreshAfterWrite().toMillis(), TimeUnit.MILLISECONDS);
        }

        if (config.isRecordStats()) {
            builder.recordStats();
        }

        // 判断是否需要LoadingCache模式（有refreshAfterWrite配置）
        this.useLoadingCache = config.getRefreshAfterWrite() != null && config.getCacheLoader() != null;

        if (useLoadingCache) {
            // 使用LoadingCache模式以支持自动刷新
            // 创建Caffeine CacheLoader适配器
            com.github.benmanes.caffeine.cache.CacheLoader<K, V> caffeineCacheLoader =
                    key -> config.<K, V>getCacheLoader().load(key);

            this.caffeineLoadingCache = builder.build(caffeineCacheLoader);
            this.caffeineCache = caffeineLoadingCache; // LoadingCache实现了Cache接口
        } else {
            // 使用普通Cache模式
            this.caffeineCache = builder.build();
            this.caffeineLoadingCache = null;
        }

        // 设置CacheLoader
        if (config.getCacheLoader() != null) {
            setLoader(config.getCacheLoader());
        }
    }

    @Override
    public V get(K key) {
        if (key == null) {
            return null;
        }

        V value = caffeineCache.getIfPresent(key);
        if (value != null) {
            recordHit();
        } else {
            recordMiss();
        }
        return value;
    }

    @Override
    public Map<K, V> getAll(Set<K> keys) {
        if (keys == null || keys.isEmpty()) {
            return Map.of();
        }

        Map<K, V> result = caffeineCache.getAllPresent(keys);

        // 记录统计
        for (K key : keys) {
            if (result.containsKey(key)) {
                recordHit();
            } else {
                recordMiss();
            }
        }

        return result;
    }

    @Override
    public void put(K key, V value) {
        if (key != null && value != null) {
            caffeineCache.put(key, value);
        }
    }

    // 为了兼容性，LocalTier也需要支持带TTL的put方法，但实际上忽略TTL
    public void put(K key, V value, Duration ttl) {
        // Caffeine不支持单独设置TTL，忽略ttl参数
        put(key, value);
    }

    @Override
    public void putAll(Map<K, V> map) {
        if (map != null && !map.isEmpty()) {
            caffeineCache.putAll(map);
        }
    }

    @Override
    public boolean putIfAbsent(K key, V value) {
        if (key != null && value != null) {
            return caffeineCache.asMap().putIfAbsent(key, value) == null;
        }
        return false;
    }

    @Override
    public void evict(K key) {
        if (key != null) {
            caffeineCache.invalidate(key);
            recordEviction();
        }
    }

    @Override
    public void evictAll(Set<K> keys) {
        if (keys != null && !keys.isEmpty()) {
            caffeineCache.invalidateAll(keys);
            for (int i = 0; i < keys.size(); i++) {
                recordEviction();
            }
        }
    }

    @Override
    public void clear() {
        long size = caffeineCache.estimatedSize();
        caffeineCache.invalidateAll();
        for (int i = 0; i < size; i++) {
            recordEviction();
        }
    }

    @Override
    public boolean containsKey(K key) {
        return key != null && caffeineCache.getIfPresent(key) != null;
    }

    @Override
    public long size() {
        return caffeineCache.estimatedSize();
    }

    @Override
    public void cleanUp() {
        caffeineCache.cleanUp();
    }

    @Override
    public void refresh(K key) {
        if (key != null) {
            if (useLoadingCache && caffeineLoadingCache != null) {
                // 如果使用LoadingCache，调用其refresh方法触发后台刷新
                caffeineLoadingCache.refresh(key);
            } else {
                // 否则只是简单地使缓存失效
                caffeineCache.invalidate(key);
            }
        }
    }

    // ==================== LocalTier特有方法实现 ====================

    @Override
    public ConcurrentMap<K, V> asMap() {
        return caffeineCache.asMap();
    }

    @Override
    protected void onConfigurationChange() {
        // Caffeine不支持运行时配置变更，这里可以记录日志或警告
    }

    /**
     * 获取底层Caffeine缓存实例
     */
    public com.github.benmanes.caffeine.cache.Cache<K, V> getCaffeineCache() {
        return caffeineCache;
    }

    /**
     * 本地缓存配置类
     */
    public static class LocalTierConfig {
        private long maximumSize = 10000;
        private Duration expireAfterWrite;
        private Duration expireAfterAccess;
        private Duration refreshAfterWrite;
        private boolean recordStats = true;
        private CacheLoader<?, ?> cacheLoader;

        public long getMaximumSize() {
            return maximumSize;
        }

        public LocalTierConfig setMaximumSize(long maximumSize) {
            this.maximumSize = maximumSize;
            return this;
        }

        public Duration getExpireAfterWrite() {
            return expireAfterWrite;
        }

        public LocalTierConfig setExpireAfterWrite(Duration expireAfterWrite) {
            this.expireAfterWrite = expireAfterWrite;
            return this;
        }

        public Duration getExpireAfterAccess() {
            return expireAfterAccess;
        }

        public LocalTierConfig setExpireAfterAccess(Duration expireAfterAccess) {
            this.expireAfterAccess = expireAfterAccess;
            return this;
        }

        public boolean isRecordStats() {
            return recordStats;
        }

        public LocalTierConfig setRecordStats(boolean recordStats) {
            this.recordStats = recordStats;
            return this;
        }

        public Duration getRefreshAfterWrite() {
            return refreshAfterWrite;
        }

        public LocalTierConfig setRefreshAfterWrite(Duration refreshAfterWrite) {
            this.refreshAfterWrite = refreshAfterWrite;
            return this;
        }

        @SuppressWarnings("unchecked")
        public <K, V> CacheLoader<K, V> getCacheLoader() {
            return (CacheLoader<K, V>) cacheLoader;
        }

        public <K, V> LocalTierConfig setCacheLoader(CacheLoader<K, V> cacheLoader) {
            this.cacheLoader = cacheLoader;
            return this;
        }
    }
}