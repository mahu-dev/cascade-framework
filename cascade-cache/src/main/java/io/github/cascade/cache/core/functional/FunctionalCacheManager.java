package io.github.cascade.cache.core.functional;

import io.github.cascade.cache.api.Cache;
import io.github.cascade.cache.api.CacheLoader;
import io.github.cascade.cache.api.CacheManager.CacheBuilder;
import io.github.cascade.cache.api.CacheManager.CacheBuilderKeyStage;
import io.github.cascade.cache.api.CacheManager.CacheBuilderValueStage;
import io.github.cascade.cache.api.CacheManager;
import io.github.cascade.cache.common.exception.CacheConfigurationException;
import io.github.cascade.cache.common.exception.CacheException;
import io.github.cascade.cache.configuration.CascadeCacheProperties;
import io.github.cascade.cache.core.distributed.NodeIdManager;
import io.github.cascade.cache.synchronization.CacheLoaderResolver;
import io.github.cascade.cache.v2.consistency.LocalVersionManager;
import io.github.cascade.cache.v2.consistency.RedisVersionManager;
import io.github.cascade.cache.v2.consistency.VersionManager;
import io.github.cascade.cache.v2.core.EngineBackedCache;
import io.github.cascade.cache.v2.loader.DistLockCoordinator;
import io.github.cascade.cache.v2.loader.RedisDistLockCoordinator;
import io.github.cascade.cache.v2.observability.CacheMetricsCollector;
import io.github.cascade.cache.v2.policy.CachePolicy;
import io.github.cascade.cache.v2.policy.SyncMode;
import io.github.cascade.cache.v2.store.CaffeineL1Store;
import io.github.cascade.cache.v2.store.L1CacheStore;
import io.github.cascade.cache.v2.store.L2CacheStore;
import io.github.cascade.cache.v2.store.RedissonL2Store;
import io.github.cascade.cache.v2.sync.InvalidationBus;
import io.github.cascade.cache.v2.sync.RedisInvalidationBus;
import lombok.Getter;
import io.micrometer.core.instrument.MeterRegistry;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * V2 函数式缓存管理器。
 *
 * 说明：
 * 1. 注解式与编程式都经由同一内核 EngineBackedCache。
 * 2. 默认支持回填、自动刷新、失效同步（配置可关闭）。
 */
