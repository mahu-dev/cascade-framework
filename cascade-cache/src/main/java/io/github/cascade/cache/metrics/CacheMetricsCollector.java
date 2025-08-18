package io.github.cascade.cache.metrics;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * 缓存指标收集器
 * 收集和统计缓存的各种性能指标
 *
 * @author Cascade Framework
 */
public class CacheMetricsCollector {

    private final String cacheName;
    private final Set<MetricsListener> listeners;
    private final ReadWriteLock lock;
    
    // 基础计数器
    private final LongAdder hitCount;
    private final LongAdder missCount;
    private final LongAdder loadCount;
    private final LongAdder loadSuccessCount;
    private final LongAdder loadExceptionCount;
    private final LongAdder evictionCount;
    private final LongAdder putCount;
    private final LongAdder removeCount;
    
    // 时间统计
    private final AtomicLong totalLoadTime;
    private final AtomicLong maxLoadTime;
    private final AtomicLong minLoadTime;
    
    // 大小统计
    private final AtomicLong currentSize;
    private final AtomicLong maxSize;
    
    // 分层统计（本地缓存和远程缓存）
    private final Map<String, TierMetrics> tierMetrics;
    
    // 时间窗口统计
    private final TimeWindowMetrics timeWindowMetrics;
    
    // 启动时间
    private final Instant startTime;

    public CacheMetricsCollector(String cacheName) {
        this.cacheName = cacheName;
        this.listeners = new CopyOnWriteArraySet<>();
        this.lock = new ReentrantReadWriteLock();
        
        this.hitCount = new LongAdder();
        this.missCount = new LongAdder();
        this.loadCount = new LongAdder();
        this.loadSuccessCount = new LongAdder();
        this.loadExceptionCount = new LongAdder();
        this.evictionCount = new LongAdder();
        this.putCount = new LongAdder();
        this.removeCount = new LongAdder();
        
        this.totalLoadTime = new AtomicLong(0);
        this.maxLoadTime = new AtomicLong(0);
        this.minLoadTime = new AtomicLong(Long.MAX_VALUE);
        
        this.currentSize = new AtomicLong(0);
        this.maxSize = new AtomicLong(0);
        
        this.tierMetrics = new ConcurrentHashMap<>();
        this.timeWindowMetrics = new TimeWindowMetrics();
        this.startTime = Instant.now();
    }

    /**
     * 记录缓存命中
     */
    public void recordHit() {
        hitCount.increment();
        timeWindowMetrics.recordHit();
        notifyListeners(MetricsEvent.HIT);
    }

    /**
     * 记录缓存未命中
     */
    public void recordMiss() {
        missCount.increment();
        timeWindowMetrics.recordMiss();
        notifyListeners(MetricsEvent.MISS);
    }

    /**
     * 记录数据加载
     *
     * @param loadTime 加载时间（纳秒）
     * @param success 是否成功
     */
    public void recordLoad(long loadTime, boolean success) {
        loadCount.increment();
        if (success) {
            loadSuccessCount.increment();
        } else {
            loadExceptionCount.increment();
        }
        
        // 更新加载时间统计
        totalLoadTime.addAndGet(loadTime);
        updateMaxLoadTime(loadTime);
        updateMinLoadTime(loadTime);
        
        timeWindowMetrics.recordLoad(loadTime, success);
        notifyListeners(success ? MetricsEvent.LOAD_SUCCESS : MetricsEvent.LOAD_EXCEPTION);
    }

    /**
     * 记录缓存放入
     */
    public void recordPut() {
        putCount.increment();
        currentSize.incrementAndGet();
        updateMaxSize();
        timeWindowMetrics.recordPut();
        notifyListeners(MetricsEvent.PUT);
    }

    /**
     * 记录缓存移除
     */
    public void recordRemove() {
        removeCount.increment();
        currentSize.decrementAndGet();
        timeWindowMetrics.recordRemove();
        notifyListeners(MetricsEvent.REMOVE);
    }

    /**
     * 记录缓存驱逐
     */
    public void recordEviction() {
        evictionCount.increment();
        currentSize.decrementAndGet();
        timeWindowMetrics.recordEviction();
        notifyListeners(MetricsEvent.EVICTION);
    }

    /**
     * 记录分层指标
     *
     * @param tierName 分层名称
     * @param event 事件类型
     */
    public void recordTierMetrics(String tierName, MetricsEvent event) {
        tierMetrics.computeIfAbsent(tierName, k -> new TierMetrics()).record(event);
    }

    /**
     * 更新当前大小
     *
     * @param size 当前大小
     */
    public void updateCurrentSize(long size) {
        currentSize.set(size);
        updateMaxSize();
    }

