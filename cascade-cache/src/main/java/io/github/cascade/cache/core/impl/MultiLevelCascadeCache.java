package io.github.cascade.cache.core.impl;

import io.github.cascade.cache.api.CacheLoader;
import io.github.cascade.cache.api.CacheStats;
import io.github.cascade.cache.api.CacheTier;
import io.github.cascade.cache.api.TieredCache;
import io.github.cascade.cache.tier.RedissonRemoteTier;
import io.github.cascade.cache.tier.SimpleCaffeineLocalTier;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Function;

/**
 * 多级缓存实现
 * 支持L1(Caffeine本地缓存) + L2(Redis分布式缓存)
 */
public class MultiLevelCascadeCache<K, V> implements TieredCache<K, V> {

    private final String name;
    private final SimpleCaffeineLocalTier<K, V> l1Cache;
    private final RedissonRemoteTier<K, V> l2Cache;
    private final CacheLoader<K, V> cacheLoader;
    private final Executor executor;

    public MultiLevelCascadeCache(String name,
                                  SimpleCaffeineLocalTier<K, V> l1Cache,
                                  RedissonRemoteTier<K, V> l2Cache,
                                  CacheLoader<K, V> cacheLoader) {
        this.name = name;
        this.l1Cache = l1Cache;
        this.l2Cache = l2Cache;
        this.cacheLoader = cacheLoader;
        this.executor = ForkJoinPool.commonPool();
    }

    @Override
    public String getName() {
        return name;
    }

    // Cache接口实现
    @Override
    public V get(K key) {
        if (key == null) {
            return null;
        }

        // 1. 先从L1缓存获取
        V value = l1Cache.get(key);
        if (value != null) {
            return value;
        }

        // 2. 从L2缓存获取
        if (l2Cache != null) {
            value = l2Cache.get(key);
            if (value != null) {
                // 提升到L1缓存
                l1Cache.put(key, value);
                return value;
            }
        }

        return null;
    }

    @Override
    public V get(K key, Function<K, V> loader) {
        V value = get(key);
        if (value == null && loader != null) {
            value = loader.apply(key);
            if (value != null) {
                put(key, value);
            }
        }
        return value;
    }

    @Override
    public Map<K, V> getAll(Set<K> keys) {
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
            l1Cache.putAll(l2Results);
        }

