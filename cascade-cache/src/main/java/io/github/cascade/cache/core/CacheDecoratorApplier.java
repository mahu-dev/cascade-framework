package io.github.cascade.cache.core;

import io.github.cascade.cache.api.Cache;
import io.github.cascade.cache.api.CacheLoader;
import io.github.cascade.cache.api.CacheRefresher;
import io.github.cascade.cache.api.CacheSync;
import io.github.cascade.cache.configuration.CascadeCacheProperties;
import io.github.cascade.cache.configuration.SyncProperties;
import io.github.cascade.cache.core.distributed.DistributedAutoRefreshCache;
import io.github.cascade.cache.core.functional.FunctionalCache;
import io.github.cascade.cache.core.simple.AutoRefreshCache;
import io.github.cascade.cache.core.simple.SyncAwareCache;
import io.github.cascade.cache.synchronization.CacheLoaderResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author lionel lionelk@163.com
 * =============================
 * Date: 2025/11/01
 * Time: 14:00
 * =============================
 */

/**
 * 缓存装饰器应用器
 * <p>
 * 职责：
 * 1. 根据配置自动应用装饰器
 * 2. 管理刷新器的创建
 * 3. 管理同步器的创建
 * <p>
 * 设计原则：
 * - 配置驱动：根据配置自动决定应用哪些装饰器
 * - 透明化：对外部隐藏装饰器的复杂性
 * - 单一职责：只负责装饰器应用，不参与缓存管理
 */
public class CacheDecoratorApplier {

    private static final Logger LOGGER = LoggerFactory.getLogger(CacheDecoratorApplier.class);

    private final List<CacheSyncFactory> syncFactories;
    private final CascadeCacheProperties defaultConfig;
    private final CacheLoaderResolver<?, ?> loaderResolver;

    // 刷新器缓存
    private final ConcurrentHashMap<String, CacheRefresher<?, ?>> refresherCache = new ConcurrentHashMap<>();

    public CacheDecoratorApplier(
            List<CacheSyncFactory> syncFactories,
            CascadeCacheProperties defaultConfig,
            CacheLoaderResolver<?, ?> loaderResolver
    ) {
        this.syncFactories = syncFactories != null ? syncFactories : List.of();
        this.defaultConfig = defaultConfig;
        this.loaderResolver = loaderResolver;
    }

    /**
     * 应用所有装饰器
     * <p>
     * 装饰器应用顺序：
     * 1. 同步装饰器（SyncAwareCache）- 如果启用
     * 2. 自动刷新装饰器（AutoRefreshCache/DistributedAutoRefreshCache）- 如果启用且有loader
     *
     * @param cache      基础缓存
     * @param definition 缓存定义
     * @param nodeId     节点ID
     * @return 装饰后的缓存
     */
    public <K, V> Cache<K, V> applyDecorators(
            Cache<K, V> cache,
            CacheDefinition<K, V> definition,
            String nodeId
    ) {
        CascadeCacheProperties config = definition.getConfig();

        // 1. 应用同步装饰器（如果启用）
        if (config.isSyncEnabled()) {
            cache = applySyncDecorator(cache, config, nodeId);
        }

        // 2. 应用自动刷新装饰器（如果启用且有loader）
        if (config.isRefreshEnabled() && definition.getLoader().isPresent()) {
            cache = applyAutoRefreshDecorator(cache, definition, config, nodeId);
        }

        return cache;
    }

    /**
     * 应用同步装饰器
     */
    private <K, V> Cache<K, V> applySyncDecorator(
            Cache<K, V> cache,
            CascadeCacheProperties config,
            String nodeId
    ) {
        CacheSync<K, V> sync = createCacheSync(cache.getName(), config);
        if (sync != null) {
            cache = SyncAwareCache.wrap(cache, sync, nodeId);
            LOGGER.debug("同步装饰器已应用: cache={}", cache.getName());
        } else {
            LOGGER.warn("无法创建同步器，跳过同步装饰器: cache={}", cache.getName());
        }
        return cache;
    }

    /**
     * 应用自动刷新装饰器
     */
    private <K, V> Cache<K, V> applyAutoRefreshDecorator(
            Cache<K, V> cache,
            CacheDefinition<K, V> definition,
            CascadeCacheProperties config,
            String nodeId
    ) {
        // 创建刷新器
        CacheRefresher<K, V> refresher = createRefresher(cache, definition);
        if (refresher == null) {
            LOGGER.warn("无法创建刷新器，跳过自动刷新装饰器: cache={}", cache.getName());
            return cache;
        }

        // 缓存刷新器
        refresherCache.put(cache.getName(), refresher);

        // 判断是否需要分布式刷新
        boolean isDistributed = config.isSyncEnabled() && config.getRefresh().isDistributedRefresh();

        if (isDistributed) {
            // 创建分布式自动刷新缓存
            CacheSync<K, V> sync = createCacheSync(cache.getName(), config);
            if (sync != null) {
                long interval = config.getRefresh().getDefaultRefreshIntervalSeconds();
                cache = new DistributedAutoRefreshCache<>(cache, refresher, sync, nodeId, interval, true);
                LOGGER.debug("分布式自动刷新装饰器已应用: cache={}, interval={}秒", cache.getName(), interval);
            } else {
                // 降级为普通自动刷新
                LOGGER.warn("无法创建同步器，降级为普通自动刷新: cache={}", cache.getName());
                cache = wrapAutoRefresh(cache, refresher, config);
            }
        } else {
            // 普通自动刷新
            cache = wrapAutoRefresh(cache, refresher, config);
        }

        return cache;
    }

