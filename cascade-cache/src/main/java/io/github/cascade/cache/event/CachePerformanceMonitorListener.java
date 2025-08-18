package io.github.cascade.cache.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 缓存性能监控监听器
 * 监控缓存性能指标并在出现异常时发出警告
 */
public class CachePerformanceMonitorListener implements CacheEventListener<CacheOperationEvent> {
    
    private static final Logger log = LoggerFactory.getLogger(CachePerformanceMonitorListener.class);
    
    private final ConcurrentMap<String, PerformanceMetrics> metricsMap = new ConcurrentHashMap<>();
    private volatile boolean enabled = true;
    
    // 配置参数
    private long slowOperationThreshold = 1000; // 1秒
    private double lowHitRateThreshold = 0.5; // 50%
    private long highErrorRateThreshold = 10; // 10次错误
    private Duration alertCooldown = Duration.ofMinutes(5); // 5分钟告警冷却
    
    @Override
    public void onEvent(CacheOperationEvent event) {
        if (!enabled) {
            return;
        }
        
        String cacheName = event.getCacheName();
        PerformanceMetrics metrics = metricsMap.computeIfAbsent(cacheName, k -> new PerformanceMetrics());
        
        // 更新指标
        updateMetrics(metrics, event);
        
        // 检查性能问题
        checkPerformanceIssues(cacheName, metrics, event);
    }
    
    private void updateMetrics(PerformanceMetrics metrics, CacheOperationEvent event) {
        long duration = event.getDurationMillis();
        
        switch (event.getEventType()) {
            case CACHE_HIT:
                metrics.recordHit(duration);
                break;
            case CACHE_MISS:
                metrics.recordMiss(duration);
                break;
            case CACHE_LOAD:
                if (event.isSuccess()) {
                    metrics.recordLoadSuccess(duration);
                } else {
                    metrics.recordLoadError();
                }
                break;
            case CACHE_PUT:
                metrics.recordPut(duration);
                break;
            case CACHE_ERROR:
                metrics.recordError();
                break;
        }
        
        // 检查慢操作
        if (duration >= slowOperationThreshold) {
            metrics.recordSlowOperation();
            
            if (metrics.shouldAlertSlowOperation()) {
                log.warn("Slow cache operation detected: cache=[{}], operation=[{}], key=[{}], duration={}ms",
                    event.getCacheName(), event.getEventType(), event.getKey(), duration);
                metrics.updateSlowOperationAlert();
            }
        }
    }
    
    private void checkPerformanceIssues(String cacheName, PerformanceMetrics metrics, CacheOperationEvent event) {
        // 检查命中率
        if (metrics.getTotalRequests() >= 100 && metrics.getHitRate() < lowHitRateThreshold) {
            if (metrics.shouldAlertLowHitRate()) {
                log.warn("Low cache hit rate detected: cache=[{}], hitRate={:.2f}%, requests={}",
                    cacheName, metrics.getHitRate() * 100, metrics.getTotalRequests());
                metrics.updateLowHitRateAlert();
            }
        }
        
        // 检查错误率
        if (metrics.getTotalErrors() >= highErrorRateThreshold) {
            if (metrics.shouldAlertHighErrorRate()) {
                log.warn("High cache error rate detected: cache=[{}], errors={}, requests={}",
                    cacheName, metrics.getTotalErrors(), metrics.getTotalRequests());
                metrics.updateHighErrorRateAlert();
            }
        }
    }
    
    /**
     * 获取性能指标
     */
    public PerformanceMetrics getMetrics(String cacheName) {
        return metricsMap.get(cacheName);
    }
    
    /**
     * 获取所有性能指标
     */
    public ConcurrentMap<String, PerformanceMetrics> getAllMetrics() {
        return new ConcurrentHashMap<>(metricsMap);
    }
    
    /**
     * 重置指标
     */
    public void resetMetrics(String cacheName) {
        PerformanceMetrics metrics = metricsMap.get(cacheName);
        if (metrics != null) {
            metrics.reset();
        }
    }
    
    /**
     * 清空所有指标
     */
    public void clearAllMetrics() {
        metricsMap.clear();
    }
    
    /**
     * 设置慢操作阈值
     */
    public void setSlowOperationThreshold(long threshold) {
        this.slowOperationThreshold = threshold;
    }
    
    /**
     * 设置低命中率阈值
     */
    public void setLowHitRateThreshold(double threshold) {
        this.lowHitRateThreshold = threshold;
    }
    
    /**
     * 设置高错误率阈值
     */
    public void setHighErrorRateThreshold(long threshold) {
        this.highErrorRateThreshold = threshold;
    }
    
    /**
     * 设置告警冷却时间
     */
    public void setAlertCooldown(Duration cooldown) {
        this.alertCooldown = cooldown;
    }
    
    @Override
    public boolean isActive() {
        return enabled;
    }
    
    @Override
    public String getName() {
        return "CachePerformanceMonitorListener";
    }
    
    @Override
    public int getPriority() {
        return 50; // 高优先级，早期处理
    }
    
    public void enable() {
        this.enabled = true;
    }
    
    public void disable() {
        this.enabled = false;
    }
    
    /**
     * 性能指标
     */
    public static class PerformanceMetrics {
        private final LongAdder hitCount = new LongAdder();
        private final LongAdder missCount = new LongAdder();
        private final LongAdder loadSuccessCount = new LongAdder();
        private final LongAdder loadErrorCount = new LongAdder();
        private final LongAdder putCount = new LongAdder();
        private final LongAdder errorCount = new LongAdder();
        private final LongAdder slowOperationCount = new LongAdder();
        
