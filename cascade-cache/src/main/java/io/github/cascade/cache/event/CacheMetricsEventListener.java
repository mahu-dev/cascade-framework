package io.github.cascade.cache.event;

import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

/**
 * 缓存指标事件监听器
 * 将缓存事件转换为Micrometer指标
 */
public class CacheMetricsEventListener implements CacheEventListener<CacheOperationEvent> {
    
    private static final Logger log = LoggerFactory.getLogger(CacheMetricsEventListener.class);
    
    private final MeterRegistry meterRegistry;
    private final ConcurrentMap<String, CacheMeters> metersMap = new ConcurrentHashMap<>();
    private volatile boolean enabled = true;
    
    public CacheMetricsEventListener(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }
    
    @Override
    public void onEvent(CacheOperationEvent event) {
        if (!enabled || meterRegistry == null) {
            return;
        }
        
        try {
            String cacheName = event.getCacheName();
            CacheMeters meters = metersMap.computeIfAbsent(cacheName, this::createMeters);
            
            recordMetrics(meters, event);
        } catch (Exception e) {
            log.warn("Error recording cache metrics for event: {}", event, e);
        }
    }
    
    private void recordMetrics(CacheMeters meters, CacheOperationEvent event) {
        String result = determineResult(event);
        long duration = event.getDurationMillis();
        
        switch (event.getEventType()) {
            case CACHE_HIT:
                Counter.builder("cascade.cache.requests")
                    .tags("cache", event.getCacheName(), "result", "hit")
                    .register(meterRegistry)
                    .increment();
                Timer.builder("cascade.cache.operation.duration")
                    .tags("cache", event.getCacheName(), "operation", "get", "result", "hit")
                    .register(meterRegistry)
                    .record(duration, TimeUnit.MILLISECONDS);
                break;
                
            case CACHE_MISS:
                Counter.builder("cascade.cache.requests")
                    .tags("cache", event.getCacheName(), "result", "miss")
                    .register(meterRegistry)
                    .increment();
                Timer.builder("cascade.cache.operation.duration")
                    .tags("cache", event.getCacheName(), "operation", "get", "result", "miss")
                    .register(meterRegistry)
                    .record(duration, TimeUnit.MILLISECONDS);
                break;
                
            case CACHE_LOAD:
                if (event.isSuccess()) {
                    Counter.builder("cascade.cache.loads")
                        .tags("cache", event.getCacheName(), "result", "success")
                        .register(meterRegistry)
                        .increment();
                    Timer.builder("cascade.cache.operation.duration")
                        .tags("cache", event.getCacheName(), "operation", "load", "result", "success")
                        .register(meterRegistry)
                        .record(duration, TimeUnit.MILLISECONDS);
                } else {
                    Counter.builder("cascade.cache.loads")
                        .tags("cache", event.getCacheName(), "result", "failure")
                        .register(meterRegistry)
                        .increment();
                    Counter.builder("cascade.cache.errors")
                        .tags("cache", event.getCacheName(), "operation", "load")
                        .register(meterRegistry)
                        .increment();
                    Timer.builder("cascade.cache.operation.duration")
                        .tags("cache", event.getCacheName(), "operation", "load", "result", "failure")
                        .register(meterRegistry)
                        .record(duration, TimeUnit.MILLISECONDS);
                }
                break;
                
            case CACHE_PUT:
                meters.putCounter.increment();
                Timer.builder("cascade.cache.operation.duration")
                    .tags("cache", event.getCacheName(), "operation", "put")
                    .register(meterRegistry)
                    .record(duration, TimeUnit.MILLISECONDS);
                break;
                
            case CACHE_EVICT:
                meters.evictionCounter.increment();
                Timer.builder("cascade.cache.operation.duration")
                    .tags("cache", event.getCacheName(), "operation", "evict")
                    .register(meterRegistry)
                    .record(duration, TimeUnit.MILLISECONDS);
                break;
                
            case CACHE_CLEAR:
                meters.clearCounter.increment();
                Timer.builder("cascade.cache.operation.duration")
                    .tags("cache", event.getCacheName(), "operation", "clear")
                    .register(meterRegistry)
                    .record(duration, TimeUnit.MILLISECONDS);
                break;
                
            case CACHE_ERROR:
                Counter.builder("cascade.cache.errors")
                    .tags("cache", event.getCacheName(), "operation", "unknown")
                    .register(meterRegistry)
                    .increment();
                break;
        }
        
        // 记录慢操作
        if (duration >= 1000) { // 1秒以上为慢操作
            Counter.builder("cascade.cache.slow_operations")
                .tags("cache", event.getCacheName(), "operation", event.getEventType().name().toLowerCase())
                .register(meterRegistry)
                .increment();
        }
    }
    
    private String determineResult(CacheOperationEvent event) {
        switch (event.getEventType()) {
            case CACHE_HIT:
                return "hit";
            case CACHE_MISS:
                return "miss";
            case CACHE_LOAD:
                return event.isSuccess() ? "success" : "failure";
            case CACHE_ERROR:
                return "error";
            default:
                return "unknown";
        }
    }
    
