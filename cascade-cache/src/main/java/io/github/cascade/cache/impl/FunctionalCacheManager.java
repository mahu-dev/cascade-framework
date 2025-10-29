package io.github.cascade.cache.impl;

import io.github.cascade.cache.config.CascadeCacheProperties;
import io.github.cascade.cache.config.SyncProperties;
import io.github.cascade.cache.core.*;
import io.github.cascade.cache.definition.CacheDefinition;
import io.github.cascade.cache.definition.CacheSyncFactory;
import io.github.cascade.cache.definition.FunctionalCacheFactory;
import io.github.cascade.cache.exception.CacheConfigurationException;
import io.github.cascade.cache.exception.CacheException;
import io.github.cascade.cache.exception.CacheExceptionHandler;
import io.github.cascade.cache.loader.CacheLoaderResolver;
import io.github.cascade.cache.util.CacheManagerStats;
import io.github.cascade.cache.util.CacheTypeResolver;
import lombok.Getter;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * @author lionel lionelk@163.com
 * =============================
 * Date: 2025/10/29
 * Time: 17:28
 * =============================
 */

/**
 * 函数式缓存管理器 - 企业级实现
 * <p>
 * 核心改进：
 * 1. 简化架构：去除复杂的注册中心，直接管理缓存实例
 * 2. 函数式设计：优先使用 FunctionalCache 和 FunctionalCacheFactory
 * 3. 智能装饰：自动应用同步装饰器等横切功能
 * 4. 配置驱动：根据配置自动选择最佳缓存策略
 * 5. 异常安全：统一的异常处理机制
 * <p>
 * P1 级重构（2025-10-29）：
 * 1. 移除类级别泛型约束，支持管理不同类型的缓存
 * 2. 修复并发安全问题，使用 AtomicBoolean 替代 volatile boolean
 * 3. 统一异常处理，使用 CacheExceptionHandler
 * 4. 改进类型系统，使用 CacheTypeResolver
 * 5. 增强可观测性，添加统计和监控
 * <p>
 * 自动刷新缓存使用示例：
 * <pre>{@code
 * // 1. 无需传递loader和刷新间隔的自动刷新缓存（推荐）
 * Cache<String, User> userCache = cacheManager.getOrCreateAutoRefreshCache(
 *     "users", String.class, User.class);
 *
 * // 2. 手动指定loader的自动刷新缓存
 * Cache<String, Product> productCache = cacheManager.getOrCreateAutoRefreshCache(
 *     "products", String.class, Product.class, userId -> productService.findById(userId));
 *
 * // 3. 完整配置的自动刷新缓存
 * Cache<String, Order> orderCache = cacheManager.getOrCreateAutoRefreshCache(
 *     "orders", String.class, Order.class, orderId -> orderService.findById(orderId), 300);
 * }</pre>
 */
