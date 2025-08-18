package io.github.cascade.cache.event;

import java.time.Duration;

/**
 * 缓存操作事件
 * 
 * @author cascade
 */
public class CacheOperationEvent extends CacheEvent {
    
    private final Object key;
    private final Object value;
    private final Duration duration;
    private final boolean success;
    private final Throwable exception;
    
    /**
     * 构造缓存操作事件
     */
    public CacheOperationEvent(String cacheName, EventType eventType, String source,
                              Object key, Object value, Duration duration, boolean success, Throwable exception) {
        super(cacheName, eventType, source);
        this.key = key;
        this.value = value;
        this.duration = duration;
        this.success = success;
        this.exception = exception;
    }
    
    /**
     * 创建缓存命中事件
     */
    public static CacheOperationEvent hit(String cacheName, String source, Object key, Object value, Duration duration) {
        return new CacheOperationEvent(cacheName, EventType.CACHE_HIT, source, key, value, duration, true, null);
    }
    
    /**
     * 创建缓存未命中事件
     */
    public static CacheOperationEvent miss(String cacheName, String source, Object key, Duration duration) {
        return new CacheOperationEvent(cacheName, EventType.CACHE_MISS, source, key, null, duration, true, null);
    }
    
    /**
     * 创建缓存加载事件
     */
    public static CacheOperationEvent load(String cacheName, String source, Object key, Object value, Duration duration, boolean success, Throwable exception) {
        return new CacheOperationEvent(cacheName, EventType.CACHE_LOAD, source, key, value, duration, success, exception);
    }
    
    /**
     * 创建缓存存储事件
     */
    public static CacheOperationEvent put(String cacheName, String source, Object key, Object value, Duration duration) {
        return new CacheOperationEvent(cacheName, EventType.CACHE_PUT, source, key, value, duration, true, null);
    }
    
    /**
     * 创建缓存删除事件
     */
    public static CacheOperationEvent evict(String cacheName, String source, Object key, Duration duration) {
        return new CacheOperationEvent(cacheName, EventType.CACHE_EVICT, source, key, null, duration, true, null);
    }
    
    /**
     * 创建缓存清空事件
     */
    public static CacheOperationEvent clear(String cacheName, String source, Duration duration) {
        return new CacheOperationEvent(cacheName, EventType.CACHE_CLEAR, source, null, null, duration, true, null);
    }
    
    /**
     * 创建缓存异常事件
     */
    public static CacheOperationEvent error(String cacheName, String source, Object key, Throwable exception) {
        return new CacheOperationEvent(cacheName, EventType.CACHE_ERROR, source, key, null, null, false, exception);
    }
    
    /**
     * 获取缓存键
     */
    public Object getKey() {
        return key;
    }
    
    /**
     * 获取缓存值
     */
    public Object getValue() {
        return value;
    }
    
    /**
     * 获取操作耗时
     */
    public Duration getDuration() {
        return duration;
    }
    
    /**
     * 操作是否成功
     */
    public boolean isSuccess() {
        return success;
    }
    
    /**
     * 获取异常信息
     */
    public Throwable getException() {
        return exception;
    }
    
    /**
     * 获取操作耗时（毫秒）
     */
    public long getDurationMillis() {
        return duration != null ? duration.toMillis() : 0;
    }
}