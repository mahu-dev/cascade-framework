package io.github.cascade.cache.core;

import io.github.cascade.cache.config.CascadeCacheProperties;

import java.util.Collection;
import java.util.function.Function;

/**
 * @author lionel lionelk@163.com
 * =============================
 * Date: 2025/7/10
 * Time: 13:23
 * =============================
 */

/**
 * 缓存管理器接口
 * <p>
 * 设计原则：
 * 1. 统一管理：管理所有缓存实例（支持不同类型的缓存）
 * 2. 类型安全：支持方法级别泛型
 * 3. 配置灵活：支持不同配置策略
 * 4. 生命周期：管理缓存的创建和销毁
 * <p>
 * 重要变更（2025-10-29）：
 * - 移除接口级别的泛型约束，允许管理不同类型的缓存
 * - 所有方法使用方法级别的泛型参数，保持类型安全
 *
 * @author cascade
 */
public interface CacheManager {

    // ==================== 缓存创建与获取 ====================

    /**
     * 获取或创建缓存（使用默认配置）
     */
    <K, V> Cache<K, V> getOrCreateCache(String cacheName, Class<K> keyType, Class<V> valueType);

    <K, V> Cache<K, V> getOrCreateAutoRefreshCache(String cacheName,
                                                   Class<K> keyType,
                                                   Class<V> valueType);


    <K, V> Cache<K, V> getOrCreateDistributedAutoRefreshCache(String cacheName,
                                                              Class<K> keyType,
                                                              Class<V> valueType);

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
     *
     * @param cacheName 缓存名称
     * @return 缓存实例，如果不存在则返回 null
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

    // ==================== 扩展功能 ====================

    /**
     * 获取或创建缓存刷新器
     *
     * @param cacheName 缓存名称
     * @return 缓存刷新器，创建失败返回null
     */
    <K, V> CacheRefresher<K, V> getOrCreateCacheRefresher(String cacheName);
}