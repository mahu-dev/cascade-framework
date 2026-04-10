package cc.coderm.cascade.bloom.impl;


import cc.coderm.cascade.bloom.config.BloomFilterProperties;
import cc.coderm.cascade.bloom.core.BloomFilterManager;
import cc.coderm.cascade.bloom.core.CascadeBloomFilter;
import cc.coderm.cascade.bloom.exception.BloomFilterException;
import cc.coderm.cascade.bloom.exception.BloomFilterInitException;
import cc.coderm.cascade.bloom.exception.BloomFilterNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 基于 Redisson 的布隆过滤器管理器实现
 * <p>
 * 维护一个本地 LRU 缓存作为过滤器实例缓存，避免重复创建 Redisson 对象。
 * <p>
 * <strong>缓存策略</strong>：
 * <ul>
 *   <li>使用 LRU（最近最少使用）淘汰策略</li>
 *   <li>超过 {@code maxCacheSize} 时自动淘汰最久未使用的过滤器</li>
   *   <li>淘汰仅影响本地缓存，不影响 Redis 中的数据</li>
   *   <li>被淘汰的过滤器再次访问时会重新创建并加入缓存</li>
   * </ul>
 *
 * @author lionel lionelk@163.com
 * =============================
 * Date: 2025-01-10
 * Time: 16:00:00
 * =============================
 */
@Slf4j
public class RedissonBloomFilterManager implements BloomFilterManager {

    private final RedissonClient redissonClient;
    private final BloomFilterProperties properties;

    /**
     * LRU 缓存，按访问顺序排序（最近访问的在末尾）
     */
    private final LinkedHashMap<String, CacheEntry> filterCache;

    /**
     * 缓存访问锁
     */
    private final Object cacheLock = new Object();

    /**
     * 统计信息
     */
    private final AtomicLong cacheHits = new AtomicLong(0);
    private final AtomicLong cacheMisses = new AtomicLong(0);
    private final AtomicLong evictions = new AtomicLong(0);

    private static final int INITIAL_CAPACITY = 16;
    private static final float LOAD_FACTOR = 0.75f;

    public RedissonBloomFilterManager(RedissonClient redissonClient, BloomFilterProperties properties) {
        this.redissonClient = redissonClient;
        this.properties = properties;
        this.filterCache = new LinkedHashMap<>(INITIAL_CAPACITY, LOAD_FACTOR, true);

        log.info("[cascade-bloom] BloomFilterManager initialized with maxCacheSize={}", properties.getMaxCacheSize());
    }

    @Override
    public <T> CascadeBloomFilter<T> getFilter(String name) {
        return getOrCreate(
                name,
                properties.getDefaultExpectedInsertions(),
                properties.getDefaultFalseProbability()
        );
    }

    @Override
    public <T> CascadeBloomFilter<T> getOrCreate(String name, long expectedInsertions, double falseProbability) {
        validateParams(name, expectedInsertions, falseProbability);

        CacheEntry entry = getFromCacheOrCompute(
            name,
            filterName -> createNewFilter(filterName, expectedInsertions, falseProbability)
        );

        return (CascadeBloomFilter<T>) entry.filter;
    }

    @Override
    public boolean exists(String name) {
        CacheEntry entry = getFromCache(name);
        if (entry != null) {
            return true;
        }
        String redisKey = buildRedisKey(name);
        return redissonClient.getBloomFilter(redisKey).isExists();
    }

    @Override
    public void remove(String name) {
        CacheEntry entry = getFromCache(name);

        if (entry != null) {
            removeCachedFilter(name, entry.filter);
        } else {
            removeFromRedisDirectly(name);
        }
    }

    @Override
    public Set<String> listFilterNames() {
        synchronized (cacheLock) {
            return new HashSet<>(filterCache.keySet());
        }
    }

    /**
     * 获取当前缓存大小
     *
     * @return 缓存中的过滤器数量
     */
    public int getCacheSize() {
        synchronized (cacheLock) {
            return filterCache.size();
        }
    }

    /**
     * 获取缓存命中次数
     *
     * @return 命中次数
     */
    public long getCacheHits() {
        return cacheHits.get();
    }

    /**
     * 获取缓存未命中次数
     *
     * @return 未命中次数
     */
    public long getCacheMisses() {
        return cacheMisses.get();
    }

    /**
     * 获取缓存淘汰次数
     *
     * @return 淘汰次数
     */
    public long getEvictions() {
        return evictions.get();
    }

    /**
     * 获取缓存命中率
     *
     * @return 命中率（0-1之间的double值）
     */
    public double getCacheHitRate() {
        long hits = cacheHits.get();
        long misses = cacheMisses.get();
        long total = hits + misses;
        if (total == 0) {
            return 0;
        }
        return (double) hits / total;
    }

    /**
     * 清空缓存
     * <p>
     * 清空所有本地缓存的过滤器实例，不会删除 Redis 中的数据。
     */
    public void clearCache() {
        synchronized (cacheLock) {
            int previousSize = filterCache.size();
            filterCache.clear();
            log.info("[cascade-bloom] BloomFilter cache cleared. previousSize={}", previousSize);
        }
    }

    /**
     * 获取最大缓存容量
     *
     * @return 最大缓存数量
     */
    public int getMaxCacheSize() {
        return properties.getMaxCacheSize();
    }

    // -------------------------------------------------------------------------
    // private helpers
    // -------------------------------------------------------------------------

