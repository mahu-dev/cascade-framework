package io.github.cascade.cache.core.unified;

import io.github.cascade.cache.api.CacheStats;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * 缓存引擎接口 - 统一的缓存存储抽象
 * 替代原来的复杂继承体系
 * 
 * @param <K> 键类型
 * @param <V> 值类型  
 * @author cascade
 */
public interface CacheEngine<K, V> {
    
    // ==================== 基础操作 ====================
    
    /**
     * 获取值
     */
    V get(K key);
    
    /**
     * 批量获取
     */
    Map<K, V> getAll(Set<K> keys);
    
    /**
     * 存储值
     */
    void put(K key, V value);
    
    /**
     * 存储值（带TTL）
     */
    void put(K key, V value, Duration ttl);
    
    /**
     * 批量存储
     */
    void putAll(Map<K, V> map);
    
    /**
     * 删除键
     */
    void evict(K key);
    
    /**
     * 批量删除
     */
    void evictAll(Set<K> keys);
    
    /**
     * 清空所有
     */
    void clear();
    
    // ==================== 查询操作 ====================
    
    /**
     * 检查是否包含键
     */
    boolean containsKey(K key);
    
    /**
     * 获取大小
     */
    long size();
    
    /**
     * 检查是否为空
     */
    default boolean isEmpty() {
        return size() == 0;
    }
    
    // ==================== 异步操作 ====================
    
    /**
     * 异步获取
     */
    default CompletableFuture<V> getAsync(K key) {
        return CompletableFuture.supplyAsync(() -> get(key));
    }
    
    /**
     * 异步存储
     */
    default CompletableFuture<Void> putAsync(K key, V value) {
        return CompletableFuture.runAsync(() -> put(key, value));
    }
    
    // ==================== 函数式操作 ====================
    
    /**
     * 获取或计算值
     */
    default V getIfPresent(K key, Function<K, V> loader) {
        V value = get(key);
        if (value == null) {
            value = loader.apply(key);
            if (value != null) {
                put(key, value);
            }
        }
        return value;
    }
    
    // ==================== 统计和维护 ====================
    
    /**
     * 获取统计信息
     */
    CacheStats getStats();
    
    /**
     * 清理过期数据
     */
    void cleanUp();
    
    /**
     * 关闭引擎
     */
    default void close() {
        // 默认实现为空
    }
}