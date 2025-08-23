package io.github.cascade.cache.core.unified;

import io.github.cascade.cache.api.CacheLoader;
import io.github.cascade.cache.api.CacheStats;
import io.github.cascade.cache.api.CacheTier;
import io.github.cascade.cache.config.CascadeCacheConfiguration;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * 缓存核心逻辑
 * 负责基础的缓存操作，包括单级和多级缓存的读写逻辑
 *
 * @param <K> 键类型
 * @param <V> 值类型
 * @author cascade
 */
@Slf4j
public class CacheCore<K, V> {

    @Getter
    private final String name;
    @Getter
    private final CacheEngine<K, V> l1Engine;
    @Getter
    private final CacheEngine<K, V> l2Engine;
    @Getter
    private final boolean isMultiTier;
    private final Executor executor;
    
    // 并行同步统计
    private final AtomicLong parallelSyncCount = new AtomicLong(0);
    private final AtomicLong syncFailureCount = new AtomicLong(0);
    private final AtomicInteger activeSyncTasks = new AtomicInteger(0);

    @Getter
    @Setter
    private CacheLoader<K, V> cacheLoader;
    @Getter
    @Setter
    private CascadeCacheConfiguration cacheConfiguration;

    public CacheCore(String name, CacheEngine<K, V> l1Engine, CacheEngine<K, V> l2Engine, Executor executor) {
        this.name = name;
        this.l1Engine = l1Engine;
        this.l2Engine = l2Engine;
        this.isMultiTier = l2Engine != null;
        this.executor = executor;
    }

    // ==================== 基础缓存操作 ====================

    /**
     * 获取缓存值
     */
    public V get(K key) {
        if (key == null) return null;
        return getFromTiers(key);
    }

    /**
     * 批量获取缓存值
     */
    public Map<K, V> getAll(Set<K> keys) {
        if (keys == null || keys.isEmpty()) return Map.of();

        if (isMultiTier) {
            return getAllFromMultiTier(keys);
        } else {
            return l1Engine.getAll(keys);
        }
    }

    /**
     * 存储缓存值
     */
    public void put(K key, V value) {
        putWithTtl(key, value, getDefaultTtl());
    }

    /**
     * 带TTL存储缓存值 - 并行优化版本
     */
    public void putWithTtl(K key, V value, Duration ttl) {
        if (key == null || value == null) return;

        if (isMultiTier) {
            // 并行写入L1和L2
            parallelSyncCount.incrementAndGet();
            activeSyncTasks.incrementAndGet();
            
            CompletableFuture<Void> l1Future = CompletableFuture.runAsync(() -> {
                try {
                    l1Engine.put(key, value, ttl);
                } catch (Exception e) {
                    log.warn("L1 put failed: key={}", key, e);
                    syncFailureCount.incrementAndGet();
                }
            }, executor);

            CompletableFuture<Void> l2Future = CompletableFuture.runAsync(() -> {
                try {
                    l2Engine.put(key, value, ttl);
                } catch (Exception e) {
                    log.warn("L2 put failed: key={}", key, e);
                    syncFailureCount.incrementAndGet();
                }
            }, executor);

            // 等待两个操作完成，但不阻塞太久
            try {
                CompletableFuture.allOf(l1Future, l2Future)
                    .orTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                    .join();
            } catch (Exception e) {
                log.warn("Parallel put timeout or failed: key={}", key, e);
                syncFailureCount.incrementAndGet();
            } finally {
                activeSyncTasks.decrementAndGet();
            }
        } else {
            // 单级缓存直接写入
            l1Engine.put(key, value, ttl);
        }

        log.debug("Cache put: key={}, tiers={}, mode={}", key, 
                isMultiTier ? "L1+L2" : "L1", 
                isMultiTier ? "parallel" : "direct");
    }