    /**
     * 包装为自动刷新缓存
     */
    private <K, V> Cache<K, V> wrapAutoRefresh(
            Cache<K, V> cache,
            CacheRefresher<K, V> refresher,
            CascadeCacheProperties config
    ) {
        long interval = config.getRefresh().getDefaultRefreshIntervalSeconds();
        Cache<K, V> wrapped = new AutoRefreshCache<>(cache, refresher, interval, true);
        LOGGER.debug("自动刷新装饰器已应用: cache={}, interval={}秒", cache.getName(), interval);
        return wrapped;
    }

    /**
     * 创建缓存同步器
     */
    @SuppressWarnings("unchecked")
    private <K, V> CacheSync<K, V> createCacheSync(String cacheName, CascadeCacheProperties config) {
        SyncProperties syncConfig = config.getSyncConfig();

        CacheSyncFactory factory = syncFactories.stream()
                .filter(f -> f.supports(syncConfig.getType()))
                .findFirst()
                .orElse(null);

        if (factory != null) {
            try {
                CacheSync<K, V> sync = factory.createCacheSync(syncConfig);
                LOGGER.debug("同步器创建成功: cache={}, type={}", cacheName, syncConfig.getType());
                return sync;
            } catch (Exception e) {
                LOGGER.error("同步器创建失败: cache={}, type={}", cacheName, syncConfig.getType(), e);
                return null;
            }
        } else {
            LOGGER.warn("未找到支持类型{}的同步工厂: cache={}", syncConfig.getType(), cacheName);
            return null;
        }
    }

    /**
     * 创建刷新器
     */
    private <K, V> CacheRefresher<K, V> createRefresher(
            Cache<K, V> cache,
            CacheDefinition<K, V> definition
    ) {
        // 提取 FunctionalCache
        FunctionalCache<K, V> functionalCache = extractFunctionalCache(cache);
        if (functionalCache == null) {
            LOGGER.warn("不是函数式缓存，无法创建刷新器: cache={}", cache.getName());
            return null;
        }

        // 获取 loader
        CacheLoader<K, V> loader = functionalCache.getLoader().orElse(null);
        if (loader == null) {
            // 尝试通过 loaderResolver 解析
            if (loaderResolver != null) {
                try {
                    @SuppressWarnings("unchecked")
                    CacheLoaderResolver<K, V> typedResolver = (CacheLoaderResolver<K, V>) loaderResolver;
                    loader = typedResolver.resolveCacheLoader(
                            functionalCache.getKeyType(),
                            functionalCache.getValueType()
                    );
                } catch (Exception e) {
                    LOGGER.warn("通过loaderResolver解析loader失败: cache={}", cache.getName(), e);
                }
            }

            if (loader == null) {
                LOGGER.warn("无法获取loader，无法创建刷新器: cache={}", cache.getName());
                return null;
            }
        }

        try {
            CacheRefresher<K, V> refresher = new ScheduledCacheRefresher<>(
                    cache.getName(),
                    cache,
                    loader,
                    definition.getConfig()
            );
            LOGGER.debug("刷新器创建成功: cache={}", cache.getName());
            return refresher;
        } catch (Exception e) {
            LOGGER.error("创建刷新器失败: cache={}", cache.getName(), e);
            return null;
        }
    }

    /**
     * 提取 FunctionalCache
     * <p>
     * 穿透装饰器层，获取底层的 FunctionalCache 实例
     */
    @SuppressWarnings("unchecked")
    private <K, V> FunctionalCache<K, V> extractFunctionalCache(Cache<K, V> cache) {
        // 直接是 FunctionalCache
        if (cache instanceof FunctionalCache<?, ?>) {
            return (FunctionalCache<K, V>) cache;
        }

        // 穿透 SyncAwareCache 装饰器
        if (cache instanceof SyncAwareCache<?, ?>) {
            SyncAwareCache<K, V> syncAware = (SyncAwareCache<K, V>) cache;
            Cache<K, V> delegate = syncAware.getDelegate();
            return extractFunctionalCache(delegate); // 递归穿透多层装饰器
        }

        // AutoRefreshCache 和 DistributedAutoRefreshCache 都是装饰器
        // 但它们没有暴露 getDelegate() 方法，这是正确的设计
        // 在创建刷新器时，我们应该在装饰之前就创建好，而不是从装饰后的缓存中提取

        return null; // 不是函数式缓存
    }

    /**
     * 检查是否有刷新器
     */
    public boolean hasRefresher(String cacheName) {
        return refresherCache.containsKey(cacheName);
    }

    /**
     * 获取刷新器
     */
    @SuppressWarnings("unchecked")
    public <K, V> CacheRefresher<K, V> getRefresher(String cacheName) {
        return (CacheRefresher<K, V>) refresherCache.get(cacheName);
    }

    /**
     * 移除刷新器
     */
    public void removeRefresher(String cacheName) {
        refresherCache.remove(cacheName);
    }

    /**
     * 清空所有刷新器
     */
    public void clearRefreshers() {
        refresherCache.clear();
    }

    /**
     * 获取刷新器数量
     */
    public int getRefresherCount() {
        return refresherCache.size();
    }
}