package io.github.cascade.cache.core.unified;

import io.github.cascade.cache.api.CacheLoader;
import io.github.cascade.cache.api.CacheStats;
import io.github.cascade.cache.api.CacheTier;
import io.github.cascade.cache.config.CascadeCacheConfiguration;
import io.github.cascade.cache.metrics.CacheMetrics;
import io.github.cascade.cache.metrics.CacheMetricsCollector;
import io.github.cascade.cache.metrics.DetailedCacheMetrics;
import io.github.cascade.cache.strategy.CacheStrategy;
import io.github.cascade.cache.strategy.MultiTierCacheStrategy;
import io.github.cascade.cache.strategy.SingleTierCacheStrategy;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Function;

/**
 * 重构后的缓存核心逻辑
 * 职责明确：只负责基础缓存操作的协调，具体策略交给专门的策略类处理
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

    // 统一策略模式：消除硬编码条件判断
    private final CacheStrategy<K, V> strategy;

    // 性能监控
    private final CacheMetricsCollector metricsCollector;

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

        // 初始化性能监控
        this.metricsCollector = new CacheMetricsCollector(name, isMultiTier);

        // 初始化统一策略
        this.strategy = isMultiTier 
            ? new MultiTierCacheStrategy<>(name, l1Engine, l2Engine, metricsCollector, executor)
            : new SingleTierCacheStrategy<>(name, l1Engine, metricsCollector);
    }

    // ==================== 配置设置 ====================

    public void setCacheLoader(CacheLoader<K, V> cacheLoader) {
        this.cacheLoader = cacheLoader;
        strategy.setCacheLoader(cacheLoader);
    }

    public void setCacheConfiguration(CascadeCacheConfiguration cacheConfiguration) {
        this.cacheConfiguration = cacheConfiguration;
        strategy.setCacheConfiguration(cacheConfiguration);
    }

    // ==================== 基础缓存操作 ====================

    /**
     * 获取缓存值
     */
    public V get(K key) {
        return strategy.get(key);
    }

    /**
     * 批量获取缓存值
     */
    public Map<K, V> getAll(Set<K> keys) {
        return strategy.getAll(keys);
    }

    /**
     * 存储缓存值
     */
    public void put(K key, V value) {
        strategy.put(key, value);
    }

    /**
     * 带TTL存储缓存值
     */
    public void putWithTtl(K key, V value, Duration ttl) {
        strategy.put(key, value, ttl);
        log.debug("Cache put: key={}, strategy={}", key, strategy.getStrategyName());
    }

    /**
     * 批量存储缓存值
     */
    public void putAll(Map<K, V> map) {
        strategy.putAll(map);
        log.debug("Cache putAll: size={}, strategy={}", map.size(), strategy.getStrategyName());
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
        strategy.evict(key);
    }

    /**
     * 批量删除缓存项
     */
    public void evictAll(Set<K> keys) {
        strategy.evictAll(keys);
    }

    /**
     * 清空所有缓存
     */
    public void clear() {
        strategy.clear();
    }

    /**
     * 检查是否包含键
     */
    public boolean containsKey(K key) {
        return strategy.containsKey(key);
    }

    /**
     * 获取缓存大小
     */
    public long size() {
        return strategy.size();
    }

    /**
     * 获取统计信息
     */
    public CacheStats getStats() {
        return strategy.getStats();
    }

    /**
     * 清理缓存
     */
    public void cleanUp() {
        strategy.cleanUp();
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

    // ==================== 分层缓存数据移动操作 ====================

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

    // ==================== 性能监控和统计 ====================

    /**
     * 获取详细的缓存性能指标
     */
    public DetailedCacheMetrics getDetailedMetrics() {
        return metricsCollector.getStats();
    }

    /**
     * 获取缓存健康状态
     */
    public CacheMetrics.CacheHealthStatus getHealthStatus() {
        return metricsCollector.getHealthStatus();
    }

    /**
     * 重置性能监控统计
     */
    public void resetPerformanceStats() {
        metricsCollector.reset();
    }

    // ==================== 辅助方法 ====================

    /**
     * 获取默认TTL
     */
    private Duration getDefaultTtl() {
        if (cacheConfiguration != null) {
            if (isMultiTier && cacheConfiguration.getL2() != null) {
                return cacheConfiguration.getL2().getDefaultTtl();
            } else if (cacheConfiguration.getL1() != null) {
                Duration ttl = cacheConfiguration.getL1().getExpireAfterWrite();
                if (ttl != null) {
                    return ttl;
                }
            }
        }
        return Duration.ofHours(1); // 默认1小时
    }

    public void close() {
        l1Engine.close();
        if (isMultiTier) {
            l2Engine.close();
        }
        log.debug("CacheCore closed: {}", name);
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