package io.github.cascade.cache.strategy;

import io.github.cascade.cache.api.CacheLoader;
import io.github.cascade.cache.api.CacheStats;
import io.github.cascade.cache.config.CascadeCacheConfiguration;
import io.github.cascade.cache.core.unified.CacheEngine;
import io.github.cascade.cache.exception.CacheExceptionHandler;
import io.github.cascade.cache.metrics.CacheMetrics;
import io.github.cascade.cache.metrics.CacheMetricsCollector;
import io.github.cascade.cache.metrics.DetailedCacheMetrics;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * 多级缓存操作策略
 * 负责处理L1和L2之间的数据流转逻辑
 *
 * @author cascade
 */
@Slf4j
public class MultiTierCacheStrategy<K, V> implements CacheStrategy<K, V> {

    private final String cacheName;
    private final CacheEngine<K, V> l1Engine;
    private final CacheEngine<K, V> l2Engine;
    private final CacheMetricsCollector metricsCollector;
    private final Executor executor;
    private final CacheExceptionHandler exceptionHandler;

    // 配置相关
    @Setter
    private CascadeCacheConfiguration cacheConfiguration;
    @Setter
    private CacheLoader<K, V> cacheLoader;

    public MultiTierCacheStrategy(String cacheName,
                                  CacheEngine<K, V> l1Engine,
                                  CacheEngine<K, V> l2Engine,
                                  CacheMetricsCollector metricsCollector,
                                  Executor executor) {
        this.cacheName = cacheName;
        this.l1Engine = l1Engine;
        this.l2Engine = l2Engine;
        this.metricsCollector = metricsCollector;
        this.executor = executor;
        this.exceptionHandler = CacheExceptionHandler.getInstance();
    }

    // ==================== 核心查询策略 ====================

    /**
     * 从多级缓存获取值 - 高性能优化版本
     */
    public V getFromTiers(K key) {
        long startTime = System.nanoTime();

        try {
            // 1. L1缓存查询
            V value = l1Engine.get(key);
            if (value != null) {
                metricsCollector.recordL1Hit();
                log.debug("Cache L1 hit: key={}", key);
                return value;
            }

            // 2. L2缓存查询
            value = l2Engine.get(key);
            if (value != null) {
                metricsCollector.recordL2Hit();
                log.debug("Cache L2 hit: key={}", key);

                // 智能异步提升到L1
                tryPromoteToL1(key, value);

                return value;
            }

            // 3. CacheLoader回源加载
            value = loadFromSource(key);
            if (value != null) {
                return value;
            }

            // 4. 缓存未命中
            metricsCollector.recordMiss();
            log.debug("Cache miss: key={}", key);
            return null;

        } finally {
            // 性能监控
            long duration = System.nanoTime() - startTime;
            if (duration > 10_000_000) { // 超过10ms记录警告
                log.warn("Cache get operation took {}ms for key: {}", duration / 1_000_000, key);
            }
        }
    }

