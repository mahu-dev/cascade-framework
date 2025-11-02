package io.github.cascade.cache.core.functional;

import io.github.cascade.cache.api.*;
import io.github.cascade.cache.common.exception.CacheConfigurationException;
import io.github.cascade.cache.common.exception.CacheException;
import io.github.cascade.cache.configuration.CascadeCacheProperties;
import io.github.cascade.cache.core.CacheDecoratorApplier;
import io.github.cascade.cache.core.CacheDefinition;
import io.github.cascade.cache.core.CacheSyncFactory;
import io.github.cascade.cache.core.FunctionalCacheFactory;
import io.github.cascade.cache.core.ScheduledCacheRefresher;
import io.github.cascade.cache.core.distributed.NodeIdManager;
import io.github.cascade.cache.synchronization.CacheLoaderResolver;
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
 * @author lionel lionelk@163.com
 * =============================
 * Date: 2025/11/01
 * Time: 15:00
 * =============================
 */

/**
 * 函数式缓存管理器 - 简化版
 * <p>
 * 设计改进（2025-11-01）：
 * 1. 职责聚焦：只负责缓存实例的管理
 * 2. 委托创建：缓存创建委托给 CacheFactory
 * 3. 自动装饰：装饰器通过配置自动应用，无需特殊方法
 * 4. 内部管理：刷新器管理移到内部，不暴露给外部
 * <p>
 * 核心组件：
 * - CacheRegistry：缓存实例注册表
 * - FunctionalCacheFactory：缓存工厂
 * - CacheDecoratorApplier：装饰器应用器（自动根据配置应用）
 * <p>
 * 简化点：
 * - 移除了 getOrCreateAutoRefreshCache() 等特殊方法
 * - 移除了 getStats() 等监控功能
 * - 移除了显式的刷新器管理接口
 * - 代码量从 821 行减少到约 300 行
 */
