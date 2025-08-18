package io.github.cascade.cache.core.operations;

import io.github.cascade.cache.api.CacheStats;
import io.github.cascade.cache.api.CacheTier;
import io.github.cascade.cache.tier.LocalTier;
import io.github.cascade.cache.tier.RemoteTier;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 分层缓存核心操作类
 * 专注于缓存的CRUD操作，不涉及同步、加载等复杂逻辑
 *
 * @param <K> 键类型
 * @param <V> 值类型
 * @author cascade
 */
@Getter
public class TieredCacheOperations<K, V> {

    private static final Logger log = LoggerFactory.getLogger(TieredCacheOperations.class);

    private final LocalTier<K, V> l1Cache;
    private final RemoteTier<K, V> l2Cache;

    public TieredCacheOperations(LocalTier<K, V> l1Cache, RemoteTier<K, V> l2Cache) {
        this.l1Cache = l1Cache;
        this.l2Cache = l2Cache;
    }

    // ==================== 基础读取操作 ====================

    /**
     * 从指定层级获取值
     */
    public V get(K key, CacheTier tier) {
        if (key == null) {
            return null;
        }

        return switch (tier) {
            case L1 -> l1Cache.get(key);
            case L2 -> l2Cache != null ? l2Cache.get(key) : null;
            default -> throw new IllegalArgumentException("Unsupported tier: " + tier);
        };
    }

    /**
     * 多级缓存获取（L1 -> L2）
     */
    public V getFromTiers(K key) {
        if (key == null) {
            return null;
        }

        // 1. 先从L1获取
        V value = l1Cache.get(key);
        if (value != null) {
            log.info("Get value from L1 cache: ke = {},value = {}", key, value);
            return value;
        }

        // 2. 从L2获取
        if (l2Cache != null) {
            value = l2Cache.get(key);
            log.info("Get value from L2 cache: key = {},value = {}", key, value);
            if (value != null) {
                // 提升到L1
                log.info("Promote value to L1 cache: key = {},value = {}", key, value);
                l1Cache.put(key, value);
                return value;
            }
        }

        return null;
    }

    /**
     * 批量获取
     */
    public Map<K, V> getAllFromTiers(Set<K> keys) {
        if (keys == null || keys.isEmpty()) {
            return Map.of();
        }

        Set<K> missingKeys = new HashSet<>(keys);

        // 1. 从L1缓存批量获取
        Map<K, V> l1Results = l1Cache.getAll(keys);
        Map<K, V> result = new HashMap<>(l1Results);
        missingKeys.removeAll(l1Results.keySet());

        // 2. 从L2缓存获取剩余的键
        if (!missingKeys.isEmpty() && l2Cache != null) {
            Map<K, V> l2Results = l2Cache.getAll(missingKeys);
            result.putAll(l2Results);

            // 将L2结果提升到L1
            if (!l2Results.isEmpty()) {
                l1Cache.putAll(l2Results);
            }
        }

        return result;
    }

    // ==================== 基础写入操作 ====================

    /**
     * 写入指定层级
     */
    public void put(K key, V value, CacheTier tier) {
        if (key == null || value == null) {
            return;
        }

        switch (tier) {
            case L1 -> l1Cache.put(key, value);
            case L2 -> {
                if (l2Cache != null) {
                    l2Cache.put(key, value);
                }
            }
            default -> throw new IllegalArgumentException("Unsupported tier: " + tier);
        }
    }

    /**
     * 写入所有层级
     */
    public void putToAllTiers(K key, V value, Duration ttl) {
        if (key == null || value == null) {
            return;
        }

        // 写入L1（不支持TTL）
        l1Cache.put(key, value);

        // 写入L2（支持TTL）
        if (l2Cache != null) {
            if (ttl != null) {
                l2Cache.put(key, value, ttl);
            } else {
                l2Cache.put(key, value);
            }
        }
    }

    /**
     * 批量写入所有层级
     */
    public void putAllToTiers(Map<K, V> map) {
        if (map == null || map.isEmpty()) {
            return;
        }

        l1Cache.putAll(map);
        if (l2Cache != null) {
            l2Cache.putAll(map);
        }
    }

    // ==================== 删除操作 ====================

    /**
     * 从指定层级删除
     */
    public void evict(K key, CacheTier tier) {
        if (key == null) {
            return;
        }

        switch (tier) {
            case L1 -> l1Cache.evict(key);
            case L2 -> {
                if (l2Cache != null) {
                    l2Cache.evict(key);
                }
            }
            default -> throw new IllegalArgumentException("Unsupported tier: " + tier);
        }
    }

    /**
     * 从所有层级删除
     */
    public void evictFromAllTiers(K key) {
        if (key == null) {
            return;
        }

        l1Cache.evict(key);
        if (l2Cache != null) {
            l2Cache.evict(key);
        }
    }

    /**
     * 批量删除
     */
    public void evictAllFromTiers(Set<K> keys) {
        if (keys == null || keys.isEmpty()) {
            return;
        }

        l1Cache.evictAll(keys);
        if (l2Cache != null) {
            l2Cache.evictAll(keys);
        }
    }

