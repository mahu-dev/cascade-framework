package io.github.cascade.cache.metrics;

import io.github.cascade.cache.api.CacheStats;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 缓存性能监控器
 * 负责收集、计算和提供缓存性能指标
 * 
 * @author cascade
 */
@Slf4j
public class CachePerformanceMonitor {
    
    private final String cacheName;
    private final boolean isMultiTier;
    
    // 并行同步统计
    private final AtomicLong parallelSyncCount = new AtomicLong(0);
    private final AtomicLong syncFailureCount = new AtomicLong(0);
    private final AtomicInteger activeSyncTasks = new AtomicInteger(0);
    
    // 性能监控指标
    private final AtomicLong l1HitCount = new AtomicLong(0);
    private final AtomicLong l2HitCount = new AtomicLong(0);
    private final AtomicLong missCount = new AtomicLong(0);
    private final AtomicLong promotionCount = new AtomicLong(0);
    private final AtomicLong promotionFailureCount = new AtomicLong(0);
    
    // 防重复提升机制
    @Getter
    private final ConcurrentHashMap<Object, Boolean> promotingKeys = new ConcurrentHashMap<>();
    
    public CachePerformanceMonitor(String cacheName, boolean isMultiTier) {
        this.cacheName = cacheName;
        this.isMultiTier = isMultiTier;
    }
    
    // ==================== 计数方法 ====================
    
    public void recordL1Hit() {
        l1HitCount.incrementAndGet();
    }
    
    public void recordL2Hit() {
        l2HitCount.incrementAndGet();
    }
    
    public void recordMiss() {
        missCount.incrementAndGet();
    }
    
    public void recordPromotionSuccess() {
        promotionCount.incrementAndGet();
    }
    
    public void recordPromotionFailure() {
        promotionFailureCount.incrementAndGet();
    }
    
    public void recordSyncStart() {
        parallelSyncCount.incrementAndGet();
        activeSyncTasks.incrementAndGet();
    }
    
    public void recordSyncEnd() {
        activeSyncTasks.decrementAndGet();
    }
    
    public void recordSyncFailure() {
        syncFailureCount.incrementAndGet();
    }
    
    // ==================== 防重复提升 ====================
    
    public boolean tryStartPromotion(Object key) {
        return promotingKeys.putIfAbsent(key, Boolean.TRUE) == null;
    }
    
    public void finishPromotion(Object key) {
        promotingKeys.remove(key);
    }
    
    // ==================== 统计信息获取 ====================
    
    /**
     * 获取详细的缓存性能指标
     */
    public CacheMetrics.DetailedCacheMetrics getDetailedMetrics() {
        return new CacheMetrics.DetailedCacheMetrics(
                cacheName,
                isMultiTier,
                l1HitCount.get(),
                l2HitCount.get(),
                missCount.get(),
                promotionCount.get(),
                promotionFailureCount.get(),
                syncFailureCount.get(),
                activeSyncTasks.get(),
                System.currentTimeMillis()
        );
    }

    /**
     * 获取并行同步统计信息
     */
    public CacheMetrics.ParallelSyncStats getParallelSyncStats() {
        return new CacheMetrics.ParallelSyncStats(
                parallelSyncCount.get(),
                syncFailureCount.get(),
                activeSyncTasks.get(),
                isMultiTier
        );
    }

    /**
     * 获取缓存健康状态
     */
    public CacheMetrics.CacheHealthStatus getHealthStatus() {
        CacheMetrics.DetailedCacheMetrics metrics = getDetailedMetrics();
        
        // 计算命中率
        long totalRequests = metrics.getL1HitCount() + metrics.getL2HitCount() + metrics.getMissCount();
        double hitRate = totalRequests > 0 ? 
                (double) (metrics.getL1HitCount() + metrics.getL2HitCount()) / totalRequests : 0.0;
        
        // 计算提升成功率
        long totalPromotions = metrics.getPromotionCount() + metrics.getPromotionFailureCount();
        double promotionSuccessRate = totalPromotions > 0 ? 
                (double) metrics.getPromotionCount() / totalPromotions : 1.0;
        
        // 判断健康状态
        CacheMetrics.CacheHealthLevel healthLevel = determineHealthLevel(hitRate, promotionSuccessRate);
        
        return new CacheMetrics.CacheHealthStatus(
                cacheName,
                healthLevel,
                hitRate,
                promotionSuccessRate,
                metrics.getActiveSyncTasks(),
                System.currentTimeMillis()
        );
    }
    
