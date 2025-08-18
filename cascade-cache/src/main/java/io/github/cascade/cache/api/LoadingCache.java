package io.github.cascade.cache.api;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * 支持自动加载的缓存接口
 *
 * @param <K> 缓存键类型
 * @param <V> 缓存值类型
 * @author cascade
 */
public interface LoadingCache<K, V> extends Cache<K, V> {

    /**
     * 获取缓存值，如果不存在则自动加载
     * 使用构建时配置的CacheLoader
     *
     * @param key 缓存键
     * @return 缓存值
     * @throws RuntimeException 如果加载失败
     */
    V getUnchecked(K key);

    /**
     * 批量获取缓存值，如果不存在则自动加载
     * 使用构建时配置的CacheLoader
     *
     * @param keys 缓存键集合
     * @return 键值对映射
     * @throws RuntimeException 如果加载失败
     */
    Map<K, V> getAllUnchecked(Set<K> keys);

    /**
     * 异步获取缓存值，如果不存在则自动加载
     *
     * @param key 缓存键
     * @return CompletableFuture包装的缓存值
     */
    CompletableFuture<V> getAsync(K key);

    /**
     * 异步批量获取缓存值，如果不存在则自动加载
     *
     * @param keys 缓存键集合
     * @return CompletableFuture包装的键值对映射
     */
    CompletableFuture<Map<K, V>> getAllAsync(Set<K> keys);

    /**
     * 刷新缓存项，重新加载值
     *
     * @param key 缓存键
     */
    void refresh(K key);

    /**
     * 异步刷新缓存项
     *
     * @param key 缓存键
     * @return CompletableFuture表示操作完成
     */
    CompletableFuture<Void> refreshAsync(K key);

    /**
     * 批量刷新缓存项
     *
     * @param keys 缓存键集合
     */
    void refreshAll(Set<K> keys);

    /**
     * 异步批量刷新缓存项
     *
     * @param keys 缓存键集合
     * @return CompletableFuture表示操作完成
     */
    CompletableFuture<Void> refreshAllAsync(Set<K> keys);

    /**
     * 获取未包装的缓存视图（不自动加载）
     *
     * @return 基础缓存接口
     */
    Cache<K, V> asCache();

    /**
     * 获取异步缓存视图
     *
     * @return 异步缓存接口
     */
    AsyncCache<K, V> asAsyncCache();

    /**
     * 预加载指定键的值
     *
     * @param key 缓存键
     * @throws Exception 如果加载失败
     */
    void preload(K key) throws Exception;

    /**
     * 批量预加载值
     *
     * @param keys 缓存键集合
     * @throws Exception 如果加载失败
     */
    void preloadAll(Set<K> keys) throws Exception;

    /**
     * 异步预加载指定键的值
     *
     * @param key 缓存键
     * @return CompletableFuture表示操作完成
     */
    CompletableFuture<Void> preloadAsync(K key);

    /**
     * 异步批量预加载值
     *
     * @param keys 缓存键集合
     * @return CompletableFuture表示操作完成
     */
    CompletableFuture<Void> preloadAllAsync(Set<K> keys);

    /**
     * 获取加载统计信息
     *
     * @return 加载统计
     */
    LoadingStats getLoadingStats();

    /**
     * 检查是否正在加载指定键
     *
     * @param key 缓存键
     * @return 如果正在加载返回true
     */
    boolean isLoading(K key);

    /**
     * 获取正在加载的键集合
     *
     * @return 正在加载的键集合
     */
    Set<K> getLoadingKeys();

    /**
     * 取消正在进行的加载操作
     *
     * @param key 缓存键
     * @return 如果取消成功返回true
     */
    boolean cancelLoading(K key);

    /**
     * 取消所有正在进行的加载操作
     *
     * @return 被取消的加载操作数量
     */
    int cancelAllLoading();
}