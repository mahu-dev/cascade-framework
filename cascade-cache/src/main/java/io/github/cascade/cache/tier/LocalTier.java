package io.github.cascade.cache.tier;

import io.github.cascade.cache.api.Cache;
import io.github.cascade.cache.api.CacheTier;

import java.time.Duration;
import java.util.concurrent.ConcurrentMap;

/**
 * 本地缓存层接口（L1缓存）
 * 定义本地缓存特有的功能和配置
 *
 * @param <K> 键类型
 * @param <V> 值类型
 * @author cascade
 */
public interface LocalTier<K, V> extends Cache<K, V> {

    /**
     * 获取缓存层级类型
     * 本地缓存固定返回L1
     *
     * @return L1缓存层级
     */
    @Override
    default CacheTier getTier() {
        return CacheTier.L1;
    }

    // ==================== 容量管理 ====================

    /**
     * 获取最大缓存容量
     *
     * @return 最大容量，-1表示无限制
     */
    long maximumSize();

    /**
     * 设置最大缓存容量
     *
     * @param size 最大容量
     */
    void setMaximumSize(long size);

    /**
     * 获取最大权重
     *
     * @return 最大权重，-1表示无限制
     */
    long maximumWeight();

    /**
     * 设置最大权重
     *
     * @param weight 最大权重
     */
    void setMaximumWeight(long weight);

    // ==================== 过期策略 ====================

    /**
     * 获取写入后过期时间
     *
     * @return 过期时间，null表示不过期
     */
    Duration expireAfterWrite();

    /**
     * 设置写入后过期时间
     *
     * @param duration 过期时间
     */
    void setExpireAfterWrite(Duration duration);

    /**
     * 获取访问后过期时间
     *
     * @return 过期时间，null表示不过期
     */
    Duration expireAfterAccess();

    /**
     * 设置访问后过期时间
     *
     * @param duration 过期时间
     */
    void setExpireAfterAccess(Duration duration);

    /**
     * 获取写入后刷新时间
     *
     * @return 刷新时间，null表示不自动刷新
     */
    Duration refreshAfterWrite();

    /**
     * 设置写入后刷新时间
     *
     * @param duration 刷新时间
     */
    void setRefreshAfterWrite(Duration duration);

    // ==================== 内存管理 ====================

    /**
     * 获取软引用模式状态
     *
     * @return 是否启用软引用
     */
    boolean isSoftValues();

    /**
     * 设置软引用模式
     *
     * @param softValues 是否启用软引用
     */
    void setSoftValues(boolean softValues);

    /**
     * 获取弱引用模式状态
     *
     * @return 是否启用弱引用
     */
    boolean isWeakKeys();

    /**
     * 设置弱引用模式
     *
     * @param weakKeys 是否启用弱引用
     */
    void setWeakKeys(boolean weakKeys);

    // ==================== 并发控制 ====================

    /**
     * 获取并发级别
     *
     * @return 并发级别
     */
    int concurrencyLevel();

    /**
     * 设置并发级别
     *
     * @param level 并发级别
     */
    void setConcurrencyLevel(int level);

    /**
     * 获取初始容量
     *
     * @return 初始容量
     */
    int initialCapacity();

    /**
     * 设置初始容量
     *
     * @param capacity 初始容量
     */
    void setInitialCapacity(int capacity);

    // ==================== 高级功能 ====================

    /**
     * 获取缓存的ConcurrentMap视图
     * 用于直接操作底层Map（谨慎使用）
     *
     * @return ConcurrentMap视图
     */
    ConcurrentMap<K, V> asMap();

    /**
     * 使缓存项无效并立即移除
     * 与evict的区别在于会触发移除监听器
     *
     * @param key 缓存键
     */
    void invalidate(K key);

    /**
     * 批量使缓存项无效
     *
     * @param keys 缓存键集合
     */
    void invalidateAll(Iterable<K> keys);

    /**
     * 清空所有缓存并触发监听器
     */
    void invalidateAll();

    /**
     * 设置移除监听器
     *
     * @param listener 移除监听器
     */
    void setRemovalListener(RemovalListener<K, V> listener);

    /**
     * 获取当前的移除监听器
     *
     * @return 移除监听器
     */
    RemovalListener<K, V> getRemovalListener();

    /**
     * 移除监听器接口
     */
    @FunctionalInterface
    interface RemovalListener<K, V> {
        /**
         * 当缓存项被移除时调用
         *
         * @param key   被移除的键
         * @param value 被移除的值
         * @param cause 移除原因
         */
        void onRemoval(K key, V value, RemovalCause cause);
    }

    /**
     * 移除原因枚举
     */
    enum RemovalCause {
        /**
         * 显式移除（调用evict、invalidate等）
         */
        EXPLICIT,

        /**
         * 被新值替换
         */
        REPLACED,

        /**
         * 达到容量限制被淘汰
         */
        SIZE,

        /**
         * 过期被移除
         */
        EXPIRED,

        /**
         * 垃圾回收（软引用、弱引用）
         */
        COLLECTED
    }
}