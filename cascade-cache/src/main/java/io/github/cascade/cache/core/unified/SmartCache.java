package io.github.cascade.cache.core.unified;

import io.github.cascade.api.HealthStatus;
import io.github.cascade.cache.api.*;
import io.github.cascade.cache.config.CascadeCacheConfiguration;
import io.github.cascade.cache.metrics.UnifiedMonitoringManager;
import io.github.cascade.cache.protection.SimplifiedCacheProtectionManager;
import io.github.cascade.cache.refresh.CacheRefreshScheduler;
import io.github.cascade.cache.sync.UnifiedCacheSynchronizer;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Function;

/**
 * 智能缓存实现
 * 采用职责分离设计，将功能分为核心缓存逻辑、增强功能管理、监控统计三个组件
 * 提供智能化的缓存管理能力，包括防护、同步、监控等增强功能
 *
 * @param <K> 键类型
 * @param <V> 值类型
 * @author cascade
 */
@Slf4j
public class SmartCache<K, V> implements Cache<K, V>, AsyncCache<K, V>, TieredCache<K, V> {

    // 职责分离的三个核心组件
    private final CacheCore<K, V> core;           // 核心缓存逻辑
    private final CacheEnhancer<K, V> enhancer;   // 增强功能管理器
    private final CacheMonitor<K, V> monitor;     // 监控统计

    private final String name;
    private final Executor executor;

    // ==================== 构造器 ====================

    /**
     * 单级缓存构造器
     */
    public SmartCache(String name, CacheEngine<K, V> engine) {
        this(name, engine, null, ForkJoinPool.commonPool());
    }

    /**
     * 多级缓存构造器
     */
    public SmartCache(String name, CacheEngine<K, V> l1Engine, CacheEngine<K, V> l2Engine) {
        this(name, l1Engine, l2Engine, ForkJoinPool.commonPool());
    }

    /**
     * 完整构造器
     */
    public SmartCache(String name, CacheEngine<K, V> l1Engine, CacheEngine<K, V> l2Engine, Executor executor) {
        this(name, l1Engine, l2Engine, executor, null);
    }
    
    /**
     * 完整构造器（包含监控管理器）
     */
    public SmartCache(String name, CacheEngine<K, V> l1Engine, CacheEngine<K, V> l2Engine, Executor executor,
                     UnifiedMonitoringManager monitoringManager) {
        this.name = name;
        this.executor = executor;

        // 创建核心组件
        this.core = new CacheCore<>(name, l1Engine, l2Engine, executor);
        this.enhancer = new CacheEnhancer<>(name, executor);
        this.monitor = new CacheMonitor<>(name, core, executor, monitoringManager);

        log.debug("Created {} smart cache: {}", core.isMultiTier() ? "multi-tier" : "single-tier", name);
    }

    // ==================== Cache接口实现 ====================

    @Override
    public V get(K key) {
        if (key == null) return null;
        return enhancer.enhanceGet(() -> {
            V value = core.get(key);
            // 监控记录
            if (value != null) {
                monitor.recordHit(key);
            } else {
                monitor.recordMiss(key);
            }

            return value;
        }, key);
    }

    @Override
    public Map<K, V> getAll(Set<K> keys) {
        if (keys == null || keys.isEmpty()) return Map.of();
        return core.getAll(keys);
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
        if (key == null || value == null) return;

        log.debug("SmartCache.put调用: key={}, value={}", key, value != null ? "非null" : "null");
        enhancer.enhancePut(() -> {
            core.put(key, value);
            monitor.recordPut(key, value);
        }, key, value);

        // 自动调度刷新任务（如果启用了刷新功能）
        log.debug("尝试启用自动刷新: key={}", key);
        enhancer.enableAutoRefresh(key);
        log.debug("自动刷新调用完成: key={}", key);
    }