    /**
     * 批量存储缓存值 - 并行优化版本
     */
    public void putAll(Map<K, V> map) {
        if (map == null || map.isEmpty()) return;

        if (isMultiTier) {
            // 并行批量写入L1和L2
            parallelSyncCount.incrementAndGet();
            activeSyncTasks.incrementAndGet();
            
            CompletableFuture<Void> l1Future = CompletableFuture.runAsync(() -> {
                try {
                    l1Engine.putAll(map);
                } catch (Exception e) {
                    log.warn("L1 putAll failed: size={}", map.size(), e);
                    syncFailureCount.incrementAndGet();
                }
            }, executor);

            CompletableFuture<Void> l2Future = CompletableFuture.runAsync(() -> {
                try {
                    l2Engine.putAll(map);
                } catch (Exception e) {
                    log.warn("L2 putAll failed: size={}", map.size(), e);
                    syncFailureCount.incrementAndGet();
                }
            }, executor);

            // 等待两个操作完成
            try {
                CompletableFuture.allOf(l1Future, l2Future)
                    .orTimeout(10, java.util.concurrent.TimeUnit.SECONDS)  // 批量操作允许更长超时
                    .join();
            } catch (Exception e) {
                log.warn("Parallel putAll timeout or failed: size={}", map.size(), e);
                syncFailureCount.incrementAndGet();
            } finally {
                activeSyncTasks.decrementAndGet();
            }
        } else {
            // 单级缓存直接写入
            l1Engine.putAll(map);
        }

        log.debug("Cache putAll: size={}, tiers={}, mode={}", map.size(), 
                isMultiTier ? "L1+L2" : "L1",
                isMultiTier ? "parallel" : "direct");
    }

    /**
     * 如果不存在则存储
     */
    public boolean putIfAbsent(K key, V value) {
        if (key == null || value == null) return false;

        V existing = get(key);
        if (existing == null) {
            put(key, value);
            return true;
        }
        return false;
    }

    /**
     * 删除缓存项
     */
    public void evict(K key) {
        if (key == null) return;

        l1Engine.evict(key);
        if (isMultiTier) {
            l2Engine.evict(key);
        }

        log.debug("Cache evict: key={}, tiers={}", key, isMultiTier ? "L1+L2" : "L1");
    }

    /**
     * 批量删除缓存项
     */
    public void evictAll(Set<K> keys) {
        if (keys == null || keys.isEmpty()) return;

        l1Engine.evictAll(keys);
        if (isMultiTier) {
            l2Engine.evictAll(keys);
        }

        log.debug("Cache evictAll: size={}, tiers={}", keys.size(), isMultiTier ? "L1+L2" : "L1");
    }

    /**
     * 清空所有缓存
     */
    public void clear() {
        l1Engine.clear();
        if (isMultiTier) {
            l2Engine.clear();
        }

        log.debug("Cache cleared: {}, tiers={}", name, isMultiTier ? "L1+L2" : "L1");
    }

    /**
     * 检查是否包含键
     */
    public boolean containsKey(K key) {
        if (key == null) return false;

        // 检查L1
        if (l1Engine.containsKey(key)) {
            return true;
        }

        // 检查L2
        if (isMultiTier && l2Engine.containsKey(key)) {
            return true;
        }

        return false;
    }

    /**
     * 获取缓存大小
     */
    public long size() {
        // 以L2为准（如果有），否则使用L1
        return isMultiTier ? l2Engine.size() : l1Engine.size();
    }

    /**
     * 获取统计信息
     */
    public CacheStats getStats() {
        if (isMultiTier) {
            return new MultiTierStats(l1Engine.getStats(), l2Engine.getStats());
        } else {
            return l1Engine.getStats();
        }
    }

    /**
     * 清理缓存
     */
    public void cleanUp() {
        l1Engine.cleanUp();
        if (isMultiTier) {
            l2Engine.cleanUp();
        }
    }

    // ==================== 分层缓存操作 ====================

