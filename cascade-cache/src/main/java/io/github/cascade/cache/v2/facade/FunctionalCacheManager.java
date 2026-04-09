package io.github.cascade.cache.v2.facade;

import io.github.cascade.cache.v2.api.Cache;
import io.github.cascade.cache.v2.api.CacheLoader;
import io.github.cascade.cache.v2.api.CacheManager.CacheBuilder;
import io.github.cascade.cache.v2.api.CacheManager.CacheBuilderKeyStage;
import io.github.cascade.cache.v2.api.CacheManager.CacheBuilderValueStage;
import io.github.cascade.cache.v2.api.CacheManager;
import io.github.cascade.cache.v2.common.exception.CacheConfigurationException;
import io.github.cascade.cache.v2.common.exception.CacheException;
import io.github.cascade.cache.configuration.CascadeCacheProperties;
import io.github.cascade.cache.configuration.SyncProperties;
import io.github.cascade.cache.v2.support.NodeIdManager;
import io.github.cascade.cache.v2.support.TypeUtils;
import io.github.cascade.cache.v2.loader.CacheLoaderResolver;
import io.github.cascade.cache.v2.consistency.LocalVersionManager;
import io.github.cascade.cache.v2.loader.LoaderPriority;
import io.github.cascade.cache.v2.consistency.RedisVersionManager;
import io.github.cascade.cache.v2.consistency.VersionManager;
import io.github.cascade.cache.v2.engine.EngineBackedCache;
import io.github.cascade.cache.v2.loader.DistLockCoordinator;
import io.github.cascade.cache.v2.loader.RedisDistLockCoordinator;
import io.github.cascade.cache.v2.observability.CacheMetricsCollector;
import io.github.cascade.cache.v2.policy.CachePolicy;
import io.github.cascade.cache.v2.policy.RefreshExecutionOptions;
import io.github.cascade.cache.v2.policy.SyncMode;
import io.github.cascade.cache.v2.store.l1.CaffeineL1Store;
import io.github.cascade.cache.v2.store.l1.L1CacheStore;
import io.github.cascade.cache.v2.store.l2.L2CacheStore;
import io.github.cascade.cache.v2.store.l2.RedissonL2Store;
import io.github.cascade.cache.v2.consistency.InvalidationBus;
import io.github.cascade.cache.v2.consistency.RedisInvalidationBus;
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
    private final ConcurrentHashMap<String, CacheDefinitionFingerprint> definitionRegistry = new ConcurrentHashMap<>();
    private final RedissonClient redissonClient;
    private final CascadeCacheProperties defaultConfig;
    private final CacheLoaderResolver loaderResolver;
    private final MeterRegistry meterRegistry;

    @Getter
    private final String nodeId;

    private final AtomicBoolean closed = new AtomicBoolean(false);

    public FunctionalCacheManager(
            RedissonClient redissonClient,
            CascadeCacheProperties defaultConfig,
            CacheLoaderResolver loaderResolver
    ) {
        this(redissonClient, defaultConfig, loaderResolver, null);
    }

    public FunctionalCacheManager(
            RedissonClient redissonClient,
            CascadeCacheProperties defaultConfig,
            CacheLoaderResolver loaderResolver,
            MeterRegistry meterRegistry
    ) {
        this.redissonClient = redissonClient;
        this.defaultConfig = defaultConfig != null ? defaultConfig : CascadeCacheProperties.defaults();
        this.loaderResolver = loaderResolver;
        this.meterRegistry = meterRegistry;
        this.nodeId = NodeIdManager.getInstance().getNodeId();
        resolveRefreshExecutionOptions("__default__", this.defaultConfig);
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
        Class<K> normalizedKeyType = TypeUtils.boxedType(keyType);
        Class<V> normalizedValueType = TypeUtils.boxedType(valueType);

        CascadeCacheProperties finalConfig = config != null ? config : defaultConfig;
        RefreshExecutionOptions refreshOptions = resolveRefreshExecutionOptions(cacheName, finalConfig);
        Function<K, V> finalLoader = resolveLoader(cacheName, normalizedKeyType, normalizedValueType, finalConfig, loader);
        CachePolicy policy = buildPolicy(finalConfig, refreshOptions);
        CacheDefinitionFingerprint expected = buildEngineFingerprint(
                cacheName,
                normalizedKeyType,
                normalizedValueType,
                policy,
                finalConfig
        );

        Cache<?, ?> existing = cacheRegistry.get(cacheName);
        if (existing != null) {
            ensureDefinitionCompatible(cacheName, expected);
            Cache<K, V> casted = (Cache<K, V>) existing;
            if (casted instanceof EngineBackedCache<?, ?> && finalLoader != null) {
                ((EngineBackedCache<K, V>) casted).setLoaderIfAbsent(finalLoader);
            }
            return casted;
        }

        Cache<K, V> created = createCache(cacheName, normalizedValueType, finalConfig, finalLoader, policy);
        Cache<?, ?> raced = cacheRegistry.putIfAbsent(cacheName, created);
        if (raced == null) {
            CacheDefinitionFingerprint previous = definitionRegistry.putIfAbsent(cacheName, expected);
            if (previous != null && !previous.equals(expected)) {
                cacheRegistry.remove(cacheName, created);
                created.close();
                throwDefinitionConflict(cacheName, previous, expected);
            }
            return created;
        }

        try {
            created.close();
        } catch (Exception ignored) {
        }
        ensureDefinitionCompatible(cacheName, expected);
        Cache<K, V> cache = (Cache<K, V>) raced;

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
    @SuppressWarnings("unchecked")
    public <K, V> void registerLoader(String cacheName,
                                      Class<K> keyType,
                                      Class<V> valueType,
                                      CacheLoader<K, V> loader) {
        checkNotClosed();
        validateParameters(cacheName, keyType, valueType);
        Class<K> normalizedKeyType = TypeUtils.boxedType(keyType);
        Class<V> normalizedValueType = TypeUtils.boxedType(valueType);
        if (loader == null) {
            throw new CacheConfigurationException("loader", null, "加载器不能为空", null);
        }
        if (loaderResolver == null) {
            throw new CacheConfigurationException(
                    "loaderResolver",
                    null,
                    "未配置CacheLoaderResolver，无法注册Loader",
                    null
            );
        }
        loaderResolver.registerLoader(cacheName, normalizedKeyType, normalizedValueType, loader);

        Cache<?, ?> existing = cacheRegistry.get(cacheName);
        if (existing instanceof EngineBackedCache<?, ?> engineBackedCache) {
            CacheLoader<K, V> resolved = loaderResolver.resolveCacheLoader(cacheName, normalizedKeyType, normalizedValueType, false);
            if (resolved != null) {
                ((EngineBackedCache<K, V>) engineBackedCache).setLoaderIfAbsent(resolved);
            }
        }
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
        CacheDefinitionFingerprint expected = CacheDefinitionFingerprint.external(cacheName, cache.getClass().getName());
        ensureDefinitionCompatible(cacheName, expected);

        Cache<?, ?> previous = cacheRegistry.put(cacheName, cache);
        definitionRegistry.put(cacheName, expected);
        if (previous == cache) {
            return false;
        }
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
        definitionRegistry.remove(cacheName);
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
        definitionRegistry.clear();
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
                                           Class<V> valueType,
                                           CascadeCacheProperties config,
                                           Function<K, V> loader,
                                           CachePolicy policy) {

        L1CacheStore<K, V> l1Store = null;
        if (policy.isL1Enabled()) {
            l1Store = new CaffeineL1Store<>(
                    config.getL1MaxSize(),
                    config.isStatsEnabled(),
                    config.getL1ExpireAfterWriteSeconds(),
                    config.getL1ExpireAfterAccessSeconds(),
                    config.getL1InitialCapacity()
            );
        }

        L2CacheStore<K, V> l2Store = null;
        if (policy.isL2Enabled()) {
            l2Store = new RedissonL2Store<>(cacheName, redissonClient, config.getL2KeyPrefix());
        }

        if (l1Store == null && l2Store == null) {
            throw new CacheConfigurationException("layers", null, "至少启用一个缓存层（L1或L2）", null);
        }

        CacheMetricsCollector metricsCollector = CacheMetricsCollector.create(cacheName, meterRegistry);
        InvalidationBus<K> bus = createInvalidationBus(cacheName, policy, config, metricsCollector);
        VersionManager<K> versionManager = createVersionManager(cacheName, config);
        DistLockCoordinator<K> lockCoordinator = createDistLockCoordinator(cacheName, policy, config);
        EngineBackedCache<K, V> cache = new EngineBackedCache<>(
                cacheName, policy, l1Store, l2Store, loader, bus, versionManager, lockCoordinator, nodeId, valueType,
                metricsCollector
        );
        LOGGER.info("V2缓存创建完成: cache={}, l1={}, l2={}, sync={}, refresh={}",
                cacheName, policy.isL1Enabled(), policy.isL2Enabled(),
                policy.getSyncMode(), policy.isAutoRefreshEnabled());
        return cache;
    }

    private <K, V> CacheDefinitionFingerprint buildEngineFingerprint(String cacheName,
                                                                     Class<K> keyType,
                                                                     Class<V> valueType,
                                                                     CachePolicy policy,
                                                                     CascadeCacheProperties config) {
        long l1MaximumSize = policy.isL1Enabled() ? config.getL1MaxSize() : -1L;
        int l1InitialCapacity = policy.isL1Enabled() ? config.getL1InitialCapacity() : -1;
        long l1ExpireAfterWriteSeconds = policy.isL1Enabled() ? config.getL1ExpireAfterWriteSeconds() : -1L;
        long l1ExpireAfterAccessSeconds = policy.isL1Enabled() ? config.getL1ExpireAfterAccessSeconds() : -1L;
        boolean l1RecordStats = policy.isL1Enabled() && config.isStatsEnabled();
        String l2KeyPrefix = policy.isL2Enabled() ? normalizeL2KeyPrefix(config.getL2KeyPrefix()) : "";
        return new CacheDefinitionFingerprint(
                cacheName,
                "engine",
                keyType.getName(),
                valueType.getName(),
                EngineBackedCache.class.getName(),
                policy.isL1Enabled(),
                policy.isL2Enabled(),
                l1MaximumSize,
                l1InitialCapacity,
                l1ExpireAfterWriteSeconds,
                l1ExpireAfterAccessSeconds,
                l1RecordStats,
                l2KeyPrefix,
                policy.getHardTtlSeconds(),
                policy.getSoftTtlSeconds(),
                policy.getRefreshIntervalSeconds(),
                policy.isAutoRefreshEnabled(),
                policy.getRefreshExecutionOptions().threadPoolSize(),
                policy.getRefreshExecutionOptions().queueCapacity(),
                policy.getRefreshExecutionOptions().allowConcurrentRefresh(),
                policy.getRefreshExecutionOptions().refreshTimeoutSeconds(),
                policy.getRefreshExecutionOptions().maxRetries(),
                policy.getRefreshExecutionOptions().retryIntervalSeconds(),
                policy.getRefreshExecutionOptions().startOnInit(),
                policy.getRefreshExecutionOptions().shutdownTimeoutSeconds(),
                policy.getSyncMode(),
                policy.isSyncUpdateEnabled(),
                policy.getSyncUpdateMaxPayloadBytes(),
                policy.isSingleFlightEnabled(),
                policy.isDistributedLockEnabled(),
                policy.getLockFailureStrategy()
        );
    }

    /**
     * 归一化 L2 前缀，保持与 RedissonL2Store 构造逻辑一致，避免等价配置误判冲突。
     */
    private static String normalizeL2KeyPrefix(String prefix) {
        String normalized = prefix == null ? "" : prefix;
        if (!normalized.isEmpty() && !normalized.endsWith(":")) {
            normalized += ":";
        }
        return normalized;
    }

    private void ensureDefinitionCompatible(String cacheName, CacheDefinitionFingerprint expected) {
        CacheDefinitionFingerprint existing = definitionRegistry.get(cacheName);
        if (existing == null) {
            return;
        }
        if (!existing.equals(expected)) {
            throwDefinitionConflict(cacheName, existing, expected);
        }
    }

    private void throwDefinitionConflict(String cacheName,
                                         CacheDefinitionFingerprint existing,
                                         CacheDefinitionFingerprint requested) {
        throw new CacheConfigurationException(
                "cacheName",
                cacheName,
                "同名缓存定义冲突，拒绝覆盖: existing=" + existing + ", requested=" + requested,
                null
        );
    }

    private CachePolicy buildPolicy(CascadeCacheProperties config, RefreshExecutionOptions refreshOptions) {
        long hardTtl = config.getL2DefaultTtlSeconds();
        long softTtl = config.getSoftTtlSeconds();
        if (softTtl <= 0) {
            softTtl = hardTtl > 0 ? Math.max(1, hardTtl / 3) : 300;
        }

        boolean l2Enabled = config.isL2Enabled() && redissonClient != null;
        SyncMode configuredSyncMode = config.getSyncConfig().getMode();
        boolean syncEnabled = config.isSyncEnabled() && l2Enabled;
        SyncMode syncMode = syncEnabled ? (configuredSyncMode != null ? configuredSyncMode : SyncMode.INVALIDATE) : SyncMode.NONE;
        boolean syncUpdateEnabled = syncEnabled
                && syncMode == SyncMode.UPDATE
                && config.getSyncConfig().isUpdateEnabled();
        if (syncMode == SyncMode.UPDATE && !syncUpdateEnabled) {
            LOGGER.warn("检测到syncMode=UPDATE但未显式开启sync.updateEnabled，自动降级为INVALIDATE");
            syncMode = SyncMode.INVALIDATE;
        }

        return CachePolicy.builder()
                .l1Enabled(config.isL1Enabled())
                .l2Enabled(l2Enabled)
                .hardTtlSeconds(hardTtl)
                .softTtlSeconds(softTtl)
                .refreshIntervalSeconds(Math.max(1, config.getRefresh().getDefaultRefreshIntervalSeconds()))
                .autoRefreshEnabled(config.isRefreshEnabled())
                .refreshExecutionOptions(refreshOptions)
                .syncMode(syncMode)
                .syncUpdateEnabled(syncUpdateEnabled)
                .syncUpdateMaxPayloadBytes(config.getSyncConfig().getUpdateMaxPayloadBytes())
                .singleFlightEnabled(config.isSingleFlightEnabled())
                .distributedLockEnabled(config.isDistributedLockEnabled())
                .lockFailureStrategy(config.getLockFailureStrategy())
                .distributedLockWaitMs(config.getDistributedLockWaitMs())
                .distributedLockLeaseMs(config.getDistributedLockLeaseMs())
                .hotKeyAccessThreshold(config.getHotKeyAccessThreshold())
                .maxTrackedKeys(config.getMaxTrackedKeys())
                .build();
    }

    private RefreshExecutionOptions resolveRefreshExecutionOptions(String cacheName, CascadeCacheProperties config) {
        CascadeCacheProperties.RefreshConfig refreshConfig = config != null && config.getRefresh() != null
                ? config.getRefresh()
                : CascadeCacheProperties.defaults().getRefresh();
        RefreshExecutionOptions defaults = RefreshExecutionOptions.defaults();
        boolean customized = refreshConfig.getThreadPoolSize() != defaults.threadPoolSize()
                || refreshConfig.getQueueCapacity() != defaults.queueCapacity()
                || refreshConfig.isAllowConcurrentRefresh() != defaults.allowConcurrentRefresh()
                || refreshConfig.getRefreshTimeoutSeconds() != defaults.refreshTimeoutSeconds()
                || refreshConfig.getMaxRetries() != defaults.maxRetries()
                || refreshConfig.getRetryIntervalSeconds() != defaults.retryIntervalSeconds()
                || refreshConfig.isStartOnInit() != defaults.startOnInit()
                || refreshConfig.getShutdownTimeoutSeconds() != defaults.shutdownTimeoutSeconds();
        if (!refreshConfig.isEnabled() && customized) {
            LOGGER.warn("刷新执行参数在refresh.enabled=false时不生效: cache={}", cacheName);
        }
        try {
            return new RefreshExecutionOptions(
                    refreshConfig.getThreadPoolSize(),
                    refreshConfig.getQueueCapacity(),
                    refreshConfig.isAllowConcurrentRefresh(),
                    refreshConfig.getRefreshTimeoutSeconds(),
                    refreshConfig.getMaxRetries(),
                    refreshConfig.getRetryIntervalSeconds(),
                    refreshConfig.isStartOnInit(),
                    refreshConfig.getShutdownTimeoutSeconds()
            );
        } catch (IllegalArgumentException e) {
            throw new CacheConfigurationException(
                    "refresh",
                    cacheName,
                    "刷新执行配置无效: " + e.getMessage(),
                    e
            );
        }
    }

    private <K> InvalidationBus<K> createInvalidationBus(String cacheName,
                                                         CachePolicy policy,
                                                         CascadeCacheProperties config,
                                                         CacheMetricsCollector metricsCollector) {
        if (policy.getSyncMode() == SyncMode.NONE || redissonClient == null) {
            return InvalidationBus.noop();
        }
        SyncProperties syncConfig = config.getSyncConfig();
        RedisInvalidationBus.PublishOptions options = new RedisInvalidationBus.PublishOptions(
                syncConfig.isAsyncPublish(),
                syncConfig.getPublishThreadPoolSize(),
                syncConfig.getPublishQueueCapacity(),
                syncConfig.getPublishMaxRetries(),
                syncConfig.getPublishRetryBackoffMs()
        );
        CacheMetricsCollector collector = metricsCollector != null
                ? metricsCollector
                : CacheMetricsCollector.create(cacheName, meterRegistry);
        return new RedisInvalidationBus<>(redissonClient, syncConfig.getTopicPrefix(), collector, options);
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

    private <K, V> Function<K, V> resolveLoader(String cacheName,
                                                Class<K> keyType,
                                                Class<V> valueType,
                                                CascadeCacheProperties config,
                                                Function<K, V> providedLoader) {
        if (providedLoader != null) {
            return guardFunction(cacheName, keyType, valueType, providedLoader);
        }
        if (loaderResolver == null) {
            return null;
        }
        try {
            CacheLoader<K, V> resolved = loaderResolver.resolveCacheLoader(cacheName, keyType, valueType, false);
            if (resolved != null) {
                return resolved;
            }
            if (!config.getLoader().isAutoDiscover()) {
                LOGGER.debug("跳过自动发现CacheLoader: cacheName={}, keyType={}, valueType={}",
                        cacheName, keyType.getSimpleName(), valueType.getSimpleName());
                return null;
            }
            resolved = loaderResolver.resolveCacheLoader(cacheName, keyType, valueType, true);
            if (resolved != null) {
                return resolved;
            }
        } catch (Exception e) {
            throw new CacheConfigurationException(
                    "loader",
                    cacheName,
                    e.getMessage(),
                    e
            );
        }
        return null;
    }

    private static <K, V> Function<K, V> guardFunction(String cacheName,
                                                       Class<K> keyType,
                                                       Class<V> valueType,
                                                       Function<K, V> delegate) {
        return new GuardedLoader<>(cacheName, keyType, valueType, delegate);
    }

    private static String className(Object source) {
        return source == null ? "null" : source.getClass().getName();
    }

    /**
     * 类型守卫loader包装器，同时透传loader优先级元信息。
     */
    private static final class GuardedLoader<K, V>
            implements Function<K, V>, LoaderPriority.Prioritized {

        private final String cacheName;
        private final Class<K> keyType;
        private final Class<V> valueType;
        private final Function<K, V> delegate;
        private final int priority;

        private GuardedLoader(String cacheName,
                              Class<K> keyType,
                              Class<V> valueType,
                              Function<K, V> delegate) {
            this.cacheName = cacheName;
            this.keyType = TypeUtils.boxedType(keyType);
            this.valueType = TypeUtils.boxedType(valueType);
            this.delegate = delegate;
            this.priority = LoaderPriority.of(delegate);
        }

        @Override
        public V apply(K rawKey) {
            K key;
            try {
                key = keyType.cast(rawKey);
            } catch (ClassCastException e) {
                throw new CacheConfigurationException(
                        "loader.keyType",
                        cacheName,
                        "Loader入参类型不匹配: expected=" + keyType.getName() + ", actual="
                                + className(rawKey),
                        e
                );
            }

            V value = delegate.apply(key);
            if (value != null && !valueType.isInstance(value)) {
                throw new CacheConfigurationException(
                        "loader.valueType",
                        cacheName,
                        "Loader返回值类型不匹配: expected=" + valueType.getName() + ", actual="
                                + value.getClass().getName(),
                        null
                );
            }
            return value;
        }

        @Override
        public int loaderPriority() {
            return priority;
        }
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
            config.getSync().setUpdateEnabled(syncMode == SyncMode.UPDATE);
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
        return source != null ? source.deepCopy() : CascadeCacheProperties.defaults();
    }
}
