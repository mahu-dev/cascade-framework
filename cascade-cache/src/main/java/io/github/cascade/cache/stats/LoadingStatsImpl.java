package io.github.cascade.cache.stats;

import io.github.cascade.cache.api.LoadingStats;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 缓存加载统计信息实现
 *
 * @author cascade
 */
public class LoadingStatsImpl implements LoadingStats {

    private final LongAdder loadSuccessCount = new LongAdder();
    private final LongAdder loadFailureCount = new LongAdder();
    private final LongAdder totalLoadTime = new LongAdder();
    private final LongAdder activeLoadCount = new LongAdder();
    private final LongAdder batchLoadCount = new LongAdder();
    private final LongAdder batchLoadSize = new LongAdder();
    private final LongAdder loadTimeoutCount = new LongAdder();
    private final LongAdder loadCancelCount = new LongAdder();
    private final AtomicLong maxLoadTime = new AtomicLong(0);
    private final AtomicLong minLoadTime = new AtomicLong(Long.MAX_VALUE);

    @Override
    public long loadSuccessCount() {
        return loadSuccessCount.sum();
    }

    @Override
    public long loadFailureCount() {
        return loadFailureCount.sum();
    }

    @Override
    public long loadCount() {
        return loadSuccessCount() + loadFailureCount();
    }

    @Override
    public double loadSuccessRate() {
        long total = loadCount();
        return total == 0 ? 1.0 : (double) loadSuccessCount() / total;
    }

    @Override
    public double loadFailureRate() {
        long total = loadCount();
        return total == 0 ? 0.0 : (double) loadFailureCount() / total;
    }

    @Override
    public long totalLoadTime() {
        return totalLoadTime.sum();
    }

    @Override
    public double averageLoadTime() {
        long total = loadCount();
        return total == 0 ? 0.0 : (double) totalLoadTime() / total;
    }

    @Override
    public long maxLoadTime() {
        long max = maxLoadTime.get();
        return max == 0 ? 0 : max;
    }

    @Override
    public long minLoadTime() {
        long min = minLoadTime.get();
        return min == Long.MAX_VALUE ? 0 : min;
    }

    @Override
    public long activeLoadCount() {
        return activeLoadCount.sum();
    }

    @Override
    public long batchLoadCount() {
        return batchLoadCount.sum();
    }

    @Override
    public double averageBatchSize() {
        long batchCount = batchLoadCount();
        return batchCount == 0 ? 0.0 : (double) batchLoadSize.sum() / batchCount;
    }

    @Override
    public long loadTimeoutCount() {
        return loadTimeoutCount.sum();
    }

    @Override
    public long loadCancelCount() {
        return loadCancelCount.sum();
    }

    // 记录方法
    public void recordLoadSuccess() {
        loadSuccessCount.increment();
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
    
    public void recordActiveLoadStart() {
        activeLoadCount.increment();
    }
    
    public void recordActiveLoadEnd() {
        activeLoadCount.decrement();
    }
    
    public void recordBatchLoadStart() {
        batchLoadCount.increment();
    }
    
    public void recordBatchSize(int size) {
        batchLoadSize.add(size);
    }
    
    public void recordLoadTimeout() {
        loadTimeoutCount.increment();
    }
    
    public void recordLoadCancel() {
        loadCancelCount.increment();
    }

    public void recordBatchLoad(int batchSize) {
        batchLoadCount.increment();
        this.batchLoadSize.add(batchSize);
    }

    @Override
    public void reset() {
        loadSuccessCount.reset();
        loadFailureCount.reset();
        totalLoadTime.reset();
        activeLoadCount.reset();
        batchLoadCount.reset();
        batchLoadSize.reset();
        loadTimeoutCount.reset();
        loadCancelCount.reset();
        maxLoadTime.set(0);
        minLoadTime.set(Long.MAX_VALUE);
    }

    @Override
    public String toString() {
        return String.format(
            "LoadingStats{successCount=%d, failureCount=%d, successRate=%.2f%%, " +
            "averageLoadTime=%.2fns, activeLoads=%d, batchLoads=%d}",
            loadSuccessCount(),
            loadFailureCount(),
            loadSuccessRate() * 100,
            averageLoadTime(),
            activeLoadCount(),
            batchLoadCount()
        );
    }
}