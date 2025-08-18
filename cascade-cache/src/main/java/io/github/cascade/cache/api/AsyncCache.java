package io.github.cascade.cache.api;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * 异步缓存接口，提供非阻塞的缓存操作
 *
 * @param <K> 缓存键类型
 * @param <V> 缓存值类型
 * @author cascade
 */
public interface AsyncCache<K, V> {

    /**
     * 异步获取缓存值
     *
     * @param key 缓存键
     * @return CompletableFuture包装的缓存值
     */
    CompletableFuture<V> getAsync(K key);

    /**
     * 异步获取缓存值，如果不存在则使用loader加载
     *
     * @param key    缓存键
     * @param loader 异步加载函数
     * @return CompletableFuture包装的缓存值
     */
    CompletableFuture<V> getAsync(K key, Function<K, CompletableFuture<V>> loader);

    /**
     * 异步批量获取缓存值
     *
     * @param keys 缓存键集合
     * @return CompletableFuture包装的键值对映射
     */
    CompletableFuture<Map<K, V>> getAllAsync(Set<K> keys);

    /**
     * 异步批量获取缓存值，如果不存在则使用loader加载
     *
     * @param keys   缓存键集合
     * @param loader 异步批量加载函数
     * @return CompletableFuture包装的键值对映射
     */
    CompletableFuture<Map<K, V>> getAllAsync(Set<K> keys, Function<Set<K>, CompletableFuture<Map<K, V>>> loader);

    /**
     * 异步存储缓存值
     *
     * @param key   缓存键
     * @param value 缓存值
     * @return CompletableFuture表示操作完成
     */
    CompletableFuture<Void> putAsync(K key, V value);

    /**
     * 异步存储缓存值，指定过期时间
     *
     * @param key   缓存键
     * @param value 缓存值
     * @param ttl   过期时间
     * @return CompletableFuture表示操作完成
     */
    CompletableFuture<Void> putAsync(K key, V value, Duration ttl);

    /**
     * 异步批量存储缓存值
     *
     * @param map 键值对映射
     * @return CompletableFuture表示操作完成
     */
    CompletableFuture<Void> putAllAsync(Map<K, V> map);

    /**
     * 异步条件存储（如果键不存在则存储）
     *
     * @param key   缓存键
     * @param value 缓存值
     * @return CompletableFuture包装的布尔值，表示是否存储成功
     */
    CompletableFuture<Boolean> putIfAbsentAsync(K key, V value);

    /**
     * 异步删除缓存项
     *
     * @param key 缓存键
     * @return CompletableFuture表示操作完成
     */
    CompletableFuture<Void> evictAsync(K key);

    /**
     * 异步批量删除缓存项
     *
     * @param keys 缓存键集合
     * @return CompletableFuture表示操作完成
     */
    CompletableFuture<Void> evictAllAsync(Set<K> keys);

    /**
     * 异步清空所有缓存
     *
     * @return CompletableFuture表示操作完成
     */
    CompletableFuture<Void> clearAsync();

    /**
     * 异步检查键是否存在
     *
     * @param key 缓存键
     * @return CompletableFuture包装的布尔值
     */
    CompletableFuture<Boolean> containsKeyAsync(K key);

    /**
     * 异步获取缓存大小
     *
     * @return CompletableFuture包装的缓存大小
     */
    CompletableFuture<Long> sizeAsync();

    /**
     * 异步刷新缓存项
     *
     * @param key 缓存键
     * @return CompletableFuture表示操作完成
     */
    CompletableFuture<Void> refreshAsync(K key);

    /**
     * 异步刷新缓存项，使用指定的loader
     *
     * @param key    缓存键
     * @param loader 异步加载函数
     * @return CompletableFuture包装的新值
     */
    CompletableFuture<V> refreshAsync(K key, Function<K, CompletableFuture<V>> loader);

    /**
     * 获取同步视图
     *
     * @return 同步缓存接口
     */
    Cache<K, V> synchronous();

    /**
     * 获取缓存名称
     *
     * @return 缓存名称
     */
    String getName();

    /**
     * 异步获取缓存统计信息
     *
     * @return CompletableFuture包装的缓存统计
     */
    CompletableFuture<CacheStats> getStatsAsync();

    /**
     * 异步清理过期的缓存项
     *
     * @return CompletableFuture表示操作完成
     */
    CompletableFuture<Void> cleanUpAsync();
}