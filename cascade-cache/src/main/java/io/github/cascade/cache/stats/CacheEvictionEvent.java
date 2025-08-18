package io.github.cascade.cache.stats;

import java.time.Instant;

/**
 * 缓存淘汰事件
 */
public class CacheEvictionEvent {
    
    public enum Cause {
        SIZE,      // 大小限制
        TIME,      // 时间过期
        MANUAL,    // 手动清除
        REPLACED   // 被替换
    }
    
    private final String cacheName;
    private final Object key;
    private final Object value;
    private final Cause cause;
    private final long weight;
    private final Instant timestamp;
    
    public CacheEvictionEvent(String cacheName, Object key, Object value, Cause cause) {
        this(cacheName, key, value, cause, 1);
    }
    
    public CacheEvictionEvent(String cacheName, Object key, Object value, Cause cause, long weight) {
        this.cacheName = cacheName;
        this.key = key;
        this.value = value;
        this.cause = cause;
        this.weight = weight;
        this.timestamp = Instant.now();
    }
    
    public String getCacheName() {
        return cacheName;
    }
    
    public Object getKey() {
        return key;
    }
    
    public Object getValue() {
        return value;
    }
    
    public Cause getCause() {
        return cause;
    }
    
    public long getWeight() {
        return weight;
    }
    
    public Instant getTimestamp() {
        return timestamp;
    }
    
    @Override
    public String toString() {
        return String.format("CacheEvictionEvent{cache='%s', key=%s, cause=%s, weight=%d, time=%s}", 
            cacheName, key, cause, weight, timestamp);
    }
}