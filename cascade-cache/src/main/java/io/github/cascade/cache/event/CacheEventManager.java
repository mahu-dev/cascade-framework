package io.github.cascade.cache.event;

import java.util.concurrent.Executor;
import java.time.Duration;

/**
 * 缓存事件管理器
 * 提供统一的事件管理接口
 * 
 * @author cascade
 */
public class CacheEventManager {
    
    private final CacheEventPublisher publisher;
    private static volatile CacheEventManager instance;
    
    public CacheEventManager() {
        this.publisher = new CacheEventPublisher();
    }
    
    public CacheEventManager(Executor executor) {
        this.publisher = new CacheEventPublisher(executor);
    }
    
    /**
     * 获取单例实例
     * 
     * @return 事件管理器实例
     */
    public static CacheEventManager getInstance() {
        if (instance == null) {
            synchronized (CacheEventManager.class) {
                if (instance == null) {
                    instance = new CacheEventManager();
                }
            }
        }
        return instance;
    }
    
    /**
     * 设置单例实例
     * 
     * @param manager 事件管理器
     */
    public static void setInstance(CacheEventManager manager) {
        instance = manager;
    }
    
    /**
     * 注册事件监听器
     * 
     * @param listener 监听器
     */
    public void registerListener(CacheEventListener<? extends CacheEvent> listener) {
        publisher.registerListener(listener);
    }
    
    /**
     * 移除事件监听器
     * 
     * @param listener 监听器
     */
    public void removeListener(CacheEventListener<? extends CacheEvent> listener) {
        publisher.removeListener(listener);
    }
    
    /**
     * 发布缓存命中事件
     * 
     * @param cacheName 缓存名称
     * @param key 缓存键
     * @param value 缓存值
     * @param duration 耗时（毫秒）
     */
    public void publishCacheHit(String cacheName, Object key, Object value, long duration) {
        CacheOperationEvent event = CacheOperationEvent.hit(cacheName, "CacheEventManager", key, value, Duration.ofMillis(duration));
        publisher.publishEvent(event);
    }
    
    /**
     * 发布缓存未命中事件
     * 
     * @param cacheName 缓存名称
     * @param key 缓存键
     * @param duration 耗时（毫秒）
     */
    public void publishCacheMiss(String cacheName, Object key, long duration) {
        CacheOperationEvent event = CacheOperationEvent.miss(cacheName, "CacheEventManager", key, Duration.ofMillis(duration));
        publisher.publishEvent(event);
    }
    
    /**
     * 发布缓存加载事件
     * 
     * @param cacheName 缓存名称
     * @param key 缓存键
     * @param value 加载的值
     * @param duration 耗时（毫秒）
     * @param success 是否成功
     */
    public void publishCacheLoad(String cacheName, Object key, Object value, long duration, boolean success) {
        CacheOperationEvent event = CacheOperationEvent.load(cacheName, "CacheEventManager", key, value, Duration.ofMillis(duration), success, null);
        publisher.publishEvent(event);
    }
    
    /**
     * 发布缓存存储事件
     * 
     * @param cacheName 缓存名称
     * @param key 缓存键
     * @param value 缓存值
     * @param duration 耗时（毫秒）
     * @param success 是否成功
     */
    public void publishCachePut(String cacheName, Object key, Object value, long duration, boolean success) {
        CacheOperationEvent event = CacheOperationEvent.put(cacheName, "CacheEventManager", key, value, Duration.ofMillis(duration));
        publisher.publishEvent(event);
    }
    
    /**
     * 发布缓存驱逐事件
     * 
     * @param cacheName 缓存名称
     * @param key 缓存键
     * @param duration 耗时（毫秒）
     * @param success 是否成功
     */
    public void publishCacheEvict(String cacheName, Object key, long duration, boolean success) {
        CacheOperationEvent event = CacheOperationEvent.evict(cacheName, "CacheEventManager", key, Duration.ofMillis(duration));
        publisher.publishEvent(event);
    }
    
    /**
     * 发布缓存清空事件
     * 
     * @param cacheName 缓存名称
     * @param duration 耗时（毫秒）
     * @param success 是否成功
     */
    public void publishCacheClear(String cacheName, long duration, boolean success) {
        CacheOperationEvent event = CacheOperationEvent.clear(cacheName, "CacheEventManager", Duration.ofMillis(duration));
        publisher.publishEvent(event);
    }
    
    /**
     * 发布缓存错误事件
     * 
     * @param cacheName 缓存名称
     * @param key 缓存键
     * @param exception 异常
     * @param duration 耗时（毫秒）
     */
    public void publishCacheError(String cacheName, Object key, Exception exception, long duration) {
        CacheOperationEvent event = CacheOperationEvent.error(cacheName, "CacheEventManager", key, exception);
        publisher.publishEvent(event);
    }
    
    /**
     * 发布自定义事件
     * 
     * @param event 事件
     */
    public void publishEvent(CacheEvent event) {
        publisher.publishEvent(event);
    }
    
    /**
     * 同步发布事件
     * 
     * @param event 事件
     */
    public void publishEventSync(CacheEvent event) {
        publisher.publishEventSync(event);
    }
    
    /**
     * 获取事件发布器
     * 
     * @return 事件发布器
     */
    public CacheEventPublisher getPublisher() {
        return publisher;
    }
    
    /**
     * 启用事件发布
     */
    public void enable() {
        publisher.enable();
    }
    
    /**
     * 禁用事件发布
     */
    public void disable() {
        publisher.disable();
    }
    
    /**
     * 是否启用
     * 
     * @return 是否启用
     */
    public boolean isEnabled() {
        return publisher.isEnabled();
    }
    
    /**
     * 获取激活的监听器数量
     * 
     * @return 激活监听器数量
     */
    public int getActiveListenerCount() {
        return publisher.getActiveListenerCount();
    }
    
    /**
     * 清空所有监听器
     */
    public void clearListeners() {
        publisher.clearListeners();
    }
}