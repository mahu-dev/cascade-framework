package io.github.cascade.cache.core.unified;

import io.github.cascade.api.HealthStatus;
import io.github.cascade.cache.api.CacheStats;
import io.github.cascade.cache.api.CacheTier;
import io.github.cascade.cache.event.UnifiedCacheEvent;
import io.github.cascade.cache.metrics.UnifiedMonitoringManager;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 缓存监控统计组件
 * 负责统计信息收集、健康状态监控等功能
 *
 * @param <K> 键类型
 * @param <V> 值类型
 * @author cascade
 */
@Slf4j
public class CacheMonitor<K, V> {

    private final String cacheName;
    private final CacheCore<K, V> cacheCore;
    private final Executor executor;
    private final UnifiedMonitoringManager monitoringManager;

    // 统计计数器
    private final LongAdder hitCount = new LongAdder();
    private final LongAdder missCount = new LongAdder();
    private final LongAdder loadCount = new LongAdder();
    private final LongAdder loadSuccessCount = new LongAdder();
    private final LongAdder loadFailureCount = new LongAdder();
    private final AtomicLong totalLoadTime = new AtomicLong(0);

    // 操作统计
    private final LongAdder putCount = new LongAdder();
    private final LongAdder evictCount = new LongAdder();
    private final LongAdder refreshCount = new LongAdder();

    // 健康状态相关
    private final AtomicLong lastHealthCheck = new AtomicLong(System.currentTimeMillis());
    private HealthStatus lastHealthStatus = HealthStatus.up();

    public CacheMonitor(String cacheName, CacheCore<K, V> cacheCore, Executor executor) {
        this(cacheName, cacheCore, executor, null);
    }

    public CacheMonitor(String cacheName, CacheCore<K, V> cacheCore, Executor executor,
                        UnifiedMonitoringManager monitoringManager) {
        this.cacheName = cacheName;
        this.cacheCore = cacheCore;
        this.executor = executor;
        this.monitoringManager = monitoringManager;
    }

    // ==================== 操作监控 ====================

    /**
     * 记录缓存命中
     */
    public void recordHit(K key) {
        hitCount.increment();
        log.trace("缓存命中: cache={}, key={}", cacheName, key);

        // 发布监控事件
        publishMonitoringEvent(UnifiedCacheEvent.Type.HIT, key, null);
    }

    /**
     * 记录缓存未命中
     */
    public void recordMiss(K key) {
        missCount.increment();
        log.trace("缓存未命中: cache={}, key={}", cacheName, key);

        // 发布监控事件
        publishMonitoringEvent(UnifiedCacheEvent.Type.MISS, key, null);
    }

    /**
     * 记录数据加载开始
     */
    public void recordLoadStart(K key) {
        loadCount.increment();
        log.trace("开始加载数据: cache={}, key={}", cacheName, key);
    }

    /**
     * 记录数据加载成功
     */
    public void recordLoadSuccess(K key, Duration loadTime) {
        loadSuccessCount.increment();
        totalLoadTime.addAndGet(loadTime.toMillis());
        log.trace("数据加载成功: cache={}, key={}, loadTime={}", cacheName, key, loadTime);

        // 发布监控事件
        publishMonitoringEvent(UnifiedCacheEvent.Type.LOAD_SUCCESS, key, null, loadTime);
    }

    /**
     * 记录数据加载失败
     */
    public void recordLoadFailure(K key, Duration loadTime, Throwable exception) {
        loadFailureCount.increment();
        totalLoadTime.addAndGet(loadTime.toMillis());
        log.debug("数据加载失败: cache={}, key={}, loadTime={}, error={}",
                cacheName, key, loadTime, exception.getMessage());

        // 发布监控事件
        publishMonitoringEvent(UnifiedCacheEvent.Type.LOAD_FAILURE, key, null, loadTime, exception);
    }

    /**
     * 记录缓存写入
     */
    public void recordPut(K key, V value) {
        putCount.increment();
        log.trace("缓存写入: cache={}, key={}", cacheName, key);

        // 发布监控事件
        publishMonitoringEvent(UnifiedCacheEvent.Type.PUT, key, value);
    }

    /**
     * 记录缓存删除
     */
    public void recordEvict(K key) {
        evictCount.increment();
        log.trace("缓存删除: cache={}, key={}", cacheName, key);

        // 发布监控事件
        publishMonitoringEvent(UnifiedCacheEvent.Type.EVICT, key, null);
    }

    /**
     * 记录缓存刷新
     */
    public void recordRefresh(K key) {
        refreshCount.increment();
        log.trace("缓存刷新: cache={}, key={}", cacheName, key);
    }

    // ==================== 统计信息获取 ====================

    /**
     * 获取基本统计信息
     */
    public CacheStats getStats() {
        return new MonitoredCacheStats();
    }

