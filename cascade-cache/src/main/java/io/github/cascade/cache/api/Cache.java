package io.github.cascade.cache.api;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * 基础缓存接口，定义了所有缓存层的核心操作
 * 这是整个缓存体系的根接口
 *
 * @param <K> 缓存键类型
 * @param <V> 缓存值类型
 * @author cascade
 */
public interface Cache<K, V> {

    // ==================== 基础读取操作 ====================

    /**
     * 获取缓存值
     *
     * @param key 缓存键
     * @return 缓存值，如果不存在则返回null
     */
    V get(K key);

    /**
     * 获取缓存值，如果不存在则使用loader加载
     *
     * @param key    缓存键
     * @param loader 加载函数
     * @return 缓存值
     */
    V get(K key, Function<K, V> loader);

    /**
     * 批量获取缓存值
     *
     * @param keys 缓存键集合
     * @return 键值对映射
     */
    Map<K, V> getAll(Set<K> keys);

    /**
     * 批量获取缓存值，如果不存在则使用loader加载
     *
     * @param keys   缓存键集合
     * @param loader 批量加载函数
     * @return 键值对映射
     */
    Map<K, V> getAll(Set<K> keys, Function<Set<K>, Map<K, V>> loader);

    // ==================== 基础写入操作 ====================

    /**
     * 存储缓存值
     *
     * @param key   缓存键
     * @param value 缓存值
     */
    void put(K key, V value);

    /**
     * 批量存储缓存值
     *
     * @param map 键值对映射
     */
    void putAll(Map<K, V> map);

    /**
     * 如果键不存在则存储
     *
     * @param key   缓存键
     * @param value 缓存值
     * @return 如果存储成功返回true，否则返回false
     */
    boolean putIfAbsent(K key, V value);

    // ==================== 删除操作 ====================

    /**
     * 删除缓存项
     *
     * @param key 缓存键
     */
    void evict(K key);

    /**
     * 批量删除缓存项
     *
     * @param keys 缓存键集合
     */
    void evictAll(Set<K> keys);

    /**
     * 清空所有缓存
     */
    void clear();

    // ==================== 查询操作 ====================

    /**
     * 检查键是否存在
     *
     * @param key 缓存键
     * @return 如果存在返回true，否则返回false
     */
    boolean containsKey(K key);

    /**
     * 获取缓存大小
     *
     * @return 缓存项数量
     */
    long size();

    /**
     * 获取估算的缓存大小
     *
     * @return 估算的缓存项数量
     */
    long estimatedSize();

    /**
     * 检查缓存是否为空
     *
     * @return 如果为空返回true，否则返回false
     */
    boolean isEmpty();

    // ==================== 元数据和管理 ====================

    /**
     * 获取缓存名称
     *
     * @return 缓存名称
     */
    String getName();

    /**
     * 获取缓存层级类型
     *
     * @return 缓存层级
     */
    CacheTier getTier();

    /**
     * 获取缓存统计信息
     *
     * @return 缓存统计
     */
    CacheStats getStats();

    // ==================== 维护操作 ====================

    /**
     * 刷新缓存项
     *
     * @param key 缓存键
     */
    void refresh(K key);

    /**
     * 异步刷新缓存项
     *
     * @param key 缓存键
     * @return CompletableFuture
     */
    CompletableFuture<Void> refreshAsync(K key);

    /**
     * 清理过期的缓存项
     */
    void cleanUp();

    // ==================== 加载器支持 ====================

    /**
     * 设置缓存加载器
     * 用于在缓存未命中时自动加载数据
     *
     * @param loader 缓存加载器
     */
    void setLoader(CacheLoader<K, V> loader);

    /**
     * 获取当前缓存加载器
     *
     * @return 当前的缓存加载器，如果没有设置则返回null
     */
    CacheLoader<K, V> getLoader();
}