public class FunctionalCacheManager implements CacheManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(FunctionalCacheManager.class);

    // 核心组件 - 使用通配符类型支持异构缓存
    private final ConcurrentHashMap<String, Cache<?, ?>> cacheRegistry = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CacheRefresher<?, ?>> refresherRegistry = new ConcurrentHashMap<>();
    private final FunctionalCacheFactory cacheFactory;
    private final CascadeCacheProperties defaultConfig;
    private final CacheLoaderResolver<?, ?> loaderResolver;
    private final List<CacheSyncFactory> syncFactories;

    @Getter
    private final String nodeId;

    // P0 修复：使用 AtomicBoolean 保证并发安全
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * 构造函数 - P0级重构修复泛型类型推导
     */
    public FunctionalCacheManager(RedissonClient redissonClient,
                                  CascadeCacheProperties defaultConfig,
                                  CacheLoaderResolver<?, ?> loaderResolver,
                                  List<CacheSyncFactory> syncFactories) {
        this.cacheFactory = new FunctionalCacheFactory(redissonClient);
        this.defaultConfig = defaultConfig != null ? defaultConfig : CascadeCacheProperties.defaults();
        this.loaderResolver = loaderResolver;
        this.syncFactories = syncFactories != null ? syncFactories : List.of();
        this.nodeId = NodeIdManager.getInstance().getNodeId();

        validateConfiguration();

        LOGGER.info("函数式缓存管理器初始化: nodeId={}, syncFactories={}",
                nodeId, this.syncFactories.size());
    }

    /**
     * 带策略的构造函数 - P0级重构修复泛型类型推导
     */
    public FunctionalCacheManager(RedissonClient redissonClient,
                                  CascadeCacheProperties defaultConfig,
                                  CacheLoaderResolver<?, ?> loaderResolver,
                                  List<CacheSyncFactory> syncFactories,
                                  FunctionalCache.CacheStrategy defaultStrategy) {
        this.cacheFactory = new FunctionalCacheFactory(redissonClient, defaultStrategy);
        this.defaultConfig = defaultConfig != null ? defaultConfig : CascadeCacheProperties.defaults();
        this.loaderResolver = loaderResolver;
        this.syncFactories = syncFactories != null ? syncFactories : List.of();
        this.nodeId = NodeIdManager.getInstance().getNodeId();

        validateConfiguration();

        LOGGER.info("函数式缓存管理器初始化: nodeId={}, defaultStrategy={}, syncFactories={}",
                nodeId, defaultStrategy, this.syncFactories.size());
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
        return CacheExceptionHandler.safeExecute(() -> {
            checkNotClosed();
            validateParameters(cacheName, keyType, valueType);

            // 首先检查缓存是否已存在
            Cache<K, V> existingCache = (Cache<K, V>) cacheRegistry.get(cacheName);
            if (existingCache == null) {
                // 使用双重检查锁定模式创建缓存
                existingCache = (Cache<K, V>) cacheRegistry.computeIfAbsent(cacheName, name ->
                        createAndDecorateCache(name, keyType, valueType, config, loader));
            }
            return existingCache;
        }, null);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <K, V> Cache<K, V> getCache(String cacheName) {
        return CacheExceptionHandler.safeExecute(() -> {
            checkNotClosed();
            return (Cache<K, V>) cacheRegistry.get(cacheName);
        }, null);
    }

    // ==================== 缓存管理接口 ====================

    @Override
    public <K, V> boolean registerCache(String cacheName, Cache<K, V> cache) {
        return CacheExceptionHandler.safeExecute(() -> {
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
        }, false);
    }

    @Override
    public boolean removeCache(String cacheName) {
        return CacheExceptionHandler.safeExecute(() -> {
            checkNotClosed();

            Cache<?, ?> removed = cacheRegistry.remove(cacheName);
            if (removed != null) {
                // 同时移除对应的刷新器
                CacheRefresher<?, ?> refresher = refresherRegistry.remove(cacheName);
                if (refresher != null && refresher instanceof ScheduledCacheRefresher) {
                    CacheExceptionHandler.safeExecute(() -> {
                        ((ScheduledCacheRefresher<?, ?>) refresher).stop();
                        return null;
                    }, null);
                }

                // 关闭缓存
                if (!removed.isClosed()) {
                    removed.close();
                }
                LOGGER.info("缓存移除成功: {}", cacheName);
                return true;
            }

            return false;
        }, false);
    }

    @Override
    public boolean containsCache(String cacheName) {
        return CacheExceptionHandler.safeExecute(() -> {
            checkNotClosed();
            return cacheRegistry.containsKey(cacheName);
        }, false);
    }

    @Override
    public Collection<String> getCacheNames() {
        return CacheExceptionHandler.safeExecute(() -> {
            checkNotClosed();
            return cacheRegistry.keySet();
        }, List.of());
    }

    @Override
    public void clearAll() {
        CacheExceptionHandler.safeExecute(() -> {
            checkNotClosed();

            LOGGER.info("开始清空所有缓存...");

            int successCount = 0;
            int errorCount = 0;

            for (Cache<?, ?> cache : cacheRegistry.values()) {
                if (CacheExceptionHandler.safeExecute(() -> {
                    cache.clear();
                    return true;
                }, false)) {
                    successCount++;
                } else {
                    errorCount++;
                    LOGGER.error("清空缓存失败: cache={}", cache.getName());
                }
            }

            LOGGER.info("清空缓存完成: success={}, error={}", successCount, errorCount);
            return null;
        }, null);
    }

    @Override
    public int getCacheCount() {
        return CacheExceptionHandler.safeExecute(() -> {
            checkNotClosed();
            return cacheRegistry.size();
        }, 0);
    }

    // ==================== 生命周期管理 ====================

    @Override
    public void close() {
        if (closed.get()) {
            return;
        }

        LOGGER.info("开始关闭函数式缓存管理器: nodeId={}", nodeId);
        closed.set(true);

        int closedCount = 0;
        int errorCount = 0;

        // 先关闭所有刷新器
        for (CacheRefresher<?, ?> refresher : refresherRegistry.values()) {
            if (CacheExceptionHandler.safeExecute(() -> {
                if (refresher instanceof ScheduledCacheRefresher) {
                    refresher.stop();
                }
                return true;
            }, false)) {
                closedCount++;
            } else {
                errorCount++;
                LOGGER.error("关闭刷新器失败: error={}", refresher.toString());
            }
        }
        refresherRegistry.clear();

        // 再关闭所有缓存
        for (Cache<?, ?> cache : cacheRegistry.values()) {
            if (CacheExceptionHandler.safeExecute(() -> {
                if (!cache.isClosed()) {
                    cache.close();
                }
                return true;
            }, false)) {
                closedCount++;
            } else {
                errorCount++;
                LOGGER.error("关闭缓存失败: cache={}", cache.getName());
            }
        }

        cacheRegistry.clear();

        LOGGER.info("函数式缓存管理器关闭完成: nodeId={}, closed={}, errors={}",
                nodeId, closedCount, errorCount);
    }

    @Override
    public boolean isClosed() {
        return closed.get();
    }

    // ==================== 扩展功能 ====================

    @Override
    @SuppressWarnings("unchecked")
    public <K, V> CacheRefresher<K, V> getOrCreateCacheRefresher(String cacheName) {
        return CacheExceptionHandler.safeExecute(() -> {
            checkNotClosed();

            // 首先检查是否已存在刷新器实例
            CacheRefresher<?, ?> existingRefresher = refresherRegistry.get(cacheName);
            if (existingRefresher != null) {
                LOGGER.debug("返回已存在的缓存刷新器: {}", cacheName);
                return (CacheRefresher<K, V>) existingRefresher;
            }

            LOGGER.debug("开始创建新的缓存刷新器: {}", cacheName);

            // 使用双重检查锁定模式创建刷新器
            CacheRefresher<?, ?> refresher = refresherRegistry.computeIfAbsent(cacheName, name -> {
                Cache<K, V> cache = getCache(name);
                if (cache == null) {
                    LOGGER.warn("缓存不存在，无法创建刷新器: {}", name);
                    return null;
                }

                // 穿透装饰器获取底层的FunctionalCache
                FunctionalCache<K, V> functionalCache = extractFunctionalCache(cache);
                if (functionalCache == null) {
                    LOGGER.warn("不是函数式缓存，无法创建刷新器: {}", name);
                    return null;
                }

                return createRefresherFromFunctionalCache(name, cache, functionalCache);
            });

            return (CacheRefresher<K, V>) refresher;
        }, null);
    }

    /**
     * 获取或创建自动刷新缓存（用户无感知版本）
     * 自动解析loader并使用默认刷新间隔
     */
    public <K, V> Cache<K, V> getOrCreateAutoRefreshCache(String cacheName,
                                                          Class<K> keyType,
                                                          Class<V> valueType) {
        return CacheExceptionHandler.<Cache<K, V>>safeExecute((Supplier<Cache<K, V>>) () -> {
            checkNotClosed();

            // 步骤1：自动解析loader
            Function<K, V> loader = resolveLoader(keyType, valueType, null);
            if (loader == null) {
                LOGGER.warn("无法自动解析缓存加载器，将创建非自动刷新缓存: cacheName={}, keyType={}, valueType={}",
                        cacheName, keyType.getSimpleName(), valueType.getSimpleName());
                return getOrCreateCache(cacheName, keyType, valueType);
            }

            // 步骤2：获取默认刷新间隔
            long defaultInterval = defaultConfig.getRefreshIntervalSeconds();
            if (defaultInterval <= 0) {
                LOGGER.warn("刷新间隔配置无效，使用默认值10分钟: cacheName={}, configInterval={}",
                        cacheName, defaultInterval);
                defaultInterval = 600; // 10分钟
            }

            // 步骤3：创建自动刷新缓存
            Cache<K, V> autoRefreshCache = getOrCreateAutoRefreshCache(cacheName, keyType, valueType, loader, defaultInterval);

            LOGGER.info("自动刷新缓存创建成功（自动解析版本）: cacheName={}, keyType={}, valueType={}, refreshInterval={}秒",
                    cacheName, keyType.getSimpleName(), valueType.getSimpleName(), defaultInterval);

            return autoRefreshCache;
        }, getOrCreateCache(cacheName, keyType, valueType));
    }

    public <K, V> Cache<K, V> getOrCreateAutoRefreshCache(String cacheName,
                                                          Class<K> keyType,
                                                          Class<V> valueType,
                                                          Function<K, V> loader,
                                                          long refreshIntervalSeconds) {
        return CacheExceptionHandler.<Cache<K, V>>safeExecute((Supplier<Cache<K, V>>) () -> {
            checkNotClosed();

            // 步骤1：获取或创建底层缓存
            Cache<K, V> cache = getOrCreateCache(cacheName, keyType, valueType, loader);

            // 步骤2：获取或创建刷新器
            CacheRefresher<K, V> refresher = getOrCreateCacheRefresher(cacheName);

            if (refresher == null) {
                LOGGER.warn("无法创建刷新器，返回普通缓存: {}", cacheName);
                return cache;
            }

            // 步骤3：使用 AutoRefreshCache 包装
            AutoRefreshCache<K, V> autoRefreshCache = new AutoRefreshCache<>(
                    cache, refresher, refreshIntervalSeconds, true);

            LOGGER.info("自动刷新缓存创建成功: cacheName={}, refreshInterval={}秒",
                    cacheName, refreshIntervalSeconds);

            return autoRefreshCache;
        }, getOrCreateCache(cacheName, keyType, valueType, loader));
    }

    /**
     * 获取或创建自动刷新缓存（使用默认刷新间隔）
     */
    public <K, V> Cache<K, V> getOrCreateAutoRefreshCache(String cacheName,
                                                          Class<K> keyType,
                                                          Class<V> valueType,
                                                          Function<K, V> loader) {
        long defaultInterval = defaultConfig.getRefresh().getDefaultRefreshIntervalSeconds();
        return getOrCreateAutoRefreshCache(cacheName, keyType, valueType, loader, defaultInterval);
    }

    /**
     * 获取或创建分布式自动刷新缓存（融合自动刷新 + 分布式同步）
     * <p>
     * 这是最强大的缓存装饰器，同时具备：
     * 1. ✅ 自动刷新：拦截 get() 操作，自动追踪热点 key 并定时刷新
     * 2. ✅ 分布式同步：监听 Redis Pub/Sub，同步其他节点的缓存变更
     * 3. ✅ 刷新时同步：本地刷新数据后，自动通知其他节点更新
     * 4. ✅ 多级缓存：支持 L1 + L2 多级缓存架构
     * <p>
     * 使用场景：
     * - 分布式应用中的热点数据自动刷新
     * - 多节点环境下的缓存一致性保证
     * - 高并发场景下减少缓存穿透
     * <p>
     * 使用示例：
     * <pre>
     * Cache&lt;String, User&gt; cache = cacheManager.getOrCreateDistributedAutoRefreshCache(
     *     "users", String.class, User.class, userLoader, 300
     * );
     * // 之后正常使用，完全无感知
     * User user = cache.get("user123").orElse(null);  // 自动追踪并刷新
     * cache.put("user456", newUser);                  // 自动同步到其他节点
     * </pre>
     *
     * @param cacheName              缓存名称
     * @param keyType                键类型
     * @param valueType              值类型
     * @param loader                 数据加载器
     * @param refreshIntervalSeconds 刷新间隔（秒）
     * @return 分布式自动刷新缓存
     */
    public <K, V> Cache<K, V> getOrCreateDistributedAutoRefreshCache(
            String cacheName,
            Class<K> keyType,
            Class<V> valueType,
            Function<K, V> loader,
            long refreshIntervalSeconds) {
        checkNotClosed();

        // 步骤1：获取或创建底层缓存（L1 + L2）
        Cache<K, V> cache = getOrCreateCache(cacheName, keyType, valueType, loader);

        // 步骤2：获取或创建刷新器
        CacheRefresher<K, V> refresher = getOrCreateCacheRefresher(cacheName);
        if (refresher == null) {
            LOGGER.warn("无法创建刷新器，返回普通缓存: {}", cacheName);
            return cache;
        }

        // 步骤3：创建同步器
        CacheSync<K, V> cacheSync = createCacheSync(cacheName, defaultConfig);
        if (cacheSync == null) {
            LOGGER.warn("无法创建同步器，返回普通自动刷新缓存: {}", cacheName);
            return new AutoRefreshCache<>(cache, refresher, refreshIntervalSeconds, true);
        }

        // 步骤4：创建分布式自动刷新缓存
        DistributedAutoRefreshCache<K, V> distributedCache = new DistributedAutoRefreshCache<>(
                cache,
                refresher,
                cacheSync,
                nodeId,
                refreshIntervalSeconds,
                true
        );

        LOGGER.info("分布式自动刷新缓存创建成功: cacheName={}, nodeId={}, refreshInterval={}秒",
                cacheName, nodeId, refreshIntervalSeconds);

        return distributedCache;
    }

    /**
     * 获取或创建分布式自动刷新缓存（使用默认刷新间隔）
     *
     * @param cacheName 缓存名称
     * @param keyType   键类型
     * @param valueType 值类型
     * @param loader    数据加载器
     * @return 分布式自动刷新缓存
     */
    public <K, V> Cache<K, V> getOrCreateDistributedAutoRefreshCache(
            String cacheName,
            Class<K> keyType,
            Class<V> valueType,
            Function<K, V> loader) {
        long defaultInterval = defaultConfig.getRefresh().getDefaultRefreshIntervalSeconds();
        return getOrCreateDistributedAutoRefreshCache(cacheName, keyType, valueType, loader, defaultInterval);
    }

    /**
     * 获取或创建分布式自动刷新缓存（自动发现 CacheLoader）
     * <p>
     * 该方法会：
     * 1. 自动发现已注册的 CacheLoader
     * 2. 使用配置文件中的默认刷新间隔
     * 3. 返回支持自动刷新和分布式同步的缓存
     * <p>
     * 使用示例：
     * <pre>
     * // 前提：已有 UserCacheLoader 实现并通过 @Service 注册
     * Cache&lt;String, User&gt; cache = cacheManager.getOrCreateDistributedAutoRefreshCache(
     *     "users", String.class, User.class
     * );
     * // 完全无感知的自动刷新 + 分布式同步
     * User user = cache.get("user123").orElse(null);
     * </pre>
     *
     * @param cacheName 缓存名称
     * @param keyType   键类型
     * @param valueType 值类型
     * @return 分布式自动刷新缓存
     */
    public <K, V> Cache<K, V> getOrCreateDistributedAutoRefreshCache(
            String cacheName,
            Class<K> keyType,
            Class<V> valueType) {
        checkNotClosed();

        // 步骤1：获取或创建底层缓存（自动发现 CacheLoader）
        Cache<K, V> cache = getOrCreateCache(cacheName, keyType, valueType);

        // 步骤2：获取或创建刷新器
        CacheRefresher<K, V> refresher = getOrCreateCacheRefresher(cacheName);
        if (refresher == null) {
            LOGGER.warn("无法创建刷新器，返回普通缓存: {}", cacheName);
            return cache;
        }

        // 步骤3：创建同步器
        CacheSync<K, V> cacheSync = createCacheSync(cacheName, defaultConfig);
        if (cacheSync == null) {
            LOGGER.warn("无法创建同步器，返回普通自动刷新缓存: {}", cacheName);
            long defaultInterval = defaultConfig.getRefresh().getDefaultRefreshIntervalSeconds();
            return new AutoRefreshCache<>(cache, refresher, defaultInterval, true);
        }

        // 步骤4：使用默认刷新间隔创建分布式自动刷新缓存
        long defaultInterval = defaultConfig.getRefresh().getDefaultRefreshIntervalSeconds();
        DistributedAutoRefreshCache<K, V> distributedCache = new DistributedAutoRefreshCache<>(
                cache,
                refresher,
                cacheSync,
                nodeId,
                defaultInterval,
                true
        );

        LOGGER.info("分布式自动刷新缓存创建成功（自动发现CacheLoader）: cacheName={}, nodeId={}, refreshInterval={}秒",
                cacheName, nodeId, defaultInterval);

        return distributedCache;
    }

    /**
     * 获取管理器统计信息
     */
    public CacheManagerStats getStats() {
        return CacheExceptionHandler.safeExecute(() -> {
            checkNotClosed();

            return CacheManagerStats.builder()
                    .nodeId(nodeId)
                    .cacheCount(getCacheCount())
                    .refresherCount(refresherRegistry.size())
                    .syncerCount(syncFactories.size())
                    .closed(closed.get())
                    .cacheNames(getCacheNames())
                    .build();
        }, CacheManagerStats.empty());
    }

    // ==================== 私有方法 ====================

    /**
     * 创建并装饰缓存
     */
    private <K, V> Cache<K, V> createAndDecorateCache(String cacheName, Class<K> keyType, Class<V> valueType,
                                                      CascadeCacheProperties config, Function<K, V> loader) {
        // 解析加载器
        Function<K, V> finalLoader = resolveLoader(keyType, valueType, loader);

        // 创建缓存定义
        CacheDefinition<K, V> definition = CacheDefinition.<K, V>builder(cacheName, keyType, valueType)
                .config(config != null ? config : defaultConfig)
                .loader(finalLoader)
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
    @SuppressWarnings("unchecked")
    private <K, V> Function<K, V> resolveLoader(Class<K> keyType, Class<V> valueType, Function<K, V> providedLoader) {
        if (providedLoader != null) {
            return providedLoader;
        }

        if (loaderResolver != null) {
            return CacheExceptionHandler.safeExecute(() -> {
                CacheLoaderResolver<K, V> typedResolver = (CacheLoaderResolver<K, V>) loaderResolver;
                return typedResolver.resolveCacheLoader(keyType, valueType);
            }, null);
        }

        return null;
    }

    /**
     * 应用装饰器
     */
    private <K, V> Cache<K, V> applyDecorators(Cache<K, V> cache, CascadeCacheProperties config) {
        // 应用同步装饰器
        if (config.isSyncEnabled()) {
            CacheSync<K, V> sync = createCacheSync(cache.getName(), config);
            if (sync != null) {
                cache = SyncAwareCache.wrap(cache, sync, nodeId);
                LOGGER.debug("同步装饰器已应用: cache={}", cache.getName());
            }
        }

        return cache;
    }

    /**
     * 创建缓存同步器
     */
    @SuppressWarnings("unchecked")
    private <K, V> CacheSync<K, V> createCacheSync(String cacheName, CascadeCacheProperties config) {
        return CacheExceptionHandler.safeExecute(() -> {
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
            return null;
        }, null);
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
     * 参数验证
     */
    private static <K, V> void validateParameters(String cacheName, Class<K> keyType, Class<V> valueType) {
        if (cacheName == null || cacheName.trim().isEmpty()) {
            throw new CacheConfigurationException("cacheName", cacheName, "缓存名称不能为空", null);
        }
        if (keyType == null || valueType == null) {
            throw new CacheConfigurationException("types", null, "键类型和值类型不能为空", null);
        }

        // 使用类型解析器验证类型
        CacheTypeResolver.validateTypePair(keyType, valueType);
    }

    private static <K, V> void validateParameters(String cacheName, Cache<K, V> cache) {
        if (cacheName == null || cacheName.trim().isEmpty()) {
            throw new CacheConfigurationException("cacheName", cacheName, "缓存名称不能为空", null);
        }
        if (cache == null) {
            throw new CacheConfigurationException("cache", null, "缓存实例不能为空", null);
        }
    }

    
    /**
     * 从函数式缓存创建刷新器
     */
    private <K, V> CacheRefresher<K, V> createRefresherFromFunctionalCache(
            String cacheName, Cache<K, V> cache, FunctionalCache<K, V> functionalCache) {

        // 创建lambda引用变量的final副本
        final String finalCacheName = cacheName;
        final Cache<K, V> finalCache = cache;
        final CascadeCacheProperties finalConfig = this.defaultConfig; // 显式引用this字段

        // 获取函数式缓存的加载器
        CacheLoader<K, V> loader = functionalCache.getLoader().orElse(null);

        // 如果缓存没有加载器，尝试通过解析器获取
        CacheLoader<K, V> finalLoader = loader;
        if (finalLoader == null && loaderResolver != null) {
            @SuppressWarnings("unchecked")
            CacheLoaderResolver<K, V> typedResolver = (CacheLoaderResolver<K, V>) loaderResolver;
            finalLoader = typedResolver.resolveCacheLoader(
                    functionalCache.getKeyType(), functionalCache.getValueType());
        }

        if (finalLoader == null) {
            LOGGER.warn("无法创建刷新器，没有找到对应的加载器: {}", finalCacheName);
            return null;
        }

        // 创建刷新器 - 避免lambda作用域问题
        try {
            CacheRefresher<K, V> refresher = new ScheduledCacheRefresher<>(
                    finalCacheName, finalCache, finalLoader, finalConfig);
            LOGGER.info("缓存刷新器创建成功: {}", finalCacheName);
            return refresher;
        } catch (Exception e) {
            LOGGER.warn("创建缓存刷新器失败: cacheName={}", finalCacheName, e);
            return null;
        }
    }

    /**
     * 穿透装饰器获取底层的FunctionalCache
     */
    @SuppressWarnings("unchecked")
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

        return null; // 不是函数式缓存
    }

    /**
     * 检查是否已关闭
     */
    private void checkNotClosed() {
        if (closed.get()) {
            throw new CacheException("管理器已关闭", null);
        }
    }

    @Override
    public String toString() {
        return String.format("FunctionalCacheManager{nodeId=%s, cacheCount=%d, closed=%s}",
                nodeId, getCacheCount(), closed.get());
    }
}