    /**
     * 获取分层统计信息
     */
    public Map<CacheTier, CacheStats> getTieredStats() {
        Map<CacheTier, CacheStats> stats = new EnumMap<>(CacheTier.class);

        if (cacheCore.getL1Engine() != null) {
            stats.put(CacheTier.L1, cacheCore.getL1Engine().getStats());
        }

        if (cacheCore.isMultiTier() && cacheCore.getL2Engine() != null) {
            stats.put(CacheTier.L2, cacheCore.getL2Engine().getStats());
        }

        return stats;
    }

    /**
     * 异步获取统计信息
     */
    public CompletableFuture<CacheStats> getStatsAsync() {
        return CompletableFuture.supplyAsync(this::getStats, executor);
    }

    /**
     * 获取详细监控信息
     */
    public MonitoringReport getMonitoringReport() {
        return new MonitoringReport(
                cacheName,
                Instant.now(),
                getStats(),
                getTieredStats(),
                getHealthStatus(),
                getPerformanceMetrics()
        );
    }

    // ==================== 健康状态监控 ====================

    /**
     * 获取健康状态
     */
    public HealthStatus getHealthStatus() {
        long now = System.currentTimeMillis();
        long lastCheck = lastHealthCheck.get();

        // 如果距离上次检查超过30秒，重新检查
        if (now - lastCheck > 30_000) {
            if (lastHealthCheck.compareAndSet(lastCheck, now)) {
                lastHealthStatus = performHealthCheck();
            }
        }

        return lastHealthStatus;
    }

    /**
     * 强制健康检查
     */
    public HealthStatus forceHealthCheck() {
        lastHealthStatus = performHealthCheck();
        lastHealthCheck.set(System.currentTimeMillis());
        return lastHealthStatus;
    }

    /**
     * 执行健康检查
     */
    private HealthStatus performHealthCheck() {
        try {
            // 检查缓存大小
            long cacheSize = cacheCore.size();

            // 检查命中率
            double hitRate = getHitRate();

            // 检查错误率
            double errorRate = getErrorRate();

            // 健康状态判断
            if (hitRate < 0.3) {
                return HealthStatus.builder()
                        .down()
                        .withDetail("reason", "命中率过低")
                        .withDetail("hitRate", hitRate)
                        .build();
            }

            if (errorRate > 0.1) {
                return HealthStatus.builder()
                        .down()
                        .withDetail("reason", "错误率过高")
                        .withDetail("errorRate", errorRate)
                        .build();
            }

            return HealthStatus.builder()
                    .up()
                    .withDetail("cacheSize", cacheSize)
                    .withDetail("hitRate", hitRate)
                    .withDetail("errorRate", errorRate)
                    .build();

        } catch (Exception e) {
            log.error("健康检查失败: cache={}", cacheName, e);
            return HealthStatus.builder()
                    .down()
                    .withDetail("reason", "健康检查异常")
                    .withDetail("error", e.getMessage())
                    .build();
        }
    }

    // ==================== 性能指标 ====================

    /**
     * 获取命中率
     */
    public double getHitRate() {
        long hits = hitCount.sum();
        long misses = missCount.sum();
        long total = hits + misses;
        return total == 0 ? 0.0 : (double) hits / total;
    }

    /**
     * 获取错误率
     */
    public double getErrorRate() {
        long totalLoads = loadCount.sum();
        long failures = loadFailureCount.sum();
        return totalLoads == 0 ? 0.0 : (double) failures / totalLoads;
    }

    /**
     * 获取平均加载时间
     */
    public double getAverageLoadTime() {
        long totalLoads = loadCount.sum();
        long totalTime = totalLoadTime.get();
        return totalLoads == 0 ? 0.0 : (double) totalTime / totalLoads;
    }

    /**
     * 获取性能指标
     */
    public PerformanceMetrics getPerformanceMetrics() {
        return new PerformanceMetrics(
                getHitRate(),
                getErrorRate(),
                getAverageLoadTime(),
                putCount.sum(),
                evictCount.sum(),
                refreshCount.sum()
        );
    }

    // ==================== 监控事件发布 ====================

    /**
     * 发布监控事件到统一监控管理器
     */
    private void publishMonitoringEvent(UnifiedCacheEvent.Type type, K key, V value) {
        publishMonitoringEvent(type, key, value, null, null);
    }

    /**
     * 发布监控事件到统一监控管理器
     */
    private void publishMonitoringEvent(UnifiedCacheEvent.Type type, K key, V value, Duration duration) {
        publishMonitoringEvent(type, key, value, duration, null);
    }