    /**
     * 带TTL的存储方法
     */
    public void putWithTtl(K key, V value, Duration ttl) {
        if (key == null || value == null) return;

        enhancer.enhancePut(() -> {
            core.putWithTtl(key, value, ttl);
            monitor.recordPut(key, value);
        }, key, value);

        // 自动调度刷新任务（如果启用了刷新功能）
        enhancer.enableAutoRefresh(key);
    }

    @Override
    public void putAll(Map<K, V> map) {
        if (map == null || map.isEmpty()) return;

        enhancer.enhancePutAll(() -> {
            core.putAll(map);
            map.forEach(monitor::recordPut);
        }, map.keySet());

        // 自动调度刷新任务（如果启用了刷新功能）
        enhancer.enableAutoRefreshAll(map.keySet());
    }

    @Override
    public boolean putIfAbsent(K key, V value) {
        if (key == null || value == null) return false;

        boolean result = core.putIfAbsent(key, value);
        if (result) {
            monitor.recordPut(key, value);
            // 注意：这里没有通过enhancer，因为putIfAbsent有返回值
            // 实际项目中可能需要更复杂的处理

            // 自动调度刷新任务（如果启用了刷新功能）
            enhancer.enableAutoRefresh(key);
        }
        return result;
    }

    @Override
    public void evict(K key) {
        if (key == null) return;

        enhancer.enhanceEvict(() -> {
            core.evict(key);
            monitor.recordEvict(key);
        }, key);
    }

    @Override
    public void evictAll(Set<K> keys) {
        if (keys == null || keys.isEmpty()) return;

        enhancer.enhanceEvictAll(() -> {
            core.evictAll(keys);
            keys.forEach(monitor::recordEvict);
        }, keys);
    }

    @Override
    public void clear() {
        enhancer.enhanceClear(core::clear);
    }

    @Override
    public boolean containsKey(K key) {
        return core.containsKey(key);
    }

    @Override
    public long size() {
        return core.size();
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
        // 返回监控组件的统计信息，而不是引擎的原始统计
        return monitor.getStats();
    }

    @Override
    public void cleanUp() {
        core.cleanUp();
    }

    /**
     * 异步清理
     */
    public CompletableFuture<Void> cleanUpAsync() {
        return CompletableFuture.runAsync(this::cleanUp, executor);
    }

    /**
     * 异步获取统计信息
     */
    public CompletableFuture<CacheStats> getStatsAsync() {
        return monitor.getStatsAsync();
    }

    /**
     * 获取同步缓存
     */
    public Cache<K, V> synchronous() {
        return this;
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
        return core.get(key, tier);
    }

    @Override
    public void put(K key, V value, CacheTier tier) {
        core.put(key, value, tier);
    }

    @Override
    public void evict(K key, CacheTier tier) {
        core.evict(key, tier);
    }

    @Override
    public CacheStats getStats(CacheTier tier) {
        Map<CacheTier, CacheStats> tieredStats = monitor.getTieredStats();
        return tieredStats.get(tier);
    }

    @Override
    public void clear(CacheTier tier) {
        switch (tier) {
            case L1 -> core.getL1Engine().clear();
            case L2 -> {
                if (core.isMultiTier()) {
                    core.getL2Engine().clear();
                }
            }
            default -> throw new IllegalArgumentException("Unsupported tier: " + tier);
        }
    }

    @Override
    public boolean containsKey(K key, CacheTier tier) {
        return switch (tier) {
            case L1 -> core.getL1Engine().containsKey(key);
            case L2 -> core.isMultiTier() && core.getL2Engine().containsKey(key);
            default -> throw new IllegalArgumentException("Unsupported tier: " + tier);
        };
    }

    @Override
    public long size(CacheTier tier) {
        return switch (tier) {
            case L1 -> core.getL1Engine().size();
            case L2 -> core.isMultiTier() ? core.getL2Engine().size() : 0;
            default -> throw new IllegalArgumentException("Unsupported tier: " + tier);
        };
    }

    @Override
    public void moveUp(K key) {
        core.moveUp(key);
    }

    @Override
    public void moveUpAll(Set<K> keys) {
        core.moveUpAll(keys);
    }

    @Override
    public void moveDown(K key) {
        core.moveDown(key);
    }

