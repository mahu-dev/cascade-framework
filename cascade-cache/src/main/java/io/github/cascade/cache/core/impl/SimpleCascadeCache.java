package io.github.cascade.cache.core.impl;

import io.github.cascade.cache.api.Cache;
import io.github.cascade.cache.api.CacheLoader;
import io.github.cascade.cache.api.CacheStats;
import io.github.cascade.cache.api.CacheTier;
import io.github.cascade.cache.protection.SimplifiedCacheProtectionManager;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * 简化版Cascade缓存实现
 * 基于内存的单级缓存，作为基础实现
 */
public class SimpleCascadeCache<K, V> implements Cache<K, V> {

    private static final Logger logger = LoggerFactory.getLogger(SimpleCascadeCache.class);

    private final String name;
    private final ConcurrentHashMap<K, CacheEntry<V>> cache;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private CacheLoader<K, V> cacheLoader;
    /**
     * -- SETTER --
     * 设置防护管理器
     * -- GETTER --
     * 获取防护管理器
     */
    @Getter
    @Setter
    private SimplifiedCacheProtectionManager protectionManager;

    // 统计信息
    private final AtomicLong hitCount = new AtomicLong(0);
    private final AtomicLong missCount = new AtomicLong(0);
    private final AtomicLong loadCount = new AtomicLong(0);
    private final AtomicLong evictionCount = new AtomicLong(0);

