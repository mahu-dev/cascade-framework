package io.github.cascade.cache.stats;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 缓存指标统计类
 * 根据设计文档实现完整的缓存指标收集
 */
public class CacheMetrics {
    
    // 命中率统计
    private final LongAdder hitCount = new LongAdder();
    private final LongAdder missCount = new LongAdder();
    
    // 加载统计
    private final LongAdder loadCount = new LongAdder();
    private final LongAdder loadSuccessCount = new LongAdder();
    private final LongAdder loadFailureCount = new LongAdder();
    private final AtomicLong totalLoadTime = new AtomicLong(0);
    
    // 淘汰统计
    private final LongAdder evictionCount = new LongAdder();
    private final LongAdder evictionWeight = new LongAdder();
    
    // 大小统计
    private volatile long estimatedSize;
    
    // 时间统计
    private final Instant creationTime = Instant.now();
    private volatile Instant lastAccessTime = Instant.now();
    
    /**
     * 记录缓存命中
     */
    public void recordHit() {
        hitCount.increment();
        updateLastAccessTime();
    }
    
    /**
     * 记录缓存未命中
     */
    public void recordMiss() {
        missCount.increment();
        updateLastAccessTime();
    }
    
    /**
     * 记录加载开始
     */
    public void recordLoadStart() {
        loadCount.increment();
    }
    
    /**
     * 记录加载成功
     */
    public void recordLoadSuccess(long loadTimeNanos) {
        loadSuccessCount.increment();
        totalLoadTime.addAndGet(loadTimeNanos);
    }
    
    /**
     * 记录加载失败
     */
    public void recordLoadFailure(long loadTimeNanos) {
        loadFailureCount.increment();
        totalLoadTime.addAndGet(loadTimeNanos);
    }
    
    /**
     * 记录淘汰操作
     */
    public void recordEviction() {
        evictionCount.increment();
    }
    
    /**
     * 记录淘汰权重
     */
    public void recordEviction(long weight) {
        evictionCount.increment();
        evictionWeight.add(weight);
    }
    
    /**
     * 更新估计大小
     */
    public void updateEstimatedSize(long size) {
        this.estimatedSize = size;
    }
    
    /**
     * 获取命中次数
     */
    public long hitCount() {
        return hitCount.sum();
    }
    
    /**
     * 获取未命中次数
     */
    public long missCount() {
        return missCount.sum();
    }
    
    /**
     * 获取总请求次数
     */
    public long requestCount() {
        return hitCount() + missCount();
    }
    
    /**
     * 获取命中率
     */
    public double hitRate() {
        long requests = requestCount();
        return requests == 0 ? 1.0 : (double) hitCount() / requests;
    }
    
    /**
     * 获取未命中率
     */
    public double missRate() {
        return 1.0 - hitRate();
    }
    
    /**
     * 获取加载次数
     */
    public long loadCount() {
        return loadCount.sum();
    }
    
    /**
     * 获取加载成功次数
     */
    public long loadSuccessCount() {
        return loadSuccessCount.sum();
    }
    
    /**
     * 获取加载失败次数
     */
    public long loadFailureCount() {
        return loadFailureCount.sum();
    }
    
    /**
     * 获取平均加载时间（纳秒）
     */
    public double averageLoadPenalty() {
        long loads = loadCount();
        return loads == 0 ? 0.0 : (double) totalLoadTime.get() / loads;
    }
    
    /**
     * 获取平均加载时间（毫秒）
     */
    public double averageLoadTimeMillis() {
        return averageLoadPenalty() / 1_000_000.0;
    }
    
    /**
     * 获取淘汰次数
     */
    public long evictionCount() {
        return evictionCount.sum();
    }
    
    /**
     * 获取淘汰权重
     */
    public long evictionWeight() {
        return evictionWeight.sum();
    }
    
    /**
     * 获取估计大小
     */
    public long estimatedSize() {
        return estimatedSize;
    }
    
    /**
     * 获取创建时间
     */
    public Instant creationTime() {
        return creationTime;
    }
    
    /**
     * 获取最后访问时间
     */
    public Instant lastAccessTime() {
        return lastAccessTime;
    }
    
    /**
     * 获取运行时长
     */
    public Duration uptime() {
        return Duration.between(creationTime, Instant.now());
    }
    
    /**
     * 重置所有统计数据
     */
    public void reset() {
        hitCount.reset();
        missCount.reset();
        loadCount.reset();
        loadSuccessCount.reset();
        loadFailureCount.reset();
        totalLoadTime.set(0);
        evictionCount.reset();
        evictionWeight.reset();
        estimatedSize = 0;
        lastAccessTime = Instant.now();
    }
    
    /**
     * 创建统计快照
     */
    public CacheStatsSnapshot snapshot() {
        return CacheStatsSnapshot.builder()
            .hitCount(hitCount())
            .missCount(missCount())
            .loadCount(loadCount())
            .loadSuccessCount(loadSuccessCount())
            .loadFailureCount(loadFailureCount())
            .averageLoadTime(averageLoadPenalty())
            .evictionCount(evictionCount())
            .evictionWeight(evictionWeight())
            .estimatedSize(estimatedSize())
            .creationTime(creationTime())
            .lastAccessTime(lastAccessTime())
            .build();
    }
    
    private void updateLastAccessTime() {
        lastAccessTime = Instant.now();
    }
    
    @Override
    public String toString() {
        return String.format(
            "CacheMetrics{hitRate=%.2f%%, requests=%d, loads=%d, evictions=%d, size=%d}",
            hitRate() * 100, requestCount(), loadCount(), evictionCount(), estimatedSize()
        );
    }
}