    /**
     * 从指定层级获取值
     */
    public V get(K key, CacheTier tier) {
        if (key == null) return null;

        return switch (tier) {
            case L1 -> l1Engine.get(key);
            case L2 -> isMultiTier ? l2Engine.get(key) : null;
            default -> throw new IllegalArgumentException("Unsupported tier: " + tier);
        };
    }

    /**
     * 向指定层级存储值
     */
    public void put(K key, V value, CacheTier tier) {
        if (key == null || value == null) return;

        switch (tier) {
            case L1 -> l1Engine.put(key, value);
            case L2 -> {
                if (isMultiTier) {
                    l2Engine.put(key, value);
                }
            }
            default -> throw new IllegalArgumentException("Unsupported tier: " + tier);
        }
    }

    /**
     * 从指定层级删除值
     */
    public void evict(K key, CacheTier tier) {
        if (key == null) return;

        switch (tier) {
            case L1 -> l1Engine.evict(key);
            case L2 -> {
                if (isMultiTier) {
                    l2Engine.evict(key);
                }
            }
            default -> throw new IllegalArgumentException("Unsupported tier: " + tier);
        }
    }

    /**
     * 将数据从L2向上移动到L1
     */
    public void moveUp(K key) {
        if (!isMultiTier || key == null) return;

        V value = l2Engine.get(key);
        if (value != null) {
            l1Engine.put(key, value);
        }
    }

    /**
     * 批量向上移动数据
     */
    public void moveUpAll(Set<K> keys) {
        if (!isMultiTier || keys == null || keys.isEmpty()) return;

        Map<K, V> values = l2Engine.getAll(keys);
        if (!values.isEmpty()) {
            l1Engine.putAll(values);
        }
    }

    /**
     * 将数据从L1向下移动到L2
     */
    public void moveDown(K key) {
        if (!isMultiTier || key == null) return;

        V value = l1Engine.get(key);
        if (value != null) {
            l2Engine.put(key, value);
            l1Engine.evict(key);
        }
    }

    /**
     * 批量向下移动数据
     */
    public void moveDownAll(Set<K> keys) {
        if (!isMultiTier || keys == null || keys.isEmpty()) return;

        Map<K, V> values = l1Engine.getAll(keys);
        if (!values.isEmpty()) {
            l2Engine.putAll(values);
            l1Engine.evictAll(keys);
        }
    }

    /**
     * 同步层级间数据
     */
    public void sync(K key) {
        if (!isMultiTier || key == null) return;

        V l1Value = l1Engine.get(key);
        V l2Value = l2Engine.get(key);

        if (l1Value != null && l2Value == null) {
            l2Engine.put(key, l1Value);
        } else if (l2Value != null && l1Value == null) {
            l1Engine.put(key, l2Value);
        }
    }

    // ==================== CacheLoader支持 ====================

    /**
     * 获取或加载缓存值
     */
    public V getOrLoad(K key) {
        V value = get(key);
        if (value == null && cacheLoader != null) {
            value = loadValue(key);
        }
        return value;
    }

    /**
     * 带映射函数的获取
     */
    public V get(K key, Function<K, V> mappingFunction) {
        V value = get(key);
        if (value == null && mappingFunction != null) {
            value = mappingFunction.apply(key);
            if (value != null) {
                put(key, value);
            }
        }
        return value;
    }

    /**
     * 加载单个值
     */
    private V loadValue(K key) {
        if (cacheLoader == null) {
            return null;
        }

        try {
            V value = cacheLoader.load(key);
            if (value != null) {
                putWithTtl(key, value, getDefaultTtl());
            }
            return value;
        } catch (Exception e) {
            log.error("Failed to load key: {}", key, e);
            return null;
        }
    }

    // ==================== 私有方法 ====================