    public SimpleCascadeCache(String name) {
        this.name = name;
        this.cache = new ConcurrentHashMap<>();
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public CacheTier getTier() {
        return CacheTier.L1; // SimpleCascadeCache是单级内存缓存
    }

    @Override
    public void setLoader(CacheLoader<K, V> loader) {
        this.cacheLoader = loader;
    }

    @Override
    public CacheLoader<K, V> getLoader() {
        return cacheLoader;
    }

    @Override
    public V get(K key) {
        checkNotClosed();

        // 如果启用了防护机制，首先检查布隆过滤器
        if (protectionManager != null && !protectionManager.mightContain(String.valueOf(key))) {
            missCount.incrementAndGet();
            return null; // 布隆过滤器判断key不存在
        }

        CacheEntry<V> entry = cache.get(key);
        if (entry != null && !entry.isExpired()) {
            hitCount.incrementAndGet();
            return entry.getValue();
        } else {
            missCount.incrementAndGet();
            if (entry != null && entry.isExpired()) {
                cache.remove(key);
                evictionCount.incrementAndGet();
            }
            return null;
        }
    }

    @Override
    public V get(K key, Function<K, V> loader) {
        checkNotClosed();
        CacheEntry<V> entry = cache.get(key);
        if (entry != null && !entry.isExpired()) {
            hitCount.incrementAndGet();
            return entry.getValue();
        }

        missCount.incrementAndGet();
        loadCount.incrementAndGet();

        // 移除过期条目
        if (entry != null && entry.isExpired()) {
            cache.remove(key);
            evictionCount.incrementAndGet();
        }

        // 使用加载器加载值
        V value = loader.apply(key);
        if (value != null) {
            put(key, value);
        }
        return value;
    }

    @Override
    public Map<K, V> getAll(Set<K> keys) {
        checkNotClosed();
        Map<K, V> result = new HashMap<>();
        for (K key : keys) {
            V value = get(key);
            if (value != null) {
                result.put(key, value);
            }
        }
        return result;
    }

    @Override
    public Map<K, V> getAll(Set<K> keys, Function<Set<K>, Map<K, V>> loader) {
        checkNotClosed();
        Map<K, V> result = new HashMap<>();
        Set<K> missingKeys = new HashSet<>();

        for (K key : keys) {
            V value = get(key);
            if (value != null) {
                result.put(key, value);
            } else {
                missingKeys.add(key);
            }
        }

        if (!missingKeys.isEmpty()) {
            loadCount.addAndGet(missingKeys.size());
            Map<K, V> loaded = loader.apply(missingKeys);
            if (loaded != null) {
                putAll(loaded);
                result.putAll(loaded);
            }
        }

        return result;
    }

    @Override
    public void put(K key, V value) {
        checkNotClosed();
        cache.put(key, new CacheEntry<>(value));

        // 如果启用了防护机制，将key添加到布隆过滤器
        if (protectionManager != null) {
            protectionManager.addToBloomFilter(String.valueOf(key));
        }
    }

    public void put(K key, V value, Duration ttl) {
        checkNotClosed();
        cache.put(key, new CacheEntry<>(value, ttl));
    }

    @Override
    public void putAll(Map<K, V> map) {
        checkNotClosed();
        for (Map.Entry<K, V> entry : map.entrySet()) {
            put(entry.getKey(), entry.getValue());
        }
    }

    @Override
    public boolean putIfAbsent(K key, V value) {
        checkNotClosed();
        CacheEntry<V> existing = cache.get(key);
        if (existing == null || existing.isExpired()) {
            if (existing != null && existing.isExpired()) {
                evictionCount.incrementAndGet();
            }
            cache.put(key, new CacheEntry<>(value));
            return true;
        }
        return false;
    }

    @Override
    public void evict(K key) {
        checkNotClosed();
        if (cache.remove(key) != null) {
            evictionCount.incrementAndGet();
        }
    }

    @Override
    public void evictAll(Set<K> keys) {
        checkNotClosed();
        for (K key : keys) {
            evict(key);
        }
    }

    @Override
    public void clear() {
        checkNotClosed();
        long size = cache.size();
        cache.clear();
        evictionCount.addAndGet(size);
    }

    @Override
    public boolean containsKey(K key) {
        checkNotClosed();
        CacheEntry<V> entry = cache.get(key);
        if (entry != null && entry.isExpired()) {
            cache.remove(key);
            evictionCount.incrementAndGet();
            return false;
        }
        return entry != null;
    }

    @Override
    public long size() {
        checkNotClosed();
        return cache.size();
    }

    @Override
    public long estimatedSize() {
        return size();
    }

    @Override
    public boolean isEmpty() {
        checkNotClosed();
        return cache.isEmpty();
    }

    @Override
    public void refresh(K key) {
        checkNotClosed();
        // 简单实现：移除键以强制重新加载
        evict(key);
    }

    @Override
    public CompletableFuture<Void> refreshAsync(K key) {
        return CompletableFuture.runAsync(() -> refresh(key));
    }

    @Override
    public CacheStats getStats() {
        return new SimpleCacheStats();
    }

    @Override
    public void cleanUp() {
        checkNotClosed();
        // 清理过期条目
        cache.entrySet().removeIf(entry -> {
            if (entry.getValue().isExpired()) {
                evictionCount.incrementAndGet();
                return true;
            }
            return false;
        });
    }

    /**
     * 启动缓存
     */
    public void start() {
        // 简单实现，无需特殊启动逻辑
        logger.info("Cache " + name + " started");
    }

    /**
     * 停止缓存
     */
    public void stop() {
        // 简单实现，无需特殊停止逻辑
        logger.info("Cache " + name + " stopped");
    }

    /**
     * 关闭缓存
     */
    public void close() {
        if (closed.compareAndSet(false, true)) {
            clear();
            logger.info("Cache " + name + " closed");
        }
    }

    private void checkNotClosed() {
        if (closed.get()) {
            throw new IllegalStateException("Cache " + name + " has been closed");
        }
    }

    /**
     * 缓存条目
     */
    private static class CacheEntry<V> {
        private final V value;
        private final long expirationTime;

        public CacheEntry(V value) {
            this.value = value;
            this.expirationTime = Long.MAX_VALUE; // 永不过期
        }

        public CacheEntry(V value, Duration ttl) {
            this.value = value;
            this.expirationTime = System.currentTimeMillis() + ttl.toMillis();
        }

        public V getValue() {
            return value;
        }

        public boolean isExpired() {
            return System.currentTimeMillis() > expirationTime;
        }
    }

    /**
     * 简单缓存统计实现
     */
    private class SimpleCacheStats implements CacheStats {

        @Override
        public long hitCount() {
            return hitCount.get();
        }

        @Override
        public long missCount() {
            return missCount.get();
        }

        @Override
        public long requestCount() {
            return hitCount() + missCount();
        }

        @Override
        public double hitRate() {
            long hits = hitCount();
            long total = requestCount();
            return total == 0 ? 1.0 : (double) hits / total;
        }

        @Override
        public double missRate() {
            return 1.0 - hitRate();
        }

        @Override
        public long loadCount() {
            return SimpleCascadeCache.this.loadCount.get();
        }

        @Override
        public long loadExceptionCount() {
            return 0; // 简单实现不跟踪异常
        }

        @Override
        public long totalLoadTime() {
            return 0; // 简单实现不跟踪加载时间
        }

        @Override
        public double averageLoadPenalty() {
            return 0; // 简单实现不跟踪加载时间
        }

        @Override
        public long evictionCount() {
            return SimpleCascadeCache.this.evictionCount.get();
        }

        @Override
        public long evictionWeight() {
            return evictionCount(); // 简单实现，权重等于次数
        }

        @Override
        public void reset() {
            hitCount.set(0);
            missCount.set(0);
            SimpleCascadeCache.this.loadCount.set(0);
            SimpleCascadeCache.this.evictionCount.set(0);
        }

        @Override
        public String toString() {
            return String.format("CacheStats{hits=%d, misses=%d, hitRate=%.2f%%, loads=%d, evictions=%d}",
                    hitCount(), missCount(), hitRate() * 100, loadCount(), evictionCount());
        }
    }
}