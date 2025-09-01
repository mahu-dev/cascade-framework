package io.github.cascade.cache.simple;

import io.github.cascade.cache.config.SyncProperties;

/**
 * 缓存同步工厂接口
 * <p>
 * 设计原则：
 * 1. 工厂模式：根据配置创建不同类型的同步器
 * 2. 类型安全：使用泛型确保类型安全
 * 3. 配置驱动：基于SyncProperties配置创建同步器
 * 4. 可扩展性：支持添加新的同步类型
 *
 * @author cascade
 */
public interface CacheSyncFactory {

    /**
     * 创建缓存同步器
     *
     * @param syncConfig 同步配置
     * @param <K>        键类型
     * @param <V>        值类型
     * @return 缓存同步器实例
     */
    <K, V> CacheSync<K, V> createCacheSync(SyncProperties syncConfig);

    /**
     * 检查是否支持指定的同步类型
     *
     * @param syncType 同步类型
     * @return 是否支持
     */
    boolean supports(SyncProperties.SyncType syncType);
}