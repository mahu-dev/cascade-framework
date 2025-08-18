package io.github.cascade.cache.sync;

import io.github.cascade.cache.api.Cache;

import java.util.Set;

/**
 * 缓存同步器接口
 *
 * @param <K> 键类型
 * @param <V> 值类型
 * @author cascade
 */
public interface CacheSynchronizer<K, V> {

    /**
     * 初始化同步器
     *
     * @param cache 缓存实例
     */
    void initialize(Cache<K, V> cache);

    /**
     * 通知缓存放入操作
     *
     * @param key 键
     * @param value 值
     */
    void notifyPut(K key, V value);

    /**
     * 通知缓存删除操作
     *
     * @param key 键
     */
    void notifyEvict(K key);

    /**
     * 通知缓存清空操作
     */
    void notifyClear();

    /**
     * 通知缓存刷新操作
     *
     * @param key 键
     */
    void notifyRefresh(K key);

    /**
     * 批量通知缓存放入操作
     *
     * @param keys 键集合
     */
    void notifyPutAll(Set<K> keys);

    /**
     * 批量通知缓存删除操作
     *
     * @param keys 键集合
     */
    void notifyEvictAll(Set<K> keys);

    /**
     * 同步缓存
     */
    void sync();

    /**
     * 启动同步器
     */
    void start();

    /**
     * 停止同步器
     */
    void stop();

    /**
     * 检查同步器是否运行中
     *
     * @return 是否运行中
     */
    boolean isRunning();

    /**
     * 获取同步器名称
     *
     * @return 同步器名称
     */
    String getName();

    /**
     * 获取同步统计信息
     *
     * @return 同步统计信息
     */
    SyncStats getStats();

    /**
     * 同步统计信息接口
     */
    interface SyncStats {
        /**
         * 获取发送消息数量
         *
         * @return 发送消息数量
         */
        long getSentMessageCount();

        /**
         * 获取接收消息数量
         *
         * @return 接收消息数量
         */
        long getReceivedMessageCount();

        /**
         * 获取发送失败数量
         *
         * @return 发送失败数量
         */
        long getFailedMessageCount();

        /**
         * 获取平均发送时间
         *
         * @return 平均发送时间（纳秒）
         */
        double getAverageSendTime();

        /**
         * 获取最后同步时间
         *
         * @return 最后同步时间戳
         */
        long getLastSyncTime();

        /**
         * 重置统计信息
         */
        void reset();
    }
}