package io.github.cascade.cache.api;

import java.util.Map;
import java.util.Set;

/**
 * 多级缓存接口，扩展基础缓存功能
 *
 * @param <K> 缓存键类型
 * @param <V> 缓存值类型
 * @author cascade
 */
public interface TieredCache<K, V> extends Cache<K, V> {

    /**
     * 从指定层级获取缓存值
     *
     * @param key  缓存键
     * @param tier 缓存层级
     * @return 缓存值，如果不存在则返回null
     */
    V get(K key, CacheTier tier);

    /**
     * 向指定层级存储缓存值
     *
     * @param key   缓存键
     * @param value 缓存值
     * @param tier  缓存层级
     */
    void put(K key, V value, CacheTier tier);

    /**
     * 从指定层级删除缓存项
     *
     * @param key  缓存键
     * @param tier 缓存层级
     */
    void evict(K key, CacheTier tier);

    /**
     * 清空指定层级的所有缓存
     *
     * @param tier 缓存层级
     */
    void clear(CacheTier tier);

    /**
     * 检查指定层级是否包含键
     *
     * @param key  缓存键
     * @param tier 缓存层级
     * @return 如果存在返回true，否则返回false
     */
    boolean containsKey(K key, CacheTier tier);

    /**
     * 获取指定层级的缓存大小
     *
     * @param tier 缓存层级
     * @return 缓存项数量
     */
    long size(CacheTier tier);

    /**
     * 获取指定层级的统计信息
     *
     * @param tier 缓存层级
     * @return 缓存统计
     */
    CacheStats getStats(CacheTier tier);

    /**
     * 将数据从低级缓存提升到高级缓存
     *
     * @param key 缓存键
     */
    void promote(K key);

    /**
     * 批量提升数据
     *
     * @param keys 缓存键集合
     */
    void promoteAll(Set<K> keys);

    /**
     * 将数据从高级缓存降级到低级缓存
     *
     * @param key 缓存键
     */
    void demote(K key);

    /**
     * 批量降级数据
     *
     * @param keys 缓存键集合
     */
    void demoteAll(Set<K> keys);

    /**
     * 同步所有层级的缓存
     *
     * @param key 缓存键
     */
    void sync(K key);

    /**
     * 批量同步缓存
     *
     * @param keys 缓存键集合
     */
    void syncAll(Set<K> keys);

    /**
     * 获取所有层级的统计信息
     *
     * @return 层级统计映射
     */
    Map<CacheTier, CacheStats> getAllStats();

    /**
     * 获取支持的缓存层级
     *
     * @return 缓存层级集合
     */
    Set<CacheTier> getSupportedTiers();

    /**
     * 检查指定层级是否可用
     *
     * @param tier 缓存层级
     * @return 如果可用返回true，否则返回false
     */
    boolean isTierAvailable(CacheTier tier);
}