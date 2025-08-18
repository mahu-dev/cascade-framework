package io.github.cascade.cache.metrics;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 缓存统计信息
 * 包含缓存的各种性能指标和统计数据
 *
 * @author Cascade Framework
 */
public class CacheStats {

    private final String cacheName;
    private final long hitCount;
    private final long missCount;
    private final long loadCount;
    private final long loadSuccessCount;
    private final long loadExceptionCount;
    private final long evictionCount;
    private final long putCount;
    private final long removeCount;
    
    private final long totalLoadTime;
    private final long maxLoadTime;
    private final long minLoadTime;
    
    private final long currentSize;
    private final long maxSize;
    
    private final Duration uptime;
    
    private final Map<String, CacheMetricsCollector.TierMetrics> tierMetrics;
    private final Map<String, CacheMetricsCollector.WindowStats> timeWindowMetrics;

    public CacheStats(String cacheName,
                     long hitCount, long missCount, long loadCount,
                     long loadSuccessCount, long loadExceptionCount,
                     long evictionCount, long putCount, long removeCount,
                     long totalLoadTime, long maxLoadTime, long minLoadTime,
                     long currentSize, long maxSize,
                     Duration uptime,
                     Map<String, CacheMetricsCollector.TierMetrics> tierMetrics,
                     Map<String, CacheMetricsCollector.WindowStats> timeWindowMetrics) {
        this.cacheName = cacheName;
        this.hitCount = hitCount;
        this.missCount = missCount;
        this.loadCount = loadCount;
        this.loadSuccessCount = loadSuccessCount;
        this.loadExceptionCount = loadExceptionCount;
        this.evictionCount = evictionCount;
        this.putCount = putCount;
        this.removeCount = removeCount;
        this.totalLoadTime = totalLoadTime;
        this.maxLoadTime = maxLoadTime;
        this.minLoadTime = minLoadTime;
        this.currentSize = currentSize;
        this.maxSize = maxSize;
        this.uptime = uptime;
        this.tierMetrics = new ConcurrentHashMap<>(tierMetrics);
        this.timeWindowMetrics = new ConcurrentHashMap<>(timeWindowMetrics);
    }

    /**
     * 获取缓存名称
     */
    public String getCacheName() {
        return cacheName;
    }

    /**
     * 获取命中次数
     */
    public long getHitCount() {
        return hitCount;
    }

    /**
     * 获取未命中次数
     */
    public long getMissCount() {
        return missCount;
    }

    /**
     * 获取总请求次数
     */
    public long getRequestCount() {
        return hitCount + missCount;
    }

    /**
     * 获取命中率
     */
    public double getHitRate() {
        long total = getRequestCount();
        return total > 0 ? (double) hitCount / total : 0.0;
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
        return loadCount;
    }

    /**
     * 获取加载成功次数
     */
    public long getLoadSuccessCount() {
        return loadSuccessCount;
    }

    /**
     * 获取加载异常次数
     */
    public long getLoadExceptionCount() {
        return loadExceptionCount;
    }

    /**
     * 获取加载成功率
     */
    public double getLoadSuccessRate() {
        return loadCount > 0 ? (double) loadSuccessCount / loadCount : 0.0;
    }

    /**
     * 获取加载异常率
     */
    public double getLoadExceptionRate() {
        return loadCount > 0 ? (double) loadExceptionCount / loadCount : 0.0;
    }

    /**
     * 获取驱逐次数
     */
    public long getEvictionCount() {
        return evictionCount;
    }

    /**
     * 获取放入次数
     */
    public long getPutCount() {
        return putCount;
    }

    /**
     * 获取移除次数
     */
    public long getRemoveCount() {
        return removeCount;
    }

    /**
     * 获取总加载时间（纳秒）
     */
    public long getTotalLoadTime() {
        return totalLoadTime;
    }

    /**
     * 获取平均加载时间（纳秒）
     */
    public double getAverageLoadTime() {
        return loadCount > 0 ? (double) totalLoadTime / loadCount : 0.0;
    }

    /**
     * 获取平均加载时间（毫秒）
     */
    public double getAverageLoadTimeMillis() {
        return getAverageLoadTime() / 1_000_000.0;
    }

    /**
     * 获取最大加载时间（纳秒）
     */
    public long getMaxLoadTime() {
        return maxLoadTime;
    }

    /**
     * 获取最大加载时间（毫秒）
     */
    public double getMaxLoadTimeMillis() {
        return maxLoadTime / 1_000_000.0;
    }

    /**
     * 获取最小加载时间（纳秒）
     */
    public long getMinLoadTime() {
        return minLoadTime;
    }

    /**
     * 获取最小加载时间（毫秒）
     */
    public double getMinLoadTimeMillis() {
        return minLoadTime / 1_000_000.0;
    }

    /**
     * 获取当前大小
     */
    public long getCurrentSize() {
        return currentSize;
    }

    /**
     * 获取最大大小
     */
    public long getMaxSize() {
        return maxSize;
    }

    /**
     * 获取运行时间
     */
    public Duration getUptime() {
        return uptime;
    }

    /**
     * 获取分层指标
     */
    public Map<String, CacheMetricsCollector.TierMetrics> getTierMetrics() {
        return new ConcurrentHashMap<>(tierMetrics);
    }