public class FunctionalCacheManager implements CacheManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(FunctionalCacheManager.class);

    // ==================== 核心组件 ====================

    private final ConcurrentHashMap<String, Cache<?, ?>> cacheRegistry = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CacheRefresher<?, ?>> refresherRegistry = new ConcurrentHashMap<>();

    private final FunctionalCacheFactory cacheFactory;
    private final CascadeCacheProperties defaultConfig;
    private final CacheLoaderResolver<?, ?> loaderResolver;
    private final CacheDecoratorApplier decoratorApplier;

    @Getter
    private final String nodeId;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    // ==================== 构造函数 ====================

    public FunctionalCacheManager(
            RedissonClient redissonClient,
            CascadeCacheProperties defaultConfig,
            CacheLoaderResolver<?, ?> loaderResolver,
            List<CacheSyncFactory> syncFactories
    ) {
        this.cacheFactory = new FunctionalCacheFactory(redissonClient);
        this.defaultConfig = defaultConfig != null ? defaultConfig : CascadeCacheProperties.defaults();
        this.loaderResolver = loaderResolver;
        this.decoratorApplier = new CacheDecoratorApplier(syncFactories, this.defaultConfig, loaderResolver);
        this.nodeId = NodeIdManager.getInstance().getNodeId();

        validateConfiguration();

        LOGGER.info("函数式缓存管理器初始化完成: nodeId={}", nodeId);
    }

    /**
     * 带策略的构造函数
     */
    public FunctionalCacheManager(
            RedissonClient redissonClient,
            CascadeCacheProperties defaultConfig,
            CacheLoaderResolver<?, ?> loaderResolver,
            List<CacheSyncFactory> syncFactories,
            FunctionalCache.CacheStrategy defaultStrategy
    ) {
        this.cacheFactory = new FunctionalCacheFactory(redissonClient, defaultStrategy);
        this.defaultConfig = defaultConfig != null ? defaultConfig : CascadeCacheProperties.defaults();
        this.loaderResolver = loaderResolver;
        this.decoratorApplier = new CacheDecoratorApplier(syncFactories, this.defaultConfig, loaderResolver);
        this.nodeId = NodeIdManager.getInstance().getNodeId();

        validateConfiguration();

        LOGGER.info("函数式缓存管理器初始化完成: nodeId={}, defaultStrategy={}", nodeId, defaultStrategy);
    }

    // ==================== CacheManager核心接口实现 ====================

    @Override
    public <K, V> Cache<K, V> getOrCreateCache(String cacheName, Class<K> keyType, Class<V> valueType) {
        return getOrCreateCache(cacheName, keyType, valueType, defaultConfig, null);
    }

    @Override
    public <K, V> Cache<K, V> getOrCreateCache(String cacheName, Class<K> keyType, Class<V> valueType,
                                                CascadeCacheProperties config) {
        return getOrCreateCache(cacheName, keyType, valueType, config, null);
    }

    @Override
    public <K, V> Cache<K, V> getOrCreateCache(String cacheName, Class<K> keyType, Class<V> valueType,
                                                Function<K, V> loader) {
        return getOrCreateCache(cacheName, keyType, valueType, defaultConfig, loader);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <K, V> Cache<K, V> getOrCreateCache(String cacheName, Class<K> keyType, Class<V> valueType,
                                                CascadeCacheProperties config, Function<K, V> loader) {
        checkNotClosed();
        validateParameters(cacheName, keyType, valueType);

        // 使用 computeIfAbsent 保证线程安全的单次创建
        Cache<K, V> cache = (Cache<K, V>) cacheRegistry.computeIfAbsent(
                cacheName,
                name -> createCache(name, keyType, valueType, config, loader)
        );

        return cache;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <K, V> Cache<K, V> getCache(String cacheName) {
        checkNotClosed();
        return (Cache<K, V>) cacheRegistry.get(cacheName);
    }

    // ==================== 缓存管理接口 ====================

    @Override
    public <K, V> boolean registerCache(String cacheName, Cache<K, V> cache) {
        checkNotClosed();
        validateParameters(cacheName, cache);

        Cache<?, ?> previous = cacheRegistry.put(cacheName, cache);
        boolean isNew = previous == null;

        if (!isNew && previous != null && !previous.isClosed()) {
            previous.close();
            LOGGER.warn("缓存已存在并被替换: {}", cacheName);
        }

        LOGGER.info("缓存注册成功: {}", cacheName);
        return isNew;
    }

    @Override
    public boolean removeCache(String cacheName) {
        checkNotClosed();

        Cache<?, ?> removed = cacheRegistry.remove(cacheName);
        if (removed != null) {
            // 清理关联的刷新器
            cleanupRefresher(cacheName);

            // 关闭缓存
            if (!removed.isClosed()) {
                removed.close();
            }

            LOGGER.info("缓存移除成功: {}", cacheName);
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

        LOGGER.info("开始清空所有缓存...");
        cacheRegistry.values().forEach(cache -> {
            try {
                cache.clear();
            } catch (Exception e) {
                LOGGER.error("清空缓存失败: cache={}", cache.getName(), e);
            }
        });

        LOGGER.info("清空缓存完成");
    }

    @Override
    public int getCacheCount() {
        checkNotClosed();
        return cacheRegistry.size();
    }

    // ==================== 生命周期管理 ====================

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        LOGGER.info("开始关闭缓存管理器: nodeId={}", nodeId);

        // 关闭所有刷新器
        refresherRegistry.values().forEach(this::stopRefresher);
        refresherRegistry.clear();

        // 关闭所有缓存
        cacheRegistry.values().forEach(cache -> {
            try {
                if (!cache.isClosed()) {
                    cache.close();
                }
            } catch (Exception e) {
                LOGGER.error("关闭缓存失败: cache={}", cache.getName(), e);
            }
        });
        cacheRegistry.clear();

        LOGGER.info("缓存管理器关闭完成: nodeId={}", nodeId);
    }

    @Override
    public boolean isClosed() {
        return closed.get();
    }

    // ==================== 私有方法 ====================

    /**
     * 创建缓存（包括装饰器应用）
     */
    private <K, V> Cache<K, V> createCache(
            String cacheName,
            Class<K> keyType,
            Class<V> valueType,
            CascadeCacheProperties config,
            Function<K, V> loader
    ) {
        // 1. 解析 loader
        Function<K, V> finalLoader = resolveLoader(keyType, valueType, loader);

        // 2. 创建缓存定义
        CacheDefinition<K, V> definition = CacheDefinition.<K, V>builder(cacheName, keyType, valueType)
                .config(config)
                .loader(finalLoader)
                .build();

        // 3. 使用工厂创建基础缓存
        Cache<K, V> cache = cacheFactory.createCache(definition);

        // 4. 应用装饰器（自动刷新、同步等）
        cache = decoratorApplier.applyDecorators(cache, definition, nodeId);

        // 5. 如果创建了刷新器，注册到刷新器注册表
        if (decoratorApplier.hasRefresher(cacheName)) {
            CacheRefresher<K, V> refresher = decoratorApplier.getRefresher(cacheName);
            refresherRegistry.put(cacheName, refresher);
        }

        LOGGER.info("缓存创建成功: name={}, type={}", cacheName, definition.getType());
        return cache;
    }

    /**
     * 解析加载器
     */
    @SuppressWarnings("unchecked")
    private <K, V> Function<K, V> resolveLoader(
            Class<K> keyType,
            Class<V> valueType,
            Function<K, V> providedLoader
    ) {
        if (providedLoader != null) {
            return providedLoader;
        }

        if (loaderResolver != null) {
            try {
                CacheLoaderResolver<K, V> typedResolver = (CacheLoaderResolver<K, V>) loaderResolver;
                CacheLoader<K, V> loader = typedResolver.resolveCacheLoader(keyType, valueType);
                return loader != null ? loader::apply : null;
            } catch (Exception e) {
                LOGGER.warn("解析加载器失败", e);
            }
        }

        return null;
    }

    /**
     * 清理刷新器
     */
    private void cleanupRefresher(String cacheName) {
        CacheRefresher<?, ?> refresher = refresherRegistry.remove(cacheName);
        if (refresher != null) {
            stopRefresher(refresher);
        }
        // 同时从装饰器应用器中移除
        decoratorApplier.removeRefresher(cacheName);
    }

    /**
     * 停止刷新器
     */
    private void stopRefresher(CacheRefresher<?, ?> refresher) {
        try {
            if (refresher instanceof ScheduledCacheRefresher) {
                ((ScheduledCacheRefresher<?, ?>) refresher).stop();
            }
        } catch (Exception e) {
            LOGGER.error("停止刷新器失败", e);
        }
    }

    /**
     * 验证配置
     */
    private void validateConfiguration() {
        if (cacheFactory == null) {
            throw new CacheConfigurationException("cacheFactory", null, "缓存工厂不能为空", null);
        }
        if (defaultConfig == null) {
            throw new CacheConfigurationException("defaultConfig", null, "默认配置不能为空", null);
        }
    }

    /**
     * 检查是否已关闭
     */
    private void checkNotClosed() {
        if (closed.get()) {
            throw new CacheException("管理器已关闭", null);
        }
    }

    /**
     * 参数验证
     */
    private static <K, V> void validateParameters(
            String cacheName,
            Class<K> keyType,
            Class<V> valueType
    ) {
        if (cacheName == null || cacheName.trim().isEmpty()) {
            throw new CacheConfigurationException("cacheName", cacheName, "缓存名称不能为空", null);
        }
        if (keyType == null || valueType == null) {
            throw new CacheConfigurationException("types", null, "键类型和值类型不能为空", null);
        }
    }

    private static <K, V> void validateParameters(String cacheName, Cache<K, V> cache) {
        if (cacheName == null || cacheName.trim().isEmpty()) {
            throw new CacheConfigurationException("cacheName", cacheName, "缓存名称不能为空", null);
        }
        if (cache == null) {
            throw new CacheConfigurationException("cache", null, "缓存实例不能为空", null);
        }
    }

    @Override
    public String toString() {
        return String.format("FunctionalCacheManager{nodeId=%s, cacheCount=%d, closed=%s}",
                nodeId, getCacheCount(), closed.get());
    }
}