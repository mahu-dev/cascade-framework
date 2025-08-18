package io.github.cascade.cache.event;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.Map;

/**
 * 缓存统计监听器
 * 收集和统计缓存操作的性能指标
 * 
 * @author cascade
 */
public class CacheStatisticsListener implements CacheEventListener<CacheOperationEvent> {
    
    private final Map<String, CacheStatistics> statisticsMap = new ConcurrentHashMap<>();
    private volatile boolean enabled = true;
    
    @Override
    public void onEvent(CacheOperationEvent event) {
        if (!enabled) {
            return;
        }
        
        String cacheName = event.getCacheName();
        CacheStatistics stats = statisticsMap.computeIfAbsent(cacheName, k -> new CacheStatistics());
        
        switch (event.getEventType()) {
            case CACHE_HIT:
                stats.recordHit(event.getDurationMillis());
                break;
            case CACHE_MISS:
                stats.recordMiss(event.getDurationMillis());
                break;
            case CACHE_LOAD:
                if (event.isSuccess()) {
                    stats.recordLoad(event.getDurationMillis());
                } else {
                    stats.recordLoadError();
                }
                break;
            case CACHE_PUT:
                stats.recordPut(event.getDurationMillis());
                break;
            case CACHE_EVICT:
                stats.recordEviction();
                break;
            case CACHE_CLEAR:
                stats.recordClear();
                break;
            case CACHE_ERROR:
                stats.recordError();
                break;
            default:
                // 忽略其他事件类型
                break;
        }
    }
    
    /**
     * 获取缓存统计信息
     * 
     * @param cacheName 缓存名称
     * @return 统计信息
     */
    public CacheStatistics getStatistics(String cacheName) {
        return statisticsMap.get(cacheName);
    }
    
    /**
     * 获取所有缓存统计信息
     * 
     * @return 统计信息映射
     */
    public Map<String, CacheStatistics> getAllStatistics() {
        return Map.copyOf(statisticsMap);
    }
    
    /**
     * 清空统计信息
     * 
     * @param cacheName 缓存名称
     */
    public void clearStatistics(String cacheName) {
        CacheStatistics stats = statisticsMap.get(cacheName);
        if (stats != null) {
            stats.reset();
        }
    }
    
    /**
     * 清空所有统计信息
     */
    public void clearAllStatistics() {
        statisticsMap.values().forEach(CacheStatistics::reset);
    }
    
    /**
     * 移除缓存统计信息
     * 
     * @param cacheName 缓存名称
     */
    public void removeStatistics(String cacheName) {
        statisticsMap.remove(cacheName);
    }
    
    /**
     * 启用统计
     */
    public void enable() {
        this.enabled = true;
    }
    
    /**
     * 禁用统计
     */
    public void disable() {
        this.enabled = false;
    }
    
    @Override
    public boolean isActive() {
        return enabled;
    }
    
    @Override
    public String getName() {
        return "CacheStatisticsListener";
    }
    
    @Override
    public int getPriority() {
        return 100; // 较低优先级
    }
    
    /**
     * 缓存统计信息
     */
    public static class CacheStatistics {
        private final LongAdder hitCount = new LongAdder();
        private final LongAdder missCount = new LongAdder();
        private final LongAdder loadCount = new LongAdder();
        private final LongAdder loadErrorCount = new LongAdder();
        private final LongAdder putCount = new LongAdder();
        private final LongAdder evictionCount = new LongAdder();
        private final LongAdder clearCount = new LongAdder();
        private final LongAdder errorCount = new LongAdder();
        
        private final LongAdder totalHitTime = new LongAdder();
        private final LongAdder totalMissTime = new LongAdder();
        private final LongAdder totalLoadTime = new LongAdder();
        private final LongAdder totalPutTime = new LongAdder();
        
        private final AtomicLong maxHitTime = new AtomicLong(0);
        private final AtomicLong maxMissTime = new AtomicLong(0);
        private final AtomicLong maxLoadTime = new AtomicLong(0);
        private final AtomicLong maxPutTime = new AtomicLong(0);
        
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
        
