package io.github.cascade.cache.strategy;

import io.github.cascade.cache.api.CacheLoader;
import io.github.cascade.cache.api.CacheStats;
import io.github.cascade.cache.config.CascadeCacheConfiguration;
import io.github.cascade.cache.core.unified.CacheEngine;
import io.github.cascade.cache.exception.CacheExceptionHandler;
import io.github.cascade.cache.metrics.CacheMetrics;
import io.github.cascade.cache.metrics.CacheMetricsCollector;
import io.github.cascade.cache.metrics.DetailedCacheMetrics;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * 单级缓存操作策略
 * 负责处理单级缓存的读写逻辑
 *
 * @author cascade
 */
@Slf4j
public class SingleTierCacheStrategy<K, V> implements CacheStrategy<K, V> {

    private final String cacheName;
    private final CacheEngine<K, V> engine;
    private final CacheMetricsCollector metricsCollector;
    private final CacheExceptionHandler exceptionHandler;

    // 配置相关
    @Setter
    private CascadeCacheConfiguration cacheConfiguration;
    @Setter
    private CacheLoader<K, V> cacheLoader;

    public SingleTierCacheStrategy(String cacheName,
                                   CacheEngine<K, V> engine,
                                   CacheMetricsCollector metricsCollector) {
        this.cacheName = cacheName;
        this.engine = engine;
        this.metricsCollector = metricsCollector;
        this.exceptionHandler = CacheExceptionHandler.getInstance();
    }

    // ==================== 核心查询策略 ====================

    /**
     * 从单级缓存获取值
     */
    public V getFromCache(K key) {
        long startTime = System.nanoTime();

        try {
            // 1. 缓存查询
            V value = engine.get(key);
            if (value != null) {
                metricsCollector.recordL1Hit();
                log.debug("Cache hit: key={}", key);
                return value;
            }

            // 2. CacheLoader回源加载
            value = loadFromSource(key);
            if (value != null) {
                return value;
            }

            // 3. 缓存未命中
            metricsCollector.recordMiss();
            log.debug("Cache miss: key={}", key);
            return null;

        } finally {
            // 性能监控
            long duration = System.nanoTime() - startTime;
            if (duration > 5_000_000) { // 超过5ms记录警告（单级缓存应该更快）
                log.warn("Cache get operation took {}ms for key: {}", duration / 1_000_000, key);
            }
        }
    }

    /**
     * 批量获取
     */
    public Map<K, V> getAllFromCache(Set<K> keys) {
        return engine.getAll(keys);
    }

    // ==================== 数据加载策略 ====================

    /**
     * 从数据源加载数据
     */
    private V loadFromSource(K key) {
        // 检查是否应该使用CacheLoader
        if (!shouldLoadFromSource()) {
            return null;
        }

        try {
            log.debug("Loading from source: key={}", key);
            V value = cacheLoader.load(key);

            if (value != null) {
                // 加载成功，存储到缓存
                engine.put(key, value, getDefaultTtl());
                log.debug("Successfully loaded and cached: key={}", key);
                return value;
            }

        } catch (Exception e) {
            // 数据源加载失败属于ERROR级别，这会影响业务数据获取
            exceptionHandler.handleKnownException("cache-loader", e, 
                CacheExceptionHandler.ErrorSeverity.ERROR, null);
        }

        return null;
    }

    /**
     * 判断是否应该从数据源加载
     */
    private boolean shouldLoadFromSource() {
        // 必须有CacheLoader
        if (cacheLoader == null) {
            return false;
        }

        // 必须有配置且刷新未启用（避免重复加载）
        if (cacheConfiguration == null) {
            return true; // 默认允许加载
        }

        return !cacheConfiguration.getRefresh().isEnabled();
    }

    // ==================== 写入策略 ====================

    /**
     * 写入缓存
     */
    public void putToCache(K key, V value, Duration ttl) {
        try {
            engine.put(key, value, ttl);
        } catch (Exception e) {
            log.warn("Cache put failed: key={}", key, e);
            throw e;
        }
    }

    /**
     * 批量写入缓存
     */
    public void putAllToCache(Map<K, V> map) {
        if (map == null || map.isEmpty()) return;

        try {
            engine.putAll(map);
        } catch (Exception e) {
            log.warn("Cache putAll failed: size={}", map.size(), e);
            throw e;
        }
    }

    // ==================== CacheStrategy接口实现 ====================

    @Override
    public V get(K key) {
        return getFromCache(key);
    }

    @Override
    public Map<K, V> getAll(Set<K> keys) {
        return getAllFromCache(keys);
    }

    @Override
    public void put(K key, V value) {
        putToCache(key, value, getDefaultTtl());
    }

    @Override
    public void put(K key, V value, Duration ttl) {
        putToCache(key, value, ttl);
    }

    @Override
    public void putAll(Map<K, V> map) {
        putAllToCache(map);
    }

    @Override
    public boolean putIfAbsent(K key, V value) {
        if (key == null || value == null) return false;
        
        boolean inserted = engine.putIfAbsent(key, value);
        log.debug("Single-tier cache putIfAbsent: key={}, inserted={}", key, inserted);
        return inserted;
    }

    @Override
    public boolean putIfAbsent(K key, V value, Duration ttl) {
        if (key == null || value == null) return false;
        
        boolean inserted = engine.putIfAbsent(key, value, ttl);
        log.debug("Single-tier cache putIfAbsent with TTL: key={}, ttl={}, inserted={}", key, ttl, inserted);
        return inserted;
    }

    @Override
    public void evict(K key) {
        if (key == null) return;

        engine.evict(key);
        log.debug("Single-tier cache evict: key={}", key);
    }

    @Override
    public void evictAll(Set<K> keys) {
        if (keys == null || keys.isEmpty()) return;

        engine.evictAll(keys);
        log.debug("Single-tier cache evictAll: size={}", keys.size());
    }

    @Override
    public void clear() {
        engine.clear();
        log.debug("Single-tier cache cleared: {}", cacheName);
    }

    @Override
    public boolean containsKey(K key) {
        if (key == null) return false;

        return engine.containsKey(key);
    }

    @Override
    public long size() {
        return engine.size();
    }

    @Override
    public CacheStats getStats() {
        return engine.getStats();
    }

    @Override
    public void cleanUp() {
        engine.cleanUp();
    }

    @Override
    public DetailedCacheMetrics getDetailedMetrics() {
        return metricsCollector.getStats();
    }

    @Override
    public CacheMetrics.CacheHealthStatus getHealthStatus() {
        return metricsCollector.getHealthStatus();
    }

    @Override
    public void resetPerformanceStats() {
        metricsCollector.reset();
    }

    @Override
    public boolean isMultiTier() {
        return false; // 单级缓存策略始终为false
    }

    @Override
    public String getStrategyName() {
        return "SingleTierCacheStrategy[" + cacheName + "]";
    }

    // ==================== 辅助方法 ====================

    /**
     * 获取默认TTL
     */
    private Duration getDefaultTtl() {
        if (cacheConfiguration != null && cacheConfiguration.getL1() != null) {
            Duration ttl = cacheConfiguration.getL1().getExpireAfterWrite();
            if (ttl != null) {
                return ttl;
            }
        }
        return Duration.ofHours(1); // 默认1小时
    }
}