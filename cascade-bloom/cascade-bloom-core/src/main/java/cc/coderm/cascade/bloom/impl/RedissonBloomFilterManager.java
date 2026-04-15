package cc.coderm.cascade.bloom.impl;


import cc.coderm.cascade.bloom.config.BloomFilterProperties;
import cc.coderm.cascade.bloom.core.BloomFilterManager;
import cc.coderm.cascade.bloom.core.CascadeBloomFilter;
import cc.coderm.cascade.bloom.exception.BloomFilterException;
import cc.coderm.cascade.bloom.exception.BloomFilterNotFoundException;
import cc.coderm.cascade.bloom.util.BloomFilterArgumentValidator;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
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
    private final LinkedHashMap<String, CascadeBloomFilter<?>> filterCache;

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
    /**
     * 正在创建中的过滤器（按 name 去重），用于避免同 key 的重复 Redis I/O。
     */
    private final ConcurrentHashMap<String, InFlightCreation> inFlightCreations = new ConcurrentHashMap<>();
    /**
     * 每个过滤器的创建世代（epoch），用于 remove/get 并发时的结果失效控制。
     */
    private final ConcurrentHashMap<String, AtomicLong> creationEpochs = new ConcurrentHashMap<>();

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
        String normalizedName = BloomFilterArgumentValidator.normalizeFilterName(name);
        return getOrCreate(
                normalizedName,
                properties.getDefaultExpectedInsertions(),
                properties.getDefaultFalseProbability()
        );
    }

    @Override
    public <T> CascadeBloomFilter<T> getOrCreate(String name, long expectedInsertions, double falseProbability) {
        String normalizedName = BloomFilterArgumentValidator.normalizeFilterName(name);
        BloomFilterArgumentValidator.validateCreateParams(expectedInsertions, falseProbability);

        CascadeBloomFilter<?> filter = getFromCacheOrCompute(normalizedName,
                filterName -> createNewFilter(filterName, expectedInsertions, falseProbability)
        );

        return (CascadeBloomFilter<T>) filter;
    }

    @Override
    public boolean exists(String name) {
        String normalizedName = BloomFilterArgumentValidator.normalizeFilterName(name);
        CascadeBloomFilter<?> cachedFilter = getCachedFilter(normalizedName);
        if (cachedFilter != null) {
            return true;
        }

        boolean redisExists = redissonClient.<String>getBloomFilter(buildRedisKey(normalizedName)).isExists();
        if (!redisExists) {
            evictIfCached(normalizedName);
            return false;
        }
        return true;
    }

    @Override
    public void remove(String name) {
        String normalizedName = BloomFilterArgumentValidator.normalizeFilterName(name);
        long invalidatedEpoch = incrementEpoch(normalizedName);
        invalidateInFlightCreation(normalizedName, invalidatedEpoch);
        CascadeBloomFilter<?> cachedFilter = getCachedFilter(normalizedName);

        if (cachedFilter != null) {
            removeCachedFilter(normalizedName, cachedFilter);
        } else {
            removeFromRedisDirectly(normalizedName);
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
     * @param name     过滤器名称
     * @param computer 计算函数
     * @return 缓存条目
     */
    private CascadeBloomFilter<?> getFromCacheOrCompute(String name, Function<String, CascadeBloomFilter<?>> computer) {
        boolean missRecorded = false;
        while (true) {
            long observedEpoch = currentEpoch(name);

            CascadeBloomFilter<?> cached = getCachedFilter(name);
            if (cached != null) {
                if (!missRecorded) {
                    cacheHits.incrementAndGet();
                }
                return cached;
            }
            if (!missRecorded) {
                cacheMisses.incrementAndGet();
                missRecorded = true;
            }

            InFlightCreation inFlight = inFlightCreations.get(name);
            if (inFlight != null) {
                if (inFlight.epoch != observedEpoch) {
                    inFlightCreations.remove(name, inFlight);
                    continue;
                }
                try {
                    return awaitInFlightCreation(name, inFlight);
                } catch (StaleCreationException ignored) {
                    continue;
                }
            }

            InFlightCreation candidate = new InFlightCreation(observedEpoch);
            InFlightCreation existing = inFlightCreations.putIfAbsent(name, candidate);
            if (existing != null) {
                continue;
            }
            try {
                return createAndPublishFilter(name, observedEpoch, computer, candidate);
            } catch (StaleCreationException ignored) {
                continue;
            }
        }
    }

    private CascadeBloomFilter<?> createAndPublishFilter(String name,
                                                         long observedEpoch,
                                                         Function<String, CascadeBloomFilter<?>> computer,
                                                         InFlightCreation creation) {
        try {
            // Redis I/O 在线程外层执行，不占用 cacheLock。
            CascadeBloomFilter<?> created = computer.apply(name);
            CascadeBloomFilter<?> published = publishToCache(name, observedEpoch, created);
            creation.future.complete(published);
            return published;
        } catch (Throwable ex) {
            creation.future.completeExceptionally(ex);
            if (ex instanceof StaleCreationException staleCreationException) {
                throw staleCreationException;
            }
            if (ex instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (ex instanceof Error error) {
                throw error;
            }
            throw new BloomFilterException("Failed to create bloom filter [" + name + "]", ex);
        } finally {
            inFlightCreations.remove(name, creation);
        }
    }

    private CascadeBloomFilter<?> awaitInFlightCreation(String name,
                                                        InFlightCreation creation) {
        try {
            return creation.future.join();
        } catch (CompletionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof StaleCreationException staleCreationException) {
                throw staleCreationException;
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new BloomFilterException("Failed to create bloom filter [" + name + "]", cause);
        }
    }

    private CascadeBloomFilter<?> publishToCache(String name,
                                                 long observedEpoch,
                                                 CascadeBloomFilter<?> created) {
        CascadeBloomFilter<?> cached;
        boolean stale;
        synchronized (cacheLock) {
            long latestEpoch = currentEpoch(name);
            stale = latestEpoch != observedEpoch;
            cached = filterCache.get(name);
            if (!stale && cached == null) {
                ensureCapacityBeforeCreate();
                filterCache.put(name, created);
                cached = created;
            }
        }

        if (!stale) {
            return cached;
        }
        if (cached != null) {
            return cached;
        }
        throw StaleCreationException.invalidated(name);
    }

    /**
     * 从缓存获取过滤器（会更新 LRU 访问顺序）
     */
    private CascadeBloomFilter<?> getCachedFilter(String name) {
        synchronized (cacheLock) {
            return filterCache.get(name);
        }
    }

    /**
     * 确保创建新过滤器前有足够容量
     */
    private void ensureCapacityBeforeCreate() {
        int maxSize = properties.getMaxCacheSize();
        int currentSize = filterCache.size();
        if (currentSize >= maxSize) {
            Map.Entry<String, CascadeBloomFilter<?>> eldest = filterCache.entrySet().iterator().next();
            String evictedName = eldest.getKey();
            filterCache.remove(evictedName);
            evictions.incrementAndGet();
            log.warn("[cascade-bloom] Cache full (size={} >= maxCacheSize={}), evicting oldest filter [{}]",
                    currentSize, maxSize, evictedName);
        }
    }

    /**
     * 从缓存中删除过滤器
     */
    private void removeFromCache(String name) {
        synchronized (cacheLock) {
            filterCache.remove(name);
            log.debug("[cascade-bloom] BloomFilter [{}] removed from local cache", name);
        }
    }

    private void evictIfCached(String name) {
        synchronized (cacheLock) {
            if (filterCache.remove(name) != null) {
                log.warn("[cascade-bloom] BloomFilter [{}] not found in Redis, evicted stale local cache entry", name);
            }
        }
    }

    private void invalidateInFlightCreation(String name, long invalidatedEpoch) {
        InFlightCreation inFlight = inFlightCreations.get(name);
        if (inFlight != null && inFlight.epoch < invalidatedEpoch) {
            inFlightCreations.remove(name, inFlight);
        }
    }

    private long currentEpoch(String name) {
        AtomicLong epoch = creationEpochs.get(name);
        return epoch == null ? 0L : epoch.get();
    }

    private long incrementEpoch(String name) {
        return creationEpochs.computeIfAbsent(name, key -> new AtomicLong(0)).incrementAndGet();
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

        return new RedissonBloomFilter<>(stringBloomFilter, redissonClient, name, effectiveExpectedInsertions, effectiveFalseProbability);
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

    private static final class InFlightCreation {
        private final long epoch;
        private final CompletableFuture<CascadeBloomFilter<?>> future = new CompletableFuture<>();

        private InFlightCreation(long epoch) {
            this.epoch = epoch;
        }
    }

    private static final class StaleCreationException extends RuntimeException {
        private StaleCreationException(String name) {
            super("BloomFilter creation invalidated for [" + name + "]", null, false, false);
        }

        private static StaleCreationException invalidated(String name) {
            return new StaleCreationException(name);
        }
    }
}
