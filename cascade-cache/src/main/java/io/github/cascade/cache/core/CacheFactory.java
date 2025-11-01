package io.github.cascade.cache.core;

import io.github.cascade.cache.api.Cache;

/**
 * 缓存工厂接口
 * 负责根据缓存定义创建具体的缓存实例
 *
 * @author cascade
 */
public interface CacheFactory {

    /**
     * 根据缓存定义创建缓存实例
     *
     * @param definition 缓存定义
     * @param <K>        键类型
     * @param <V>        值类型
     * @return 缓存实例
     */
    <K, V> Cache<K, V> createCache(CacheDefinition<K, V> definition);

    /**
     * 检查是否支持指定的缓存类型
     *
     * @param cacheType 缓存类型
     * @return 是否支持
     */
    boolean supports(CacheType cacheType);

    /**
     * 获取工厂名称，用于日志和调试
     *
     * @return 工厂名称
     */
    String getFactoryName();
}