package io.github.cascade.cache.strategy;

import io.github.cascade.cache.api.CacheLoader;
import io.github.cascade.cache.api.CacheStats;
import io.github.cascade.cache.config.CascadeCacheConfiguration;
import io.github.cascade.cache.metrics.CacheMetrics;
import io.github.cascade.cache.metrics.DetailedCacheMetrics;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * 统一的缓存策略接口
 * 定义了所有缓存操作的标准方法，无论是单级还是多级缓存
 * 
 * @param <K> 键类型
 * @param <V> 值类型
 * @author cascade
 */
public interface CacheStrategy<K, V> {
    
    // ==================== 核心缓存操作 ====================
    
    /**
     * 从缓存获取值
     */
    V get(K key);
    
    /**
     * 批量获取缓存值
     */
    Map<K, V> getAll(Set<K> keys);
    
    /**
     * 存储缓存值（使用默认TTL）
     */
    void put(K key, V value);
    
    /**
     * 带TTL存储缓存值
     */
    void put(K key, V value, Duration ttl);
    
    /**
     * 批量存储缓存值
     */
    void putAll(Map<K, V> map);
    
    /**
     * 删除缓存项
     */
    void evict(K key);
    
    /**
     * 批量删除缓存项
     */
    void evictAll(Set<K> keys);
    
    /**
     * 清空所有缓存
     */
    void clear();
    
    /**
     * 检查是否包含键
     */
    boolean containsKey(K key);
    
    /**
     * 获取缓存大小
     */
    long size();
    
    /**
     * 获取统计信息
     */
    CacheStats getStats();
    
    /**
     * 清理缓存
     */
    void cleanUp();
    
    // ==================== 配置管理 ====================
    
    /**
     * 设置缓存配置
     */
    void setCacheConfiguration(CascadeCacheConfiguration cacheConfiguration);
    
    /**
     * 设置缓存加载器
     */
    void setCacheLoader(CacheLoader<K, V> cacheLoader);
    
    // ==================== 性能监控 ====================
    
    /**
     * 获取详细的缓存性能指标
     */
    DetailedCacheMetrics getDetailedMetrics();
    
    /**
     * 获取缓存健康状态
     */
    CacheMetrics.CacheHealthStatus getHealthStatus();
    
    /**
     * 重置性能监控统计
     */
    void resetPerformanceStats();
    
    // ==================== 策略特性查询 ====================
    
    /**
     * 是否为多级缓存策略
     */
    boolean isMultiTier();
    
    /**
     * 获取策略名称
     */
    String getStrategyName();
    
    /**
     * 获取支持的特性描述
     */
    default String getFeatureDescription() {
        return isMultiTier() ? "Multi-tier cache strategy with L1+L2" : "Single-tier cache strategy";
    }
}