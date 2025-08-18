package io.github.cascade.cache.stats;

import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 缓存指标收集器
 * 集成Micrometer实现实时监控指标收集
 */
@Component
public class CacheMetricsCollector implements MeterBinder {
    
    private static final Logger log = LoggerFactory.getLogger(CacheMetricsCollector.class);
    
    private final ConcurrentMap<String, CacheMetrics> cacheMetrics = new ConcurrentHashMap<>();
    private MeterRegistry meterRegistry;
    
    @Override
    public void bindTo(MeterRegistry registry) {
        this.meterRegistry = registry;
        
        // 注册缓存指标
        for (String cacheName : cacheMetrics.keySet()) {
            bindCacheMetrics(cacheName, registry);
        }
    }
    
    /**
     * 注册缓存
     */
    public void registerCache(String cacheName, CacheMetrics metrics) {
        cacheMetrics.put(cacheName, metrics);
        if (meterRegistry != null) {
            bindCacheMetrics(cacheName, meterRegistry);
        }
    }
    
    /**
     * 取消注册缓存
     */
    public void unregisterCache(String cacheName) {
        cacheMetrics.remove(cacheName);
        // 移除相关的指标
        if (meterRegistry != null) {
            // Micrometer中的remove方法需要Meter.Id，这里简化为日志记录
            log.info("Unregistering cache metrics for: {}", cacheName);
        }
    }
    
    /**
     * 记录缓存命中事件
     */
    @EventListener
    public void handleCacheHit(CacheHitEvent event) {
        String cacheName = event.getCacheName();
        String tier = event.getTier() != null ? event.getTier().name() : "unknown";
        
        if (meterRegistry != null) {
            Counter.builder("cascade.cache.hits")
                .tag("cache", cacheName)
                .tag("tier", tier)
                .register(meterRegistry)
                .increment();
        }
    }
    
    /**
     * 记录缓存未命中事件
     */
    @EventListener
    public void handleCacheMiss(CacheMissEvent event) {
        String cacheName = event.getCacheName();
        String tier = event.getTier() != null ? event.getTier().name() : "unknown";
        
        if (meterRegistry != null) {
            Counter.builder("cascade.cache.misses")
                .tag("cache", cacheName)
                .tag("tier", tier)
                .register(meterRegistry)
                .increment();
        }
    }
    
    /**
     * 记录缓存淘汰事件
     */
    @EventListener
    public void handleCacheEviction(CacheEvictionEvent event) {
        String cacheName = event.getCacheName();
        String cause = event.getCause() != null ? event.getCause().name() : "unknown";
        
        if (meterRegistry != null) {
            Counter.builder("cascade.cache.evictions")
                .tag("cache", cacheName)
                .tag("cause", cause)
                .register(meterRegistry)
                .increment();
        }
    }
    
    /**
     * 记录缓存加载事件
     */
    @EventListener
    public void handleCacheLoad(CacheLoadEvent event) {
        String cacheName = event.getCacheName();
        boolean success = event.isSuccess();
        long durationNanos = event.getDurationNanos();
        
        if (meterRegistry != null) {
            Timer.builder("cascade.cache.load")
                .tag("cache", cacheName)
                .tag("result", success ? "success" : "failure")
                .register(meterRegistry)
                .record(durationNanos, java.util.concurrent.TimeUnit.NANOSECONDS);
        }
    }
    
    /**
     * 定期记录缓存大小和其他指标
     */
    @Scheduled(fixedRate = 30000) // 30秒
    public void recordCacheSize() {
        if (meterRegistry == null) {
            return;
        }
        
        for (var entry : cacheMetrics.entrySet()) {
            String cacheName = entry.getKey();
            CacheMetrics metrics = entry.getValue();
            
            try {
                // 记录缓存大小
                Gauge.builder("cascade.cache.size", metrics, CacheMetrics::estimatedSize)
                    .tag("cache", cacheName)
                    .register(meterRegistry);
                
                // 记录命中率
                Gauge.builder("cascade.cache.hit_rate", metrics, CacheMetrics::hitRate)
                    .tag("cache", cacheName)
                    .register(meterRegistry);
                
                // 记录平均加载时间
                Gauge.builder("cascade.cache.avg_load_time_millis", metrics, CacheMetrics::averageLoadTimeMillis)
                    .tag("cache", cacheName)
                    .register(meterRegistry);
                
                // 记录运行时长
                Gauge.builder("cascade.cache.uptime_seconds", metrics, m -> m.uptime().toSeconds())
                    .tag("cache", cacheName)
                    .register(meterRegistry);
                
            } catch (Exception e) {
                log.warn("Failed to record metrics for cache: {}", cacheName, e);
            }
        }
    }
    
    /**
     * 获取缓存指标
     */
    public CacheMetrics getCacheMetrics(String cacheName) {
        return cacheMetrics.get(cacheName);
    }
    
    /**
     * 获取所有缓存的指标快照
     */
    public ConcurrentMap<String, CacheStatsSnapshot> getAllStatsSnapshots() {
        ConcurrentMap<String, CacheStatsSnapshot> snapshots = new ConcurrentHashMap<>();
        for (var entry : cacheMetrics.entrySet()) {
            snapshots.put(entry.getKey(), entry.getValue().snapshot());
        }
        return snapshots;
    }
    
    private void bindCacheMetrics(String cacheName, MeterRegistry registry) {
        CacheMetrics metrics = cacheMetrics.get(cacheName);
        if (metrics == null) {
            return;
        }
        
        // 基础计数器
        Gauge.builder("cascade.cache.requests", metrics, CacheMetrics::hitCount)
            .tag("cache", cacheName)
            .tag("result", "hit")
            .register(registry);
        
        Gauge.builder("cascade.cache.requests", metrics, CacheMetrics::missCount)
            .tag("cache", cacheName)
            .tag("result", "miss")
            .register(registry);
        
        Gauge.builder("cascade.cache.loads", metrics, CacheMetrics::loadSuccessCount)
            .tag("cache", cacheName)
            .tag("result", "success")
            .register(registry);
        
        Gauge.builder("cascade.cache.loads", metrics, CacheMetrics::loadFailureCount)
            .tag("cache", cacheName)
            .tag("result", "failure")
            .register(registry);
        
        Gauge.builder("cascade.cache.evictions", metrics, CacheMetrics::evictionCount)
            .tag("cache", cacheName)
            .register(registry);
    }
}