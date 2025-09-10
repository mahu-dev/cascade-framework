package io.github.cascade.cache.simple;

import io.github.cascade.cache.config.CascadeCacheProperties;
import io.github.cascade.cache.config.SyncProperties;
import lombok.Getter;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 函数式缓存管理器 - 简化优雅的实现
 * <p>
 * 核心改进：
 * 1. 简化架构：去除复杂的注册中心，直接管理缓存实例
 * 2. 函数式设计：优先使用 FunctionalCache 和 FunctionalCacheFactory
 * 3. 智能装饰：自动应用同步装饰器等横切功能
 * 4. 配置驱动：根据配置自动选择最佳缓存策略
 *
 * @author cascade
 */
public class FunctionalCacheManager<K, V> implements CacheManager<K, V> {

    private static final Logger LOGGER = LoggerFactory.getLogger(FunctionalCacheManager.class);

    // 核心组件
    private final ConcurrentHashMap<String, Cache<K, V>> cacheRegistry = new ConcurrentHashMap<>();
    private final FunctionalCacheFactory cacheFactory;
    private final CascadeCacheProperties defaultConfig;
    private final CacheLoaderResolver<K, V> loaderResolver;
    private final List<CacheSyncFactory> syncFactories;

    @Getter
    private final String nodeId;

    private volatile boolean closed;

    /**
     * 构造函数
     */
    public FunctionalCacheManager(RedissonClient redissonClient,
                                  CascadeCacheProperties defaultConfig,
                                  CacheLoaderResolver<K, V> loaderResolver,
                                  List<CacheSyncFactory> syncFactories) {
        this.cacheFactory = new FunctionalCacheFactory(redissonClient);
        this.defaultConfig = defaultConfig != null ? defaultConfig : CascadeCacheProperties.defaults();
        this.loaderResolver = loaderResolver;
        this.syncFactories = syncFactories != null ? syncFactories : List.of();
        this.nodeId = generateNodeId();

        LOGGER.info("函数式缓存管理器初始化: nodeId={}, syncFactories={}",
                nodeId, this.syncFactories.size());
    }

    /**
     * 带策略的构造函数
     */
    public FunctionalCacheManager(RedissonClient redissonClient,
                                  CascadeCacheProperties defaultConfig,
                                  CacheLoaderResolver<K, V> loaderResolver,
                                  List<CacheSyncFactory> syncFactories,
                                  FunctionalCache.CacheStrategy defaultStrategy) {
        this.cacheFactory = new FunctionalCacheFactory(redissonClient, defaultStrategy);
        this.defaultConfig = defaultConfig != null ? defaultConfig : CascadeCacheProperties.defaults();
        this.loaderResolver = loaderResolver;
        this.syncFactories = syncFactories != null ? syncFactories : List.of();
        this.nodeId = generateNodeId();

        LOGGER.info("函数式缓存管理器初始化: nodeId={}, defaultStrategy={}, syncFactories={}",
                nodeId, defaultStrategy, this.syncFactories.size());
    }

    // ==================== CacheManager核心接口实现 ====================

    @Override
    public Cache<K, V> getOrCreateCache(String cacheName, Class<K> keyType, Class<V> valueType) {
        return getOrCreateCache(cacheName, keyType, valueType, defaultConfig, null);
    }

    @Override
    public Cache<K, V> getOrCreateCache(String cacheName, Class<K> keyType, Class<V> valueType,
                                        CascadeCacheProperties config) {
        return getOrCreateCache(cacheName, keyType, valueType, config, null);
    }

    @Override
    public Cache<K, V> getOrCreateCache(String cacheName, Class<K> keyType, Class<V> valueType,
                                        Function<K, V> loader) {
        return getOrCreateCache(cacheName, keyType, valueType, defaultConfig, loader);
    }

