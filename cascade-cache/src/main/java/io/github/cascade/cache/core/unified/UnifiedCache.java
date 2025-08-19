package io.github.cascade.cache.core.unified;

import io.github.cascade.cache.api.*;
import io.github.cascade.cache.protection.SimplifiedCacheProtectionManager;
import io.github.cascade.cache.sync.UnifiedCacheSynchronizer;
import io.github.cascade.cache.refresh.CacheRefreshScheduler;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Function;

/**
 * 统一缓存实现
 * 使用组合模式替代继承，支持单级和多级缓存
 *
 * @param <K> 键类型
 * @param <V> 值类型
 * @author cascade
 */
@Slf4j
public class UnifiedCache<K, V> implements Cache<K, V>, AsyncCache<K, V>, TieredCache<K, V> {

    private final String name;
    private final CacheEngine<K, V> l1Engine;
    private final CacheEngine<K, V> l2Engine;
    /**
     * -- GETTER --
     * 检查是否为多级缓存
     */
    @Getter
    private final boolean isMultiTier;
    private final Executor executor;

    // 功能组件
    private CacheLoader<K, V> cacheLoader;
    /**
     * -- SETTER --
     * 设置防护管理器
     */
    @Setter
    private SimplifiedCacheProtectionManager protectionManager;
    /**
     * -- GETTER --
     * 获取同步器
     */
    @Getter
    private UnifiedCacheSynchronizer<K, V> synchronizer;
    /**
     * -- GETTER --
     * 获取刷新调度器
     */
    @Getter
    private CacheRefreshScheduler<K, V> refreshScheduler;

    /**
     * 单级缓存构造器
     */
    public UnifiedCache(String name, CacheEngine<K, V> engine) {
        this(name, engine, null, ForkJoinPool.commonPool());
    }

    /**
     * 多级缓存构造器
     */
    public UnifiedCache(String name, CacheEngine<K, V> l1Engine, CacheEngine<K, V> l2Engine) {
        this(name, l1Engine, l2Engine, ForkJoinPool.commonPool());
    }

    /**
     * 完整构造器
     */
    public UnifiedCache(String name, CacheEngine<K, V> l1Engine, CacheEngine<K, V> l2Engine, Executor executor) {
        this.name = name;
        this.l1Engine = l1Engine;
        this.l2Engine = l2Engine;
        this.isMultiTier = l2Engine != null;
        this.executor = executor;

        log.debug("Created {} cache: {}", isMultiTier ? "multi-tier" : "single-tier", name);
    }

    // ==================== Cache接口实现 ====================

    @Override
    public V get(K key) {
        if (key == null) return null;

        // 防护检查
        if (protectionManager != null) {
            // 简化防护逻辑，暂时跳过
        }

        V value = getFromTiers(key);

        // 后处理
        if (protectionManager != null) {
            // 简化防护逻辑，暂时跳过
        }

        return value;
    }

    @Override
    public Map<K, V> getAll(Set<K> keys) {
        if (keys == null || keys.isEmpty()) return Map.of();

        if (isMultiTier) {
            return getAllFromMultiTier(keys);
        } else {
            return l1Engine.getAll(keys);
        }
    }

    @Override
    public Map<K, V> getAll(Set<K> keys, Function<Set<K>, Map<K, V>> loader) {
        if (keys == null || keys.isEmpty()) return Map.of();

        Map<K, V> result = getAll(keys);
        keys.removeAll(result.keySet());

        if (!keys.isEmpty() && loader != null) {
            Map<K, V> loaded = loader.apply(keys);
            if (loaded != null && !loaded.isEmpty()) {
                putAll(loaded);
                result.putAll(loaded);
            }
        }

        return result;
    }

    @Override
    public void put(K key, V value) {
        putWithTtl(key, value, null);
    }

    public void putWithTtl(K key, V value, Duration ttl) {
        if (key == null || value == null) return;

        // 防护检查
        if (protectionManager != null) {
            // 简化防护逻辑，暂时跳过
        }

        // 写入所有层级
        l1Engine.put(key, value, ttl);
        if (isMultiTier) {
            l2Engine.put(key, value, ttl);
        }

        // 后处理
        if (protectionManager != null) {
            // 简化防护逻辑，暂时跳过
        }

        // 触发分布式同步
        if (synchronizer != null) {
            synchronizer.notifyPut(key, value);
        }

        log.debug("Cache put: key={}, tiers={}", key, isMultiTier ? "L1+L2" : "L1");
    }

