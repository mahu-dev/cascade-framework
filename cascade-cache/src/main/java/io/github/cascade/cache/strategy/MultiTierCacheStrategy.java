package io.github.cascade.cache.strategy;

import io.github.cascade.cache.api.CacheLoader;
import io.github.cascade.cache.config.CascadeCacheConfiguration;
import io.github.cascade.cache.core.unified.CacheEngine;
import io.github.cascade.cache.metrics.CachePerformanceMonitor;
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
public class MultiTierCacheStrategy<K, V> {

    private final String cacheName;
    private final CacheEngine<K, V> l1Engine;
    private final CacheEngine<K, V> l2Engine;
    private final CachePerformanceMonitor performanceMonitor;
    private final Executor executor;

    // 配置相关
    @Setter
    private CascadeCacheConfiguration cacheConfiguration;
    @Setter
    private CacheLoader<K, V> cacheLoader;

    public MultiTierCacheStrategy(String cacheName,
                                  CacheEngine<K, V> l1Engine,
                                  CacheEngine<K, V> l2Engine,
                                  CachePerformanceMonitor performanceMonitor,
                                  Executor executor) {
        this.cacheName = cacheName;
        this.l1Engine = l1Engine;
        this.l2Engine = l2Engine;
        this.performanceMonitor = performanceMonitor;
        this.executor = executor;
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
                performanceMonitor.recordL1Hit();
                log.debug("Cache L1 hit: key={}", key);
                return value;
            }

            // 2. L2缓存查询
            value = l2Engine.get(key);
            if (value != null) {
                performanceMonitor.recordL2Hit();
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
            performanceMonitor.recordMiss();
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
                        performanceMonitor.recordSyncFailure();
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
        if (!performanceMonitor.tryStartPromotion(key)) {
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

                        performanceMonitor.recordPromotionSuccess();
                        log.debug("Async promotion to L1 completed: key={}", key);

                    } catch (Exception e) {
                        performanceMonitor.recordPromotionFailure();
                        performanceMonitor.recordSyncFailure();
                        log.warn("Async promotion to L1 failed: key={}, error: {}", key, e.getMessage());

                    } finally {
                        // 清理防重复提升标记
                        performanceMonitor.finishPromotion(key);
                    }
                }, executor).orTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .exceptionally(throwable -> {
                    performanceMonitor.finishPromotion(key);
                    performanceMonitor.recordPromotionFailure();
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
            log.error("Failed to load from source: key={}, error: {}", key, e.getMessage(), e);
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
        performanceMonitor.recordSyncStart();

        CompletableFuture<Void> l1Future = CompletableFuture.runAsync(() -> {
            try {
                l1Engine.put(key, value, ttl);
            } catch (Exception e) {
                log.warn("L1 put failed: key={}", key, e);
                performanceMonitor.recordSyncFailure();
            }
        }, executor);

        CompletableFuture<Void> l2Future = CompletableFuture.runAsync(() -> {
            try {
                l2Engine.put(key, value, ttl);
            } catch (Exception e) {
                log.warn("L2 put failed: key={}", key, e);
                performanceMonitor.recordSyncFailure();
            }
        }, executor);

        // 等待两个操作完成，但不阻塞太久
        try {
            CompletableFuture.allOf(l1Future, l2Future)
                    .orTimeout(5, TimeUnit.SECONDS)
                    .join();
        } catch (Exception e) {
            log.warn("Parallel put timeout or failed: key={}", key, e);
            performanceMonitor.recordSyncFailure();
        } finally {
            performanceMonitor.recordSyncEnd();
        }
    }

    /**
     * 批量写入所有层级
     */
    public void putAllToTiers(Map<K, V> map) {
        if (map == null || map.isEmpty()) return;

        performanceMonitor.recordSyncStart();

        CompletableFuture<Void> l1Future = CompletableFuture.runAsync(() -> {
            try {
                l1Engine.putAll(map);
            } catch (Exception e) {
                log.warn("L1 putAll failed: size={}", map.size(), e);
                performanceMonitor.recordSyncFailure();
            }
        }, executor);

        CompletableFuture<Void> l2Future = CompletableFuture.runAsync(() -> {
            try {
                l2Engine.putAll(map);
            } catch (Exception e) {
                log.warn("L2 putAll failed: size={}", map.size(), e);
                performanceMonitor.recordSyncFailure();
            }
        }, executor);

        // 等待两个操作完成
        try {
            CompletableFuture.allOf(l1Future, l2Future)
                    .orTimeout(10, java.util.concurrent.TimeUnit.SECONDS)  // 批量操作允许更长超时
                    .join();
        } catch (Exception e) {
            log.warn("Parallel putAll timeout or failed: size={}", map.size(), e);
            performanceMonitor.recordSyncFailure();
        } finally {
            performanceMonitor.recordSyncEnd();
        }
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
}