        return result;
    }

    @Override
    public Map<K, V> getAll(Set<K> keys, Function<Set<K>, Map<K, V>> loader) {
        if (keys == null || keys.isEmpty()) {
            return Map.of();
        }

        Map<K, V> result = getAll(keys);
        Set<K> missingKeys = new HashSet<>(keys);
        missingKeys.removeAll(result.keySet());

        if (!missingKeys.isEmpty() && loader != null) {
            Map<K, V> loaded = loader.apply(missingKeys);
            if (loaded != null) {
                putAll(loaded);
                result.putAll(loaded);
            }
        }

        return result;
    }

    @Override
    public void put(K key, V value) {
        put(key, value, (Duration) null);
    }

    public void put(K key, V value, Duration ttl) {
        if (key == null || value == null) {
            return;
        }

        // 同时写入L1和L2
        l1Cache.put(key, value, ttl);
        if (l2Cache != null) {
            l2Cache.put(key, value, ttl);
        }
    }

    @Override
    public void putAll(Map<K, V> map) {
        if (map == null || map.isEmpty()) {
            return;
        }

        // 同时写入L1和L2
        l1Cache.putAll(map);
        if (l2Cache != null) {
            l2Cache.putAll(map);
        }
    }

    @Override
    public boolean putIfAbsent(K key, V value) {
        if (key == null || value == null) {
            return false;
        }

        // 先检查L1缓存
        if (l1Cache.containsKey(key)) {
            return false;
        }

        // 再检查L2缓存
        if (l2Cache != null && l2Cache.containsKey(key)) {
            // 如果L2存在，提升到L1
            V existingValue = l2Cache.get(key);
            if (existingValue != null) {
                l1Cache.put(key, existingValue);
                return false;
            }
        }

        // 都不存在，执行插入
        put(key, value);
        return true;
    }

    @Override
    public void evict(K key) {
        if (key == null) {
            return;
        }

        // 从两级缓存中删除
        l1Cache.evict(key);
        if (l2Cache != null) {
            l2Cache.evict(key);
        }
    }

    @Override
    public void evictAll(Set<K> keys) {
        if (keys == null || keys.isEmpty()) {
            return;
        }

        // 从两级缓存中批量删除
        l1Cache.evictAll(keys);
        if (l2Cache != null) {
            l2Cache.evictAll(keys);
        }
    }

    @Override
    public void clear() {
        l1Cache.clear();
        if (l2Cache != null) {
            l2Cache.clear();
        }
    }

    @Override
    public boolean containsKey(K key) {
        if (key == null) {
            return false;
        }
        return l1Cache.containsKey(key) || (l2Cache != null && l2Cache.containsKey(key));
    }

    @Override
    public long size() {
        return l1Cache.size() + (l2Cache != null ? l2Cache.size() : 0);
    }

    @Override
    public long estimatedSize() {
        return size();
    }

    @Override
    public boolean isEmpty() {
        return l1Cache.isEmpty() && (l2Cache == null || l2Cache.isEmpty());
    }

    @Override
    public void refresh(K key) {
        if (key == null || cacheLoader == null) {
            return;
        }

        try {
            V value = cacheLoader.load(key);
            if (value != null) {
                put(key, value);
            }
        } catch (Exception e) {
            // 刷新失败，记录日志但不抛异常
        }
    }

    @Override
    public CompletableFuture<Void> refreshAsync(K key) {
        return CompletableFuture.runAsync(() -> refresh(key), executor);
    }

    @Override
    public CacheStats getStats() {
        // 合并L1和L2的统计信息
        return l1Cache.getStats(); // 简化实现，后续可以扩展
    }

    @Override
    public void cleanUp() {
        l1Cache.cleanUp();
        if (l2Cache != null) {
            l2Cache.cleanUp();
        }
    }

    // TieredCache接口实现
    @Override
    public V get(K key, CacheTier tier) {
        if (key == null) {
            return null;
        }

        return switch (tier) {
            case L1 -> l1Cache.get(key);
            case L2 -> l2Cache != null ? l2Cache.get(key) : null;
            case L3 -> null; // L3层暂不支持
            case MULTI_LEVEL -> null; // 多级缓存层不适用
        };
    }

    @Override
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
            case L3 -> {
                // L3层暂不支持
            }
        }
    }

    @Override
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
            case L3 -> {
                // L3层暂不支持
            }
        }
    }

    @Override
    public void clear(CacheTier tier) {
        switch (tier) {
            case L1 -> l1Cache.clear();
            case L2 -> {
                if (l2Cache != null) {
                    l2Cache.clear();
                }
            }
            case L3 -> {
                // L3层暂不支持
            }
        }
    }

    @Override
    public boolean containsKey(K key, CacheTier tier) {
        if (key == null) {
            return false;
        }

        return switch (tier) {
            case L1 -> l1Cache.containsKey(key);
            case L2 -> l2Cache != null && l2Cache.containsKey(key);
            case L3 -> false; // L3层暂不支持
            case MULTI_LEVEL -> false; // 多级缓存层不适用
        };
    }

    @Override
    public long size(CacheTier tier) {
        return switch (tier) {
            case L1 -> l1Cache.size();
            case L2 -> l2Cache != null ? l2Cache.size() : 0;
            case L3 -> 0; // L3层暂不支持
            case MULTI_LEVEL -> 0; // 多级缓存层不适用
        };
    }

    @Override
    public CacheStats getStats(CacheTier tier) {
        return switch (tier) {
            case L1 -> l1Cache.getStats();
            case L2 -> l2Cache != null ? l2Cache.getStats() : null;
            case L3 -> null; // L3层暂不支持
            case MULTI_LEVEL -> null; // 多级缓存层不适用
        };
    }

    @Override
    public void promote(K key) {
        if (key == null || l2Cache == null) {
            return;
        }

        V value = l2Cache.get(key);
        if (value != null) {
            l1Cache.put(key, value);
        }
    }

    @Override
    public void promoteAll(Set<K> keys) {
        if (keys == null || keys.isEmpty() || l2Cache == null) {
            return;
        }

        Map<K, V> values = l2Cache.getAll(keys);
        l1Cache.putAll(values);
    }

    @Override
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

    @Override
    public void demoteAll(Set<K> keys) {
        if (keys == null || keys.isEmpty() || l2Cache == null) {
            return;
        }

        Map<K, V> values = l1Cache.getAll(keys);
        l2Cache.putAll(values);
        l1Cache.evictAll(keys);
    }

    @Override
    public void sync(K key) {
        // 同步L1和L2中的数据
        if (key == null) {
            return;
        }

        V l1Value = l1Cache.get(key);
        V l2Value = l2Cache != null ? l2Cache.get(key) : null;

        if (l1Value != null && l2Value == null) {
            // L1有，L2没有，写入L2
            l2Cache.put(key, l1Value);
        } else if (l1Value == null && l2Value != null) {
            // L2有，L1没有，写入L1
            l1Cache.put(key, l2Value);
        }
    }

    @Override
    public void syncAll(Set<K> keys) {
        if (keys == null || keys.isEmpty()) {
            return;
        }

        for (K key : keys) {
            sync(key);
        }
    }

    @Override
    public Map<CacheTier, CacheStats> getAllStats() {
        Map<CacheTier, CacheStats> stats = new HashMap<>();
        stats.put(CacheTier.L1, l1Cache.getStats());
        if (l2Cache != null) {
            stats.put(CacheTier.L2, l2Cache.getStats());
        }
        return stats;
    }

    @Override
    public Set<CacheTier> getSupportedTiers() {
        Set<CacheTier> tiers = new HashSet<>();
        tiers.add(CacheTier.L1);
        if (l2Cache != null) {
            tiers.add(CacheTier.L2);
        }
        return tiers;
    }

    @Override
    public boolean isTierAvailable(CacheTier tier) {
        return switch (tier) {
            case L1 -> l1Cache != null;
            case L2 -> l2Cache != null;
            case L3 -> false; // L3层暂不支持
            case MULTI_LEVEL -> false; // 多级缓存层不适用
        };
    }

    @Override
    public CacheTier getTier() {
        return CacheTier.MULTI_LEVEL;
    }

    @Override
    public void setLoader(CacheLoader<K, V> loader) {
        // 多级缓存的loader设置需要传播到各层
        if (l1Cache != null) {
            l1Cache.setLoader(loader);
        }
        if (l2Cache != null) {
            l2Cache.setLoader(loader);
        }
    }

    @Override
    public CacheLoader<K, V> getLoader() {
        return cacheLoader;
    }


    // Getter方法
    public SimpleCaffeineLocalTier<K, V> getL1Cache() {
        return l1Cache;
    }

    public RedissonRemoteTier<K, V> getL2Cache() {
        return l2Cache;
    }

    public CacheLoader<K, V> getCacheLoader() {
        return cacheLoader;
    }
}