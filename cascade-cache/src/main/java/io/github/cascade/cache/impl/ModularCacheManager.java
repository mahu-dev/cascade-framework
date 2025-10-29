//package io.github.cascade.cache.impl;
//
//import io.github.cascade.cache.config.CascadeCacheProperties;
//import io.github.cascade.cache.core.Cache;
//import io.github.cascade.cache.core.CacheManager;
//import io.github.cascade.cache.core.CacheRefresher;
//import io.github.cascade.cache.definition.CacheSyncFactory;
//import io.github.cascade.cache.definition.FunctionalCacheFactory;
//import io.github.cascade.cache.exception.CacheException;
//import io.github.cascade.cache.loader.CacheLoaderResolver;
//import io.github.cascade.cache.util.CacheManagerStats;
//import lombok.Getter;
//import org.redisson.api.RedissonClient;
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//
//import java.time.Duration;
//import java.util.Collection;
//import java.util.List;
//import java.util.Optional;
//import java.util.function.Function;
//
/// **
// * 模块化缓存管理器 - 企业级现代化实现
// * <p>
// * 核心架构：
// * 基于7个核心组件的模块化设计，采用组合模式整合功能：
// * 1. CacheRegistry - 缓存注册表
// * 2. CacheRefresherManager - 刷新器管理器
// * 3. CacheConfigurationManager - 配置管理器
// * 4. CacheLifecycleManager - 生命周期管理器
// * 5. CacheMetricsCollector - 指标收集器
// * 6. CacheFactory - 缓存工厂
// * 7. AutoRefreshCacheManager - 自动刷新管理器
// * <p>
// * 设计特点：
// * - 完全组件化：每个组件职责单一，易于测试和维护
// * - 现代化API：使用Builder模式和链式调用
// * - 类型安全：完善的泛型设计
// * - 高性能：现代并发控制机制
// * - 企业级特性：完整的监控、配置管理、异常处理
// * - 统一异常处理：CacheOperationTemplate统一处理异常
// * <p>
// * 使用示例：
// * <pre>{@code
// * // 创建管理器
// * ModularCacheManager manager = ModularCacheManager.builder()
// *     .redissonClient(redissonClient)
// *     .config(config)
// *     .loaderResolver(loaderResolver)
// *     .syncFactories(syncFactories)
// *     .build();
// *
// * // 创建缓存
// * Cache<String, User> userCache = manager.<String, User>cache()
// *     .name("users")
// *     .loader(id -> userService.findById(id))
// *     .ttl(Duration.ofMinutes(30))
// *     .autoRefresh(true)
// *     .refreshInterval(Duration.ofMinutes(5))
// *     .build();
// *
// * // 分布式自动刷新缓存
// * Cache<String, Product> productCache = manager.<String, Product>cache()
// *     .name("products")
// *     .loader(id -> productService.findById(id))
// *     .distributed(true)
// *     .autoRefresh(true)
// *     .build();
// *
// * // 获取统计信息
// * CacheStats stats = manager.getStats("users");
// * }</pre>
// *
// * @author lionel lionelk@163.com
// * =============================
// * Date: 2025/10/30
// * Time: 09:15
// * =============================
// */
//public class ModularCacheManager implements CacheManager {
//
//    private static final Logger LOGGER = LoggerFactory.getLogger(ModularCacheManager.class);
//
//    // ==================== 核心组件 ====================
//
//    private final CacheRegistry cacheRegistry;
//    private final CacheRefresherManager refresherManager;
//    private final CacheConfigurationManager configManager;
//    private final CacheLifecycleManager lifecycleManager;
//    private final CacheMetricsCollector metricsCollector;
//    private final CacheFactory cacheFactory;
//    private final AutoRefreshCacheManager autoRefreshManager;
//    private final CacheOperationTemplate operationTemplate;
//
//    @Getter
//    private final String nodeId;
//
//    // ==================== 构造函数 ====================
//
//    /**
//     * 构造函数
//     *
//     * @param nodeId            节点ID
//     * @param cacheRegistry     缓存注册表
//     * @param refresherManager  刷新器管理器
//     * @param configManager     配置管理器
//     * @param lifecycleManager  生命周期管理器
//     * @param metricsCollector  指标收集器
//     * @param cacheFactory      缓存工厂
//     * @param autoRefreshManager 自动刷新管理器
//     */
//    private ModularCacheManager(String nodeId,
//                               CacheRegistry cacheRegistry,
//                               CacheRefresherManager refresherManager,
//                               CacheConfigurationManager configManager,
//                               CacheLifecycleManager lifecycleManager,
//                               CacheMetricsCollector metricsCollector,
//                               CacheFactory cacheFactory,
//                               AutoRefreshCacheManager autoRefreshManager) {
//        this.nodeId = nodeId;
//        this.cacheRegistry = cacheRegistry;
//        this.refresherManager = refresherManager;
//        this.configManager = configManager;
//        this.lifecycleManager = lifecycleManager;
//        this.metricsCollector = metricsCollector;
//        this.cacheFactory = cacheFactory;
//        this.autoRefreshManager = autoRefreshManager;
//        this.operationTemplate = new CacheOperationTemplate(metricsCollector);
//
//        // 初始化生命周期管理器
//        lifecycleManager.initialize();
//
//        LOGGER.info("模块化缓存管理器初始化完成: nodeId={}", nodeId);
//    }
//
//    /**
//     * 创建Builder
//     *
//     * @return Builder实例
//     */
//    public static Builder builder() {
//        return new Builder();
//    }
//
//    // ==================== CacheManager接口实现 ====================
//
//    @Override
//    public <K, V> Cache<K, V> getOrCreateCache(String cacheName, Class<K> keyType, Class<V> valueType) {
//        return getOrCreateCache(cacheName, keyType, valueType, configManager.getDefaultConfig(), null);
//    }
//
//    @Override
//    public <K, V> Cache<K, V> getOrCreateCache(String cacheName, Class<K> keyType, Class<V> valueType,
//                                               CascadeCacheProperties config) {
//        return getOrCreateCache(cacheName, keyType, valueType, config, null);
//    }
//
//    @Override
//    public <K, V> Cache<K, V> getOrCreateCache(String cacheName, Class<K> keyType, Class<V> valueType,
//                                               Function<K, V> loader) {
//        return getOrCreateCache(cacheName, keyType, valueType, configManager.getDefaultConfig(), loader);
//    }
//
//    @Override
//    public <K, V> Cache<K, V> getOrCreateCache(String cacheName, Class<K> keyType, Class<V> valueType,
//                                               CascadeCacheProperties config, Function<K, V> loader) {
//        return operationTemplate.execute("getOrCreateCache", () -> {
//            lifecycleManager.checkNotClosed();
//            configManager.validateCacheParameters(cacheName, keyType, valueType);
//
//            return cacheRegistry.computeIfAbsent(cacheName, name ->
//                cacheFactory.createAndDecorateCache(name, keyType, valueType,
//                    configManager.validateAndGetConfig(config), loader));
//        }, cacheName, () -> null);
//    }
//
//    @Override
//    @SuppressWarnings("unchecked")
//    public <K, V> Cache<K, V> getCache(String cacheName) {
//        return operationTemplate.execute("getCache", () -> {
//            lifecycleManager.checkNotClosed();
//            return (Cache<K, V>) cacheRegistry.getCache(cacheName);
//        }, cacheName, () -> null);
//    }
//
//    // ==================== 缓存管理接口 ====================
//
//    @Override
//    public <K, V> boolean registerCache(String cacheName, Cache<K, V> cache) {
//        return operationTemplate.execute("registerCache", () -> {
//            lifecycleManager.checkNotClosed();
//            configManager.validateCacheRegistration(cacheName, cache);
//            return cacheRegistry.registerCache(cacheName, cache);
//        }, cacheName, () -> false);
//    }
//
//    @Override
//    public boolean removeCache(String cacheName) {
//        return operationTemplate.execute("removeCache", () -> {
//            lifecycleManager.checkNotClosed();
//
//            // 移除刷新器
//            refresherManager.removeCacheRefresher(cacheName);
//
//            // 移除缓存
//            return cacheRegistry.removeCache(cacheName);
//        }, cacheName, () -> false);
//    }
//
//    @Override
//    public boolean containsCache(String cacheName) {
//        return operationTemplate.execute("containsCache", () -> {
//            lifecycleManager.checkNotClosed();
//            return cacheRegistry.containsCache(cacheName);
//        }, cacheName, () -> false);
//    }
//
//    @Override
//    public Collection<String> getCacheNames() {
//        return operationTemplate.execute("getCacheNames", () -> {
//            lifecycleManager.checkNotClosed();
//            return cacheRegistry.getCacheNames();
//        }, "system", () -> List.of());
//    }
//
//    @Override
//    public void clearAll() {
//        operationTemplate.execute("clearAll", () -> {
//            lifecycleManager.checkNotClosed();
//            cacheRegistry.clearAll();
//            return null;
//        }, "system", () -> null);
//    }
//
//    @Override
//    public int getCacheCount() {
//        return operationTemplate.execute("getCacheCount", () -> {
//            lifecycleManager.checkNotClosed();
//            return cacheRegistry.getCacheCount();
//        }, "system", () -> 0);
//    }
//
//    // ==================== 生命周期管理 ====================
//
//    @Override
//    public void close() {
//        if (lifecycleManager.isClosed()) {
//            return;
//        }
//
//        LOGGER.info("开始关闭模块化缓存管理器: nodeId={}", nodeId);
//
//        long startTime = System.currentTimeMillis();
//
//        try {
//            // 按照生命周期顺序关闭组件
//            lifecycleManager.close();
//
//            long duration = System.currentTimeMillis() - startTime;
//            LOGGER.info("模块化缓存管理器关闭完成: nodeId={}, duration={}ms", nodeId, duration);
//        } catch (Exception e) {
//            LOGGER.error("关闭模块化缓存管理器时发生错误: nodeId={}", nodeId, e);
//        }
//    }
//
//    @Override
//    public boolean isClosed() {
//        return lifecycleManager.isClosed();
//    }
//
//    // ==================== 扩展功能 ====================
//
//    @Override
//    @SuppressWarnings("unchecked")
//    public <K, V> CacheRefresher<K, V> getOrCreateCacheRefresher(String cacheName) {
//        return operationTemplate.execute("getOrCreateCacheRefresher", () -> {
//            lifecycleManager.checkNotClosed();
//            Cache<K, V> cache = getCache(cacheName);
//            return cache != null ? (CacheRefresher<K, V>) refresherManager.getOrCreateCacheRefresher(cacheName, cache) : null;
//        }, cacheName, () -> null);
//    }
//
//    /**
//     * 获取或创建自动刷新缓存（用户无感知版本）
//     */
//    public <K, V> Cache<K, V> getOrCreateAutoRefreshCache(String cacheName,
//                                                          Class<K> keyType,
//                                                          Class<V> valueType) {
//        return operationTemplate.execute("getOrCreateAutoRefreshCache", () -> {
//            lifecycleManager.checkNotClosed();
//            return autoRefreshManager.getOrCreateAutoRefreshCache(cacheName, keyType, valueType);
//        }, cacheName, () -> getOrCreateCache(cacheName, keyType, valueType));
//    }
//
//    /**
//     * 获取或创建自动刷新缓存（使用默认刷新间隔）
//     */
//    public <K, V> Cache<K, V> getOrCreateAutoRefreshCache(String cacheName,
//                                                          Class<K> keyType,
//                                                          Class<V> valueType,
//                                                          Function<K, V> loader) {
//        return operationTemplate.execute("getOrCreateAutoRefreshCache", () -> {
//            lifecycleManager.checkNotClosed();
//            return autoRefreshManager.getOrCreateAutoRefreshCache(cacheName, keyType, valueType, loader);
//        }, cacheName, () -> getOrCreateCache(cacheName, keyType, valueType, loader));
//    }
//
//    /**
//     * 获取或创建自动刷新缓存（完整配置）
//     */
//    public <K, V> Cache<K, V> getOrCreateAutoRefreshCache(String cacheName,
//                                                          Class<K> keyType,
//                                                          Class<V> valueType,
//                                                          Function<K, V> loader,
//                                                          long refreshIntervalSeconds) {
//        return operationTemplate.execute("getOrCreateAutoRefreshCache", () -> {
//            lifecycleManager.checkNotClosed();
//            return autoRefreshManager.getOrCreateAutoRefreshCache(cacheName, keyType, valueType,
//                loader, refreshIntervalSeconds);
//        }, cacheName, () -> getOrCreateCache(cacheName, keyType, valueType, loader));
//    }
//
//    /**
//     * 获取或创建分布式自动刷新缓存（完整配置）
//     */
//    public <K, V> Cache<K, V> getOrCreateDistributedAutoRefreshCache(
//            String cacheName,
//            Class<K> keyType,
//            Class<V> valueType,
//            Function<K, V> loader,
//            long refreshIntervalSeconds) {
//        return operationTemplate.execute("getOrCreateDistributedAutoRefreshCache", () -> {
//            lifecycleManager.checkNotClosed();
//            return autoRefreshManager.getOrCreateDistributedAutoRefreshCache(cacheName, keyType, valueType,
//                loader, refreshIntervalSeconds);
//        }, cacheName, () -> getOrCreateCache(cacheName, keyType, valueType, loader));
//    }
//
//    /**
//     * 获取或创建分布式自动刷新缓存（使用默认刷新间隔）
//     */
//    public <K, V> Cache<K, V> getOrCreateDistributedAutoRefreshCache(
//            String cacheName,
//            Class<K> keyType,
//            Class<V> valueType,
//            Function<K, V> loader) {
//        return operationTemplate.execute("getOrCreateDistributedAutoRefreshCache", () -> {
//            lifecycleManager.checkNotClosed();
//            return autoRefreshManager.getOrCreateDistributedAutoRefreshCache(cacheName, keyType, valueType, loader);
//        }, cacheName, () -> getOrCreateCache(cacheName, keyType, valueType, loader));
//    }
//
//    /**
//     * 获取或创建分布式自动刷新缓存（自动发现CacheLoader）
//     */
//    public <K, V> Cache<K, V> getOrCreateDistributedAutoRefreshCache(
//            String cacheName,
//            Class<K> keyType,
//            Class<V> valueType) {
//        return operationTemplate.execute("getOrCreateDistributedAutoRefreshCache", () -> {
//            lifecycleManager.checkNotClosed();
//            return autoRefreshManager.getOrCreateDistributedAutoRefreshCache(cacheName, keyType, valueType);
//        }, cacheName, () -> getOrCreateCache(cacheName, keyType, valueType));
//    }
//
//    /**
//     * 获取管理器统计信息
//     */
//    public CacheManagerStats getStats() {
//        return operationTemplate.execute("getStats", () -> {
//            return metricsCollector.generateStats(cacheRegistry, refresherManager, configManager);
//        }, "system", () -> CacheManagerStats.empty());
//    }
//
//    /**
//     * 获取特定缓存的统计信息
//     *
//     * @param cacheName 缓存名称
//     * @return 缓存统计信息，如果缓存不存在则返回空
//     */
//    public Optional<CacheStats> getStats(String cacheName) {
//        return operationTemplate.execute("getCacheStats", () -> {
//            lifecycleManager.checkNotClosed();
//            Cache<?, ?> cache = cacheRegistry.getCache(cacheName);
//            if (cache != null) {
//                return Optional.of(CacheStats.builder()
//                    .cacheName(cacheName)
//                    .cacheType(cache.getClass().getSimpleName())
//                    .hitRate(metricsCollector.getCacheHitRate())
//                    .errorRate(metricsCollector.getErrorRate())
//                    .build());
//            }
//            return Optional.empty();
//        }, cacheName, () -> Optional.empty());
//    }
//
//    /**
//     * 重置所有统计数据
//     */
//    public void resetStats() {
//        operationTemplate.execute("resetStats", () -> {
//            metricsCollector.reset();
//            return null;
//        }, "system", () -> null);
//    }
//
//    /**
//     * 获取管理器健康状态
//     *
//     * @return 健康状态描述
//     */
//    public HealthStatus getHealthStatus() {
//        return operationTemplate.execute("getHealthStatus", () -> {
//            if (lifecycleManager.isClosed()) {
//                return HealthStatus.DOWN;
//            }
//
//            int cacheCount = cacheRegistry.getCacheCount();
//            if (cacheCount == 0) {
//                return HealthStatus.UP; // 空状态是健康的
//            }
//
//            double errorRate = metricsCollector.getErrorRate();
//            if (errorRate > 50.0) {
//                return HealthStatus.DOWN;
//            } else if (errorRate > 10.0) {
//                return HealthStatus.WARNING;
//            }
//
//            return HealthStatus.UP;
//        }, "system", () -> HealthStatus.DOWN);
//    }
//
//    /**
//     * 创建缓存构建器
//     *
//     * @param <K> 键类型
//     * @param <V> 值类型
//     * @return 缓存构建器
//     */
//    public <K, V> CacheBuilder<K, V> cache() {
//        return new CacheBuilder<>(this);
//    }
//
//    // ==================== Builder类 ====================
//
//    /**
//     * 缓存管理器构建器
//     */
//    public static class Builder {
//        private RedissonClient redissonClient;
//        private CascadeCacheProperties config;
//        private CacheLoaderResolver<?, ?> loaderResolver;
//        private List<CacheSyncFactory> syncFactories;
//        private io.github.cascade.cache.impl.FunctionalCache.CacheStrategy defaultStrategy =
//            io.github.cascade.cache.impl.FunctionalCache.CacheStrategy.STANDARD;
//
//        /**
//         * 设置Redisson客户端
//         */
//        public Builder redissonClient(RedissonClient redissonClient) {
//            this.redissonClient = redissonClient;
//            return this;
//        }
//
//        /**
//         * 设置缓存配置
//         */
//        public Builder config(CascadeCacheProperties config) {
//            this.config = config;
//            return this;
//        }
//
//        /**
//         * 设置加载器解析器
//         */
//        public Builder loaderResolver(CacheLoaderResolver<?, ?> loaderResolver) {
//            this.loaderResolver = loaderResolver;
//            return this;
//        }
//
//        /**
//         * 设置同步工厂列表
//         */
//        public Builder syncFactories(List<CacheSyncFactory> syncFactories) {
//            this.syncFactories = syncFactories;
//            return this;
//        }
//
//        /**
//         * 设置默认缓存策略
//         */
//        public Builder defaultStrategy(io.github.cascade.cache.impl.FunctionalCache.CacheStrategy strategy) {
//            this.defaultStrategy = strategy;
//            return this;
//        }
//
//        /**
//         * 构建模块化缓存管理器
//         */
//        public ModularCacheManager build() {
//            // 生成节点ID
//            String nodeId = NodeIdManager.getInstance().getNodeId();
//
//            // 创建配置管理器
//            CacheConfigurationManager configManager = new CacheConfigurationManager(config);
//
//            // 创建核心组件
//            CacheRegistry cacheRegistry = new CacheRegistry();
//            CacheRefresherManager refresherManager = new CacheRefresherManager(
//                configManager.getDefaultConfig(), loaderResolver);
//            CacheMetricsCollector metricsCollector = new CacheMetricsCollector(nodeId);
//
//            // 创建工厂
//            FunctionalCacheFactory functionalCacheFactory = new FunctionalCacheFactory(redissonClient, defaultStrategy);
//            CacheFactory cacheFactory = new CacheFactory(functionalCacheFactory, loaderResolver, syncFactories, nodeId);
//
//            // 创建生命周期管理器
//            CacheLifecycleManager lifecycleManager = new CacheLifecycleManager(nodeId, cacheRegistry, refresherManager);
//
//            // 创建自动刷新管理器
//            AutoRefreshCacheManager autoRefreshManager = new AutoRefreshCacheManager(
//                cacheRegistry, refresherManager, cacheFactory, configManager, loaderResolver, nodeId);
//
//            return new ModularCacheManager(nodeId, cacheRegistry, refresherManager, configManager,
//                lifecycleManager, metricsCollector, cacheFactory, autoRefreshManager);
//        }
//    }
//
//    /**
//     * 缓存构建器
//     */
//    public static class CacheBuilder<K, V> {
//        private final ModularCacheManager manager;
//        private String name;
//        private Class<K> keyType;
//        private Class<V> valueType;
//        private Function<K, V> loader;
//        private Duration ttl;
//        private boolean autoRefresh = false;
//        private Duration refreshInterval;
//        private boolean distributed = false;
//        private CascadeCacheProperties config;
//
//        private CacheBuilder(ModularCacheManager manager) {
//            this.manager = manager;
//        }
//
//        /**
//         * 设置缓存名称
//         */
//        public CacheBuilder<K, V> name(String name) {
//            this.name = name;
//            return this;
//        }
//
//        /**
//         * 设置键值类型
//         */
//        public CacheBuilder<K, V> types(Class<K> keyType, Class<V> valueType) {
//            this.keyType = keyType;
//            this.valueType = valueType;
//            return this;
//        }
//
//        /**
//         * 设置加载器
//         */
//        public CacheBuilder<K, V> loader(Function<K, V> loader) {
//            this.loader = loader;
//            return this;
//        }
//
//        /**
//         * 设置TTL
//         */
//        public CacheBuilder<K, V> ttl(Duration ttl) {
//            this.ttl = ttl;
//            return this;
//        }
//
//        /**
//         * 启用自动刷新
//         */
//        public CacheBuilder<K, V> autoRefresh(boolean autoRefresh) {
//            this.autoRefresh = autoRefresh;
//            return this;
//        }
//
//        /**
//         * 设置刷新间隔
//         */
//        public CacheBuilder<K, V> refreshInterval(Duration refreshInterval) {
//            this.refreshInterval = refreshInterval;
//            return this;
//        }
//
//        /**
//         * 启用分布式功能
//         */
//        public CacheBuilder<K, V> distributed(boolean distributed) {
//            this.distributed = distributed;
//            return this;
//        }
//
//        /**
//         * 设置自定义配置
//         */
//        public CacheBuilder<K, V> config(CascadeCacheProperties config) {
//            this.config = config;
//            return this;
//        }
//
//        /**
//         * 构建缓存
//         */
//        public Cache<K, V> build() {
//            validateBuilder();
//
//            // 根据配置选择合适的创建方法
//            if (distributed && autoRefresh) {
//                long interval = refreshInterval != null ? refreshInterval.getSeconds() :
//                    manager.configManager.getDefaultRefreshInterval();
//                return manager.getOrCreateDistributedAutoRefreshCache(name, keyType, valueType, loader, interval);
//            } else if (autoRefresh) {
//                long interval = refreshInterval != null ? refreshInterval.getSeconds() :
//                    manager.configManager.getDefaultRefreshInterval();
//                return manager.getOrCreateAutoRefreshCache(name, keyType, valueType, loader, interval);
//            } else {
//                return manager.getOrCreateCache(name, keyType, valueType, config, loader);
//            }
//        }
//
//        private void validateBuilder() {
//            if (name == null || name.trim().isEmpty()) {
//                throw new CacheException("缓存名称不能为空", null);
//            }
//            if (keyType == null || valueType == null) {
//                throw new CacheException("键类型和值类型不能为空", null);
//            }
//        }
//    }
//
//    // ==================== 健康状态枚举 ====================
//
//    /**
//     * 健康状态
//     */
//    public enum HealthStatus {
//        UP,        // 健康
//        WARNING,   // 警告
//        DOWN       // 不可用
//    }
//
//    /**
//     * 缓存统计信息
//     */
//    @Getter
//    public static class CacheStats {
//        private final String cacheName;
//        private final String cacheType;
//        private final double hitRate;
//        private final double errorRate;
//
//        private CacheStats(Builder builder) {
//            this.cacheName = builder.cacheName;
//            this.cacheType = builder.cacheType;
//            this.hitRate = builder.hitRate;
//            this.errorRate = builder.errorRate;
//        }
//
//        public static Builder builder() {
//            return new Builder();
//        }
//
//        public static class Builder {
//            private String cacheName;
//            private String cacheType;
//            private double hitRate;
//            private double errorRate;
//
//            public Builder cacheName(String cacheName) {
//                this.cacheName = cacheName;
//                return this;
//            }
//
//            public Builder cacheType(String cacheType) {
//                this.cacheType = cacheType;
//                return this;
//            }
//
//            public Builder hitRate(double hitRate) {
//                this.hitRate = hitRate;
//                return this;
//            }
//
//            public Builder errorRate(double errorRate) {
//                this.errorRate = errorRate;
//                return this;
//            }
//
//            public CacheStats build() {
//                return new CacheStats(this);
//            }
//        }
//    }
//
//    @Override
//    public String toString() {
//        return String.format("ModularCacheManager{nodeId='%s', status='%s', cacheCount=%d, closed=%s}",
//                nodeId, lifecycleManager.getStatus(), getCacheCount(), isClosed());
//    }
//}