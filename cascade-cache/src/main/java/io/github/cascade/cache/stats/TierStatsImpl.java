package io.github.cascade.cache.stats;

import io.github.cascade.cache.api.CacheStats;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 缓存层级统计信息实现
 *
 * @author cascade
 */
public class TierStatsImpl implements CacheStats {

    private final LongAdder hitCount = new LongAdder();
    private final LongAdder missCount = new LongAdder();
    private final LongAdder loadCount = new LongAdder();
    private final LongAdder loadFailureCount = new LongAdder();
    private final LongAdder totalLoadTime = new LongAdder();
    private final LongAdder evictionCount = new LongAdder();
    private final LongAdder evictionWeight = new LongAdder();
    private final AtomicLong maxLoadTime = new AtomicLong(0);
    private final AtomicLong minLoadTime = new AtomicLong(Long.MAX_VALUE);

    @Override
    public long hitCount() {
        return hitCount.sum();
    }

    @Override
    public long missCount() {
        return missCount.sum();
    }

    @Override
    public long requestCount() {
        return hitCount() + missCount();
    }

    @Override
    public double hitRate() {
        long requestCount = requestCount();
        return requestCount == 0 ? 1.0 : (double) hitCount() / requestCount;
    }

    @Override
    public double missRate() {
        long requestCount = requestCount();
        return requestCount == 0 ? 0.0 : (double) missCount() / requestCount;
    }

    @Override
    public long loadCount() {
        return loadCount.sum();
    }

    @Override
    public long loadExceptionCount() {
        return loadFailureCount.sum();
    }

    @Override
    public long totalLoadTime() {
        return totalLoadTime.sum();
    }

    @Override
    public double averageLoadPenalty() {
        long totalLoads = loadCount();
        return totalLoads == 0 ? 0.0 : (double) totalLoadTime() / totalLoads;
    }

    @Override
    public long evictionCount() {
        return evictionCount.sum();
    }

    @Override
    public long evictionWeight() {
        return evictionWeight.sum();
    }

    @Override
    public void reset() {
        hitCount.reset();
        missCount.reset();
        loadCount.reset();
        loadFailureCount.reset();
        totalLoadTime.reset();
        evictionCount.reset();
        evictionWeight.reset();
        maxLoadTime.set(0);
        minLoadTime.set(Long.MAX_VALUE);
    }

    // 记录方法
    public void recordHit() {
        hitCount.increment();
    }

    public void recordMiss() {
        missCount.increment();
    }

    public void recordLoad() {
        loadCount.increment();
    }

    public void recordLoadFailure() {
        loadFailureCount.increment();
    }

    public void recordLoadTime(long nanos) {
        totalLoadTime.add(nanos);
        
        // 更新最大加载时间
        long currentMax = maxLoadTime.get();
        while (nanos > currentMax) {
            if (maxLoadTime.compareAndSet(currentMax, nanos)) {
                break;
            }
            currentMax = maxLoadTime.get();
        }
        
        // 更新最小加载时间
        long currentMin = minLoadTime.get();
        while (nanos < currentMin) {
            if (minLoadTime.compareAndSet(currentMin, nanos)) {
                break;
            }
            currentMin = minLoadTime.get();
        }
    }

    public void recordEviction() {
        evictionCount.increment();
    }

    public void recordEviction(long weight) {
        evictionCount.increment();
        evictionWeight.add(weight);
    }

    public long getMaxLoadTime() {
        long max = maxLoadTime.get();
        return max == 0 ? 0 : max;
    }

    public long getMinLoadTime() {
        long min = minLoadTime.get();
        return min == Long.MAX_VALUE ? 0 : min;
    }

    @Override
    public String toString() {
        return String.format(
            "TierStats{hitCount=%d, missCount=%d, hitRate=%.2f%%, loadCount=%d, " +
            "loadFailureCount=%d, averageLoadPenalty=%.2fns, evictionCount=%d}",
            hitCount(),
            missCount(),
            hitRate() * 100,
            loadCount(),
            loadExceptionCount(),
            averageLoadPenalty(),
            evictionCount()
        );
    }
}