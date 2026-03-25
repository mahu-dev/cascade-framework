package io.github.cascade.cache.core.functional;

import io.github.cascade.cache.api.Cache;
import io.github.cascade.cache.api.CacheLoader;
import io.github.cascade.cache.api.CacheManager;
import io.github.cascade.cache.common.exception.CacheConfigurationException;
import io.github.cascade.cache.common.exception.CacheException;
import io.github.cascade.cache.configuration.CascadeCacheProperties;
import io.github.cascade.cache.core.distributed.NodeIdManager;
import io.github.cascade.cache.synchronization.CacheLoaderResolver;
import io.github.cascade.cache.v2.core.EngineBackedCache;
import io.github.cascade.cache.v2.policy.CachePolicy;
import io.github.cascade.cache.v2.policy.SyncMode;
import io.github.cascade.cache.v2.store.CaffeineL1Store;
import io.github.cascade.cache.v2.store.L1CacheStore;
import io.github.cascade.cache.v2.store.L2CacheStore;
import io.github.cascade.cache.v2.store.RedissonL2Store;
import io.github.cascade.cache.v2.sync.InvalidationBus;
import io.github.cascade.cache.v2.sync.RedisInvalidationBus;
import lombok.Getter;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;
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

    @Getter
    private final String nodeId;

    private final AtomicBoolean closed = new AtomicBoolean(false);

    public FunctionalCacheManager(
            RedissonClient redissonClient,
            CascadeCacheProperties defaultConfig,
            CacheLoaderResolver<?, ?> loaderResolver,
            List<?> ignoredSyncFactories
    ) {
        this.redissonClient = redissonClient;
        this.defaultConfig = defaultConfig != null ? defaultConfig : CascadeCacheProperties.defaults();
        this.loaderResolver = loaderResolver;
        this.nodeId = NodeIdManager.getInstance().getNodeId();
        LOGGER.info("V2缓存管理器初始化完成: nodeId={}", nodeId);
    }

    public FunctionalCacheManager(
            RedissonClient redissonClient,
            CascadeCacheProperties defaultConfig,
            CacheLoaderResolver<?, ?> loaderResolver,
            List<?> ignoredSyncFactories,
            FunctionalCache.CacheStrategy ignoredStrategy
    ) {
        this(redissonClient, defaultConfig, loaderResolver, ignoredSyncFactories);
        LOGGER.info("V2缓存管理器策略参数已忽略，统一由CachePolicy驱动");
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
        EngineBackedCache<K, V> cache = new EngineBackedCache<>(
                cacheName, policy, l1Store, l2Store, loader, bus, nodeId
        );
        LOGGER.info("V2缓存创建完成: cache={}, l1={}, l2={}, sync={}, refresh={}",
                cacheName, policy.isL1Enabled(), policy.isL2Enabled(),
                policy.getSyncMode(), policy.isAutoRefreshEnabled());
        return cache;
    }

    private CachePolicy buildPolicy(CascadeCacheProperties config) {
        long hardTtl = config.getL2DefaultTtlSeconds();
        long softTtl = config.getRefresh().getDefaultRefreshIntervalSeconds();
        if (softTtl <= 0) {
            softTtl = hardTtl > 0 ? Math.max(1, hardTtl / 3) : 300;
        }

        boolean l2Enabled = config.isL2Enabled() && redissonClient != null;
        SyncMode syncMode = config.isSyncEnabled() && l2Enabled ? SyncMode.INVALIDATE : SyncMode.NONE;

        return CachePolicy.builder()
                .l1Enabled(config.isL1Enabled())
                .l2Enabled(l2Enabled)
                .hardTtlSeconds(hardTtl)
                .softTtlSeconds(softTtl)
                .refreshIntervalSeconds(Math.max(1, config.getRefresh().getDefaultRefreshIntervalSeconds()))
                .autoRefreshEnabled(config.isRefreshEnabled())
                .syncMode(syncMode)
                .build();
    }

    private <K> InvalidationBus<K> createInvalidationBus(CachePolicy policy, CascadeCacheProperties config) {
        if (policy.getSyncMode() == SyncMode.NONE || redissonClient == null) {
            return InvalidationBus.noop();
        }
        return new RedisInvalidationBus<>(redissonClient, config.getSyncConfig().getTopicPrefix());
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
}