    /**
     * 从多级缓存获取值 - 并行提升优化版本
     */
    private V getFromTiers(K key) {
        // 1. 先从L1获取
        V value = l1Engine.get(key);
        if (value != null) {
            log.debug("Cache L1 hit: key={}", key);
            return value;
        }

        // 2. 从L2获取（如果有）
        if (isMultiTier) {
            value = l2Engine.get(key);
            if (value != null) {
                log.debug("Cache L2 hit: key={}", key);
                
                // 异步提升到L1（不阻塞返回）
                final V finalValue = value;
                CompletableFuture.runAsync(() -> {
                    try {
                        if (cacheConfiguration != null) {
                            l1Engine.put(key, finalValue, cacheConfiguration.getL1().getExpireAfterWrite());
                        } else {
                            l1Engine.put(key, finalValue);
                        }
                        log.debug("Async promotion to L1 completed: key={}", key);
                    } catch (Exception e) {
                        log.warn("Async promotion to L1 failed: key={}", key, e);
                        syncFailureCount.incrementAndGet();
                    }
                }, executor);
                
                return value;
            }
        }

        // 3. 使用CacheLoader加载（如果有）,并且刷新未启用，因为上层高级API 已经处理了刷新逻辑
        if (cacheLoader != null && !cacheConfiguration.getRefresh().isEnabled()) {
            try {
                value = cacheLoader.load(key);
                if (value != null) {
                    log.debug("Cache loaded: key={}", key);
                    putWithTtl(key, value, getDefaultTtl());
                    return value;
                }
            } catch (Exception e) {
                log.error("Failed to load key: {}", key, e);
            }
        }

        log.debug("Cache miss: key={}", key);
        return null;
    }

    /**
     * 多级缓存批量获取 - 并行提升优化版本
     */
    private Map<K, V> getAllFromMultiTier(Set<K> keys) {
        Map<K, V> result = new HashMap<>(keys.size());
        Set<K> remainingKeys = new HashSet<>(keys); // 创建副本，避免修改原始集合
        
        // 1. 从L1获取
        Map<K, V> l1Results = l1Engine.getAll(remainingKeys);
        result.putAll(l1Results);

        // 2. 从L2获取剩余的
        remainingKeys.removeAll(l1Results.keySet());
        if (!remainingKeys.isEmpty()) {
            Map<K, V> l2Results = l2Engine.getAll(remainingKeys);
            result.putAll(l2Results);

            // 异步批量提升到L1（不阻塞返回）
            if (!l2Results.isEmpty()) {
                CompletableFuture.runAsync(() -> {
                    try {
                        l1Engine.putAll(l2Results);
                        log.debug("Async batch promotion to L1 completed: size={}", l2Results.size());
                    } catch (Exception e) {
                        log.warn("Async batch promotion to L1 failed: size={}", l2Results.size(), e);
                        syncFailureCount.incrementAndGet();
                    }
                }, executor);
            }
        }

        return result;
    }

    /**
     * 获取默认TTL
     */
    private Duration getDefaultTtl() {
        if (cacheConfiguration != null) {
            return cacheConfiguration.getL2().getDefaultTtl();
        }
        return Duration.ofHours(1); // 默认1小时
    }

    // ==================== 性能监控和统计 ====================

    /**
     * 获取并行同步统计信息
     */
    public ParallelSyncStats getParallelSyncStats() {
        return new ParallelSyncStats(
                parallelSyncCount.get(),
                syncFailureCount.get(),
                activeSyncTasks.get(),
                isMultiTier
        );
    }

    /**
     * 重置并行同步统计
     */
    public void resetParallelSyncStats() {
        parallelSyncCount.set(0);
        syncFailureCount.set(0);
        // 不重置activeSyncTasks，因为它表示当前活跃任务数
    }

    /**
     * 获取综合性能指标
     */
    public ComprehensiveMetrics getComprehensiveMetrics() {
        CacheStats stats = getStats();
        ParallelSyncStats syncStats = getParallelSyncStats();
        
        return new ComprehensiveMetrics(
                name,
                isMultiTier,
                stats,
                syncStats,
                System.currentTimeMillis()
        );
    }

