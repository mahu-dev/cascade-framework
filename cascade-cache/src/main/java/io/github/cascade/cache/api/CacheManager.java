package io.github.cascade.cache.api;

import io.github.cascade.api.HealthStatus;

import java.util.Collection;
import java.util.Map;
import java.util.function.Function;

/**
 * 缓存管理器接口
 * 负责创建、管理和监控缓存实例
 * 简化版本，只保留核心功能
 *
 * @author cascade
 */
public interface CacheManager {

    // ==================== 核心缓存管理方法 ====================

    /**
     * 获取已存在的缓存
     * 不会自动创建缓存，如果缓存不存在返回null
     *
     * @param cacheName 缓存名称
     * @param <K>       键类型
     * @param <V>       值类型
     * @return 缓存实例，不存在时返回null
     */
    <K, V> Cache<K, V> getCache(String cacheName);

    <K, V> Cache<K, V> getOrCreateCache(String cacheName);

    /**
     * 注册缓存
     * 将缓存实例注册到管理器中
     *
     * @param cacheName 缓存名称
     * @param cache     缓存实例
     * @param <K>       键类型
     * @param <V>       值类型
     * @return 是否注册成功
     */
    <K, V> boolean registerCache(String cacheName, Cache<K, V> cache);

    /**
     * 强制注册缓存（覆盖已存在的缓存）
     *
     * @param cacheName 缓存名称
     * @param cache     缓存实例
     * @param <K>       键类型
     * @param <V>       值类型
     * @return 被覆盖的旧缓存实例，如果没有则返回null
     */
    <K, V> Cache<K, V> forceRegisterCache(String cacheName, Cache<K, V> cache);

    /**
     * 删除缓存
     *
     * @param cacheName 缓存名称
     * @return 被删除的缓存实例，如果不存在则返回null
     */
    Cache<?, ?> removeCache(String cacheName);

    /**
     * 获取所有缓存名称
     *
     * @return 缓存名称集合
     */
    Collection<String> getCacheNames();

    /**
     * 获取所有缓存实例
     *
     * @return 缓存映射表
     */
    Map<String, Cache<?, ?>> getAllCaches();

    /**
     * 清空所有缓存
     */
    void clearAllCaches();

    /**
     * 检查缓存是否存在
     *
     * @param cacheName 缓存名称
     * @return 是否存在
     */
    boolean containsCache(String cacheName);

    // ==================== 生命周期管理 ====================

    /**
     * 关闭管理器，释放所有资源
     */
    void close();

    /**
     * 检查管理器是否已关闭
     *
     * @return 是否已关闭
     */
    boolean isClosed();

    // ==================== 监控和统计 ====================

    /**
     * 获取健康状态
     *
     * @return 健康状态
     */
    HealthStatus getHealthStatus();

    /**
     * 获取缓存数量
     *
     * @return 缓存总数
     */
    int getCacheCount();

    /**
     * 获取统计信息
     *
     * @return 统计信息映射表
     */
    Map<String, Object> getStats();

    // ==================== 工厂方法 ====================

    /**
     * 设置缓存工厂函数
     *
     * @param cacheFactory 缓存工厂函数
     */
    void setCacheFactory(Function<String, Cache<?, ?>> cacheFactory);

    /**
     * 获取缓存工厂函数
     *
     * @return 缓存工厂函数
     */
    Function<String, Cache<?, ?>> getCacheFactory();
}