    private CacheMeters createMeters(String cacheName) {
        try {
            return new CacheMeters(cacheName, meterRegistry);
        } catch (Exception e) {
            log.error("Failed to create meters for cache: {}", cacheName, e);
            throw e;
        }
    }
    
    /**
     * 移除缓存的所有指标
     */
    public void removeCacheMeters(String cacheName) {
        CacheMeters meters = metersMap.remove(cacheName);
        if (meters != null) {
            try {
                meters.close();
            } catch (Exception e) {
                log.warn("Error closing meters for cache: {}", cacheName, e);
            }
        }
    }
    
    /**
     * 获取缓存指标
     */
    public CacheMeters getCacheMeters(String cacheName) {
        return metersMap.get(cacheName);
    }
    
    @Override
    public boolean isActive() {
        return enabled;
    }
    
    @Override
    public String getName() {
        return "CacheMetricsEventListener";
    }
    
    @Override
    public int getPriority() {
        return 10; // 最高优先级
    }
    
    public void enable() {
        this.enabled = true;
    }
    
    public void disable() {
        this.enabled = false;
    }
    
    /**
     * 缓存指标容器
     */
    public static class CacheMeters implements AutoCloseable {
        
        private final String cacheName;
        private final MeterRegistry registry;
        
        // 计数器
        public final Counter requestCounter;
        public final Counter loadCounter;
        public final Counter putCounter;
        public final Counter evictionCounter;
        public final Counter clearCounter;
        public final Counter errorCounter;
        public final Counter slowOperationCounter;
        
        // 定时器
        public final Timer operationTimer;
        
        // 仪表
        public final Gauge hitRateGauge;
        
        private volatile double currentHitRate = 0.0;
        
        public CacheMeters(String cacheName, MeterRegistry registry) {
            this.cacheName = cacheName;
            this.registry = registry;
            
            Tags commonTags = Tags.of("cache", cacheName);
            
            // 创建计数器
            this.requestCounter = Counter.builder("cascade.cache.requests")
                .description("Cache request count")
                .tags(commonTags)
                .register(registry);
                
            this.loadCounter = Counter.builder("cascade.cache.loads")
                .description("Cache load count")
                .tags(commonTags)
                .register(registry);
                
            this.putCounter = Counter.builder("cascade.cache.puts")
                .description("Cache put count")
                .tags(commonTags)
                .register(registry);
                
            this.evictionCounter = Counter.builder("cascade.cache.evictions")
                .description("Cache eviction count")
                .tags(commonTags)
                .register(registry);
                
            this.clearCounter = Counter.builder("cascade.cache.clears")
                .description("Cache clear count")
                .tags(commonTags)
                .register(registry);
                
            this.errorCounter = Counter.builder("cascade.cache.errors")
                .description("Cache error count")
                .tags(commonTags)
                .register(registry);
                
            this.slowOperationCounter = Counter.builder("cascade.cache.slow_operations")
                .description("Cache slow operation count")
                .tags(commonTags)
                .register(registry);
            
            // 创建定时器
            this.operationTimer = Timer.builder("cascade.cache.operation.duration")
                .description("Cache operation duration")
                .tags(commonTags)
                .register(registry);
            
            // 创建仪表
            this.hitRateGauge = Gauge.builder("cascade.cache.hit_rate", this, CacheMeters::getCurrentHitRate)
                .description("Cache hit rate")
                .tags(commonTags)
                .register(registry);
        }
        
        private double getCurrentHitRate() {
            return currentHitRate;
        }
        
        /**
         * 更新命中率
         */
        public void updateHitRate(double hitRate) {
            this.currentHitRate = hitRate;
        }
        
        @Override
        public void close() {
            // Micrometer会自动管理指标的生命周期
            // 这里只需要清理本地状态
            currentHitRate = 0.0;
        }
        
        public String getCacheName() {
            return cacheName;
        }
        
        /**
         * 获取请求总数
         */
        public double getRequestCount() {
            return requestCounter.count();
        }
        
        /**
         * 获取加载总数
         */
        public double getLoadCount() {
            return loadCounter.count();
        }
        
        /**
         * 获取存储总数
         */
        public double getPutCount() {
            return putCounter.count();
        }
        
        /**
         * 获取驱逐总数
         */
        public double getEvictionCount() {
            return evictionCounter.count();
        }
        
        /**
         * 获取清空总数
         */
        public double getClearCount() {
            return clearCounter.count();
        }
        
        /**
         * 获取错误总数
         */
        public double getErrorCount() {
            return errorCounter.count();
        }
        
        /**
         * 获取慢操作总数
         */
        public double getSlowOperationCount() {
            return slowOperationCounter.count();
        }
        
        /**
         * 获取平均操作时间（毫秒）
         */
        public double getAverageOperationTime() {
            return operationTimer.mean(TimeUnit.MILLISECONDS);
        }
        
        /**
         * 获取最大操作时间（毫秒）
         */
        public double getMaxOperationTime() {
            return operationTimer.max(TimeUnit.MILLISECONDS);
        }
    }
}