    private CacheMetrics.CacheHealthLevel determineHealthLevel(double hitRate, double promotionSuccessRate) {
        if (hitRate >= 0.9 && promotionSuccessRate >= 0.95) {
            return CacheMetrics.CacheHealthLevel.EXCELLENT;
        } else if (hitRate >= 0.75 && promotionSuccessRate >= 0.85) {
            return CacheMetrics.CacheHealthLevel.GOOD;
        } else if (hitRate >= 0.5 && promotionSuccessRate >= 0.7) {
            return CacheMetrics.CacheHealthLevel.FAIR;
        } else {
            return CacheMetrics.CacheHealthLevel.POOR;
        }
    }

    /**
     * 获取综合性能指标
     */
    public ComprehensiveMetrics getComprehensiveMetrics(CacheStats cacheStats) {
        CacheMetrics.ParallelSyncStats syncStats = getParallelSyncStats();

        return new ComprehensiveMetrics(
                cacheName,
                isMultiTier,
                cacheStats,
                syncStats,
                System.currentTimeMillis()
        );
    }

    /**
     * 重置性能监控统计
     */
    public void resetPerformanceStats() {
        parallelSyncCount.set(0);
        syncFailureCount.set(0);
        l1HitCount.set(0);
        l2HitCount.set(0);
        missCount.set(0);
        promotionCount.set(0);
        promotionFailureCount.set(0);
        promotingKeys.clear();
        // 不重置activeSyncTasks，因为它表示当前活跃任务数
        log.info("Performance stats reset for cache: {}", cacheName);
    }
    
    // ==================== 内部类 ====================
    
    /**
     * 综合性能指标
     */
    @Getter
    public static class ComprehensiveMetrics {
        private final String cacheName;
        private final boolean multiTier;
        private final CacheStats cacheStats;
        private final CacheMetrics.ParallelSyncStats syncStats;
        private final long timestamp;

        public ComprehensiveMetrics(String cacheName, boolean multiTier, CacheStats cacheStats,
                                    CacheMetrics.ParallelSyncStats syncStats, long timestamp) {
            this.cacheName = cacheName;
            this.multiTier = multiTier;
            this.cacheStats = cacheStats;
            this.syncStats = syncStats;
            this.timestamp = timestamp;
        }

        /**
         * 获取缓存健康度评分 (0-100)
         */
        public int getHealthScore() {
            double hitRate = cacheStats.hitRate();
            double syncSuccessRate = syncStats.getSuccessRate();

            // 命中率权重70%，同步成功率权重30%
            return (int) (hitRate * 70 + syncSuccessRate * 30);
        }

        /**
         * 获取性能等级
         */
        public PerformanceGrade getPerformanceGrade() {
            int score = getHealthScore();
            if (score >= 90) return PerformanceGrade.EXCELLENT;
            else if (score >= 75) return PerformanceGrade.GOOD;
            else if (score >= 60) return PerformanceGrade.FAIR;
            else return PerformanceGrade.POOR;
        }

        @Override
        public String toString() {
            return String.format(
                    "ComprehensiveMetrics{cache='%s', multiTier=%s, hitRate=%.2f%%, " +
                            "requests=%d, syncSuccess=%.2f%%, health=%d, grade=%s}",
                    cacheName, multiTier, cacheStats.hitRate() * 100,
                    cacheStats.requestCount(), syncStats.getSuccessRate() * 100,
                    getHealthScore(), getPerformanceGrade()
            );
        }
    }

    /**
     * 性能等级枚举
     */
    @Getter
    public enum PerformanceGrade {
        EXCELLENT("优秀", 90, 100),
        GOOD("良好", 75, 89),
        FAIR("一般", 60, 74),
        POOR("较差", 0, 59);

        private final String description;
        private final int minScore;
        private final int maxScore;

        PerformanceGrade(String description, int minScore, int maxScore) {
            this.description = description;
            this.minScore = minScore;
            this.maxScore = maxScore;
        }
    }
}