    /**
     * 获取时间窗口指标
     */
    public Map<String, CacheMetricsCollector.WindowStats> getTimeWindowMetrics() {
        return new ConcurrentHashMap<>(timeWindowMetrics);
    }

    /**
     * 获取指定分层的指标
     */
    public CacheMetricsCollector.TierMetrics getTierMetrics(String tierName) {
        return tierMetrics.get(tierName);
    }

    /**
     * 获取指定时间窗口的指标
     */
    public CacheMetricsCollector.WindowStats getTimeWindowMetrics(String windowName) {
        return timeWindowMetrics.get(windowName);
    }

    /**
     * 获取吞吐量（每秒请求数）
     */
    public double getThroughput() {
        long uptimeSeconds = uptime.getSeconds();
        return uptimeSeconds > 0 ? (double) getRequestCount() / uptimeSeconds : 0.0;
    }

    /**
     * 获取加载吞吐量（每秒加载数）
     */
    public double getLoadThroughput() {
        long uptimeSeconds = uptime.getSeconds();
        return uptimeSeconds > 0 ? (double) loadCount / uptimeSeconds : 0.0;
    }

    /**
     * 获取驱逐率（每秒驱逐数）
     */
    public double getEvictionRate() {
        long uptimeSeconds = uptime.getSeconds();
        return uptimeSeconds > 0 ? (double) evictionCount / uptimeSeconds : 0.0;
    }

    /**
     * 转换为字符串表示
     */
    @Override
    public String toString() {
        return String.format(
            "CacheStats{cacheName='%s', hitCount=%d, missCount=%d, hitRate=%.2f%%, " +
            "loadCount=%d, loadSuccessRate=%.2f%%, avgLoadTime=%.2fms, " +
            "currentSize=%d, maxSize=%d, uptime=%s}",
            cacheName, hitCount, missCount, getHitRate() * 100,
            loadCount, getLoadSuccessRate() * 100, getAverageLoadTimeMillis(),
            currentSize, maxSize, uptime
        );
    }

    /**
     * 创建构建器
     */
    public static Builder builder(String cacheName) {
        return new Builder(cacheName);
    }

    /**
     * 构建器
     */
    public static class Builder {
        private final String cacheName;
        private long hitCount = 0;
        private long missCount = 0;
        private long loadCount = 0;
        private long loadSuccessCount = 0;
        private long loadExceptionCount = 0;
        private long evictionCount = 0;
        private long putCount = 0;
        private long removeCount = 0;
        private long totalLoadTime = 0;
        private long maxLoadTime = 0;
        private long minLoadTime = 0;
        private long currentSize = 0;
        private long maxSize = 0;
        private Duration uptime = Duration.ZERO;
        private Map<String, CacheMetricsCollector.TierMetrics> tierMetrics = new ConcurrentHashMap<>();
        private Map<String, CacheMetricsCollector.WindowStats> timeWindowMetrics = new ConcurrentHashMap<>();

        public Builder(String cacheName) {
            this.cacheName = cacheName;
        }

        public Builder hitCount(long hitCount) {
            this.hitCount = hitCount;
            return this;
        }

        public Builder missCount(long missCount) {
            this.missCount = missCount;
            return this;
        }

        public Builder loadCount(long loadCount) {
            this.loadCount = loadCount;
            return this;
        }

        public Builder loadSuccessCount(long loadSuccessCount) {
            this.loadSuccessCount = loadSuccessCount;
            return this;
        }

        public Builder loadExceptionCount(long loadExceptionCount) {
            this.loadExceptionCount = loadExceptionCount;
            return this;
        }

        public Builder evictionCount(long evictionCount) {
            this.evictionCount = evictionCount;
            return this;
        }

        public Builder putCount(long putCount) {
            this.putCount = putCount;
            return this;
        }

        public Builder removeCount(long removeCount) {
            this.removeCount = removeCount;
            return this;
        }

        public Builder totalLoadTime(long totalLoadTime) {
            this.totalLoadTime = totalLoadTime;
            return this;
        }

        public Builder maxLoadTime(long maxLoadTime) {
            this.maxLoadTime = maxLoadTime;
            return this;
        }

        public Builder minLoadTime(long minLoadTime) {
            this.minLoadTime = minLoadTime;
            return this;
        }

        public Builder currentSize(long currentSize) {
            this.currentSize = currentSize;
            return this;
        }

        public Builder maxSize(long maxSize) {
            this.maxSize = maxSize;
            return this;
        }

        public Builder uptime(Duration uptime) {
            this.uptime = uptime;
            return this;
        }

        public Builder tierMetrics(Map<String, CacheMetricsCollector.TierMetrics> tierMetrics) {
            this.tierMetrics = tierMetrics;
            return this;
        }

        public Builder timeWindowMetrics(Map<String, CacheMetricsCollector.WindowStats> timeWindowMetrics) {
            this.timeWindowMetrics = timeWindowMetrics;
            return this;
        }

        public CacheStats build() {
            return new CacheStats(
                cacheName, hitCount, missCount, loadCount,
                loadSuccessCount, loadExceptionCount,
                evictionCount, putCount, removeCount,
                totalLoadTime, maxLoadTime, minLoadTime,
                currentSize, maxSize, uptime,
                tierMetrics, timeWindowMetrics
            );
        }
    }
}