    /**
     * 发布监控事件到统一监控管理器
     */
    private void publishMonitoringEvent(UnifiedCacheEvent.Type type, K key, V value, Duration duration, Throwable exception) {
        if (monitoringManager != null) {
            UnifiedCacheEvent.Builder builder = UnifiedCacheEvent.builder(cacheName, type)
                    .key(key)
                    .value(value);

            if (duration != null) {
                builder.duration(duration);
            }

            if (exception != null) {
                builder.exception(exception);
            }

            monitoringManager.publishEvent(builder.build());
        }
    }

    // ==================== 统计重置 ====================

    /**
     * 重置所有统计信息
     */
    public void resetStats() {
        hitCount.reset();
        missCount.reset();
        loadCount.reset();
        loadSuccessCount.reset();
        loadFailureCount.reset();
        totalLoadTime.set(0);
        putCount.reset();
        evictCount.reset();
        refreshCount.reset();

        log.info("统计信息已重置: cache={}", cacheName);
    }

    // ==================== 内部类 ====================

    /**
     * 监控的缓存统计信息实现
     */
    private class MonitoredCacheStats implements CacheStats {

        @Override
        public long hitCount() {
            return CacheMonitor.this.hitCount.sum();
        }

        @Override
        public long missCount() {
            return CacheMonitor.this.missCount.sum();
        }

        @Override
        public double hitRate() {
            return getHitRate();
        }

        @Override
        public double missRate() {
            return 1.0 - hitRate();
        }

        @Override
        public long loadCount() {
            return CacheMonitor.this.loadCount.sum();
        }

        @Override
        public double averageLoadPenalty() {
            return getAverageLoadTime();
        }

        @Override
        public long evictionCount() {
            return CacheMonitor.this.evictCount.sum();
        }

        @Override
        public long evictionWeight() {
            // 简化实现，返回删除次数
            return evictionCount();
        }

        @Override
        public long requestCount() {
            return hitCount() + missCount();
        }

        @Override
        public long loadExceptionCount() {
            return CacheMonitor.this.loadFailureCount.sum();
        }

        @Override
        public long totalLoadTime() {
            return CacheMonitor.this.totalLoadTime.get();
        }

        @Override
        public void reset() {
            resetStats();
        }

        @Override
        public String toString() {
            return String.format(
                    "CacheStats{requests=%d, hits=%d, misses=%d, hitRate=%.2f%%, loads=%d, avgLoadTime=%.2fms, evictions=%d, exceptions=%d}",
                    requestCount(),
                    hitCount(),
                    missCount(),
                    hitRate() * 100,
                    loadCount(),
                    averageLoadPenalty(),
                    evictionCount(),
                    loadExceptionCount()
            );
        }
    }

    /**
     * 性能指标
     */
    public static class PerformanceMetrics {
        private final double hitRate;
        private final double errorRate;
        private final double averageLoadTime;
        private final long putCount;
        private final long evictCount;
        private final long refreshCount;

        public PerformanceMetrics(double hitRate, double errorRate, double averageLoadTime,
                                  long putCount, long evictCount, long refreshCount) {
            this.hitRate = hitRate;
            this.errorRate = errorRate;
            this.averageLoadTime = averageLoadTime;
            this.putCount = putCount;
            this.evictCount = evictCount;
            this.refreshCount = refreshCount;
        }

        // Getters
        public double getHitRate() {
            return hitRate;
        }

        public double getErrorRate() {
            return errorRate;
        }

        public double getAverageLoadTime() {
            return averageLoadTime;
        }

        public long getPutCount() {
            return putCount;
        }

        public long getEvictCount() {
            return evictCount;
        }

        public long getRefreshCount() {
            return refreshCount;
        }

        @Override
        public String toString() {
            return String.format("PerformanceMetrics{hitRate=%.2f, errorRate=%.2f, avgLoadTime=%.2fms, ops=[put=%d, evict=%d, refresh=%d]}",
                    hitRate, errorRate, averageLoadTime, putCount, evictCount, refreshCount);
        }
    }

    /**
     * 监控报告
     */
    @Getter
    public static class MonitoringReport {
        // Getters
        private final String cacheName;
        private final Instant timestamp;
        private final CacheStats overallStats;
        private final Map<CacheTier, CacheStats> tieredStats;
        private final HealthStatus healthStatus;
        private final PerformanceMetrics performanceMetrics;

        public MonitoringReport(String cacheName, Instant timestamp, CacheStats overallStats,
                                Map<CacheTier, CacheStats> tieredStats, HealthStatus healthStatus,
                                PerformanceMetrics performanceMetrics) {
            this.cacheName = cacheName;
            this.timestamp = timestamp;
            this.overallStats = overallStats;
            this.tieredStats = tieredStats;
            this.healthStatus = healthStatus;
            this.performanceMetrics = performanceMetrics;
        }

        @Override
        public String toString() {
            return String.format("MonitoringReport{cache=%s, time=%s, health=%s, performance=%s}",
                    cacheName, timestamp, healthStatus.getStatus(), performanceMetrics);
        }
    }
}