    @Override
    public void moveDownAll(Set<K> keys) {
        core.moveDownAll(keys);
    }

    @Override
    public void sync(K key) {
        core.sync(key);
    }

    @Override
    public void syncAll(Set<K> keys) {
        if (keys == null || keys.isEmpty()) return;
        keys.forEach(this::sync);
    }

    @Override
    public Map<CacheTier, CacheStats> getAllStats() {
        return monitor.getTieredStats();
    }

    @Override
    public Set<CacheTier> getSupportedTiers() {
        return core.isMultiTier() ? Set.of(CacheTier.L1, CacheTier.L2) : Set.of(CacheTier.L1);
    }

    @Override
    public boolean isTierAvailable(CacheTier tier) {
        return switch (tier) {
            case L1 -> true;
            case L2 -> core.isMultiTier();
            default -> false;
        };
    }

    // ==================== 扩展功能方法 ====================

    /**
     * 获取缓存值或使用loader加载
     */
    public V getOrLoad(K key) {
        if (key == null) return null;

        return enhancer.enhanceLoad(() -> {
            // 先尝试从缓存获取
            V cachedValue = core.get(key);
            if (cachedValue != null) {
                monitor.recordHit(key);
                return cachedValue;
            }

            // 缓存未命中，尝试加载
            monitor.recordMiss(key);
            return loadAndCache(key);
        }, key);
    }

    /**
     * 加载数据并缓存
     */
    private V loadAndCache(K key) {
        CacheLoader<K, V> loader = core.getCacheLoader();
        if (loader == null) {
            log.debug("No CacheLoader configured for cache: {}, key: {}", name, key);
            return null;
        }
        try {
            V loadedValue = loader.load(key);
            if (loadedValue != null) {
                // 使用SmartCache的put方法触发自动刷新
                put(key, loadedValue);
                log.debug("Loaded and cached: key={}", key);
            }
            return loadedValue;
        } catch (Exception e) {
            log.error("Failed to load key: {} from cache: {}", key, name, e);
            return null;
        }
    }

    @Override
    public V get(K key, Function<K, V> mappingFunction) {
        V value = core.get(key);
        if (value == null && mappingFunction != null) {
            value = mappingFunction.apply(key);
            if (value != null) {
                // 使用SmartCache的put方法，这样会触发自动刷新
                put(key, value);
            }
        }
        return value;
    }

    /**
     * 刷新缓存值
     */
    public void refresh(K key) {
        monitor.recordRefresh(key);
        enhancer.refresh(key, () -> {
            // 这里需要CacheLoader，实际实现中可能需要从core获取
            CacheLoader<K, V> loader = core.getCacheLoader();
            if (loader != null) {
                try {
                    return loader.load(key);
                } catch (Exception e) {
                    log.error("刷新失败: key={}", key, e);
                    return null;
                }
            }
            return null;
        });
    }

    /**
     * 异步刷新缓存值
     */
    @Override
    public CompletableFuture<Void> refreshAsync(K key) {
        monitor.recordRefresh(key);
        return enhancer.refreshAsync(key, () -> {
            CacheLoader<K, V> loader = core.getCacheLoader();
            if (loader != null) {
                try {
                    return loader.load(key);
                } catch (Exception e) {
                    log.error("异步刷新失败: key={}", key, e);
                    return null;
                }
            }
            return null;
        });
    }

    @Override
    public CompletableFuture<V> refreshAsync(K key, Function<K, CompletableFuture<V>> loader) {
        if (loader == null) {
            // 使用现有loader异步刷新，然后获取值
            return refreshAsync(key).thenCompose(v -> getAsync(key));
        }

        return loader.apply(key).thenApply(value -> {
            if (value != null) {
                put(key, value);
            }
            return value;
        });
    }

    // ==================== 配置和管理方法 ====================

    /**
     * 获取缓存加载器
     */
    @Override
    public CacheLoader<K, V> getLoader() {
        return core.getCacheLoader();
    }

