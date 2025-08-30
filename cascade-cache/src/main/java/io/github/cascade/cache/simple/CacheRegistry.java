package io.github.cascade.cache.simple;

import java.util.Collection;
import java.util.function.Supplier;

/**
 * 缓存注册中心接口
 * 负责缓存实例的注册、查找和管理
 * 
 * @author cascade
 */
public interface CacheRegistry {
    
    /**
     * 注册缓存实例
     * 
     * @param name 缓存名称
     * @param cache 缓存实例
     * @param <K> 键类型
     * @param <V> 值类型
     * @return 是否注册成功（false表示已存在）
     */
    <K, V> boolean register(String name, Cache<K, V> cache);
    
    /**
     * 获取缓存实例
     * 
     * @param name 缓存名称
     * @param <K> 键类型
     * @param <V> 值类型
     * @return 缓存实例，不存在时返回null
     */
    <K, V> Cache<K, V> get(String name);
    
    /**
     * 获取缓存实例，不存在时使用提供的工厂方法创建
     * 
     * @param name 缓存名称
     * @param factory 缓存工厂方法
     * @param <K> 键类型
     * @param <V> 值类型
     * @return 缓存实例
     */
    <K, V> Cache<K, V> computeIfAbsent(String name, Supplier<Cache<K, V>> factory);
    
    /**
     * 移除缓存实例
     * 
     * @param name 缓存名称
     * @return 被移除的缓存实例，不存在时返回null
     */
    <K, V> Cache<K, V> remove(String name);
    
    /**
     * 检查是否包含指定名称的缓存
     * 
     * @param name 缓存名称
     * @return 是否包含
     */
    boolean contains(String name);
    
    /**
     * 获取所有缓存名称
     * 
     * @return 缓存名称集合
     */
    Collection<String> getCacheNames();
    
    /**
     * 获取缓存总数
     * 
     * @return 缓存总数
     */
    int size();
    
    /**
     * 清空所有缓存
     */
    void clear();
    
    /**
     * 检查注册中心是否为空
     * 
     * @return 是否为空
     */
    boolean isEmpty();
}