    /**
     * 从缓存获取或计算过滤器
     * <p>
     * 使用 LRU 策略：访问时将 entry 移到末尾，超过限制时删除最老的 entry。
     *
     * @param name 过滤器名称
     * @param computer 计算函数
     * @return 缓存条目
     */
    private CacheEntry getFromCacheOrCompute(String name, java.util.function.Function<String, CascadeBloomFilter<?>> computer) {
        synchronized (cacheLock) {
            CacheEntry entry = filterCache.get(name);
            if (entry != null) {
                entry.lastAccessTime = System.nanoTime();
                filterCache.put(name, entry);
                cacheHits.incrementAndGet();
                return entry;
            }

            cacheMisses.incrementAndGet();
            ensureCapacityBeforeCreate();

            CacheEntry newEntry = new CacheEntry(computer.apply(name));
            filterCache.put(name, newEntry);
            return newEntry;
        }
    }

    /**
     * 从缓存获取过滤器（不更新访问时间）
     */
    private CacheEntry getFromCache(String name) {
        synchronized (cacheLock) {
            return filterCache.get(name);
        }
    }

    /**
     * 确保创建新过滤器前有足够容量
     */
    private void ensureCapacityBeforeCreate() {
        int maxSize = properties.getMaxCacheSize();
        if (filterCache.size() >= maxSize) {
            Map.Entry<String, CacheEntry> eldest = filterCache.entrySet().iterator().next();
            String evictedName = eldest.getKey();
            filterCache.remove(evictedName);
            evictions.incrementAndGet();
            log.warn("[cascade-bloom] Cache full (size={}), evicting oldest filter [{}], maxCacheSize={}",
                    filterCache.size(), evictedName, maxSize);
        }
    }

    /**
     * 从缓存中删除过滤器
     */
    private void removeFromCache(String name) {
        filterCache.remove(name);
        log.debug("[cascade-bloom] BloomFilter [{}] removed from local cache", name);
    }

    /**
     * 创建新的布隆过滤器
     */
    private CascadeBloomFilter<?> createNewFilter(String name, long expectedInsertions, double falseProbability) {
        String redisKey = buildRedisKey(name);
        RBloomFilter<String> stringBloomFilter = redissonClient.<String>getBloomFilter(redisKey);

        boolean initialized = stringBloomFilter.tryInit(expectedInsertions, falseProbability);
        long effectiveExpectedInsertions = expectedInsertions;
        double effectiveFalseProbability = falseProbability;

        if (initialized) {
            log.info("[cascade-bloom] BloomFilter [{}] created. key={}, expectedInsertions={}, falseProbability={}",
                    name, redisKey, expectedInsertions, falseProbability);
        } else {
            effectiveExpectedInsertions = stringBloomFilter.getExpectedInsertions();
            effectiveFalseProbability = stringBloomFilter.getFalseProbability();

            if (effectiveExpectedInsertions != expectedInsertions
                    || Double.compare(effectiveFalseProbability, falseProbability) != 0) {
                log.warn("[cascade-bloom] BloomFilter [{}] config mismatch when reusing existing filter. " +
                                "requested(expectedInsertions={}, falseProbability={}), " +
                                "actual(expectedInsertions={}, falseProbability={}), key={}",
                        name,
                        expectedInsertions,
                        falseProbability,
                        effectiveExpectedInsertions,
                        effectiveFalseProbability,
                        redisKey);
            }
            log.info("[cascade-bloom] BloomFilter [{}] already exists in Redis, reusing. key={}", name, redisKey);
        }

        return new RedissonBloomFilter<>(stringBloomFilter, name, effectiveExpectedInsertions, effectiveFalseProbability);
    }

    /**
     * 删除已缓存的过滤器
     */
    private void removeCachedFilter(String name, CascadeBloomFilter<?> filter) {
        try {
            filter.delete();
            removeFromCache(name);
            log.info("[cascade-bloom] BloomFilter [{}] removed successfully", name);
        } catch (Exception e) {
            log.error("[cascade-bloom] Failed to delete bloom filter [{}] from Redis, cache remains unchanged", name, e);
            throw new BloomFilterException(
                    "Failed to remove bloom filter [" + name + "] from Redis. Operation aborted.", e);
        }
    }

    /**
     * 直接从Redis删除过滤器
     */
    private void removeFromRedisDirectly(String name) {
        String redisKey = buildRedisKey(name);
        RBloomFilter<String> stringBloomFilter = redissonClient.<String>getBloomFilter(redisKey);

        if (!stringBloomFilter.isExists()) {
            throw new BloomFilterNotFoundException(name);
        }

        try {
            stringBloomFilter.delete();
            log.info("[cascade-bloom] BloomFilter [{}] removed from Redis (no local cache)", name);
        } catch (Exception e) {
            log.error("[cascade-bloom] Failed to delete bloom filter [{}] from Redis", name, e);
            throw new BloomFilterException(
                    "Failed to remove bloom filter [" + name + "] from Redis.", e);
        }
    }

    /**
     * 构建 Redis key
     */
    private String buildRedisKey(String name) {
        return properties.getKeyPrefix() + name;
    }

    /**
     * 验证参数
     */
    private static void validateParams(String name, long expectedInsertions, double falseProbability) {
        if (name == null || name.trim().isEmpty()) {
            throw new BloomFilterInitException("BloomFilter name must not be blank");
        }
        if (expectedInsertions <= 0) {
            throw new BloomFilterInitException(
                    "expectedInsertions must be positive, but got: " + expectedInsertions);
        }
        if (falseProbability <= 0 || falseProbability >= 1) {
            throw new BloomFilterInitException(
                    "falseProbability must be in range (0, 1), but got: " + falseProbability);
        }
    }

    /**
     * 缓存条目
     */
    private static class CacheEntry {
        final CascadeBloomFilter<?> filter;
        volatile long lastAccessTime;

        CacheEntry(CascadeBloomFilter<?> filter) {
            this.filter = filter;
            this.lastAccessTime = System.nanoTime();
        }
    }
}