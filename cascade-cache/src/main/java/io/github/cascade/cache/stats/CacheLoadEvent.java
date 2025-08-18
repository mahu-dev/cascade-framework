package io.github.cascade.cache.stats;

import java.time.Instant;

/**
 * 缓存加载事件
 */
public class CacheLoadEvent {
    
    private final String cacheName;
    private final Object key;
    private final Object value;
    private final boolean success;
    private final long durationNanos;
    private final Throwable exception;
    private final Instant timestamp;
    
    public CacheLoadEvent(String cacheName, Object key, Object value, long durationNanos) {
        this(cacheName, key, value, true, durationNanos, null);
    }
    
    public CacheLoadEvent(String cacheName, Object key, long durationNanos, Throwable exception) {
        this(cacheName, key, null, false, durationNanos, exception);
    }
    
    private CacheLoadEvent(String cacheName, Object key, Object value, boolean success, 
                          long durationNanos, Throwable exception) {
        this.cacheName = cacheName;
        this.key = key;
        this.value = value;
        this.success = success;
        this.durationNanos = durationNanos;
        this.exception = exception;
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
    
    public boolean isSuccess() {
        return success;
    }
    
    public long getDurationNanos() {
        return durationNanos;
    }
    
    public double getDurationMillis() {
        return durationNanos / 1_000_000.0;
    }
    
    public Throwable getException() {
        return exception;
    }
    
    public Instant getTimestamp() {
        return timestamp;
    }
    
    @Override
    public String toString() {
        if (success) {
            return String.format("CacheLoadEvent{cache='%s', key=%s, success=true, duration=%.2fms, time=%s}", 
                cacheName, key, getDurationMillis(), timestamp);
        } else {
            return String.format("CacheLoadEvent{cache='%s', key=%s, success=false, duration=%.2fms, error=%s, time=%s}", 
                cacheName, key, getDurationMillis(), exception != null ? exception.getClass().getSimpleName() : "unknown", timestamp);
        }
    }
}