public class FunctionalCacheManager implements CacheManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(FunctionalCacheManager.class);

    private final ConcurrentHashMap<String, Cache<?, ?>> cacheRegistry = new ConcurrentHashMap<>();
    private final RedissonClient redissonClient;
    private final CascadeCacheProperties defaultConfig;
    private final CacheLoaderResolver<?, ?> loaderResolver;
    private final MeterRegistry meterRegistry;

    @Getter
    private final String nodeId;

    private final AtomicBoolean closed = new AtomicBoolean(false);

    public FunctionalCacheManager(
            RedissonClient redissonClient,
            CascadeCacheProperties defaultConfig,
            CacheLoaderResolver<?, ?> loaderResolver
    ) {
        this(redissonClient, defaultConfig, loaderResolver, null);
    }

    public FunctionalCacheManager(
            RedissonClient redissonClient,
            CascadeCacheProperties defaultConfig,
            CacheLoaderResolver<?, ?> loaderResolver,
            MeterRegistry meterRegistry
    ) {
        this.redissonClient = redissonClient;
        this.defaultConfig = defaultConfig != null ? defaultConfig : CascadeCacheProperties.defaults();
        this.loaderResolver = loaderResolver;
        this.meterRegistry = meterRegistry;
        this.nodeId = NodeIdManager.getInstance().getNodeId();
        LOGGER.info("V2缓存管理器初始化完成: nodeId={}", nodeId);
    }

    @Override
    public <K, V> Cache<K, V> getOrCreateCache(String cacheName, Class<K> keyType, Class<V> valueType) {
        return getOrCreateCache(cacheName, keyType, valueType, defaultConfig, null);
    }

    @Override
    public <K, V> Cache<K, V> getOrCreateCache(String cacheName,
                                               Class<K> keyType,
                                               Class<V> valueType,
                                               CascadeCacheProperties config) {
        return getOrCreateCache(cacheName, keyType, valueType, config, null);
    }

    @Override
    public <K, V> Cache<K, V> getOrCreateCache(String cacheName,
                                               Class<K> keyType,
                                               Class<V> valueType,
                                               Function<K, V> loader) {
        return getOrCreateCache(cacheName, keyType, valueType, defaultConfig, loader);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <K, V> Cache<K, V> getOrCreateCache(String cacheName,
                                               Class<K> keyType,
                                               Class<V> valueType,
                                               CascadeCacheProperties config,
                                               Function<K, V> loader) {
        checkNotClosed();
        validateParameters(cacheName, keyType, valueType);

        CascadeCacheProperties finalConfig = config != null ? config : defaultConfig;
        Function<K, V> finalLoader = resolveLoader(keyType, valueType, finalConfig, loader);

        Cache<K, V> cache = (Cache<K, V>) cacheRegistry.computeIfAbsent(cacheName, name ->
                createCache(name, finalConfig, finalLoader));

        if (cache instanceof EngineBackedCache<?, ?> && finalLoader != null) {
            ((EngineBackedCache<K, V>) cache).setLoaderIfAbsent(finalLoader);
        }
        return cache;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <K, V> Cache<K, V> getCache(String cacheName) {
        checkNotClosed();
        return (Cache<K, V>) cacheRegistry.get(cacheName);
    }

    @Override
    public <K, V> boolean registerCache(String cacheName, Cache<K, V> cache) {
        checkNotClosed();
        if (cacheName == null || cacheName.trim().isEmpty()) {
            throw new CacheConfigurationException("cacheName", cacheName, "缓存名称不能为空", null);
        }
        if (cache == null) {
            throw new CacheConfigurationException("cache", null, "缓存实例不能为空", null);
        }

        Cache<?, ?> previous = cacheRegistry.put(cacheName, cache);
        if (previous != null && !previous.isClosed()) {
            previous.close();
            return false;
        }
        return true;
    }

    @Override
    public boolean removeCache(String cacheName) {
        checkNotClosed();
        Cache<?, ?> cache = cacheRegistry.remove(cacheName);
        if (cache != null) {
            try {
                cache.close();
            } catch (Exception e) {
                LOGGER.warn("关闭缓存失败: cache={}, error={}", cacheName, e.getMessage());
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean containsCache(String cacheName) {
        checkNotClosed();
        return cacheRegistry.containsKey(cacheName);
    }

    @Override
    public Collection<String> getCacheNames() {
        checkNotClosed();
        return cacheRegistry.keySet();
    }

    @Override
    public void clearAll() {
        checkNotClosed();
        cacheRegistry.values().forEach(cache -> {
            try {
                cache.clear();
            } catch (Exception e) {
                LOGGER.warn("清空缓存失败: cache={}, error={}", cache.getName(), e.getMessage());
            }
        });
    }

    @Override
    public int getCacheCount() {
        checkNotClosed();
        return cacheRegistry.size();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        cacheRegistry.values().forEach(cache -> {
            try {
                cache.close();
            } catch (Exception e) {
                LOGGER.warn("关闭缓存失败: cache={}, error={}", cache.getName(), e.getMessage());
            }
        });
        cacheRegistry.clear();
    }

    @Override
    public boolean isClosed() {
        return closed.get();
    }

    @Override
    public Map<String, Object> diagnostics(String cacheName) {
        checkNotClosed();
        Cache<?, ?> cache = cacheRegistry.get(cacheName);
        if (cache == null) {
            return Map.of("cacheName", cacheName, "exists", false);
        }
        if (cache instanceof EngineBackedCache<?, ?> engineBackedCache) {
            Map<String, Object> result = new LinkedHashMap<>(engineBackedCache.diagnosticsSnapshot());
            result.put("exists", true);
            return result;
        }
        return Map.of("cacheName", cacheName, "exists", true, "type", cache.getClass().getName());
    }

    @Override
    public CacheBuilderKeyStage newCache(String cacheName) {
        checkNotClosed();
        return new CacheBuilderKeyStage() {
            @Override
            public <K> CacheBuilderValueStage<K> keyType(Class<K> keyType) {
                return new CacheBuilderValueStage<>() {
                    @Override
                    public <V> CacheBuilder<K, V> valueType(Class<V> valueType) {
                        return new BuilderImpl<>(cacheName, keyType, valueType);
                    }
                };
            }
        };
    }

    private <K, V> Cache<K, V> createCache(String cacheName,
                                           CascadeCacheProperties config,
                                           Function<K, V> loader) {
        CachePolicy policy = buildPolicy(config);

        L1CacheStore<K, V> l1Store = null;
        if (policy.isL1Enabled()) {
            l1Store = new CaffeineL1Store<>(config.getL1MaxSize(), config.isStatsEnabled());
        }

        L2CacheStore<K, V> l2Store = null;
        if (policy.isL2Enabled()) {
            l2Store = new RedissonL2Store<>(cacheName, redissonClient, config.getL2KeyPrefix());
        }

        if (l1Store == null && l2Store == null) {
            throw new CacheConfigurationException("layers", null, "至少启用一个缓存层（L1或L2）", null);
        }

        InvalidationBus<K> bus = createInvalidationBus(policy, config);
        VersionManager<K> versionManager = createVersionManager(cacheName, config);
        DistLockCoordinator<K> lockCoordinator = createDistLockCoordinator(cacheName, policy, config);
        CacheMetricsCollector metricsCollector = CacheMetricsCollector.create(cacheName, meterRegistry);
        EngineBackedCache<K, V> cache = new EngineBackedCache<>(
                cacheName, policy, l1Store, l2Store, loader, bus, versionManager, lockCoordinator, nodeId,
                metricsCollector
        );
        LOGGER.info("V2缓存创建完成: cache={}, l1={}, l2={}, sync={}, refresh={}",
                cacheName, policy.isL1Enabled(), policy.isL2Enabled(),
                policy.getSyncMode(), policy.isAutoRefreshEnabled());
        return cache;
    }

    private CachePolicy buildPolicy(CascadeCacheProperties config) {
        long hardTtl = config.getL2DefaultTtlSeconds();
        long softTtl = config.getSoftTtlSeconds();
        if (softTtl <= 0) {
            softTtl = hardTtl > 0 ? Math.max(1, hardTtl / 3) : 300;
        }

        boolean l2Enabled = config.isL2Enabled() && redissonClient != null;
        SyncMode configuredSyncMode = config.getSyncConfig().getMode();
        SyncMode syncMode = config.isSyncEnabled() && l2Enabled
                ? (configuredSyncMode != null ? configuredSyncMode : SyncMode.INVALIDATE)
                : SyncMode.NONE;

        return CachePolicy.builder()
                .l1Enabled(config.isL1Enabled())
                .l2Enabled(l2Enabled)
                .hardTtlSeconds(hardTtl)
                .softTtlSeconds(softTtl)
                .refreshIntervalSeconds(Math.max(1, config.getRefresh().getDefaultRefreshIntervalSeconds()))
                .autoRefreshEnabled(config.isRefreshEnabled())
                .syncMode(syncMode)
                .singleFlightEnabled(config.isSingleFlightEnabled())
                .distributedLockEnabled(config.isDistributedLockEnabled())
                .distributedLockWaitMs(config.getDistributedLockWaitMs())
                .distributedLockLeaseMs(config.getDistributedLockLeaseMs())
                .hotKeyAccessThreshold(config.getHotKeyAccessThreshold())
                .maxTrackedKeys(config.getMaxTrackedKeys())
                .build();
    }

    private <K> InvalidationBus<K> createInvalidationBus(CachePolicy policy, CascadeCacheProperties config) {
        if (policy.getSyncMode() == SyncMode.NONE || redissonClient == null) {
            return InvalidationBus.noop();
        }
        return new RedisInvalidationBus<>(redissonClient, config.getSyncConfig().getTopicPrefix());
    }

    private <K> VersionManager<K> createVersionManager(String cacheName, CascadeCacheProperties config) {
        if (redissonClient == null || !config.isL2Enabled()) {
            return new LocalVersionManager<>();
        }
        return new RedisVersionManager<>(cacheName, redissonClient, config.getL2KeyPrefix());
    }

    private <K> DistLockCoordinator<K> createDistLockCoordinator(String cacheName,
                                                                 CachePolicy policy,
                                                                 CascadeCacheProperties config) {
        if (!policy.isDistributedLockEnabled() || redissonClient == null || !policy.isL2Enabled()) {
            return DistLockCoordinator.noop();
        }
        return new RedisDistLockCoordinator<>(cacheName, redissonClient, config.getL2KeyPrefix());
    }

    @SuppressWarnings("unchecked")
    private <K, V> Function<K, V> resolveLoader(Class<K> keyType,
                                                Class<V> valueType,
                                                CascadeCacheProperties config,
                                                Function<K, V> providedLoader) {
        if (providedLoader != null) {
            return providedLoader;
        }
        if (loaderResolver != null && config.getLoader().isAutoDiscover()) {
            try {
                CacheLoaderResolver<K, V> typedResolver = (CacheLoaderResolver<K, V>) loaderResolver;
                CacheLoader<K, V> resolved = typedResolver.resolveCacheLoader(keyType, valueType);
                return resolved != null ? resolved::apply : null;
            } catch (Exception e) {
                LOGGER.warn("自动发现加载器失败: keyType={}, valueType={}, error={}",
                        keyType.getSimpleName(), valueType.getSimpleName(), e.getMessage());
            }
        }
        return null;
    }

    private void checkNotClosed() {
        if (closed.get()) {
            throw new CacheException("管理器已关闭", null);
        }
    }

    private static <K, V> void validateParameters(String cacheName, Class<K> keyType, Class<V> valueType) {
        if (cacheName == null || cacheName.trim().isEmpty()) {
            throw new CacheConfigurationException("cacheName", cacheName, "缓存名称不能为空", null);
        }
        if (keyType == null || valueType == null) {
            throw new CacheConfigurationException("types", null, "键类型和值类型不能为空", null);
        }
    }

    private final class BuilderImpl<K, V> implements CacheBuilder<K, V> {

        private final String cacheName;
        private final Class<K> keyType;
        private final Class<V> valueType;
        private final CascadeCacheProperties config;
        private Function<K, V> loader;

        private BuilderImpl(String cacheName, Class<K> keyType, Class<V> valueType) {
            this.cacheName = cacheName;
            this.keyType = keyType;
            this.valueType = valueType;
            this.config = copyDefaultConfig(defaultConfig);
        }

        @Override
        public CacheBuilder<K, V> loader(Function<K, V> loader) {
            this.loader = loader;
            return this;
        }

        @Override
        public CacheBuilder<K, V> ttlSeconds(long ttlSeconds) {
            if (ttlSeconds > 0) {
                config.getL2().setDefaultTtlSeconds(ttlSeconds);
            }
            return this;
        }

        @Override
        public CacheBuilder<K, V> softTtlSeconds(long softTtlSeconds) {
            if (softTtlSeconds > 0) {
                config.getRefresh().setDefaultRefreshIntervalSeconds(softTtlSeconds);
            }
            return this;
        }

        @Override
        public CacheBuilder<K, V> syncMode(SyncMode syncMode) {
            config.getSync().setMode(syncMode != null ? syncMode : SyncMode.INVALIDATE);
            config.getSync().setEnabled(syncMode != SyncMode.NONE);
            return this;
        }

        @Override
        public CacheBuilder<K, V> autoRefresh(boolean enabled) {
            config.getRefresh().setEnabled(enabled);
            return this;
        }

        @Override
        public CacheBuilder<K, V> enableL1(boolean enabled) {
            config.getL1().setEnabled(enabled);
            return this;
        }

        @Override
        public CacheBuilder<K, V> enableL2(boolean enabled) {
            config.getL2().setEnabled(enabled);
            return this;
        }

        @Override
        public Cache<K, V> build() {
            return getOrCreateCache(cacheName, keyType, valueType, config, loader);
        }
    }

    private static CascadeCacheProperties copyDefaultConfig(CascadeCacheProperties source) {
        CascadeCacheProperties target = CascadeCacheProperties.defaults();
        target.setEnabled(source.isEnabled());
        target.setDefaultCacheName(source.getDefaultCacheName());

        target.getL1().setEnabled(source.getL1().isEnabled());
        target.getL1().setMaximumSize(source.getL1().getMaximumSize());
        target.getL1().setExpireAfterWriteSeconds(source.getL1().getExpireAfterWriteSeconds());
        target.getL1().setExpireAfterAccessSeconds(source.getL1().getExpireAfterAccessSeconds());
        target.getL1().setRecordStats(source.getL1().isRecordStats());
        target.getL1().setInitialCapacity(source.getL1().getInitialCapacity());
        target.getL1().setConcurrencyLevel(source.getL1().getConcurrencyLevel());

        target.getL2().setEnabled(source.getL2().isEnabled());
        target.getL2().setKeyPrefix(source.getL2().getKeyPrefix());
        target.getL2().setDefaultTtlSeconds(source.getL2().getDefaultTtlSeconds());
        target.getL2().setEnableBatch(source.getL2().isEnableBatch());
        target.getL2().setBatchSize(source.getL2().getBatchSize());
        target.getL2().setSerializer(source.getL2().getSerializer());
        target.getL2().setTimeoutSeconds(source.getL2().getTimeoutSeconds());

        target.getSync().setEnabled(source.getSync().isEnabled());
        target.getSync().setMode(source.getSync().getMode());
        target.getSync().setType(source.getSync().getType());
        target.getSync().setTopicPrefix(source.getSync().getTopicPrefix());
        target.getSync().setAsyncPublish(source.getSync().isAsyncPublish());
        target.getSync().setTimeoutMs(source.getSync().getTimeoutMs());
        target.getSync().setBatchSize(source.getSync().getBatchSize());

        target.getRefresh().setEnabled(source.getRefresh().isEnabled());
        target.getRefresh().setDefaultRefreshIntervalSeconds(source.getRefresh().getDefaultRefreshIntervalSeconds());
        target.getRefresh().setMinRefreshIntervalSeconds(source.getRefresh().getMinRefreshIntervalSeconds());
        target.getRefresh().setMaxRefreshIntervalSeconds(source.getRefresh().getMaxRefreshIntervalSeconds());
        target.getRefresh().setDistributedRefresh(source.getRefresh().isDistributedRefresh());
        target.getRefresh().setThreadPoolSize(source.getRefresh().getThreadPoolSize());
        target.getRefresh().setQueueCapacity(source.getRefresh().getQueueCapacity());
        target.getRefresh().setAllowConcurrentRefresh(source.getRefresh().isAllowConcurrentRefresh());
        target.getRefresh().setRefreshTimeoutSeconds(source.getRefresh().getRefreshTimeoutSeconds());
        target.getRefresh().setMaxRetries(source.getRefresh().getMaxRetries());
        target.getRefresh().setRetryIntervalSeconds(source.getRefresh().getRetryIntervalSeconds());
        target.getRefresh().setStartOnInit(source.getRefresh().isStartOnInit());
        target.getRefresh().setShutdownTimeoutSeconds(source.getRefresh().getShutdownTimeoutSeconds());

        target.getLoader().setAutoDiscover(source.getLoader().isAutoDiscover());
        target.getLoader().setEnableStats(source.getLoader().isEnableStats());
        target.getLoader().setTimeoutSeconds(source.getLoader().getTimeoutSeconds());

        target.getProtection().setSingleFlightEnabled(source.getProtection().isSingleFlightEnabled());
        target.getProtection().setDistributedLockEnabled(source.getProtection().isDistributedLockEnabled());
        target.getProtection().setDistributedLockWaitMs(source.getProtection().getDistributedLockWaitMs());
        target.getProtection().setDistributedLockLeaseMs(source.getProtection().getDistributedLockLeaseMs());
        target.getProtection().setHotKeyAccessThreshold(source.getProtection().getHotKeyAccessThreshold());
        target.getProtection().setMaxTrackedKeys(source.getProtection().getMaxTrackedKeys());

        return target;
    }
}
