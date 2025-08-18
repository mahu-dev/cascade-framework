package io.github.cascade.cache.stats;

import io.github.cascade.cache.api.CacheTier;

import java.time.Instant;

/**
 * 缓存命中事件
 */
public class CacheHitEvent {
    
    private final String cacheName;
    private final Object key;
    private final CacheTier tier;
    private final Instant timestamp;
    
    public CacheHitEvent(String cacheName, Object key, CacheTier tier) {
        this.cacheName = cacheName;
        this.key = key;
        this.tier = tier;
        this.timestamp = Instant.now();
    }
    
    public String getCacheName() {
        return cacheName;
    }
    
    public Object getKey() {
        return key;
    }
    
    public CacheTier getTier() {
        return tier;
    }
    
    public Instant getTimestamp() {
        return timestamp;
    }
    
    @Override
    public String toString() {
        return String.format("CacheHitEvent{cache='%s', key=%s, tier=%s, time=%s}", 
            cacheName, key, tier, timestamp);
    }
}