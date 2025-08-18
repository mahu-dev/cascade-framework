package io.github.cascade.cache.sync;

import java.util.function.Consumer;

/**
 * 简化的缓存同步器接口
 * 用于发布和接收缓存同步事件
 */
public interface SimpleCacheSynchronizer {
    
    /**
     * 启动同步器
     * 
     * @param eventHandler 事件处理器
     */
    void start(Consumer<CacheSyncEvent> eventHandler);
    
    /**
     * 停止同步器
     */
    void stop();
    
    /**
     * 发布同步事件
     * 
     * @param event 同步事件
     */
    void publishEvent(CacheSyncEvent event);
    
    /**
     * 获取同步器名称
     * 
     * @return 同步器名称
     */
    String getName();
    
    /**
     * 检查是否运行中
     * 
     * @return 是否运行中
     */
    boolean isRunning();
    
    /**
     * 获取统计信息
     * 
     * @return 统计信息
     */
    SynchronizerStats getStats();
    
    /**
     * 同步器统计信息
     */
    interface SynchronizerStats {
        /**
         * 发送的事件数量
         */
        long getSentEvents();
        
        /**
         * 接收的事件数量
         */
        long getReceivedEvents();
        
        /**
         * 失败的事件数量
         */
        long getFailedEvents();
        
        /**
         * 最后活动时间
         */
        long getLastActivityTime();
        
        /**
         * 重置统计
         */
        void reset();
    }
}