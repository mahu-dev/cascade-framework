package io.github.cascade.cache.event;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * 缓存事件基类
 * 
 * @author cascade
 */
public abstract class CacheEvent {
    
    /**
     * 事件类型枚举
     */
    public enum EventType {
        /** 缓存命中 */
        CACHE_HIT,
        /** 缓存未命中 */
        CACHE_MISS,
        /** 缓存加载 */
        CACHE_LOAD,
        /** 缓存存储 */
        CACHE_PUT,
        /** 缓存删除 */
        CACHE_EVICT,
        /** 缓存清空 */
        CACHE_CLEAR,
        /** 缓存刷新 */
        CACHE_REFRESH,
        /** 缓存预热开始 */
        CACHE_WARMUP_START,
        /** 缓存预热完成 */
        CACHE_WARMUP_COMPLETE,
        /** 缓存预热失败 */
        CACHE_WARMUP_FAILED,
        /** 缓存同步 */
        CACHE_SYNC,
        /** 缓存异常 */
        CACHE_ERROR
    }
    
    private final String cacheName;
    private final EventType eventType;
    private final Instant timestamp;
    private final String source;
    private final Map<String, Object> metadata;
    
    protected CacheEvent(String cacheName, EventType eventType, String source) {
        this.cacheName = cacheName;
        this.eventType = eventType;
        this.source = source;
        this.timestamp = Instant.now();
        this.metadata = new HashMap<>();
    }
    
    /**
     * 获取缓存名称
     */
    public String getCacheName() {
        return cacheName;
    }
    
    /**
     * 获取事件类型
     */
    public EventType getEventType() {
        return eventType;
    }
    
    /**
     * 获取事件时间戳
     */
    public Instant getTimestamp() {
        return timestamp;
    }
    
    /**
     * 获取事件源
     */
    public String getSource() {
        return source;
    }
    
    /**
     * 获取元数据
     */
    public Map<String, Object> getMetadata() {
        return metadata;
    }
    
    /**
     * 添加元数据
     */
    public CacheEvent addMetadata(String key, Object value) {
        this.metadata.put(key, value);
        return this;
    }
    
    /**
     * 获取元数据值
     */
    @SuppressWarnings("unchecked")
    public <T> T getMetadata(String key) {
        return (T) metadata.get(key);
    }
    
    @Override
    public String toString() {
        return String.format("%s{cacheName='%s', eventType=%s, timestamp=%s, source='%s', metadata=%s}",
                getClass().getSimpleName(), cacheName, eventType, timestamp, source, metadata);
    }
}