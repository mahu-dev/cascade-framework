package io.github.cascade.cache.builder;

import io.github.cascade.cache.api.Cache;
import io.github.cascade.cache.api.CacheLoader;
import io.github.cascade.cache.config.CascadeCacheConfiguration;

import java.util.concurrent.Executor;

/**
 * 增强功能配置步骤
 * 定义缓存构建过程中的第二步：配置各种增强功能
 * 
 * @param <K> 键类型
 * @param <V> 值类型
 * @author cascade
 */
public interface EnhancementConfigurationStep<K, V> {
    
    // ==================== 防护功能配置 ====================
    
    /**
     * 启用防护机制
     * 使用指定的防护配置（布隆过滤器、随机TTL、分布式锁等）
     */
    EnhancementConfigurationStep<K, V> enableProtection(CascadeCacheConfiguration.ProtectionConfig protectionConfig);
    
    /**
     * 启用基础防护机制
     * 使用默认的防护配置
     */
    EnhancementConfigurationStep<K, V> enableProtection();
    
    // ==================== 自动刷新配置 ====================
    
    /**
     * 启用自动刷新
     * 使用指定的刷新配置
     */
    EnhancementConfigurationStep<K, V> enableAutoRefresh(CascadeCacheConfiguration.RefreshConfig refreshConfig);
    
    /**
     * 启用基础自动刷新
     * 使用默认的刷新配置
     */
    EnhancementConfigurationStep<K, V> enableAutoRefresh();
    
    // ==================== 缓存同步配置 ====================
    
    /**
     * 启用缓存同步
     * 使用指定的同步配置
     */
    EnhancementConfigurationStep<K, V> enableCacheSync(CascadeCacheConfiguration.SyncConfig syncConfig);
    
    /**
     * 启用基础缓存同步
     * 使用默认的同步配置
     */
    EnhancementConfigurationStep<K, V> enableCacheSync();
    
    // ==================== 核心组件配置 ====================
    
    /**
     * 设置缓存加载器
     * 用于在缓存未命中时从数据源加载数据
     */
    EnhancementConfigurationStep<K, V> withCacheLoader(CacheLoader<K, V> cacheLoader);
    
    /**
     * 设置执行器
     * 用于异步操作和并发控制
     */
    EnhancementConfigurationStep<K, V> withExecutor(Executor executor);
    
    // ==================== 构建方法 ====================
    
    /**
     * 构建缓存
     * 使用默认配置构建缓存实例
     */
    Cache<K, V> build();
    
    /**
     * 使用指定配置构建缓存
     * 允许覆盖默认配置
     */
    Cache<K, V> build(CascadeCacheConfiguration cacheConfig);
}