    /**
     * 清空指定层级
     */
    public void clear(CacheTier tier) {
        switch (tier) {
            case L1 -> l1Cache.clear();
            case L2 -> {
                if (l2Cache != null) {
                    l2Cache.clear();
                }
            }
            default -> throw new IllegalArgumentException("Unsupported tier: " + tier);
        }
    }

    /**
     * 清空所有层级
     */
    public void clearAllTiers() {
        l1Cache.clear();
        if (l2Cache != null) {
            l2Cache.clear();
        }
    }

    // ==================== 查询操作 ====================

    /**
     * 检查指定层级是否包含键
     */
    public boolean containsKey(K key, CacheTier tier) {
        if (key == null) {
            return false;
        }

        return switch (tier) {
            case L1 -> l1Cache.containsKey(key);
            case L2 -> l2Cache != null && l2Cache.containsKey(key);
            default -> throw new IllegalArgumentException("Unsupported tier: " + tier);
        };
    }

    /**
     * 检查任一层级是否包含键
     */
    public boolean containsKeyInAnyTier(K key) {
        if (key == null) {
            return false;
        }

        return l1Cache.containsKey(key) || (l2Cache != null && l2Cache.containsKey(key));
    }

    /**
     * 获取指定层级大小
     */
    public long size(CacheTier tier) {
        return switch (tier) {
            case L1 -> l1Cache.size();
            case L2 -> l2Cache != null ? l2Cache.size() : 0;
            default -> throw new IllegalArgumentException("Unsupported tier: " + tier);
        };
    }

    /**
     * 获取总大小（以L2为准，如果没有L2则使用L1）
     */
    public long totalSize() {
        return l2Cache != null ? l2Cache.size() : l1Cache.size();
    }

    /**
     * 检查是否为空
     */
    public boolean isEmpty() {
        return l1Cache.isEmpty() && (l2Cache == null || l2Cache.isEmpty());
    }

    // ==================== 统计信息 ====================

    /**
     * 获取指定层级统计
     */
    public CacheStats getStats(CacheTier tier) {
        return switch (tier) {
            case L1 -> l1Cache.getStats();
            case L2 -> l2Cache != null ? l2Cache.getStats() : null;
            default -> throw new IllegalArgumentException("Unsupported tier: " + tier);
        };
    }

    /**
     * 获取所有层级统计
     */
    public Map<CacheTier, CacheStats> getAllStats() {
        Map<CacheTier, CacheStats> stats = new HashMap<>();
        stats.put(CacheTier.L1, l1Cache.getStats());
        if (l2Cache != null) {
            stats.put(CacheTier.L2, l2Cache.getStats());
        }
        return stats;
    }

    // ==================== 层级管理 ====================

    /**
     * 数据提升（L2 -> L1）
     */
    public void promote(K key) {
        if (key == null || l2Cache == null) {
            return;
        }

        V value = l2Cache.get(key);
        if (value != null) {
            l1Cache.put(key, value);
        }
    }

    /**
     * 批量数据提升
     */
    public void promoteAll(Set<K> keys) {
        if (keys == null || keys.isEmpty() || l2Cache == null) {
            return;
        }

        Map<K, V> values = l2Cache.getAll(keys);
        if (!values.isEmpty()) {
            l1Cache.putAll(values);
        }
    }

    /**
     * 数据降级（L1 -> L2）
     */
    public void demote(K key) {
        if (key == null || l2Cache == null) {
            return;
        }

        V value = l1Cache.get(key);
        if (value != null) {
            l2Cache.put(key, value);
            l1Cache.evict(key);
        }
    }

    /**
     * 批量数据降级
     */
    public void demoteAll(Set<K> keys) {
        if (keys == null || keys.isEmpty() || l2Cache == null) {
            return;
        }

        Map<K, V> values = l1Cache.getAll(keys);
        if (!values.isEmpty()) {
            l2Cache.putAll(values);
            l1Cache.evictAll(keys);
        }
    }

    /**
     * 同步层级数据
     */
    public void sync(K key) {
        if (key == null || l2Cache == null) {
            return;
        }

        V l1Value = l1Cache.get(key);
        V l2Value = l2Cache.get(key);

        if (l1Value != null && l2Value == null) {
            // L1有，L2没有，写入L2
            l2Cache.put(key, l1Value);
        } else if (l1Value == null && l2Value != null) {
            // L2有，L1没有，写入L1
            l1Cache.put(key, l2Value);
        }
    }

    // ==================== 维护操作 ====================

    /**
     * 清理缓存
     */
    public void cleanUp() {
        l1Cache.cleanUp();
        if (l2Cache != null) {
            l2Cache.cleanUp();
        }
    }

    /**
     * 获取支持的层级
     */
    public Set<CacheTier> getSupportedTiers() {
        Set<CacheTier> tiers = new HashSet<>();
        tiers.add(CacheTier.L1);
        if (l2Cache != null) {
            tiers.add(CacheTier.L2);
        }
        return tiers;
    }

    /**
     * 检查层级是否可用
     */
    public boolean isTierAvailable(CacheTier tier) {
        return switch (tier) {
            case L1 -> l1Cache != null;
            case L2 -> l2Cache != null;
            default -> false;
        };
    }

    // ==================== Getter方法 ====================

}