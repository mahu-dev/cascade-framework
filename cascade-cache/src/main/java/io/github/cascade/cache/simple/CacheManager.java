package io.github.cascade.cache.simple;

import io.github.cascade.cache.config.CascadeCacheProperties;

import java.util.Collection;
import java.util.function.Function;

/**
 * 缓存管理器接口
 * <p>
 * 设计原则：
 * 1. 统一管理：管理所有缓存实例
 * 2. 类型安全：支持泛型
 * 3. 配置灵活：支持不同配置策略
 * 4. 生命周期：管理缓存的创建和销毁
 *
 * @author cascade
 */
public interface CacheManager {

    // ==================== 缓存创建与获取 ====================

    /**
     * 获取或创建缓存（使用默认配置）
     */
    <K, V> Cache<K, V> getOrCreateCache(String cacheName, Class<K> keyType, Class<V> valueType);

    /**
     * 获取或创建缓存（使用自定义配置）
     */
    <K, V> Cache<K, V> getOrCreateCache(String cacheName, Class<K> keyType, Class<V> valueType,
                                        CascadeCacheProperties config);

    /**
     * 获取或创建缓存（带加载器）
     */
    <K, V> Cache<K, V> getOrCreateCache(String cacheName, Class<K> keyType, Class<V> valueType,
                                        Function<K, V> loader);

    /**
     * 获取或创建缓存（完整配置）
     */
    <K, V> Cache<K, V> getOrCreateCache(String cacheName, Class<K> keyType, Class<V> valueType,
                                        CascadeCacheProperties config, Function<K, V> loader);

    /**
     * 获取已存在的缓存
     */
    <K, V> Cache<K, V> getCache(String cacheName);

    // ==================== 缓存管理 ====================

    /**
     * 注册缓存实例
     */
    <K, V> boolean registerCache(String cacheName, Cache<K, V> cache);

    /**
     * 移除缓存
     */
    boolean removeCache(String cacheName);

    /**
     * 检查缓存是否存在
     */
    boolean containsCache(String cacheName);

    /**
     * 获取所有缓存名称
     */
    Collection<String> getCacheNames();

    /**
     * 清空所有缓存
     */
    void clearAll();

    /**
     * 获取缓存数量
     */
    int getCacheCount();

    // ==================== 生命周期管理 ====================

    /**
     * 关闭管理器
     */
    void close();

    /**
     * 检查是否已关闭
     */
    boolean isClosed();
}