        private final LongAdder totalHitTime = new LongAdder();
        private final LongAdder totalMissTime = new LongAdder();
        private final LongAdder totalLoadTime = new LongAdder();
        private final LongAdder totalPutTime = new LongAdder();
        
        private final AtomicLong maxHitTime = new AtomicLong(0);
        private final AtomicLong maxMissTime = new AtomicLong(0);
        private final AtomicLong maxLoadTime = new AtomicLong(0);
        private final AtomicLong maxPutTime = new AtomicLong(0);
        
        // 告警时间戳
        private final AtomicLong lastSlowOperationAlert = new AtomicLong(0);
        private final AtomicLong lastLowHitRateAlert = new AtomicLong(0);
        private final AtomicLong lastHighErrorRateAlert = new AtomicLong(0);
        
        private final Duration alertCooldown = Duration.ofMinutes(5);
        
        void recordHit(long duration) {
            hitCount.increment();
            totalHitTime.add(duration);
            updateMax(maxHitTime, duration);
        }
        
        void recordMiss(long duration) {
            missCount.increment();
            totalMissTime.add(duration);
            updateMax(maxMissTime, duration);
        }
        
        void recordLoadSuccess(long duration) {
            loadSuccessCount.increment();
            totalLoadTime.add(duration);
            updateMax(maxLoadTime, duration);
        }
        
        void recordLoadError() {
            loadErrorCount.increment();
        }
        
        void recordPut(long duration) {
            putCount.increment();
            totalPutTime.add(duration);
            updateMax(maxPutTime, duration);
        }
        
        void recordError() {
            errorCount.increment();
        }
        
        void recordSlowOperation() {
            slowOperationCount.increment();
        }
        
        private void updateMax(AtomicLong maxValue, long newValue) {
            maxValue.updateAndGet(current -> Math.max(current, newValue));
        }
        
        boolean shouldAlertSlowOperation() {
            long now = System.currentTimeMillis();
            long lastAlert = lastSlowOperationAlert.get();
            return now - lastAlert >= alertCooldown.toMillis();
        }
        
        boolean shouldAlertLowHitRate() {
            long now = System.currentTimeMillis();
            long lastAlert = lastLowHitRateAlert.get();
            return now - lastAlert >= alertCooldown.toMillis();
        }
        
        boolean shouldAlertHighErrorRate() {
            long now = System.currentTimeMillis();
            long lastAlert = lastHighErrorRateAlert.get();
            return now - lastAlert >= alertCooldown.toMillis();
        }
        
        void updateSlowOperationAlert() {
            lastSlowOperationAlert.set(System.currentTimeMillis());
        }
        
        void updateLowHitRateAlert() {
            lastLowHitRateAlert.set(System.currentTimeMillis());
        }
        
        void updateHighErrorRateAlert() {
            lastHighErrorRateAlert.set(System.currentTimeMillis());
        }
        
        public long getHitCount() {
            return hitCount.sum();
        }
        
        public long getMissCount() {
            return missCount.sum();
        }
        
        public long getTotalRequests() {
            return getHitCount() + getMissCount();
        }
        
        public double getHitRate() {
            long total = getTotalRequests();
            return total == 0 ? 0.0 : (double) getHitCount() / total;
        }
        
        public long getLoadSuccessCount() {
            return loadSuccessCount.sum();
        }
        
        public long getLoadErrorCount() {
            return loadErrorCount.sum();
        }
        
        public long getPutCount() {
            return putCount.sum();
        }
        
        public long getTotalErrors() {
            return errorCount.sum() + loadErrorCount.sum();
        }
        
        public long getSlowOperationCount() {
            return slowOperationCount.sum();
        }
        
        public double getAverageHitTime() {
            long hits = getHitCount();
            return hits == 0 ? 0.0 : (double) totalHitTime.sum() / hits;
        }
        
        public double getAverageMissTime() {
            long misses = getMissCount();
            return misses == 0 ? 0.0 : (double) totalMissTime.sum() / misses;
        }
        
        public double getAverageLoadTime() {
            long loads = getLoadSuccessCount();
            return loads == 0 ? 0.0 : (double) totalLoadTime.sum() / loads;
        }
        
        public double getAveragePutTime() {
            long puts = getPutCount();
            return puts == 0 ? 0.0 : (double) totalPutTime.sum() / puts;
        }
        
        public long getMaxHitTime() {
            return maxHitTime.get();
        }
        
        public long getMaxMissTime() {
            return maxMissTime.get();
        }
        
        public long getMaxLoadTime() {
            return maxLoadTime.get();
        }
        
        public long getMaxPutTime() {
            return maxPutTime.get();
        }
        
        public void reset() {
            hitCount.reset();
            missCount.reset();
            loadSuccessCount.reset();
            loadErrorCount.reset();
            putCount.reset();
            errorCount.reset();
            slowOperationCount.reset();
            
            totalHitTime.reset();
            totalMissTime.reset();
            totalLoadTime.reset();
            totalPutTime.reset();
            
            maxHitTime.set(0);
            maxMissTime.set(0);
            maxLoadTime.set(0);
            maxPutTime.set(0);
            
            lastSlowOperationAlert.set(0);
            lastLowHitRateAlert.set(0);
            lastHighErrorRateAlert.set(0);
        }
        
        @Override
        public String toString() {
            return String.format(
                "PerformanceMetrics{requests=%d, hitRate=%.2f%%, errors=%d, slowOps=%d, " +
                "avgHitTime=%.2fms, avgLoadTime=%.2fms}",
                getTotalRequests(), getHitRate() * 100, getTotalErrors(), getSlowOperationCount(),
                getAverageHitTime(), getAverageLoadTime()
            );
        }
    }
}