    /**
     * 获取缓存统计信息
     *
     * @return 统计信息
     */
    public CacheStats getStats() {
        lock.readLock().lock();
        try {
            long hits = hitCount.sum();
            long misses = missCount.sum();
            long loads = loadCount.sum();
            long loadSuccesses = loadSuccessCount.sum();
            long loadExceptions = loadExceptionCount.sum();
            long evictions = evictionCount.sum();
            long puts = putCount.sum();
            long removes = removeCount.sum();
            
            long totalLoad = totalLoadTime.get();
            long maxLoad = maxLoadTime.get();
            long minLoad = minLoadTime.get() == Long.MAX_VALUE ? 0 : minLoadTime.get();
            
            long current = currentSize.get();
            long max = maxSize.get();
            
            Duration uptime = Duration.between(startTime, Instant.now());
            
            return new CacheStats(
                cacheName,
                hits, misses, loads, loadSuccesses, loadExceptions,
                evictions, puts, removes,
                totalLoad, maxLoad, minLoad,
                current, max,
                uptime,
                new ConcurrentHashMap<>(tierMetrics),
                timeWindowMetrics.getSnapshot()
            );
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 重置统计信息
     */
    public void reset() {
        lock.writeLock().lock();
        try {
            hitCount.reset();
            missCount.reset();
            loadCount.reset();
            loadSuccessCount.reset();
            loadExceptionCount.reset();
            evictionCount.reset();
            putCount.reset();
            removeCount.reset();
            
            totalLoadTime.set(0);
            maxLoadTime.set(0);
            minLoadTime.set(Long.MAX_VALUE);
            
            currentSize.set(0);
            maxSize.set(0);
            
            tierMetrics.clear();
            timeWindowMetrics.reset();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 添加指标监听器
     *
     * @param listener 监听器
     */
    public void addListener(MetricsListener listener) {
        listeners.add(listener);
    }

    /**
     * 移除指标监听器
     *
     * @param listener 监听器
     */
    public void removeListener(MetricsListener listener) {
        listeners.remove(listener);
    }

    /**
     * 更新最大加载时间
     */
    private void updateMaxLoadTime(long loadTime) {
        long current;
        do {
            current = maxLoadTime.get();
            if (loadTime <= current) {
                break;
            }
        } while (!maxLoadTime.compareAndSet(current, loadTime));
    }

    /**
     * 更新最小加载时间
     */
    private void updateMinLoadTime(long loadTime) {
        long current;
        do {
            current = minLoadTime.get();
            if (loadTime >= current) {
                break;
            }
        } while (!minLoadTime.compareAndSet(current, loadTime));
    }

    /**
     * 更新最大大小
     */
    private void updateMaxSize() {
        long current = currentSize.get();
        long max;
        do {
            max = maxSize.get();
            if (current <= max) {
                break;
            }
        } while (!maxSize.compareAndSet(max, current));
    }

    /**
     * 通知监听器
     */
    private void notifyListeners(MetricsEvent event) {
        for (MetricsListener listener : listeners) {
            try {
                listener.onMetricsEvent(cacheName, event);
            } catch (Exception e) {
                // 记录日志但不影响正常流程
            }
        }
    }

    /**
     * 分层指标
     */
    public static class TierMetrics {
        private final LongAdder hitCount = new LongAdder();
        private final LongAdder missCount = new LongAdder();
        private final LongAdder putCount = new LongAdder();
        private final LongAdder removeCount = new LongAdder();
        private final LongAdder evictionCount = new LongAdder();

        public void record(MetricsEvent event) {
            switch (event) {
                case HIT:
                    hitCount.increment();
                    break;
                case MISS:
                    missCount.increment();
                    break;
                case PUT:
                    putCount.increment();
                    break;
                case REMOVE:
                    removeCount.increment();
                    break;
                case EVICTION:
                    evictionCount.increment();
                    break;
            }
        }

        public long getHitCount() { return hitCount.sum(); }
        public long getMissCount() { return missCount.sum(); }
        public long getPutCount() { return putCount.sum(); }
        public long getRemoveCount() { return removeCount.sum(); }
        public long getEvictionCount() { return evictionCount.sum(); }
        
        public double getHitRate() {
            long hits = getHitCount();
            long total = hits + getMissCount();
            return total > 0 ? (double) hits / total : 0.0;
        }
    }

    /**
     * 时间窗口指标
     */
    public static class TimeWindowMetrics {
        private final Map<String, WindowStats> windows = new ConcurrentHashMap<>();
        private final long[] windowSizes = {60, 300, 900, 3600}; // 1分钟、5分钟、15分钟、1小时

        public TimeWindowMetrics() {
            for (long windowSize : windowSizes) {
                windows.put(windowSize + "s", new WindowStats(windowSize));
            }
        }

        public void recordHit() {
            windows.values().forEach(WindowStats::recordHit);
        }

        public void recordMiss() {
            windows.values().forEach(WindowStats::recordMiss);
        }

        public void recordLoad(long loadTime, boolean success) {
            windows.values().forEach(w -> w.recordLoad(loadTime, success));
        }

        public void recordPut() {
            windows.values().forEach(WindowStats::recordPut);
        }

        public void recordRemove() {
            windows.values().forEach(WindowStats::recordRemove);
        }

        public void recordEviction() {
            windows.values().forEach(WindowStats::recordEviction);
        }

        public void reset() {
            windows.values().forEach(WindowStats::reset);
        }

        public Map<String, WindowStats> getSnapshot() {
            return new ConcurrentHashMap<>(windows);
        }
    }

    /**
     * 窗口统计
     */
    public static class WindowStats {
        private final long windowSizeSeconds;
        private final Map<Long, CounterSnapshot> snapshots = new ConcurrentHashMap<>();
        private final LongAdder hitCount = new LongAdder();
        private final LongAdder missCount = new LongAdder();
        private final LongAdder loadCount = new LongAdder();
        private final LongAdder putCount = new LongAdder();
        private final LongAdder removeCount = new LongAdder();
        private final LongAdder evictionCount = new LongAdder();
        private final AtomicLong totalLoadTime = new AtomicLong();

        public WindowStats(long windowSizeSeconds) {
            this.windowSizeSeconds = windowSizeSeconds;
        }

        public void recordHit() {
            hitCount.increment();
            updateSnapshot();
        }

        public void recordMiss() {
            missCount.increment();
            updateSnapshot();
        }

        public void recordLoad(long loadTime, boolean success) {
            loadCount.increment();
            totalLoadTime.addAndGet(loadTime);
            updateSnapshot();
        }

        public void recordPut() {
            putCount.increment();
            updateSnapshot();
        }

        public void recordRemove() {
            removeCount.increment();
            updateSnapshot();
        }

        public void recordEviction() {
            evictionCount.increment();
            updateSnapshot();
        }

        public void reset() {
            hitCount.reset();
            missCount.reset();
            loadCount.reset();
            putCount.reset();
            removeCount.reset();
            evictionCount.reset();
            totalLoadTime.set(0);
            snapshots.clear();
        }

        private void updateSnapshot() {
            long currentWindow = System.currentTimeMillis() / 1000 / windowSizeSeconds;
            snapshots.put(currentWindow, new CounterSnapshot(
                hitCount.sum(), missCount.sum(), loadCount.sum(),
                putCount.sum(), removeCount.sum(), evictionCount.sum(),
                totalLoadTime.get()
            ));
            
            // 清理过期窗口
            snapshots.entrySet().removeIf(entry -> 
                entry.getKey() < currentWindow - 1);
        }

        public long getHitCount() { return hitCount.sum(); }
        public long getMissCount() { return missCount.sum(); }
        public long getLoadCount() { return loadCount.sum(); }
        public long getPutCount() { return putCount.sum(); }
        public long getRemoveCount() { return removeCount.sum(); }
        public long getEvictionCount() { return evictionCount.sum(); }
        public long getTotalLoadTime() { return totalLoadTime.get(); }
        
        public double getHitRate() {
            long hits = getHitCount();
            long total = hits + getMissCount();
            return total > 0 ? (double) hits / total : 0.0;
        }
        
        public double getAverageLoadTime() {
            long loads = getLoadCount();
            return loads > 0 ? (double) getTotalLoadTime() / loads / 1_000_000 : 0.0; // 转换为毫秒
        }
    }

    /**
     * 计数器快照
     */
    public static class CounterSnapshot {
        private final long hitCount;
        private final long missCount;
        private final long loadCount;
        private final long putCount;
        private final long removeCount;
        private final long evictionCount;
        private final long totalLoadTime;

        public CounterSnapshot(long hitCount, long missCount, long loadCount,
                             long putCount, long removeCount, long evictionCount,
                             long totalLoadTime) {
            this.hitCount = hitCount;
            this.missCount = missCount;
            this.loadCount = loadCount;
            this.putCount = putCount;
            this.removeCount = removeCount;
            this.evictionCount = evictionCount;
            this.totalLoadTime = totalLoadTime;
        }

        public long getHitCount() { return hitCount; }
        public long getMissCount() { return missCount; }
        public long getLoadCount() { return loadCount; }
        public long getPutCount() { return putCount; }
        public long getRemoveCount() { return removeCount; }
        public long getEvictionCount() { return evictionCount; }
        public long getTotalLoadTime() { return totalLoadTime; }
    }

    /**
     * 指标事件
     */
    public enum MetricsEvent {
        HIT, MISS, LOAD_SUCCESS, LOAD_EXCEPTION, PUT, REMOVE, EVICTION
    }

    /**
     * 指标监听器
     */
    public interface MetricsListener {
        void onMetricsEvent(String cacheName, MetricsEvent event);
    }
}