package io.github.cascade.cache.factory;

import io.github.cascade.cache.api.Cache;
import io.github.cascade.cache.config.CascadeCacheConfiguration;

/**
 * 缓存工厂接口
 * 负责根据配置创建不同类型的缓存实例
 * 
 * @author cascade
 */
public interface CacheFactory {
    
    /**
     * 创建缓存实例（使用默认配置）
     * 
     * @param cacheName 缓存名称
     * @param keyType Key类型
     * @param valueType Value类型
     * @return 缓存实例
     */
    <K, V> Cache<K, V> createCache(String cacheName, Class<K> keyType, Class<V> valueType);
    
    /**
     * 创建缓存实例（使用指定配置）
     * 
     * @param cacheName 缓存名称
     * @param config 缓存配置
     * @param keyType Key类型
     * @param valueType Value类型
     * @return 缓存实例
     */
    <K, V> Cache<K, V> createCache(String cacheName, CascadeCacheConfiguration config, Class<K> keyType, Class<V> valueType);
    
    /**
     * 检查工厂是否支持指定的缓存类型
     * 
     * @param cacheType 缓存类型
     * @return 是否支持
     */
    boolean supports(String cacheType);
}