    @Override
    public Cache<K, V> getOrCreateCache(String cacheName, Class<K> keyType, Class<V> valueType,
                                        CascadeCacheProperties config, Function<K, V> loader) {
        checkNotClosed();
        validateParameters(cacheName, keyType, valueType);

        // 首先检查缓存是否已存在
        Cache<K, V> existingCache = cacheRegistry.get(cacheName);
        if (existingCache != null) {
            return existingCache;
        }

        // 使用双重检查锁定模式创建缓存
        return cacheRegistry.computeIfAbsent(cacheName, name ->
                createAndDecorateCache(name, keyType, valueType, config, loader));
    }

    @Override
    public Cache<K, V> getCache(String cacheName) {
        checkNotClosed();
        return cacheRegistry.get(cacheName);
    }

    // ==================== 缓存管理接口 ====================

    @Override
    public boolean registerCache(String cacheName, Cache<K, V> cache) {
        checkNotClosed();
        validateParameters(cacheName, cache);

        Cache<?, ?> previous = cacheRegistry.put(cacheName, cache);
        boolean isNew = previous == null;

        if (isNew) {
            LOGGER.info("缓存注册成功: {}", cacheName);
        } else {
            LOGGER.warn("缓存已存在并被替换: {}", cacheName);
            // 关闭之前的缓存
            if (!previous.isClosed()) {
                previous.close();
            }
        }

        return isNew;
    }

