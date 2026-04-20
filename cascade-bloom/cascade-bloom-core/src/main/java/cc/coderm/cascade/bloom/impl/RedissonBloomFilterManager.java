package cc.coderm.cascade.bloom.impl;


import cc.coderm.cascade.bloom.config.BloomFilterProperties;
import cc.coderm.cascade.bloom.core.BloomFilterManager;
import cc.coderm.cascade.bloom.core.CascadeBloomFilter;
import cc.coderm.cascade.bloom.exception.BloomFilterException;
import cc.coderm.cascade.bloom.exception.BloomFilterNotFoundException;
import cc.coderm.cascade.bloom.util.BloomFilterArgumentValidator;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

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
    private final String filterRegistryKey;
    /**
     * 预定义过滤器参数索引（按规范化名称）。
     * <p>
     * 用于保证 getFilter(name) 在“缓存 miss + Redis key 缺失”重建场景下，
     * 仍优先使用配置文件中的 filters[name] 参数，而不是全局默认值。
     */
    private final Map<String, FilterCreateConfig> predefinedFilterConfigs;

    /**
     * LRU 缓存，按访问顺序排序（最近访问的在末尾）
     */
    private final LinkedHashMap<String, CascadeBloomFilter<?>> filterCache;
    /**
     * 最近发生的本地缓存缺失提示（仅用于语义化日志）。
     * <p>
     * 例如：过滤器因 LRU 被逐出后，remove() 再次访问本地缓存会 miss，
     * 通过该提示可以区分“LRU 逐出”与“从未在本地缓存出现”。
     */
    private final LinkedHashMap<String, CacheMissHint> recentCacheMissHints;

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
    /**
     * 缓存条目的存在性探测状态（探测窗口 + 单飞探测）。
     */
    private final ConcurrentHashMap<String, CacheValidationState> cacheValidationStates = new ConcurrentHashMap<>();
    /**
     * 过滤器存在性快照状态（管理器视角，最终一致）。
     */
    private final ConcurrentHashMap<String, NameExistenceState> nameExistenceStates = new ConcurrentHashMap<>();
    /**
     * 缓存命中后，多久探测一次 Redis 是否仍存在（纳秒）。
     */
    private final long cacheExistenceProbeIntervalNanos;
    /**
     * 缓存命中后的 Redis 存在性探测超时（毫秒）。
     */
    private final long cacheExistenceProbeTimeoutMillis;

    private static final int INITIAL_CAPACITY = 16;
    private static final float LOAD_FACTOR = 0.75f;
    private static final long EXISTENCE_PROBE_FAILURE_BACKOFF_NANOS = TimeUnit.SECONDS.toNanos(1L);
    private static final String FILTER_REGISTRY_KEY_PREFIX = "__cascade_bloom__:registered_filters:";
    private static final int CACHE_MISS_HINT_CAPACITY_MULTIPLIER = 4;
    private final int maxCacheMissHintSize;

    public RedissonBloomFilterManager(RedissonClient redissonClient, BloomFilterProperties properties) {
        this.redissonClient = redissonClient;
        this.properties = properties;
        this.filterRegistryKey = buildFilterRegistryKey(properties.getKeyPrefix());
        this.predefinedFilterConfigs = buildPredefinedFilterConfigIndex(properties);
        this.filterCache = new LinkedHashMap<>(INITIAL_CAPACITY, LOAD_FACTOR, true);
        this.recentCacheMissHints = new LinkedHashMap<>(INITIAL_CAPACITY, LOAD_FACTOR, true);
        long configuredHintSize = (long) properties.getMaxCacheSize() * CACHE_MISS_HINT_CAPACITY_MULTIPLIER;
        this.maxCacheMissHintSize = (int) Math.min(Integer.MAX_VALUE, Math.max((long) INITIAL_CAPACITY, configuredHintSize));
        this.cacheExistenceProbeIntervalNanos = TimeUnit.MILLISECONDS.toNanos(properties.getCacheExistenceProbeIntervalMillis());
        this.cacheExistenceProbeTimeoutMillis = properties.getCacheExistenceProbeTimeoutMillis();

        log.info("[cascade-bloom] BloomFilterManager initialized with maxCacheSize={}, cacheExistenceProbeIntervalMillis={}, cacheExistenceProbeTimeoutMillis={}, filterRegistryKey={}, predefinedFilterConfigCount={}",
                properties.getMaxCacheSize(),
                properties.getCacheExistenceProbeIntervalMillis(),
                cacheExistenceProbeTimeoutMillis,
                filterRegistryKey,
                predefinedFilterConfigs.size());
    }

    @Override
    public <T> CascadeBloomFilter<T> getFilter(String name) {
        String normalizedName = BloomFilterArgumentValidator.normalizeFilterName(name);
        FilterCreateConfig createConfig = resolveCreateConfig(normalizedName);
        CascadeBloomFilter<?> filter = getFromCacheOrCompute(
                normalizedName,
                createConfig.expectedInsertions,
                createConfig.falseProbability,
                ConfigConsistencyPolicy.RELAXED,
                filterName -> createNewFilter(
                        filterName,
                        createConfig.expectedInsertions,
                        createConfig.falseProbability,
                        ConfigConsistencyPolicy.RELAXED
                )
        );
        return (CascadeBloomFilter<T>) filter;
    }

    @Override
    public <T> CascadeBloomFilter<T> getOrCreate(String name, long expectedInsertions, double falseProbability) {
        String normalizedName = BloomFilterArgumentValidator.normalizeFilterName(name);
        BloomFilterArgumentValidator.validateCreateParams(expectedInsertions, falseProbability);

        CascadeBloomFilter<?> filter = getFromCacheOrCompute(normalizedName,
                expectedInsertions,
                falseProbability,
                ConfigConsistencyPolicy.STRICT,
                filterName -> createNewFilter(filterName, expectedInsertions, falseProbability, ConfigConsistencyPolicy.STRICT)
        );

        return (CascadeBloomFilter<T>) filter;
    }

    @Override
    public boolean exists(String name) {
        String normalizedName = BloomFilterArgumentValidator.normalizeFilterName(name);
        CascadeBloomFilter<?> cached = getValidatedCachedFilter(normalizedName);
        if (cached != null) {
            markNameExistence(normalizedName, true);
            return true;
        }
        return existsByRegistrySnapshot(normalizedName);
    }

    @Override
    public boolean existsInRedis(String name) {
        String normalizedName = BloomFilterArgumentValidator.normalizeFilterName(name);
        boolean redisExists = redissonClient.<String>getBloomFilter(buildRedisKey(normalizedName)).isExists();
        if (!redisExists) {
            evictStaleLocalCacheIfPresent(normalizedName);
            unregisterFilterNameQuietly(normalizedName);
            markNameExistence(normalizedName, false);
            return false;
        }
        markNameExistence(normalizedName, true);
        return true;
    }

    @Override
    public void remove(String name) {
        String normalizedName = BloomFilterArgumentValidator.normalizeFilterName(name);
        long preDeleteEpoch = incrementEpoch(normalizedName);
        invalidateInFlightCreation(normalizedName, preDeleteEpoch);
        CacheLookupResult cacheLookupResult = getCachedFilterWithMissHint(normalizedName);
        CascadeBloomFilter<?> cachedFilter = cacheLookupResult.cachedFilter;

        if (cachedFilter != null) {
            removeCachedFilter(normalizedName, cachedFilter);
        } else {
            removeFromRedisDirectly(normalizedName, cacheLookupResult.missHint);
        }

        // 删除成功后再次提升 epoch，阻断“删除窗口”内启动的创建结果回写本地缓存。
        long postDeleteEpoch = incrementEpoch(normalizedName);
        invalidateInFlightCreation(normalizedName, postDeleteEpoch);
        removeLocalCacheEntry(normalizedName);
    }

    @Override
    public Set<String> listCachedFilterNames() {
        synchronized (cacheLock) {
            return new HashSet<>(filterCache.keySet());
        }
    }

    @Override
    public Set<String> listRegisteredFilterNames() {
        try {
            return new HashSet<>(registrySet().readAll());
        } catch (RuntimeException exception) {
            throw new BloomFilterException(
                    "Failed to list registered bloom filters from Redis registry key [" + filterRegistryKey + "]",
                    exception
            );
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
            cacheValidationStates.clear();
            nameExistenceStates.clear();
            recentCacheMissHints.clear();
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
    private CascadeBloomFilter<?> getFromCacheOrCompute(String name,
                                                        long expectedInsertions,
                                                        double falseProbability,
                                                        ConfigConsistencyPolicy consistencyPolicy,
                                                        Function<String, CascadeBloomFilter<?>> computer) {
        boolean missRecorded = false;
        while (true) {
            long observedEpoch = currentEpoch(name);

            CascadeBloomFilter<?> cached = getValidatedCachedFilter(name);
            if (cached != null) {
                assertFilterConfigConsistentIfRequired(name, expectedInsertions, falseProbability, cached, "cache", consistencyPolicy);
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
                    CascadeBloomFilter<?> createdByPeer = awaitInFlightCreation(name, inFlight);
                    assertFilterConfigConsistentIfRequired(
                            name,
                            expectedInsertions,
                            falseProbability,
                            createdByPeer,
                            "in-flight",
                            consistencyPolicy
                    );
                    return createdByPeer;
                } catch (StaleCreationException ignored) {
                    continue;
                } catch (ConfigMismatchException configMismatchException) {
                    if (consistencyPolicy.shouldEnforceConfigConsistency()) {
                        throw configMismatchException;
                    }
                    continue;
                }
            }

            InFlightCreation candidate = new InFlightCreation(observedEpoch);
            InFlightCreation existing = inFlightCreations.putIfAbsent(name, candidate);
            if (existing != null) {
                continue;
            }
            try {
                CascadeBloomFilter<?> created = createAndPublishFilter(name, observedEpoch, computer, candidate);
                assertFilterConfigConsistentIfRequired(name, expectedInsertions, falseProbability, created, "created", consistencyPolicy);
                return created;
            } catch (StaleCreationException ignored) {
                continue;
            } catch (ConfigMismatchException configMismatchException) {
                if (consistencyPolicy.shouldEnforceConfigConsistency()) {
                    throw configMismatchException;
                }
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
        boolean inserted = false;
        synchronized (cacheLock) {
            long latestEpoch = currentEpoch(name);
            stale = latestEpoch != observedEpoch;
            cached = filterCache.get(name);
            if (!stale && cached == null) {
                ensureCapacityBeforeCreate();
                filterCache.put(name, created);
                clearCacheMissHint(name);
                cached = created;
                inserted = true;
            }
        }
        if (inserted) {
            markCacheEntryFresh(name);
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

    private CacheLookupResult getCachedFilterWithMissHint(String name) {
        synchronized (cacheLock) {
            CascadeBloomFilter<?> cached = filterCache.get(name);
            if (cached != null) {
                clearCacheMissHint(name);
                return CacheLookupResult.cached(cached);
            }
            CacheMissHint missHint = consumeCacheMissHint(name);
            return CacheLookupResult.miss(missHint == null ? CacheMissHint.NOT_IN_LOCAL_CACHE : missHint);
        }
    }

    /**
     * 获取已通过 Redis 存在性校验的缓存过滤器。
     * <p>
     * 防止本地缓存命中但 Redis key 已被外部删除导致返回陈旧实例。
     */
    private CascadeBloomFilter<?> getValidatedCachedFilter(String name) {
        CascadeBloomFilter<?> cached = getCachedFilter(name);
        if (cached == null) {
            cacheValidationStates.remove(name);
            return null;
        }

        ValidationProbeTicket probeTicket = tryAcquireValidationProbe(name);
        if (probeTicket == null) {
            return cached;
        }
        try {
            ProbeOutcome probeOutcome = probeCachedFilterExistence(name, cached);
            if (probeOutcome == ProbeOutcome.EXISTS) {
                scheduleNextProbe(probeTicket.state, probeTicket.nowNanos, cacheExistenceProbeIntervalNanos);
                return cached;
            }
            if (probeOutcome == ProbeOutcome.UNKNOWN) {
                scheduleNextProbe(probeTicket.state, probeTicket.nowNanos, EXISTENCE_PROBE_FAILURE_BACKOFF_NANOS);
                return cached;
            }
            evictCachedFilterIfSame(name, cached);
            unregisterFilterNameQuietly(name);
            CascadeBloomFilter<?> refreshed = getCachedFilter(name);
            if (refreshed == null) {
                cacheValidationStates.remove(name);
            }
            return refreshed;
        } finally {
            probeTicket.state.probing.set(false);
        }
    }

    private ValidationProbeTicket tryAcquireValidationProbe(String name) {
        CacheValidationState state = cacheValidationStates.computeIfAbsent(name, key -> new CacheValidationState(0L));
        long now = System.nanoTime();
        if (cacheExistenceProbeIntervalNanos > 0 && now < state.nextProbeAtNanos) {
            return null;
        }
        if (!state.probing.compareAndSet(false, true)) {
            return null;
        }
        long acquiredAt = System.nanoTime();
        if (cacheExistenceProbeIntervalNanos > 0 && acquiredAt < state.nextProbeAtNanos) {
            state.probing.set(false);
            return null;
        }
        return new ValidationProbeTicket(state, acquiredAt);
    }

    private ProbeOutcome probeCachedFilterExistence(String name, CascadeBloomFilter<?> cachedFilter) {
        try {
            boolean exists = checkCachedFilterExistenceWithTimeout(cachedFilter);
            if (!exists) {
                log.warn("[cascade-bloom] Cached bloom filter [{}] no longer exists in Redis, evicting stale local instance", name);
                return ProbeOutcome.MISSING;
            }
            return ProbeOutcome.EXISTS;
        } catch (RuntimeException exception) {
            log.warn("[cascade-bloom] Failed to probe cached bloom filter [{}], keeping local instance and retrying later", name, exception);
            return ProbeOutcome.UNKNOWN;
        }
    }

    private boolean checkCachedFilterExistenceWithTimeout(CascadeBloomFilter<?> cachedFilter) {
        if (cachedFilter instanceof RedissonBloomFilter<?> redissonBloomFilter) {
            return redissonBloomFilter.isExistsWithinTimeout(cacheExistenceProbeTimeoutMillis);
        }
        return cachedFilter.isExists();
    }

    private void scheduleNextProbe(CacheValidationState state, long nowNanos, long delayNanos) {
        state.nextProbeAtNanos = delayNanos <= 0L ? nowNanos : saturatingAdd(nowNanos, delayNanos);
    }

    private void scheduleNextExistenceProbe(NameExistenceState state, long nowNanos, long delayNanos) {
        state.nextProbeAtNanos = delayNanos <= 0L ? nowNanos : saturatingAdd(nowNanos, delayNanos);
    }

    private void markCacheEntryFresh(String name) {
        CacheValidationState state = cacheValidationStates.computeIfAbsent(name, key -> new CacheValidationState(0L));
        scheduleNextProbe(state, System.nanoTime(), cacheExistenceProbeIntervalNanos);
    }

    private static long saturatingAdd(long left, long right) {
        long result = left + right;
        if (((left ^ result) & (right ^ result)) < 0) {
            return Long.MAX_VALUE;
        }
        return result;
    }

    private void evictCachedFilterIfSame(String name, CascadeBloomFilter<?> expected) {
        synchronized (cacheLock) {
            CascadeBloomFilter<?> current = filterCache.get(name);
            if (current == expected) {
                filterCache.remove(name);
                cacheValidationStates.remove(name);
                log.warn("[cascade-bloom] BloomFilter [{}] stale cache entry removed", name);
            }
        }
    }

    private boolean existsByRegistrySnapshot(String name) {
        NameExistenceState state = nameExistenceStates.computeIfAbsent(name, key -> new NameExistenceState(false, 0L));
        long now = System.nanoTime();
        if (cacheExistenceProbeIntervalNanos > 0 && now < state.nextProbeAtNanos) {
            return state.exists;
        }
        if (!state.probing.compareAndSet(false, true)) {
            return state.exists;
        }
        long acquiredAt = System.nanoTime();
        if (cacheExistenceProbeIntervalNanos > 0 && acquiredAt < state.nextProbeAtNanos) {
            state.probing.set(false);
            return state.exists;
        }
        try {
            boolean exists = probeBloomExistence(name);
            state.exists = exists;
            scheduleNextExistenceProbe(state, acquiredAt, cacheExistenceProbeIntervalNanos);
            if (!exists) {
                unregisterFilterNameQuietly(name);
            }
            return exists;
        } catch (RuntimeException exception) {
            scheduleNextExistenceProbe(state, acquiredAt, EXISTENCE_PROBE_FAILURE_BACKOFF_NANOS);
            log.warn("[cascade-bloom] Failed to probe redis existence for bloom filter [{}], returning last known value={}",
                    name, state.exists, exception);
            return state.exists;
        } finally {
            state.probing.set(false);
        }
    }

    private boolean probeBloomExistence(String name) {
        return redissonClient.<String>getBloomFilter(buildRedisKey(name)).isExists();
    }

    private void markNameExistence(String name, boolean exists) {
        NameExistenceState state = nameExistenceStates.computeIfAbsent(name, key -> new NameExistenceState(exists, 0L));
        state.exists = exists;
        scheduleNextExistenceProbe(state, System.nanoTime(), cacheExistenceProbeIntervalNanos);
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
            cacheValidationStates.remove(evictedName);
            rememberCacheMissHint(evictedName, CacheMissHint.EVICTED_BY_LRU);
            evictions.incrementAndGet();
            log.warn("[cascade-bloom] Cache full (size={} >= maxCacheSize={}), evicting oldest filter [{}]",
                    currentSize, maxSize, evictedName);
        }
    }

    /**
     * 删除本地缓存条目并统一清理附属状态。
     * <p>
     * 无论是否命中缓存，都会清理存在性探测状态与 miss-hint，确保状态收敛。
     *
     * @return {@code true} 表示缓存中存在并已删除；{@code false} 表示原本不存在
     */
    private boolean removeLocalCacheEntry(String name) {
        synchronized (cacheLock) {
            boolean removed = filterCache.remove(name) != null;
            cacheValidationStates.remove(name);
            clearCacheMissHint(name);
            return removed;
        }
    }

    private void evictStaleLocalCacheIfPresent(String name) {
        if (removeLocalCacheEntry(name)) {
            log.warn("[cascade-bloom] BloomFilter [{}] not found in Redis, evicted stale local cache entry", name);
        }
    }

    private void rememberCacheMissHint(String name, CacheMissHint hint) {
        recentCacheMissHints.put(name, hint);
        trimCacheMissHintsIfNecessary();
    }

    private CacheMissHint consumeCacheMissHint(String name) {
        return recentCacheMissHints.remove(name);
    }

    private void clearCacheMissHint(String name) {
        recentCacheMissHints.remove(name);
    }

    private void trimCacheMissHintsIfNecessary() {
        while (recentCacheMissHints.size() > maxCacheMissHintSize) {
            Map.Entry<String, CacheMissHint> eldest = recentCacheMissHints.entrySet().iterator().next();
            recentCacheMissHints.remove(eldest.getKey());
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

    private void assertFilterConfigConsistent(String name,
                                              long requestedExpectedInsertions,
                                              double requestedFalseProbability,
                                              CascadeBloomFilter<?> actualFilter,
                                              String source) {
        assertFilterConfigConsistent(
                name,
                requestedExpectedInsertions,
                requestedFalseProbability,
                actualFilter.getExpectedInsertions(),
                actualFilter.getFalseProbability(),
                source
        );
    }

    private void assertFilterConfigConsistent(String name,
                                              long requestedExpectedInsertions,
                                              double requestedFalseProbability,
                                              long actualExpectedInsertions,
                                              double actualFalseProbability,
                                              String source) {
        if (requestedExpectedInsertions == actualExpectedInsertions
                && Double.compare(requestedFalseProbability, actualFalseProbability) == 0) {
            return;
        }
        throw new ConfigMismatchException(
                "BloomFilter [" + name + "] configuration mismatch. "
                        + "requested(expectedInsertions=" + requestedExpectedInsertions
                        + ", falseProbability=" + requestedFalseProbability + "), "
                        + "actual(expectedInsertions=" + actualExpectedInsertions
                        + ", falseProbability=" + actualFalseProbability + "), "
                + "source=" + source
        );
    }

    private void assertFilterConfigConsistentIfRequired(String name,
                                                        long requestedExpectedInsertions,
                                                        double requestedFalseProbability,
                                                        CascadeBloomFilter<?> actualFilter,
                                                        String source,
                                                        ConfigConsistencyPolicy consistencyPolicy) {
        if (!consistencyPolicy.shouldEnforceConfigConsistency()) {
            return;
        }
        assertFilterConfigConsistent(name, requestedExpectedInsertions, requestedFalseProbability, actualFilter, source);
    }

    /**
     * 创建新的布隆过滤器
     */
    private CascadeBloomFilter<?> createNewFilter(String name,
                                                  long expectedInsertions,
                                                  double falseProbability,
                                                  ConfigConsistencyPolicy consistencyPolicy) {
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
            if (consistencyPolicy.shouldEnforceConfigConsistency()) {
                assertFilterConfigConsistent(
                        name,
                        expectedInsertions,
                        falseProbability,
                        effectiveExpectedInsertions,
                        effectiveFalseProbability,
                        "redis"
                );
            }
            log.info("[cascade-bloom] BloomFilter [{}] already exists in Redis, reusing. key={}, expectedInsertions={}, falseProbability={}",
                    name, redisKey, effectiveExpectedInsertions, effectiveFalseProbability);
        }

        registerFilterName(name);

        return new RedissonBloomFilter<>(stringBloomFilter, redissonClient, name, effectiveExpectedInsertions, effectiveFalseProbability);
    }

    /**
     * 删除已缓存的过滤器
     */
    private void removeCachedFilter(String name, CascadeBloomFilter<?> filter) {
        try {
            filter.delete();
            unregisterFilterName(name);
            removeLocalCacheEntry(name);
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
    private void removeFromRedisDirectly(String name, CacheMissHint missHint) {
        String redisKey = buildRedisKey(name);
        RBloomFilter<String> stringBloomFilter = redissonClient.<String>getBloomFilter(redisKey);

        if (!stringBloomFilter.isExists()) {
            unregisterFilterNameQuietly(name);
            throw new BloomFilterNotFoundException(name);
        }

        try {
            stringBloomFilter.delete();
            unregisterFilterName(name);
            if (missHint == CacheMissHint.EVICTED_BY_LRU) {
                log.info("[cascade-bloom] BloomFilter [{}] removed from Redis (local cache miss due to prior LRU eviction)", name);
            } else {
                log.info("[cascade-bloom] BloomFilter [{}] removed from Redis (not present in local cache)", name);
            }
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

    private static String buildFilterRegistryKey(String keyPrefix) {
        return FILTER_REGISTRY_KEY_PREFIX + keyPrefix;
    }

    private static Map<String, FilterCreateConfig> buildPredefinedFilterConfigIndex(BloomFilterProperties properties) {
        Map<String, FilterCreateConfig> index = new ConcurrentHashMap<>();
        long defaultExpectedInsertions = properties.getDefaultExpectedInsertions();
        double defaultFalseProbability = properties.getDefaultFalseProbability();
        for (BloomFilterProperties.BloomFilterDefinition definition : properties.getFilters()) {
            if (definition == null) {
                continue;
            }
            String normalizedName = BloomFilterArgumentValidator.normalizeFilterName(definition.getName());
            long expectedInsertions = definition.getExpectedInsertions() == null
                    ? defaultExpectedInsertions
                    : definition.getExpectedInsertions();
            double falseProbability = definition.getFalseProbability() == null
                    ? defaultFalseProbability
                    : definition.getFalseProbability();
            BloomFilterArgumentValidator.validateCreateParams(expectedInsertions, falseProbability);
            index.put(normalizedName, new FilterCreateConfig(expectedInsertions, falseProbability));
        }
        return Map.copyOf(index);
    }

    private FilterCreateConfig resolveCreateConfig(String normalizedName) {
        FilterCreateConfig predefinedConfig = predefinedFilterConfigs.get(normalizedName);
        if (predefinedConfig != null) {
            return predefinedConfig;
        }
        return new FilterCreateConfig(
                properties.getDefaultExpectedInsertions(),
                properties.getDefaultFalseProbability()
        );
    }

    private RSet<String> registrySet() {
        return redissonClient.getSet(filterRegistryKey);
    }

    private void registerFilterName(String name) {
        try {
            registrySet().add(name);
            markNameExistence(name, true);
        } catch (RuntimeException exception) {
            throw new BloomFilterException(
                    "Failed to register bloom filter [" + name + "] into Redis registry key [" + filterRegistryKey + "]",
                    exception
            );
        }
    }

    private void unregisterFilterName(String name) {
        try {
            registrySet().remove(name);
            markNameExistence(name, false);
        } catch (RuntimeException exception) {
            throw new BloomFilterException(
                    "Failed to unregister bloom filter [" + name + "] from Redis registry key [" + filterRegistryKey + "]",
                    exception
            );
        }
    }

    private void unregisterFilterNameQuietly(String name) {
        try {
            registrySet().remove(name);
            markNameExistence(name, false);
        } catch (RuntimeException exception) {
            log.warn("[cascade-bloom] Failed to unregister bloom filter [{}] from registry key [{}] in best-effort mode",
                    name, filterRegistryKey, exception);
        }
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

    private static final class CacheValidationState {
        private volatile long nextProbeAtNanos;
        private final AtomicBoolean probing = new AtomicBoolean(false);

        private CacheValidationState(long nextProbeAtNanos) {
            this.nextProbeAtNanos = nextProbeAtNanos;
        }
    }

    private static final class NameExistenceState {
        private volatile boolean exists;
        private volatile long nextProbeAtNanos;
        private final AtomicBoolean probing = new AtomicBoolean(false);

        private NameExistenceState(boolean exists, long nextProbeAtNanos) {
            this.exists = exists;
            this.nextProbeAtNanos = nextProbeAtNanos;
        }
    }

    private static final class ValidationProbeTicket {
        private final CacheValidationState state;
        private final long nowNanos;

        private ValidationProbeTicket(CacheValidationState state, long nowNanos) {
            this.state = state;
            this.nowNanos = nowNanos;
        }
    }

    private enum ProbeOutcome {
        EXISTS,
        MISSING,
        UNKNOWN
    }

    private enum ConfigConsistencyPolicy {
        STRICT,
        RELAXED;

        private boolean shouldEnforceConfigConsistency() {
            return this == STRICT;
        }
    }

    private static final class ConfigMismatchException extends BloomFilterException {
        private ConfigMismatchException(String message) {
            super(message);
        }
    }

    private enum CacheMissHint {
        EVICTED_BY_LRU,
        NOT_IN_LOCAL_CACHE
    }

    private static final class CacheLookupResult {
        private final CascadeBloomFilter<?> cachedFilter;
        private final CacheMissHint missHint;

        private CacheLookupResult(CascadeBloomFilter<?> cachedFilter, CacheMissHint missHint) {
            this.cachedFilter = cachedFilter;
            this.missHint = missHint;
        }

        private static CacheLookupResult cached(CascadeBloomFilter<?> cachedFilter) {
            return new CacheLookupResult(cachedFilter, null);
        }

        private static CacheLookupResult miss(CacheMissHint missHint) {
            return new CacheLookupResult(null, missHint);
        }
    }

    private static final class FilterCreateConfig {
        private final long expectedInsertions;
        private final double falseProbability;

        private FilterCreateConfig(long expectedInsertions, double falseProbability) {
            this.expectedInsertions = expectedInsertions;
            this.falseProbability = falseProbability;
        }
    }
}
