package io.github.cascade.cache.metrics;

import lombok.Getter;

/**
 * 缓存性能监控指标集合
 * 
 * @author cascade
 */
public class CacheMetrics {

    /**
     * 详细的缓存性能指标
     */
    @Getter
    public static class DetailedCacheMetrics {
        private final String cacheName;
        private final boolean multiTier;
        private final long l1HitCount;
        private final long l2HitCount;
        private final long missCount;
        private final long promotionCount;
        private final long promotionFailureCount;
        private final long syncFailureCount;
        private final int activeSyncTasks;
        private final long timestamp;

        public DetailedCacheMetrics(String cacheName, boolean multiTier, long l1HitCount, 
                                   long l2HitCount, long missCount, long promotionCount,
                                   long promotionFailureCount, long syncFailureCount, 
                                   int activeSyncTasks, long timestamp) {
            this.cacheName = cacheName;
            this.multiTier = multiTier;
            this.l1HitCount = l1HitCount;
            this.l2HitCount = l2HitCount;
            this.missCount = missCount;
            this.promotionCount = promotionCount;
            this.promotionFailureCount = promotionFailureCount;
            this.syncFailureCount = syncFailureCount;
            this.activeSyncTasks = activeSyncTasks;
            this.timestamp = timestamp;
        }

        public long getTotalRequests() {
            return l1HitCount + l2HitCount + missCount;
        }

        public double getHitRate() {
            long total = getTotalRequests();
            return total > 0 ? (double) (l1HitCount + l2HitCount) / total : 0.0;
        }

        public double getPromotionSuccessRate() {
            long totalPromotions = promotionCount + promotionFailureCount;
            return totalPromotions > 0 ? (double) promotionCount / totalPromotions : 1.0;
        }

        @Override
        public String toString() {
            return String.format(
                    "DetailedCacheMetrics{cache='%s', multiTier=%s, l1Hits=%d, l2Hits=%d, " +
                            "misses=%d, hitRate=%.2f%%, promotions=%d/%d, syncFailures=%d, active=%d}",
                    cacheName, multiTier, l1HitCount, l2HitCount, missCount, 
                    getHitRate() * 100, promotionCount, promotionCount + promotionFailureCount,
                    syncFailureCount, activeSyncTasks
            );
        }
    }

    /**
     * 并行同步统计信息
     */
    @Getter
    public static class ParallelSyncStats {
        private final long totalSyncCount;
        private final long failureCount;
        private final int activeTasks;
        private final boolean multiTier;
        private final long timestamp;

        public ParallelSyncStats(long totalSyncCount, long failureCount, int activeTasks, boolean multiTier) {
            this.totalSyncCount = totalSyncCount;
            this.failureCount = failureCount;
            this.activeTasks = activeTasks;
            this.multiTier = multiTier;
            this.timestamp = System.currentTimeMillis();
        }

        public double getSuccessRate() {
            return totalSyncCount > 0 ? (double) (totalSyncCount - failureCount) / totalSyncCount : 1.0;
        }

        public double getFailureRate() {
            return totalSyncCount > 0 ? (double) failureCount / totalSyncCount : 0.0;
        }

        @Override
        public String toString() {
            return String.format(
                    "ParallelSyncStats{total=%d, failures=%d(%.2f%%), active=%d, multiTier=%s}",
                    totalSyncCount, failureCount, getFailureRate() * 100, activeTasks, multiTier
            );
        }
    }

    /**
     * 缓存健康状态
     */
    @Getter
    public static class CacheHealthStatus {
        private final String cacheName;
        private final CacheHealthLevel level;
        private final double hitRate;
        private final double promotionSuccessRate;
        private final int activeTasks;
        private final long timestamp;

        public CacheHealthStatus(String cacheName, CacheHealthLevel level, double hitRate,
                                double promotionSuccessRate, int activeTasks, long timestamp) {
            this.cacheName = cacheName;
            this.level = level;
            this.hitRate = hitRate;
            this.promotionSuccessRate = promotionSuccessRate;
            this.activeTasks = activeTasks;
            this.timestamp = timestamp;
        }

        public boolean isHealthy() {
            return level == CacheHealthLevel.EXCELLENT || level == CacheHealthLevel.GOOD;
        }

        @Override
        public String toString() {
            return String.format(
                    "CacheHealthStatus{cache='%s', level=%s, hitRate=%.2f%%, promotionRate=%.2f%%, active=%d}",
                    cacheName, level, hitRate * 100, promotionSuccessRate * 100, activeTasks
            );
        }
    }

    /**
     * 缓存健康等级枚举
     */
    @Getter
    public enum CacheHealthLevel {
        EXCELLENT("优秀"),
        GOOD("良好"),
        FAIR("一般"),
        POOR("较差");

        private final String description;

        CacheHealthLevel(String description) {
            this.description = description;
        }
    }
}