    /**
     * 设置缓存加载器
     */
    @Override
    public void setLoader(CacheLoader<K, V> loader) {
        core.setCacheLoader(loader);
    }

    /**
     * 设置配置
     */
    public void setCacheConfiguration(CascadeCacheConfiguration configuration) {
        core.setCacheConfiguration(configuration);
    }

    /**
     * 获取配置
     */
    public CascadeCacheConfiguration getCacheConfiguration() {
        return core.getCacheConfiguration();
    }

    /**
     * 设置防护管理器
     */
    public void setProtectionManager(SimplifiedCacheProtectionManager protectionManager) {
        enhancer.setProtectionManager(protectionManager);
    }

    /**
     * 设置同步器
     */
    public void setSynchronizer(UnifiedCacheSynchronizer<K, V> synchronizer) {
        enhancer.setSynchronizer(synchronizer);
        if (synchronizer != null) {
            // 这里可能需要将core传递给synchronizer进行初始化
            // 实际实现中可能需要更复杂的初始化逻辑
        }
    }

    /**
     * 获取同步器
     */
    public UnifiedCacheSynchronizer<K, V> getSynchronizer() {
        return enhancer.getSynchronizer();
    }

    /**
     * 设置刷新调度器
     */
    public void setRefreshScheduler(CacheRefreshScheduler<K, V> refreshScheduler) {
        enhancer.setRefreshScheduler(refreshScheduler);
    }

    /**
     * 获取刷新调度器
     */
    public CacheRefreshScheduler<K, V> getRefreshScheduler() {
        return enhancer.getRefreshScheduler();
    }

    /**
     * 启用键的定时刷新
     */
    public void enableAutoRefresh(K key) {
        enhancer.enableAutoRefresh(key);
    }

    /**
     * 批量启用键的定时刷新
     */
    public void enableAutoRefreshAll(Set<K> keys) {
        enhancer.enableAutoRefreshAll(keys);
    }

    /**
     * 禁用键的定时刷新
     */
    public void disableAutoRefresh(K key) {
        enhancer.disableAutoRefresh(key);
    }

    /**
     * 获取刷新统计信息
     */
    public CacheRefreshScheduler.RefreshStats getRefreshStats() {
        return enhancer.getRefreshStats();
    }

    // ==================== 监控和健康检查 ====================

    /**
     * 获取健康状态
     */
    public HealthStatus getHealthStatus() {
        return monitor.getHealthStatus();
    }

    /**
     * 强制健康检查
     */
    public HealthStatus forceHealthCheck() {
        return monitor.forceHealthCheck();
    }

    /**
     * 获取监控报告
     */
    public CacheMonitor.MonitoringReport getMonitoringReport() {
        return monitor.getMonitoringReport();
    }

    /**
     * 获取性能指标
     */
    public CacheMonitor.PerformanceMetrics getPerformanceMetrics() {
        return monitor.getPerformanceMetrics();
    }

    /**
     * 重置统计信息
     */
    public void resetStats() {
        monitor.resetStats();
    }

    // ==================== 生命周期管理 ====================

    /**
     * 关闭缓存
     */
    public void close() {
        log.info("正在关闭智能缓存: {}", name);

        // 按依赖关系顺序关闭组件
        enhancer.close();  // 先关闭增强功能
        core.close();      // 再关闭核心缓存
        // monitor不需要特殊关闭逻辑

        log.info("智能缓存已关闭: {}", name);
    }

    /**
     * 检查是否为多级缓存
     */
    public boolean isMultiTier() {
        return core.isMultiTier();
    }

    /**
     * 获取缓存层级
     */
    @Override
    public CacheTier getTier() {
        return core.isMultiTier() ? CacheTier.L2 : CacheTier.L1;
    }

    /**
     * 获取缓存名称
     */
    @Override
    public String getName() {
        return name;
    }

    @Override
    public String toString() {
        return String.format("SmartCache{name='%s', multiTier=%s, enhancer=%s}",
                name, core.isMultiTier(), enhancer.getStatus());
    }
}