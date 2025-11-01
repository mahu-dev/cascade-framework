package io.github.cascade.cache.api;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * @author lionel lionelk@163.com
 * =============================
 * Date: 2025/7/10
 * Time: 13:23
 * =============================
 */

/**
 * 统一缓存接口
 * <p>
 * 设计原则：
 * 1. 接口精简：只保留核心方法
 * 2. 支持异步：提供CompletableFuture支持
 * 3. 函数式：使用Lambda表达式
 * 4. 类型安全：泛型支持
 *
 * @param <K> 键类型
 * @param <V> 值类型
 * @author cascade
 */
public interface Cache<K, V> {

    // ==================== 基础CRUD操作 ====================

    /**
     * 获取缓存值
     */
    Optional<V> get(K key);

    /**
     * 获取缓存值，如果不存在则使用loader加载
     */
    V getOrLoad(K key, Function<K, V> loader);

    /**
     * 异步获取缓存值
     */
    CompletableFuture<Optional<V>> getAsync(K key);

    /**
     * 存储缓存值
     */
    void put(K key, V value);

    /**
     * 存储缓存值（带TTL，单位：秒）
     */
    void put(K key, V value, long ttlSeconds);

    /**
     * 异步存储缓存值
     */
    CompletableFuture<Void> putAsync(K key, V value);

    /**
     * 删除缓存
     */
    void evict(K key);

    /**
     * 清空所有缓存
     */
    void clear();

    /**
     * 批量获取
     */
    Map<K, V> getAll(Iterable<K> keys);

    /**
     * 批量存储
     */
    void putAll(Map<K, V> entries);

    // ==================== 缓存状态查询 ====================

    /**
     * 检查键是否存在
     */
    boolean containsKey(K key);

    /**
     * 获取缓存大小
     */
    long size();

    /**
     * 获取缓存名称
     */
    String getName();

    // ==================== 生命周期管理 ====================

    /**
     * 关闭缓存
     */
    void close();

    /**
     * 检查是否已关闭
     */
    boolean isClosed();
}