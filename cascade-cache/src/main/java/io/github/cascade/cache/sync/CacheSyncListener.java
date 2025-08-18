package io.github.cascade.cache.sync;

/**
 * 缓存同步监听器接口
 */
public interface CacheSyncListener {
    
    /**
     * 处理缓存同步事件
     * 
     * @param event 缓存同步事件
     */
    void onSyncEvent(CacheSyncEvent event);
    
    /**
     * 获取监听器标识
     * 
     * @return 监听器标识
     */
    String getListenerId();
    
    /**
     * 判断是否应该处理该事件
     * 
     * @param event 缓存同步事件
     * @return 是否处理
     */
    default boolean shouldHandle(CacheSyncEvent event) {
        return true;
    }
    
    /**
     * 监听器是否激活
     * 
     * @return 是否激活
     */
    default boolean isActive() {
        return true;
    }
}