    @Override
    public void putAll(Map<K, V> map) {
        if (map == null || map.isEmpty()) return;

        l1Engine.putAll(map);
        if (isMultiTier) {
            l2Engine.putAll(map);
        }

        // 触发分布式同步
        if (synchronizer != null) {
            synchronizer.notifyPutAll(map.keySet());
        }

        log.debug("Cache putAll: size={}, tiers={}", map.size(), isMultiTier ? "L1+L2" : "L1");
    }

    @Override
    public boolean putIfAbsent(K key, V value) {
        if (key == null || value == null) return false;

        V existing = get(key);
        if (existing == null) {
            put(key, value);
            return true;
        }
        return false;
    }

    @Override
    public void evict(K key) {
        if (key == null) return;

        l1Engine.evict(key);
        if (isMultiTier) {
            l2Engine.evict(key);
        }

        // 触发分布式同步
        if (synchronizer != null) {
            synchronizer.notifyEvict(key);
        }

        log.debug("Cache evict: key={}, tiers={}", key, isMultiTier ? "L1+L2" : "L1");
    }

    @Override
    public void evictAll(Set<K> keys) {
        if (keys == null || keys.isEmpty()) return;

        l1Engine.evictAll(keys);
        if (isMultiTier) {
            l2Engine.evictAll(keys);
        }

        // 触发分布式同步
        if (synchronizer != null) {
            synchronizer.notifyEvictAll(keys);
        }

        log.debug("Cache evictAll: size={}, tiers={}", keys.size(), isMultiTier ? "L1+L2" : "L1");
    }

    @Override
    public void clear() {
        l1Engine.clear();
        if (isMultiTier) {
            l2Engine.clear();
        }

        // 触发分布式同步
        if (synchronizer != null) {
            synchronizer.notifyClear();
        }

        log.debug("Cache cleared: {}, tiers={}", name, isMultiTier ? "L1+L2" : "L1");
    }

    @Override
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

    @Override
    public long size() {
        // 以L2为准（如果有），否则使用L1
        return isMultiTier ? l2Engine.size() : l1Engine.size();
    }

    @Override
    public long estimatedSize() {
        return size();
    }

    @Override
    public boolean isEmpty() {
        return size() == 0;
    }

    @Override
    public CacheStats getStats() {
        if (isMultiTier) {
            return new MultiTierStats(l1Engine.getStats(), l2Engine.getStats());
        } else {
            return l1Engine.getStats();
        }
    }

    @Override
    public void cleanUp() {
        l1Engine.cleanUp();
        if (isMultiTier) {
            l2Engine.cleanUp();
        }
    }

    @Override
    public CompletableFuture<Void> cleanUpAsync() {
        return CompletableFuture.runAsync(this::cleanUp, executor);
    }

    @Override
    public CompletableFuture<CacheStats> getStatsAsync() {
        return CompletableFuture.supplyAsync(this::getStats, executor);
    }

    @Override
    public Cache<K, V> synchronous() {
        return this;
    }

    // ==================== 扩展功能方法 ====================

    /**
     * 获取缓存值或使用loader加载
     */
    public V getOrLoad(K key) {
        V value = get(key);
        if (value == null && cacheLoader != null) {
            value = loadValue(key);
        }
        return value;
    }

    @Override
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
     * 加载单个值（私有方法）
     */
    private V loadValue(K key) {
        if (cacheLoader == null) {
            return null;
        }

        try {
            V value = cacheLoader.load(key);
            if (value != null) {
                put(key, value);
            }
            return value;
        } catch (Exception e) {
            log.error("Failed to load key: {}", key, e);
            return null;
        }
    }


    /**
     * 刷新缓存值
     */
    public void refresh(K key) {
        if (cacheLoader == null) {
            log.warn("Cannot refresh key {}: no CacheLoader configured", key);
            return;
        }

        // 异步刷新
        CompletableFuture.supplyAsync(() -> {
            try {
                return cacheLoader.load(key);
            } catch (Exception e) {
                log.error("Failed to refresh key: {}", key, e);
                return null;
            }
        }, executor).thenAccept(value -> {
            if (value != null) {
                put(key, value);
                // 触发分布式同步 (put方法已经包含同步逻辑)
                // 但对于refresh操作，我们需要特别通知
                if (synchronizer != null) {
                    synchronizer.notifyRefresh(key);
                }
            }
        });
    }