    // ==================== Getter/Setter ====================

    public void close() {
        l1Engine.close();
        if (isMultiTier) {
            l2Engine.close();
        }
        log.debug("CacheCore closed: {}", name);
    }

    /**
     * 多级缓存统计信息
     */
    private static class MultiTierStats implements CacheStats {
        private final CacheStats l1Stats;
        private final CacheStats l2Stats;

        public MultiTierStats(CacheStats l1Stats, CacheStats l2Stats) {
            this.l1Stats = l1Stats;
            this.l2Stats = l2Stats;
        }

        @Override
        public long hitCount() {
            return l1Stats.hitCount() + l2Stats.hitCount();
        }

        @Override
        public long missCount() {
            return l1Stats.missCount() + l2Stats.missCount();
        }

        @Override
        public double hitRate() {
            long hits = hitCount();
            long total = hits + missCount();
            return total == 0 ? 0.0 : (double) hits / total;
        }

        @Override
        public double missRate() {
            return 1.0 - hitRate();
        }

        @Override
        public long loadCount() {
            return l1Stats.loadCount() + l2Stats.loadCount();
        }

        @Override
        public double averageLoadPenalty() {
            return (l1Stats.averageLoadPenalty() + l2Stats.averageLoadPenalty()) / 2;
        }

        @Override
        public long evictionCount() {
            return l1Stats.evictionCount() + l2Stats.evictionCount();
        }

        @Override
        public long evictionWeight() {
            return l1Stats.evictionWeight() + l2Stats.evictionWeight();
        }

        @Override
        public long requestCount() {
            return l1Stats.requestCount() + l2Stats.requestCount();
        }

        @Override
        public long loadExceptionCount() {
            return l1Stats.loadExceptionCount() + l2Stats.loadExceptionCount();
        }

        @Override
        public long totalLoadTime() {
            return l1Stats.totalLoadTime() + l2Stats.totalLoadTime();
        }

        @Override
        public void reset() {
            l1Stats.reset();
            l2Stats.reset();
        }
    }

    // ==================== 监控数据类 ====================

    /**
     * 并行同步统计信息
     */
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

        public long getTotalSyncCount() { return totalSyncCount; }
        public long getFailureCount() { return failureCount; }
        public int getActiveTasks() { return activeTasks; }
        public boolean isMultiTier() { return multiTier; }
        public long getTimestamp() { return timestamp; }

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
     * 综合性能指标
     */
    public static class ComprehensiveMetrics {
        private final String cacheName;
        private final boolean multiTier;
        private final CacheStats cacheStats;
        private final ParallelSyncStats syncStats;
        private final long timestamp;

        public ComprehensiveMetrics(String cacheName, boolean multiTier, CacheStats cacheStats, 
                                   ParallelSyncStats syncStats, long timestamp) {
            this.cacheName = cacheName;
            this.multiTier = multiTier;
            this.cacheStats = cacheStats;
            this.syncStats = syncStats;
            this.timestamp = timestamp;
        }

        public String getCacheName() { return cacheName; }
        public boolean isMultiTier() { return multiTier; }
        public CacheStats getCacheStats() { return cacheStats; }
        public ParallelSyncStats getSyncStats() { return syncStats; }
        public long getTimestamp() { return timestamp; }

        /**
         * 获取缓存健康度评分 (0-100)
         */
        public int getHealthScore() {
            double hitRate = cacheStats.hitRate();
            double syncSuccessRate = syncStats.getSuccessRate();
            
            // 命中率权重70%，同步成功率权重30%
            return (int) ((hitRate * 70 + syncSuccessRate * 30));
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

        public String getDescription() { return description; }
        public int getMinScore() { return minScore; }
        public int getMaxScore() { return maxScore; }
    }
}