    @Override
    public boolean removeCache(String cacheName) {
        checkNotClosed();

        Cache<?, ?> removed = cacheRegistry.remove(cacheName);
        if (removed != null) {
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

        int successCount = 0;
        int errorCount = 0;

        for (Cache<?, ?> cache : cacheRegistry.values()) {
            try {
                cache.clear();
                successCount++;
            } catch (RuntimeException e) {
                errorCount++;
                LOGGER.error("清空缓存失败: cache={}, error={}", cache.getName(), e.getMessage());
            }
        }

        LOGGER.info("清空缓存完成: success={}, error={}", successCount, errorCount);
    }

    @Override
    public int getCacheCount() {
        checkNotClosed();
        return cacheRegistry.size();
    }

    // ==================== 生命周期管理 ====================

    @Override
    public void close() {
        if (closed) {
            return;
        }

        LOGGER.info("开始关闭函数式缓存管理器: nodeId={}", nodeId);
        closed = true;

        int closedCount = 0;
        int errorCount = 0;

        for (Cache<?, ?> cache : cacheRegistry.values()) {
            try {
                if (!cache.isClosed()) {
                    cache.close();
                    closedCount++;
                }
            } catch (RuntimeException e) {
                errorCount++;
                LOGGER.error("关闭缓存失败: cache={}, error={}", cache.getName(), e.getMessage());
            }
        }

        cacheRegistry.clear();

        LOGGER.info("函数式缓存管理器关闭完成: nodeId={}, closed={}, errors={}",
                nodeId, closedCount, errorCount);
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    // ==================== 扩展功能 ====================

    @Override
    public CacheRefresher<K, V> getOrCreateCacheRefresher(String cacheName) {
        checkNotClosed();

        CacheRefresher<K, V> result = null;

        Cache<K, V> cache = getCache(cacheName);
        if (cache == null) {
            LOGGER.warn("缓存不存在，无法创建刷新器: {}", cacheName);
        } else {
            // 穿透装饰器获取底层的FunctionalCache
            FunctionalCache<K, V> functionalCache = extractFunctionalCache(cache);
            if (functionalCache == null) {
                LOGGER.warn("不是函数式缓存，无法创建刷新器: {}", cacheName);
            } else {
                result = createRefresherFromFunctionalCache(cacheName, cache, functionalCache);
            }
        }

        return result;
    }

    /**
     * 从函数式缓存创建刷新器
     */
    private CacheRefresher<K, V> createRefresherFromFunctionalCache(
            String cacheName, Cache<K, V> cache, FunctionalCache<K, V> functionalCache) {

        // 获取函数式缓存的加载器
        CacheLoader<K, V> loader = functionalCache.getLoader().orElse(null);

        // 如果缓存没有加载器，尝试通过解析器获取
        if (loader == null && loaderResolver != null) {
            loader = loaderResolver.resolveCacheLoader(
                    functionalCache.getKeyType(), functionalCache.getValueType());
        }

        if (loader == null) {
            LOGGER.warn("无法创建刷新器，没有找到对应的加载器: {}", cacheName);
            return null;
        }

        // 创建刷新器
        try {
            CacheRefresher<K, V> refresher = new ScheduledCacheRefresher<>(
                    cacheName, cache, loader, defaultConfig);
            LOGGER.info("缓存刷新器创建成功: {}", cacheName);
            return refresher;
        } catch (RuntimeException e) {
            LOGGER.error("创建缓存刷新器失败: cache={}, error={}", cacheName, e.getMessage());
            return null;
        }
    }

    /**
     * 创建带自定义策略的缓存
     */
    public Cache<K, V> createCache(String cacheName, Class<K> keyType, Class<V> valueType,
                                   CascadeCacheProperties config, Function<K, V> loader,
                                   FunctionalCache.CacheStrategy strategy) {
        checkNotClosed();
        validateParameters(cacheName, keyType, valueType);

        if (containsCache(cacheName)) {
            throw new IllegalArgumentException("缓存已存在: " + cacheName);
        }

        Cache<K, V> cache = cacheFactory.createCache(
                CacheDefinition.<K, V>builder(cacheName, keyType, valueType)
                        .config(config != null ? config : defaultConfig)
                        .loader(loader)
                        .loaderResolver(loaderResolver)
                        .build(),
                strategy
        );

        // 应用装饰器
        cache = applyDecorators(cache, config != null ? config : defaultConfig);

        // 注册缓存
        registerCache(cacheName, cache);

        return cache;
    }

    /**
     * 创建自定义管道缓存
     */
    public Cache<K, V> createCustomPipelineCache(String cacheName,
                                                 Class<K> keyType,
                                                 Class<V> valueType,
                                                 Function<K, V> customPipeline) {
        checkNotClosed();
        validateParameters(cacheName, keyType, valueType);

        if (containsCache(cacheName)) {
            throw new IllegalArgumentException("缓存已存在: " + cacheName);
        }

        Cache<K, V> cache = cacheFactory.createCustomPipelineCache(
                cacheName, keyType, valueType, customPipeline);

        // 注册缓存
        registerCache(cacheName, cache);

        return cache;
    }

    /**
     * 获取管理器统计信息
     */
    public CacheManagerStats getStats() {
        checkNotClosed();

        return CacheManagerStats.builder()
                .nodeId(nodeId)
                .cacheCount(getCacheCount())
                .refresherCount(0) // 简化实现，不单独跟踪
                .syncerCount(0)    // 简化实现，不单独跟踪
                .closed(closed)
                .cacheNames(getCacheNames())
                .build();
    }

    // ==================== 私有方法 ====================

    /**
     * 创建并装饰缓存
     */
    private Cache<K, V> createAndDecorateCache(String cacheName, Class<K> keyType, Class<V> valueType,
                                               CascadeCacheProperties config, Function<K, V> loader) {
        // 解析加载器
        Function<K, V> finalLoader = resolveLoader(keyType, valueType, loader);

        // 创建缓存定义
        CacheDefinition<K, V> definition = CacheDefinition.<K, V>builder(cacheName, keyType, valueType)
                .config(config != null ? config : defaultConfig)
                .loader(finalLoader)
                .loaderResolver(loaderResolver)
                .build();

        // 使用工厂创建缓存
        Cache<K, V> cache = cacheFactory.createCache(definition);

        // 应用装饰器
        cache = applyDecorators(cache, definition.getConfig());

        LOGGER.info("函数式缓存创建成功: name={}, type={}", cacheName, definition.getType());
        return cache;
    }

    /**
     * 解析加载器
     */
    private Function<K, V> resolveLoader(Class<K> keyType, Class<V> valueType, Function<K, V> providedLoader) {
        if (providedLoader != null) {
            return providedLoader;
        }

        if (loaderResolver != null) {
            try {
                return loaderResolver.resolveCacheLoader(keyType, valueType);
            } catch (RuntimeException e) {
                LOGGER.warn("解析加载器失败: keyType={}, valueType={}, error={}",
                        keyType.getSimpleName(), valueType.getSimpleName(), e.getMessage());
            }
        }

        return null;
    }

    /**
     * 应用装饰器
     */
    private Cache<K, V> applyDecorators(Cache<K, V> cache, CascadeCacheProperties config) {
        // 应用同步装饰器
        if (config.isSyncEnabled()) {
            CacheSync<K, V> sync = createCacheSync(cache.getName(), config);
            if (sync != null) {
                cache = SyncAwareCache.wrap(cache, sync, nodeId);
                LOGGER.debug("同步装饰器已应用: cache={}", cache.getName());
            }
        }

        // 可以在这里添加更多装饰器，如监控、安全等

        return cache;
    }

    /**
     * 创建缓存同步器
     */
    private CacheSync<K, V> createCacheSync(String cacheName, CascadeCacheProperties config) {
        try {
            SyncProperties syncConfig = config.getSyncConfig();

            // 查找合适的同步工厂
            CacheSyncFactory factory = syncFactories.stream()
                    .filter(f -> f.supports(syncConfig.getType()))
                    .findFirst()
                    .orElse(null);

            if (factory != null) {
                CacheSync<K, V> sync = factory.createCacheSync(syncConfig);
                LOGGER.debug("同步器创建成功: cache={}, type={}", cacheName, syncConfig.getType());
                return sync;
            } else {
                LOGGER.warn("未找到支持类型{}的同步工厂: cache={}", syncConfig.getType(), cacheName);
            }
        } catch (RuntimeException e) {
            LOGGER.error("创建同步器失败: cache={}, error={}", cacheName, e.getMessage());
        }

        return null;
    }

    /**
     * 参数验证
     */
    private static void validateParameters(String cacheName, Class<?> keyType, Class<?> valueType) {
        if (cacheName == null || cacheName.trim().isEmpty()) {
            throw new IllegalArgumentException("缓存名称不能为空");
        }
        if (keyType == null || valueType == null) {
            throw new IllegalArgumentException("键类型和值类型不能为空");
        }
    }

    private static void validateParameters(String cacheName, Object cache) {
        if (cacheName == null || cacheName.trim().isEmpty()) {
            throw new IllegalArgumentException("缓存名称不能为空");
        }
        if (cache == null) {
            throw new IllegalArgumentException("缓存实例不能为空");
        }
    }

    /**
     * 生成节点ID
     */
    private static String generateNodeId() {
        String appName = System.getProperty("spring.application.name", "functional-cache");
        long timestamp = System.currentTimeMillis() % 100000;
        return String.format("%s-%d", appName, timestamp);
    }

    /**
     * 穿透装饰器获取底层的FunctionalCache
     * <p>
     * 解决装饰器模式导致的类型识别问题：
     * SyncAwareCache(FunctionalCache) -> FunctionalCache
     */
    private <K, V> FunctionalCache<K, V> extractFunctionalCache(Cache<K, V> cache) {
        // 直接是FunctionalCache
        if (cache instanceof FunctionalCache<?, ?>) {
            return (FunctionalCache<K, V>) cache;
        }

        // 穿透SyncAwareCache装饰器
        if (cache instanceof SyncAwareCache<?, ?>) {
            SyncAwareCache<K, V> syncAware = (SyncAwareCache<K, V>) cache;
            Cache<K, V> delegate = syncAware.getDelegate();
            return extractFunctionalCache(delegate); // 递归穿透多层装饰器
        }

        // 可以在这里添加对其他装饰器的支持
        // if (cache instanceof OtherDecoratorCache<?, ?>) { ... }

        return null; // 不是函数式缓存
    }

    /**
     * 检查是否已关闭
     */
    private void checkNotClosed() {
        if (closed) {
            throw new IllegalStateException("缓存管理器已关闭: " + nodeId);
        }
    }

    @Override
    public String toString() {
        return String.format("FunctionalCacheManager{nodeId=%s, cacheCount=%d, closed=%s}",
                nodeId, getCacheCount(), closed);
    }
}