    /**
     * 异步刷新缓存值
     */
    @Override
    public CompletableFuture<Void> refreshAsync(K key) {
        if (cacheLoader == null) {
            log.warn("Cannot refresh key {}: no CacheLoader configured", key);
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                return cacheLoader.load(key);
            } catch (Exception e) {
                log.error("Failed to refresh key: {}", key, e);
                return null;
            }
        }, executor).thenAccept(value -> {
            if (value != null) {
                put(key, value);
                // 触发分布式同步 (put方法已经包含同步逻辑)
                // 但对于refresh操作，我们需要特别通知
                if (synchronizer != null) {
                    synchronizer.notifyRefresh(key);
                }
            }
        });
    }

    @Override
    public CompletableFuture<V> refreshAsync(K key, Function<K, CompletableFuture<V>> loader) {
        if (loader == null) {
            // 返回通过CacheLoader加载的值
            if (cacheLoader == null) {
                log.warn("Cannot refresh key {}: no CacheLoader configured", key);
                return CompletableFuture.completedFuture(null);
            }

            return CompletableFuture.supplyAsync(() -> {
                try {
                    return cacheLoader.load(key);
                } catch (Exception e) {
                    log.error("Failed to refresh key: {}", key, e);
                    return null;
                }
            }, executor).thenApply(value -> {
                if (value != null) {
                    put(key, value);
                }
                return value;
            });
        }

        return loader.apply(key).thenApply(value -> {
            if (value != null) {
                put(key, value);
            }
            return value;
        });
    }

    // ==================== AsyncCache接口实现 ====================

    @Override
    public CompletableFuture<V> getAsync(K key) {
        return CompletableFuture.supplyAsync(() -> get(key), executor);
    }

    @Override
    public CompletableFuture<V> getAsync(K key, Function<K, CompletableFuture<V>> loader) {
        V value = get(key);
        if (value != null) {
            return CompletableFuture.completedFuture(value);
        }
        return loader.apply(key).thenApply(v -> {
            if (v != null) {
                put(key, v);
            }
            return v;
        });
    }

    @Override
    public CompletableFuture<Map<K, V>> getAllAsync(Set<K> keys) {
        return CompletableFuture.supplyAsync(() -> getAll(keys), executor);
    }

    @Override
    public CompletableFuture<Map<K, V>> getAllAsync(Set<K> keys, Function<Set<K>, CompletableFuture<Map<K, V>>> loader) {
        Map<K, V> result = getAll(keys);
        keys.removeAll(result.keySet());
        if (keys.isEmpty()) {
            return CompletableFuture.completedFuture(result);
        }
        return loader.apply(keys).thenApply(loaded -> {
            if (loaded != null && !loaded.isEmpty()) {
                putAll(loaded);
                result.putAll(loaded);
            }
            return result;
        });
    }

    @Override
    public CompletableFuture<Void> putAsync(K key, V value) {
        return CompletableFuture.runAsync(() -> put(key, value), executor);
    }

    @Override
    public CompletableFuture<Void> putAsync(K key, V value, Duration ttl) {
        return CompletableFuture.runAsync(() -> putWithTtl(key, value, ttl), executor);
    }

    @Override
    public CompletableFuture<Void> putAllAsync(Map<K, V> map) {
        return CompletableFuture.runAsync(() -> putAll(map), executor);
    }

    @Override
    public CompletableFuture<Boolean> putIfAbsentAsync(K key, V value) {
        return CompletableFuture.supplyAsync(() -> putIfAbsent(key, value), executor);
    }

    @Override
    public CompletableFuture<Void> evictAsync(K key) {
        return CompletableFuture.runAsync(() -> evict(key), executor);
    }

    @Override
    public CompletableFuture<Void> evictAllAsync(Set<K> keys) {
        return CompletableFuture.runAsync(() -> evictAll(keys), executor);
    }

    @Override
    public CompletableFuture<Void> clearAsync() {
        return CompletableFuture.runAsync(this::clear, executor);
    }

    @Override
    public CompletableFuture<Boolean> containsKeyAsync(K key) {
        return CompletableFuture.supplyAsync(() -> containsKey(key), executor);
    }

    @Override
    public CompletableFuture<Long> sizeAsync() {
        return CompletableFuture.supplyAsync(this::size, executor);
    }

    // ==================== TieredCache接口实现 ====================

    @Override
    public V get(K key, CacheTier tier) {
        if (key == null) return null;

        return switch (tier) {
            case L1 -> l1Engine.get(key);
            case L2 -> isMultiTier ? l2Engine.get(key) : null;
            default -> throw new IllegalArgumentException("Unsupported tier: " + tier);
        };
    }

    @Override
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

    @Override
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

    @Override
    public CacheStats getStats(CacheTier tier) {
        return switch (tier) {
            case L1 -> l1Engine.getStats();
            case L2 -> isMultiTier ? l2Engine.getStats() : null;
            default -> throw new IllegalArgumentException("Unsupported tier: " + tier);
        };
    }

    @Override
    public void clear(CacheTier tier) {
        switch (tier) {
            case L1 -> l1Engine.clear();
            case L2 -> {
                if (isMultiTier) {
                    l2Engine.clear();
                }
            }
            default -> throw new IllegalArgumentException("Unsupported tier: " + tier);
        }
    }

    @Override
    public boolean containsKey(K key, CacheTier tier) {
        if (key == null) return false;

        return switch (tier) {
            case L1 -> l1Engine.containsKey(key);
            case L2 -> isMultiTier && l2Engine.containsKey(key);
            default -> throw new IllegalArgumentException("Unsupported tier: " + tier);
        };
    }

    @Override
    public long size(CacheTier tier) {
        return switch (tier) {
            case L1 -> l1Engine.size();
            case L2 -> isMultiTier ? l2Engine.size() : 0;
            default -> throw new IllegalArgumentException("Unsupported tier: " + tier);
        };
    }

    @Override
    public void promote(K key) {
        if (!isMultiTier || key == null) return;

        V value = l2Engine.get(key);
        if (value != null) {
            l1Engine.put(key, value);
        }
    }

    @Override
    public void promoteAll(Set<K> keys) {
        if (!isMultiTier || keys == null || keys.isEmpty()) return;

        Map<K, V> values = l2Engine.getAll(keys);
        if (!values.isEmpty()) {
            l1Engine.putAll(values);
        }
    }

    @Override
    public void demote(K key) {
        if (!isMultiTier || key == null) return;

        V value = l1Engine.get(key);
        if (value != null) {
            l2Engine.put(key, value);
            l1Engine.evict(key);
        }
    }

    @Override
    public void demoteAll(Set<K> keys) {
        if (!isMultiTier || keys == null || keys.isEmpty()) return;

        Map<K, V> values = l1Engine.getAll(keys);
        if (!values.isEmpty()) {
            l2Engine.putAll(values);
            l1Engine.evictAll(keys);
        }
    }

    @Override
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

    @Override
    public void syncAll(Set<K> keys) {
        if (!isMultiTier || keys == null || keys.isEmpty()) return;

        keys.forEach(this::sync);
    }

    @Override
    public Map<CacheTier, CacheStats> getAllStats() {
        Map<CacheTier, CacheStats> stats = new HashMap<>();
        stats.put(CacheTier.L1, l1Engine.getStats());
        if (isMultiTier) {
            stats.put(CacheTier.L2, l2Engine.getStats());
        }
        return stats;
    }

    @Override
    public Set<CacheTier> getSupportedTiers() {
        return isMultiTier ? Set.of(CacheTier.L1, CacheTier.L2) : Set.of(CacheTier.L1);
    }

    @Override
    public boolean isTierAvailable(CacheTier tier) {
        return switch (tier) {
            case L1 -> true;
            case L2 -> isMultiTier;
            default -> false;
        };
    }

    // ==================== 配置方法 ====================

    /**
     * 获取缓存加载器
     */
    @Override
    public CacheLoader<K, V> getLoader() {
        return cacheLoader;
    }

    /**
     * 设置缓存加载器
     */
    @Override
    public void setLoader(CacheLoader<K, V> loader) {
        this.cacheLoader = loader;
    }

    /**
     * 设置同步器
     */
    public void setSynchronizer(UnifiedCacheSynchronizer<K, V> synchronizer) {
        this.synchronizer = synchronizer;
        if (synchronizer != null) {
            synchronizer.initialize(this);
            if (!synchronizer.isRunning()) {
                synchronizer.start();
            }
        }
    }

    /**
     * 设置刷新调度器
     */
    public void setRefreshScheduler(CacheRefreshScheduler<K, V> refreshScheduler) {
        // 关闭旧的调度器
        if (this.refreshScheduler != null) {
            this.refreshScheduler.shutdown();
        }
        
        this.refreshScheduler = refreshScheduler;
        log.info("Cache refresh scheduler configured for cache: {}", name);
    }

    /**
     * 启用键的定时刷新
     */
    public void enableAutoRefresh(K key) {
        if (refreshScheduler == null) {
            log.warn("Cannot enable auto refresh for key {}: no refresh scheduler configured", key);
            return;
        }
        
        if (cacheLoader == null) {
            log.warn("Cannot enable auto refresh for key {}: no CacheLoader configured", key);
            return;
        }
        
        refreshScheduler.scheduleRefresh(key);
        log.debug("Auto refresh enabled for key: {}", key);
    }

    /**
     * 批量启用键的定时刷新
     */
    public void enableAutoRefreshAll(Set<K> keys) {
        if (keys == null || keys.isEmpty()) return;
        
        keys.forEach(this::enableAutoRefresh);
        log.info("Auto refresh enabled for {} keys in cache: {}", keys.size(), name);
    }

    /**
     * 禁用键的定时刷新
     */
    public void disableAutoRefresh(K key) {
        if (refreshScheduler == null) return;
        
        refreshScheduler.cancelRefresh(key);
        log.debug("Auto refresh disabled for key: {}", key);
    }

    /**
     * 获取刷新统计信息
     */
    public CacheRefreshScheduler.RefreshStats getRefreshStats() {
        if (refreshScheduler == null) {
            return new CacheRefreshScheduler.RefreshStats(0, 0, Duration.ZERO);
        }
        return refreshScheduler.getStats();
    }

    // ==================== 私有方法 ====================

    /**
     * 从多级缓存获取值
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
                // 提升到L1
                l1Engine.put(key, value);
                return value;
            }
        }

        // 3. 使用CacheLoader加载（如果有）
        if (cacheLoader != null) {
            try {
                value = cacheLoader.load(key);
                if (value != null) {
                    log.debug("Cache loaded: key={}", key);
                    put(key, value);
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
     * 多级缓存批量获取
     */
    private Map<K, V> getAllFromMultiTier(Set<K> keys) {
        Map<K, V> result = new HashMap<>(keys.size());

        // 1. 从L1获取
        Map<K, V> l1Results = l1Engine.getAll(keys);
        result.putAll(l1Results);

        // 2. 从L2获取剩余的
        keys.removeAll(l1Results.keySet());
        if (!keys.isEmpty()) {
            Map<K, V> l2Results = l2Engine.getAll(keys);
            result.putAll(l2Results);

            // 提升到L1
            if (!l2Results.isEmpty()) {
                l1Engine.putAll(l2Results);
            }
        }

        return result;
    }

    // ==================== 生命周期方法 ====================

    /**
     * 关闭缓存
     */
    public void close() {
        // 关闭刷新调度器
        if (refreshScheduler != null) {
            refreshScheduler.shutdown();
            log.debug("Cache refresh scheduler stopped for: {}", name);
        }
        
        // 关闭同步器
        if (synchronizer != null) {
            synchronizer.stop();
            log.debug("Cache synchronizer stopped for: {}", name);
        }
        
        // 关闭缓存引擎
        l1Engine.close();
        if (isMultiTier) {
            l2Engine.close();
        }
        
        log.info("Cache closed: {}", name);
    }

    /**
     * 获取缓存层级
     */
    @Override
    public CacheTier getTier() {
        return isMultiTier ? CacheTier.L2 : CacheTier.L1;
    }

    /**
     * 获取缓存名称
     */
    @Override
    public String getName() {
        return name;
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
}