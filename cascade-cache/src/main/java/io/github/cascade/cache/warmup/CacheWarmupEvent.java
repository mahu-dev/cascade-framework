package io.github.cascade.cache.warmup;

import java.time.Instant;
import java.util.List;

/**
 * 缓存预热事件
 */
public abstract class CacheWarmupEvent {
    
    private final String cacheName;
    private final Instant timestamp;
    
    protected CacheWarmupEvent(String cacheName) {
        this.cacheName = cacheName;
        this.timestamp = Instant.now();
    }
    
    public String getCacheName() {
        return cacheName;
    }
    
    public Instant getTimestamp() {
        return timestamp;
    }
    
    /**
     * 预热开始事件
     */
    public static class Started extends CacheWarmupEvent {
        private final int totalStrategies;
        
        public Started(String cacheName, int totalStrategies) {
            super(cacheName);
            this.totalStrategies = totalStrategies;
        }
        
        public int getTotalStrategies() {
            return totalStrategies;
        }
        
        @Override
        public String toString() {
            return String.format("CacheWarmupStarted{cache='%s', strategies=%d, timestamp=%s}",
                getCacheName(), totalStrategies, getTimestamp());
        }
    }
    
    /**
     * 策略完成事件
     */
    public static class StrategyCompleted extends CacheWarmupEvent {
        private final String strategyName;
        private final CacheWarmupStrategy.WarmupResult result;
        
        public StrategyCompleted(String cacheName, String strategyName, CacheWarmupStrategy.WarmupResult result) {
            super(cacheName);
            this.strategyName = strategyName;
            this.result = result;
        }
        
        public String getStrategyName() {
            return strategyName;
        }
        
        public CacheWarmupStrategy.WarmupResult getResult() {
            return result;
        }
        
        @Override
        public String toString() {
            return String.format("CacheWarmupStrategyCompleted{cache='%s', strategy='%s', result=%s, timestamp=%s}",
                getCacheName(), strategyName, result, getTimestamp());
        }
    }
    
    /**
     * 策略失败事件
     */
    public static class StrategyFailed extends CacheWarmupEvent {
        private final String strategyName;
        private final Throwable error;
        
        public StrategyFailed(String cacheName, String strategyName, Throwable error) {
            super(cacheName);
            this.strategyName = strategyName;
            this.error = error;
        }
        
        public String getStrategyName() {
            return strategyName;
        }
        
        public Throwable getError() {
            return error;
        }
        
        @Override
        public String toString() {
            return String.format("CacheWarmupStrategyFailed{cache='%s', strategy='%s', error='%s', timestamp=%s}",
                getCacheName(), strategyName, error.getMessage(), getTimestamp());
        }
    }
    
    /**
     * 预热完成事件
     */
    public static class Completed extends CacheWarmupEvent {
        private final List<CacheWarmupStrategy.WarmupResult> results;
        
        public Completed(String cacheName, List<CacheWarmupStrategy.WarmupResult> results) {
            super(cacheName);
            this.results = results;
        }
        
        public List<CacheWarmupStrategy.WarmupResult> getResults() {
            return results;
        }
        
        public long getTotalLoaded() {
            return results.stream().mapToLong(CacheWarmupStrategy.WarmupResult::getLoadedCount).sum();
        }
        
        public long getTotalFailed() {
            return results.stream().mapToLong(CacheWarmupStrategy.WarmupResult::getFailedCount).sum();
        }
        
        public boolean isOverallSuccess() {
            return results.stream().anyMatch(CacheWarmupStrategy.WarmupResult::isSuccess);
        }
        
        @Override
        public String toString() {
            return String.format("CacheWarmupCompleted{cache='%s', loaded=%d, failed=%d, success=%s, timestamp=%s}",
                getCacheName(), getTotalLoaded(), getTotalFailed(), isOverallSuccess(), getTimestamp());
        }
    }
    
    /**
     * 预热取消事件
     */
    public static class Cancelled extends CacheWarmupEvent {
        
        public Cancelled(String cacheName) {
            super(cacheName);
        }
        
        @Override
        public String toString() {
            return String.format("CacheWarmupCancelled{cache='%s', timestamp=%s}",
                getCacheName(), getTimestamp());
        }
    }
}