    /**
     * 批量获取策略
     */
    public Map<K, V> getAllFromTiers(Set<K> keys) {
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
                        metricsCollector.recordSyncFailure();
                    }
                }, executor);
            }
        }

        return result;
    }

    // ==================== 数据提升策略 ====================

    /**
     * 智能提升到L1缓存
     */
    private void tryPromoteToL1(K key, V value) {
        // 防重复提升检查
        if (!metricsCollector.tryStartPromotion(key)) {
            log.debug("Skipping promotion - already in progress: key={}", key);
            return;
        }

        final V finalValue = value;
        CompletableFuture.runAsync(() -> {
                    try {
                        Duration ttl = getL1PromotionTtl();
                        if (ttl != null) {
                            l1Engine.put(key, finalValue, ttl);
                        } else {
                            l1Engine.put(key, finalValue);
                        }

                        metricsCollector.recordPromotionSuccess();
                        log.debug("Async promotion to L1 completed: key={}", key);

                    } catch (Exception e) {
                        metricsCollector.recordPromotionFailure();
                        metricsCollector.recordSyncFailure();
                        log.warn("Async promotion to L1 failed: key={}, error: {}", key, e.getMessage());

                    } finally {
                        // 清理防重复提升标记
                        metricsCollector.finishPromotion(key);
                    }
                }, executor).orTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .exceptionally(throwable -> {
                    metricsCollector.finishPromotion(key);
                    metricsCollector.recordPromotionFailure();
                    log.warn("Async promotion timeout for key: {}", key);
                    return null;
                });
    }

    /**
     * 获取L1提升的TTL配置
     */
    private Duration getL1PromotionTtl() {
        if (cacheConfiguration == null || cacheConfiguration.getL1() == null) {
            return null;
        }

        // 优先使用L1的过期配置
        Duration ttl = cacheConfiguration.getL1().getExpireAfterWrite();
        if (ttl == null) {
            ttl = cacheConfiguration.getL1().getExpireAfterAccess();
        }

        return ttl;
    }

    // ==================== 数据加载策略 ====================

    /**
     * 从数据源加载数据
     */
    private V loadFromSource(K key) {
        // 检查是否应该使用CacheLoader
        if (!shouldLoadFromSource()) {
            return null;
        }

        try {
            log.debug("Loading from source: key={}", key);
            V value = cacheLoader.load(key);

            if (value != null) {
                // 加载成功，存储到缓存
                putToAllTiers(key, value, getDefaultTtl());
                log.debug("Successfully loaded and cached: key={}", key);
                return value;
            }

        } catch (Exception e) {
            // 数据源加载失败属于ERROR级别，这会影响业务数据获取
            exceptionHandler.handleKnownException("cache-loader", e, 
                CacheExceptionHandler.ErrorSeverity.ERROR, null);
        }

        return null;
    }

    /**
     * 判断是否应该从数据源加载
     */
    private boolean shouldLoadFromSource() {
        // 必须有CacheLoader
        if (cacheLoader == null) {
            return false;
        }

        // 必须有配置且刷新未启用（避免重复加载）
        if (cacheConfiguration == null) {
            return true; // 默认允许加载
        }

        return !cacheConfiguration.getRefresh().isEnabled();
    }

    // ==================== 写入策略 ====================

    /**
     * 向所有层级写入数据
     */
    public void putToAllTiers(K key, V value, Duration ttl) {
        metricsCollector.recordSyncStart();

        CompletableFuture<Void> l1Future = CompletableFuture.runAsync(() -> {
            try {
                l1Engine.put(key, value, ttl);
            } catch (Exception e) {
                log.warn("L1 put failed: key={}", key, e);
                metricsCollector.recordSyncFailure();
            }
        }, executor);

        CompletableFuture<Void> l2Future = CompletableFuture.runAsync(() -> {
            try {
                l2Engine.put(key, value, ttl);
            } catch (Exception e) {
                log.warn("L2 put failed: key={}", key, e);
                metricsCollector.recordSyncFailure();
            }
        }, executor);

        // 等待两个操作完成，但不阻塞太久
        try {
            CompletableFuture.allOf(l1Future, l2Future)
                    .orTimeout(5, TimeUnit.SECONDS)
                    .join();
        } catch (Exception e) {
            // 使用新的异常处理策略 - 并行写入超时或失败属于WARN级别，记录日志但不中断业务流程
            metricsCollector.recordSyncFailure();
            exceptionHandler.handleKnownException("parallel-put", e, 
                CacheExceptionHandler.ErrorSeverity.WARN, null);
        } finally {
            metricsCollector.recordSyncEnd();
        }
    }

    /**
     * 批量写入所有层级
     */
    public void putAllToTiers(Map<K, V> map) {
        if (map == null || map.isEmpty()) return;

        metricsCollector.recordSyncStart();

        CompletableFuture<Void> l1Future = CompletableFuture.runAsync(() -> {
            try {
                l1Engine.putAll(map);
            } catch (Exception e) {
                log.warn("L1 putAll failed: size={}", map.size(), e);
                metricsCollector.recordSyncFailure();
            }
        }, executor);

        CompletableFuture<Void> l2Future = CompletableFuture.runAsync(() -> {
            try {
                l2Engine.putAll(map);
            } catch (Exception e) {
                log.warn("L2 putAll failed: size={}", map.size(), e);
                metricsCollector.recordSyncFailure();
            }
        }, executor);

        // 等待两个操作完成
        try {
            CompletableFuture.allOf(l1Future, l2Future)
                    .orTimeout(10, java.util.concurrent.TimeUnit.SECONDS)  // 批量操作允许更长超时
                    .join();
        } catch (Exception e) {
            // 使用新的异常处理策略 - 并行批量写入超时或失败属于WARN级别
            metricsCollector.recordSyncFailure();
            exceptionHandler.handleKnownException("parallel-put-all", e, 
                CacheExceptionHandler.ErrorSeverity.WARN, null);
        } finally {
            metricsCollector.recordSyncEnd();
        }
    }

    // ==================== CacheStrategy接口实现 ====================

    @Override
    public V get(K key) {
        return getFromTiers(key);
    }

    @Override
    public Map<K, V> getAll(Set<K> keys) {
        return getAllFromTiers(keys);
    }

    @Override
    public void put(K key, V value) {
        putToAllTiers(key, value, getDefaultTtl());
    }

    @Override
    public void put(K key, V value, Duration ttl) {
        putToAllTiers(key, value, ttl);
    }

    @Override
    public void putAll(Map<K, V> map) {
        putAllToTiers(map);
    }

    @Override
    public void evict(K key) {
        if (key == null) return;

        l1Engine.evict(key);
        l2Engine.evict(key);
        
        log.debug("Multi-tier cache evict: key={}", key);
    }

    @Override
    public void evictAll(Set<K> keys) {
        if (keys == null || keys.isEmpty()) return;

        l1Engine.evictAll(keys);
        l2Engine.evictAll(keys);
        
        log.debug("Multi-tier cache evictAll: size={}", keys.size());
    }

    @Override
    public void clear() {
        l1Engine.clear();
        l2Engine.clear();
        
        log.debug("Multi-tier cache cleared: {}", cacheName);
    }

    @Override
    public boolean containsKey(K key) {
        if (key == null) return false;
        
        return l1Engine.containsKey(key) || l2Engine.containsKey(key);
    }

    @Override
    public long size() {
        // 以L2为准（多级缓存的总大小）
        return l2Engine.size();
    }

    @Override
    public CacheStats getStats() {
        return new MultiTierStats(l1Engine.getStats(), l2Engine.getStats());
    }

    @Override
    public void cleanUp() {
        l1Engine.cleanUp();
        l2Engine.cleanUp();
    }

    @Override
    public DetailedCacheMetrics getDetailedMetrics() {
        return metricsCollector.getStats();
    }

    @Override
    public CacheMetrics.CacheHealthStatus getHealthStatus() {
        return metricsCollector.getHealthStatus();
    }

    @Override
    public void resetPerformanceStats() {
        metricsCollector.reset();
    }

    @Override
    public boolean isMultiTier() {
        return true; // 多级缓存策略始终为true
    }

    @Override
    public String getStrategyName() {
        return "MultiTierCacheStrategy[" + cacheName + "]";
    }

    // ==================== 辅助方法 ====================

    /**
     * 获取默认TTL
     */
    private Duration getDefaultTtl() {
        if (cacheConfiguration != null && cacheConfiguration.getL2() != null) {
            return cacheConfiguration.getL2().getDefaultTtl();
        }
        return Duration.ofHours(1); // 默认1小时
    }

    // ==================== 内部类 ====================

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
}