        void recordLoad(long duration) {
            loadCount.increment();
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
        
        void recordEviction() {
            evictionCount.increment();
        }
        
        void recordClear() {
            clearCount.increment();
        }
        
        void recordError() {
            errorCount.increment();
        }
        
        private void updateMax(AtomicLong maxValue, long newValue) {
            maxValue.updateAndGet(current -> Math.max(current, newValue));
        }
        
        /**
         * 获取命中次数
         */
        public long getHitCount() {
            return hitCount.sum();
        }
        
        /**
         * 获取未命中次数
         */
        public long getMissCount() {
            return missCount.sum();
        }
        
        /**
         * 获取总请求次数
         */
        public long getRequestCount() {
            return getHitCount() + getMissCount();
        }
        
        /**
         * 获取命中率
         */
        public double getHitRate() {
            long requests = getRequestCount();
            return requests == 0 ? 0.0 : (double) getHitCount() / requests;
        }
        
        /**
         * 获取未命中率
         */
        public double getMissRate() {
            return 1.0 - getHitRate();
        }
        
        /**
         * 获取加载次数
         */
        public long getLoadCount() {
            return loadCount.sum();
        }
        
        /**
         * 获取加载错误次数
         */
        public long getLoadErrorCount() {
            return loadErrorCount.sum();
        }
        
        /**
         * 获取存储次数
         */
        public long getPutCount() {
            return putCount.sum();
        }
        
        /**
         * 获取驱逐次数
         */
        public long getEvictionCount() {
            return evictionCount.sum();
        }
        
        /**
         * 获取清空次数
         */
        public long getClearCount() {
            return clearCount.sum();
        }
        
        /**
         * 获取错误次数
         */
        public long getErrorCount() {
            return errorCount.sum();
        }
        
        /**
         * 获取平均命中时间
         */
        public double getAverageHitTime() {
            long hits = getHitCount();
            return hits == 0 ? 0.0 : (double) totalHitTime.sum() / hits;
        }
        
        /**
         * 获取平均未命中时间
         */
        public double getAverageMissTime() {
            long misses = getMissCount();
            return misses == 0 ? 0.0 : (double) totalMissTime.sum() / misses;
        }
        
        /**
         * 获取平均加载时间
         */
        public double getAverageLoadTime() {
            long loads = getLoadCount();
            return loads == 0 ? 0.0 : (double) totalLoadTime.sum() / loads;
        }
        
        /**
         * 获取平均存储时间
         */
        public double getAveragePutTime() {
            long puts = getPutCount();
            return puts == 0 ? 0.0 : (double) totalPutTime.sum() / puts;
        }
        
        /**
         * 获取最大命中时间
         */
        public long getMaxHitTime() {
            return maxHitTime.get();
        }
        
        /**
         * 获取最大未命中时间
         */
        public long getMaxMissTime() {
            return maxMissTime.get();
        }
        
        /**
         * 获取最大加载时间
         */
        public long getMaxLoadTime() {
            return maxLoadTime.get();
        }
        
        /**
         * 获取最大存储时间
         */
        public long getMaxPutTime() {
            return maxPutTime.get();
        }
        
        /**
         * 重置统计信息
         */
        public void reset() {
            hitCount.reset();
            missCount.reset();
            loadCount.reset();
            loadErrorCount.reset();
            putCount.reset();
            evictionCount.reset();
            clearCount.reset();
            errorCount.reset();
            
            totalHitTime.reset();
            totalMissTime.reset();
            totalLoadTime.reset();
            totalPutTime.reset();
            
            maxHitTime.set(0);
            maxMissTime.set(0);
            maxLoadTime.set(0);
            maxPutTime.set(0);
        }
        
        @Override
        public String toString() {
            return String.format(
                "CacheStatistics{requests=%d, hits=%d, misses=%d, hitRate=%.2f%%, " +
                "loads=%d, puts=%d, evictions=%d, errors=%d}",
                getRequestCount(), getHitCount(), getMissCount(), getHitRate() * 100,
                getLoadCount(), getPutCount(), getEvictionCount(), getErrorCount()
            );
        }
    }
}