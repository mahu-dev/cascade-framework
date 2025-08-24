package io.github.cascade.cache.strategy;

import io.github.cascade.cache.api.CacheLoader;
import io.github.cascade.cache.config.CascadeCacheConfiguration;
import io.github.cascade.cache.core.unified.CacheEngine;
import io.github.cascade.cache.metrics.CachePerformanceMonitor;
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
public class SingleTierCacheStrategy<K, V> {
    
    private final String cacheName;
    private final CacheEngine<K, V> engine;
    private final CachePerformanceMonitor performanceMonitor;
    
    // 配置相关
    private CascadeCacheConfiguration cacheConfiguration;
    private CacheLoader<K, V> cacheLoader;
    
    public SingleTierCacheStrategy(String cacheName,
                                   CacheEngine<K, V> engine,
                                   CachePerformanceMonitor performanceMonitor) {
        this.cacheName = cacheName;
        this.engine = engine;
        this.performanceMonitor = performanceMonitor;
    }
    
    public void setCacheConfiguration(CascadeCacheConfiguration cacheConfiguration) {
        this.cacheConfiguration = cacheConfiguration;
    }
    
    public void setCacheLoader(CacheLoader<K, V> cacheLoader) {
        this.cacheLoader = cacheLoader;
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
                performanceMonitor.recordL1Hit();
                log.debug("Cache hit: key={}", key);
                return value;
            }

            // 2. CacheLoader回源加载
            value = loadFromSource(key);
            if (value != null) {
                return value;
            }

            // 3. 缓存未命中
            performanceMonitor.recordMiss();
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
            log.error("Failed to load from source: key={}, error: {}", key, e.getMessage(), e);
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