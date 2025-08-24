package io.github.cascade.cache.core.unified;

import io.github.cascade.cache.api.Cache;
import io.github.cascade.cache.api.CacheLoader;
import io.github.cascade.cache.config.CascadeCacheConfiguration;
import io.github.cascade.cache.core.CacheLoaderResolver;
import io.github.cascade.cache.core.unified.CaffeineEngine.CaffeineConfig;
import io.github.cascade.cache.core.unified.RedisEngine.RedisConfig;
import io.github.cascade.cache.event.UnifiedEventProcessor;
import io.github.cascade.cache.metrics.UnifiedMonitoringManager;
import io.github.cascade.cache.protection.*;
import io.github.cascade.cache.refresh.CacheRefreshScheduler;
import io.github.cascade.cache.sync.RedissonCacheSyncManager;
import io.github.cascade.cache.sync.UnifiedCacheSynchronizer;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.context.ApplicationContext;

import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

/**
 * 统一缓存构建器
 * 使用新的简化架构，替代原来复杂的继承体系
 *
 * @param <K> 键类型
 * @param <V> 值类型
 * @author cascade
 */
@Slf4j
public class UnifiedCacheBuilder<K, V> {

    private final String cacheName;
    private Class<K> keyType;
    private Class<V> valueType;

    // 基础配置
    private Executor executor = ForkJoinPool.commonPool();
    private CacheLoader<K, V> cacheLoader;

    // L1配置
    private final CaffeineConfig<K, V> l1Config = new CaffeineConfig<>();
    private boolean enableL1 = true;

    // L2配置
    private final RedisConfig l2Config = new RedisConfig();
    private boolean enableL2 = false;
    private RedissonClient redissonClient;

    // 防护配置
    private boolean enableProtection = false;
    private CascadeBloomFilter bloomFilter;
    private RandomTtlProtection randomTtl;
    private RedissonLockProtection distributedLock;

    // 同步配置
    private boolean enableSync = false;
    private String syncTopic = "cascade:cache:sync";
    private Duration syncTimeout = Duration.ofSeconds(5);
    private boolean asyncSync = true;

    // 刷新配置
    private boolean enableAutoRefresh = false;
    private Duration refreshInterval = Duration.ofSeconds(30);
    private boolean refreshOnAccess = false;

    /**
     * -- SETTER --
     * 设置Spring应用上下文
     */
    // Spring上下文（用于自动发现）
    @Setter
    private static ApplicationContext applicationContext;
    /**
     * -- SETTER --
     * 设置CacheLoader解析器
     */
    @Setter
    private static CacheLoaderResolver cacheLoaderResolver;

    // 自动发现配置
    private boolean autoDiscoverLoader = true;

    // 监控管理器
    private UnifiedMonitoringManager monitoringManager;

    /**
     * 构造器
     */
    public UnifiedCacheBuilder(String cacheName) {
        this.cacheName = cacheName;
    }

    /**
     * 从配置创建构建器
     */
    public UnifiedCacheBuilder(CascadeCacheConfiguration config) {
        this.cacheName = config.getName();
        applyConfiguration(config);
    }


    /**
     * 创建构建器
     */
    public static <K, V> UnifiedCacheBuilder<K, V> newBuilder(String cacheName) {
        return new UnifiedCacheBuilder<>(cacheName);
    }

    /**
     * 创建类型化构建器
     */
    public static <K, V> UnifiedCacheBuilder<K, V> newBuilder(String cacheName, Class<K> keyType, Class<V> valueType) {
        return new UnifiedCacheBuilder<K, V>(cacheName).types(keyType, valueType);
    }

    /**
     * String Key快捷构建器
     */
    public static <V> UnifiedCacheBuilder<String, V> stringCache(String cacheName, Class<V> valueType) {
        return newBuilder(cacheName, String.class, valueType);
    }

    /**
     * Long Key快捷构建器
     */
    public static <V> UnifiedCacheBuilder<Long, V> longCache(String cacheName, Class<V> valueType) {
        return newBuilder(cacheName, Long.class, valueType);
    }

    // ==================== 类型配置 ====================

    /**
     * 设置键值类型
     */
    public UnifiedCacheBuilder<K, V> types(Class<K> keyType, Class<V> valueType) {
        this.keyType = keyType;
        this.valueType = valueType;
        return this;
    }

    /**
     * 设置执行器
     */
    public UnifiedCacheBuilder<K, V> executor(Executor executor) {
        this.executor = executor;
        return this;
    }

    /**
     * 设置缓存加载器
     */
    public UnifiedCacheBuilder<K, V> loader(CacheLoader<K, V> loader) {
        this.cacheLoader = loader;
        this.l1Config.setCacheLoader(loader);
        this.l2Config.setCacheLoader(loader);
        return this;
    }

    // ==================== L1缓存配置 ====================


    public void configL1(CascadeCacheConfiguration.L1Config l1Config) {
        this.enableL1 = l1Config.isEnabled();
        this.l1Config.setMaximumSize(l1Config.getMaximumSize());
        this.l1Config.setExpireAfterAccess(l1Config.getExpireAfterAccess());
        this.l1Config.setExpireAfterWrite(l1Config.getExpireAfterWrite());
        this.l1Config.setRecordStats(l1Config.isRecordStats());
        this.l1Config.setInitialCapacity(l1Config.getInitialCapacity());
        this.l1Config.setCacheLoader(this.cacheLoader);
    }

    /**
     * 启用/禁用L1缓存
     */
    public UnifiedCacheBuilder<K, V> enableL1(boolean enable) {
        this.enableL1 = enable;
        return this;
    }

    // ==================== L2缓存配置 ====================

    /**
     * 启用Redis L2缓存
     */
    public UnifiedCacheBuilder<K, V> enableL2(boolean enable) {
        this.enableL2 = enable;
        return this;
    }

    public void configL2(CascadeCacheConfiguration.L2Config l2Config) {
        this.enableL2 = l2Config.isEnabled();
        this.l2Config.setKeyPrefix(l2Config.getKeyPrefix());
        this.l2Config.setCacheLoader(this.cacheLoader);
        this.l2Config.setDefaultTtl(l2Config.getDefaultTtl());
        this.l2Config.setRefreshAfterWrite(l2Config.getRefreshAfterWrite());
        this.l2Config.setEnableBatch(l2Config.isEnableBatch());
        this.l2Config.setBatchSize(l2Config.getBatchSize());
    }

    /**
     * 启用Redis L2缓存（使用指定客户端）
     */
    public UnifiedCacheBuilder<K, V> withRedis(RedissonClient client) {
        this.enableL2 = true;
        this.redissonClient = client;
        return this;
    }

    /**
     * 启用Redis L2缓存（自动发现客户端）
     */
    public UnifiedCacheBuilder<K, V> withRedis() {
        this.enableL2 = true;
        this.redissonClient = getRedissonClientFromSpring();
        if (this.redissonClient == null) {
            throw new IllegalStateException("RedissonClient not found in Spring context");
        }
        return this;
    }

    /**
     * 设置Redis键前缀
     */
    public UnifiedCacheBuilder<K, V> keyPrefix(String prefix) {
        this.l2Config.setKeyPrefix(prefix);
        return this;
    }


    // ==================== 防护配置 ====================

    /**
     * 启用防护机制
     */
    public UnifiedCacheBuilder<K, V> enableProtection(boolean enable) {
        this.enableProtection = enable;
        return this;
    }

    /**
     * 配置布隆过滤器
     */
    public UnifiedCacheBuilder<K, V> bloomFilter(long expectedElements, double falsePositiveRate) {
        this.enableProtection = true;
        if (redissonClient != null) {
            this.bloomFilter = RedissonBloomFilterProtection.builder(redissonClient)
                    .filterName(cacheName + "_bloom")
                    .expectedElements(expectedElements)
                    .falsePositiveRate(falsePositiveRate)
                    .build();
        }
        return this;
    }

    /**
     * 配置随机TTL防雪崩
     */
    public UnifiedCacheBuilder<K, V> randomTtl(Duration baseTtl, double jitterRatio) {
        this.enableProtection = true;
        Duration jitterRange = Duration.ofMillis((long) (baseTtl.toMillis() * jitterRatio));
        this.randomTtl = new RandomTtlProtection(baseTtl, jitterRange,
                RandomTtlProtection.JitterStrategy.UNIFORM);
        return this;
    }

    public UnifiedCacheBuilder<K, V> randomTtl(CascadeCacheConfiguration.ProtectionConfig.RandomTtlConfig randomTtlConfig) {
        this.enableProtection = true;
        Duration jitterRange = Duration.ofMillis((long) (randomTtlConfig.getBaseTtl().toMillis() * randomTtlConfig.getJitterRatio()));
        this.randomTtl = new RandomTtlProtection(randomTtlConfig.getBaseTtl(), jitterRange,
                RandomTtlProtection.JitterStrategy.UNIFORM);
        return this;
    }

    /**
     * 配置分布式锁防热点
     */
    public UnifiedCacheBuilder<K, V> distributedLock(Duration lockTimeout) {
        this.enableProtection = true;
        if (redissonClient != null) {
            this.distributedLock = new RedissonLockProtection(
                    redissonClient,
                    cacheName + "_lock:",
                    lockTimeout,
                    Duration.ofMillis(100),
                    3,
                    Duration.ofMillis(50)
            );
        }
        return this;
    }

    public UnifiedCacheBuilder<K, V> distributedLock(CascadeCacheConfiguration.ProtectionConfig.DistributedLockConfig
                                                             distributedLockConfig) {
        this.enableProtection = true;
        if (redissonClient != null) {
            this.distributedLock = new RedissonLockProtection(
                    redissonClient,
                    cacheName + "_lock:",
                    distributedLockConfig.getLockTimeout(),
                    distributedLockConfig.getWaitTimeout(),
                    distributedLockConfig.getMaxRetries(),
                    distributedLockConfig.getRetryDelay()
            );
        }
        return this;
    }

    // ==================== 便捷配置方法 ====================

    /**
     * 一键配置基础缓存
     */
    public UnifiedCacheBuilder<K, V> basicConfig(long maxSize, Duration expireAfter) {
        this.l1Config.setMaximumSize(maxSize);
        this.l1Config.setExpireAfterWrite(expireAfter);
        return this;
    }


    /**
     * 一键配置防护机制
     */
    public UnifiedCacheBuilder<K, V> withProtection() {
        return enableProtection(true)
                .bloomFilter(100000, 0.01)
                .randomTtl(Duration.ofMinutes(30), 0.1)
                .distributedLock(Duration.ofSeconds(5));
    }

    // ==================== 同步配置 ====================

    /**
     * 启用分布式同步
     */
    public UnifiedCacheBuilder<K, V> enableSync(boolean enabled) {
        this.enableSync = enabled;
        return this;
    }

    /**
     * 设置同步主题
     */
    public UnifiedCacheBuilder<K, V> syncTopic(String topic) {
        this.syncTopic = topic;
        return this;
    }

    /**
     * 设置同步超时时间
     */
    public UnifiedCacheBuilder<K, V> syncTimeout(Duration timeout) {
        this.syncTimeout = timeout;
        return this;
    }

    /**
     * 设置是否异步同步
     */
    public UnifiedCacheBuilder<K, V> asyncSync(boolean async) {
        this.asyncSync = async;
        return this;
    }

    /**
     * 一键配置分布式同步
     */
    public UnifiedCacheBuilder<K, V> withSync() {
        return enableSync(true)
                .syncTopic("cascade:cache:sync:" + cacheName)
                .syncTimeout(Duration.ofSeconds(5))
                .asyncSync(true);
    }

    // ==================== 刷新配置 ====================

    /**
     * 启用自动刷新
     */
    public UnifiedCacheBuilder<K, V> enableAutoRefresh(boolean enabled) {
        this.enableAutoRefresh = enabled;
        return this;
    }

    /**
     * 设置刷新间隔
     */
    public UnifiedCacheBuilder<K, V> refreshInterval(Duration interval) {
        this.refreshInterval = interval;
        return this;
    }

    /**
     * 设置访问时是否触发刷新检查
     */
    public UnifiedCacheBuilder<K, V> refreshOnAccess(boolean enabled) {
        this.refreshOnAccess = enabled;
        return this;
    }

    /**
     * 一键配置自动刷新
     */
    public UnifiedCacheBuilder<K, V> withAutoRefresh() {
        return enableAutoRefresh(true)
                .refreshInterval(Duration.ofSeconds(5))
                .refreshOnAccess(false);
    }

    /**
     * 一键配置自动刷新（自定义间隔）
     */
    public UnifiedCacheBuilder<K, V> withAutoRefresh(Duration interval) {
        return enableAutoRefresh(true)
                .refreshInterval(interval)
                .refreshOnAccess(false);
    }

    // ==================== 构建方法 ====================

    /**
     * 构建缓存
     */
    public Cache<K, V> build(CascadeCacheConfiguration cacheConfig) {
        long startTime = System.currentTimeMillis();
        try {
            log.debug("Starting to build cache: {}", cacheName);

            validateConfig();

            // 创建缓存引擎
            CacheEngines engines = createCacheEngines();

            // 创建统一缓存
            SmartCache<K, V> cache = createSmartCache(engines, cacheConfig);

            // 配置缓存组件
            configureCache(cache);

            long buildTime = System.currentTimeMillis() - startTime;
            log.info("Successfully built cache '{}' in {}ms - L1={}, L2={}, protection={}, sync={}, autoRefresh={}",
                    cacheName, buildTime, enableL1, enableL2, enableProtection, enableSync, enableAutoRefresh);

            return cache;
        } catch (Exception e) {
            long buildTime = System.currentTimeMillis() - startTime;
            log.error("Failed to build cache '{}' after {}ms: {}", cacheName, buildTime, e.getMessage(), e);
            throw new RuntimeException("Cache build failed for: " + cacheName, e);
        }
    }

    /**
     * 缓存引擎容器
     */
    private static class CacheEngines {
        final CacheEngine<?, ?> l1Engine;
        final CacheEngine<?, ?> l2Engine;

        CacheEngines(CacheEngine<?, ?> l1Engine, CacheEngine<?, ?> l2Engine) {
            this.l1Engine = l1Engine;
            this.l2Engine = l2Engine;
        }
    }

    /**
     * 创建缓存引擎
     */
    private CacheEngines createCacheEngines() {
        CacheEngine<K, V> l1Engine = createL1Engine();
        CacheEngine<K, V> l2Engine = createL2Engine();

        if (l1Engine == null && l2Engine == null) {
            throw new IllegalStateException("At least one cache tier must be enabled for cache: " + cacheName);
        }

        return new CacheEngines(l1Engine, l2Engine);
    }

    /**
     * 创建L1缓存引擎
     */
    private CacheEngine<K, V> createL1Engine() {
        if (!enableL1) {
            return null;
        }

        try {
            CacheEngine<K, V> l1Engine = new CaffeineEngine<>(cacheName, l1Config);
            log.debug("Created L1 engine for cache: {}", cacheName);
            return l1Engine;
        } catch (Exception e) {
            log.error("Failed to create L1 engine for cache: {}", cacheName, e);
            throw new RuntimeException("L1 engine creation failed", e);
        }
    }

    /**
     * 创建L2缓存引擎
     */
    private CacheEngine<K, V> createL2Engine() {
        if (!enableL2 || redissonClient == null) {
            return null;
        }

        try {
            CacheEngine<K, V> l2Engine = new RedisEngine<>(cacheName, redissonClient, l2Config);
            log.debug("Created L2 engine for cache: {}", cacheName);
            return l2Engine;
        } catch (Exception e) {
            log.error("Failed to create L2 engine for cache: {}", cacheName, e);
            throw new RuntimeException("L2 engine creation failed", e);
        }
    }

    /**
     * 创建SmartCache实例
     */
    @SuppressWarnings("unchecked")
    private SmartCache<K, V> createSmartCache(CacheEngines engines, CascadeCacheConfiguration cacheConfig) {
        SmartCache<K, V> cache = new SmartCache<>(
                cacheName,
                (CacheEngine<K, V>) engines.l1Engine,
                (CacheEngine<K, V>) engines.l2Engine,
                executor,
                monitoringManager
        );
        cache.setCacheConfiguration(cacheConfig);
        return cache;
    }

    /**
     * 配置缓存的所有组件
     */
    private void configureCache(SmartCache<K, V> cache) {
        configureCacheLoader(cache);
        configureProtectionManager(cache);
        configureSynchronizer(cache);
        configureRefreshScheduler(cache);
    }

    /**
     * 配置缓存加载器
     */
    private void configureCacheLoader(SmartCache<K, V> cache) {
        if (cacheLoader != null) {
            cache.setLoader(cacheLoader);
            log.debug("Set explicit CacheLoader for cache: {}", cacheName);
        } else if (autoDiscoverLoader && cacheLoaderResolver != null && keyType != null && valueType != null) {
            discoverAndSetCacheLoader(cache);
        }
    }

    /**
     * 自动发现并设置缓存加载器
     */
    private void discoverAndSetCacheLoader(SmartCache<K, V> cache) {
        try {
            CacheLoader<K, V> discoveredLoader = cacheLoaderResolver.resolveCacheLoader(keyType, valueType);
            if (discoveredLoader != null) {
                cache.setLoader(discoveredLoader);
                this.cacheLoader = discoveredLoader;
                log.info("Auto-discovered CacheLoader: {} for cache: {}",
                        discoveredLoader.getClass().getSimpleName(), cacheName);
            } else {
                log.debug("No compatible CacheLoader found for cache: {} with types <{}, {}>",
                        cacheName, keyType != null ? keyType.getSimpleName() : "null",
                        valueType != null ? valueType.getSimpleName() : "null");
            }
        } catch (Exception e) {
            log.warn("Failed to discover CacheLoader for cache: {}: {}", cacheName, e.getMessage());
        }
    }

    /**
     * 配置防护机制
     */
    private void configureProtectionManager(SmartCache<K, V> cache) {
        if (!enableProtection) {
            return;
        }

        try {
            log.debug("Configuring protection mechanisms for cache: {}", cacheName);
            SimplifiedCacheProtectionManager.Builder protectionBuilder = SimplifiedCacheProtectionManager.builder();

            if (bloomFilter != null) {
                protectionBuilder.bloomFilter(bloomFilter);
                log.debug("Added bloom filter protection for cache: {}", cacheName);
            }

            if (randomTtl != null) {
                protectionBuilder.randomTtl(randomTtl);
                log.debug("Added random TTL protection for cache: {}", cacheName);
            }

            if (distributedLock != null) {
                protectionBuilder.redissonLock(distributedLock);
                log.debug("Added distributed lock protection for cache: {}", cacheName);
            }

            cache.setProtectionManager(protectionBuilder.build());
            log.info("Successfully configured protection for cache: {}", cacheName);
        } catch (Exception e) {
            log.error("Failed to configure protection for cache: {}: {}", cacheName, e.getMessage(), e);
            throw new RuntimeException("Protection configuration failed", e);
        }
    }

    /**
     * 配置同步器
     */
    private void configureSynchronizer(SmartCache<K, V> cache) {
        if (!enableSync || redissonClient == null) {
            return;
        }

        try {
            log.debug("Configuring synchronizer for cache: {}", cacheName);

            RedissonCacheSyncManager syncManager = new RedissonCacheSyncManager(redissonClient, syncTopic);
            UnifiedEventProcessor eventProcessor = new UnifiedEventProcessor(asyncSync);
            UnifiedCacheSynchronizer<K, V> synchronizer = new UnifiedCacheSynchronizer<>(
                    cacheName, syncManager, eventProcessor);

            cache.setSynchronizer(synchronizer);
            log.info("Successfully configured distributed sync for cache: '{}' with topic: '{}'",
                    cacheName, syncTopic);
        } catch (Exception e) {
            log.error("Failed to configure distributed sync for cache: {}: {}", cacheName, e.getMessage(), e);
            // 不抛出异常，允许缓存在没有同步的情况下工作
            log.warn("Cache '{}' will work without distributed synchronization", cacheName);
        }
    }

    /**
     * 配置刷新调度器
     */
    private void configureRefreshScheduler(SmartCache<K, V> cache) {
        if (!enableAutoRefresh || cacheLoader == null) {
            return;
        }

        try {
            log.debug("Configuring refresh scheduler for cache: {}", cacheName);

            CacheRefreshScheduler.RefreshCallback<K, V> refreshCallback = (key, newValue) -> {
                cache.put(key, newValue);
                log.debug("Auto refreshed cache key: '{}' for cache: '{}'", key, cacheName);
            };

            CacheRefreshScheduler<K, V> refreshScheduler = new CacheRefreshScheduler<>(
                    refreshCallback, cacheLoader, refreshInterval);

            cache.setRefreshScheduler(refreshScheduler);
            log.info("Successfully configured auto refresh for cache: '{}' with interval: {}s",
                    cacheName, refreshInterval.toSeconds());
        } catch (Exception e) {
            log.error("Failed to configure auto refresh for cache: {}: {}", cacheName, e.getMessage(), e);
            // 不抛出异常，允许缓存在没有自动刷新的情况下工作
            log.warn("Cache '{}' will work without auto refresh", cacheName);
        }
    }

    // ==================== 私有方法 ====================

    /**
     * 验证配置
     */
    private void validateConfig() {
        log.debug("Validating configuration for cache: {}", cacheName);

        validateCacheName();
        validateTierConfiguration();
        validateRedisConfiguration();
        validateProtectionConfiguration();
        validateSyncConfiguration();
        validateRefreshConfiguration();

        log.debug("Configuration validation completed for cache: {}", cacheName);
    }

    private void validateCacheName() {
        if (cacheName == null || cacheName.trim().isEmpty()) {
            throw new IllegalArgumentException("Cache name cannot be null or empty");
        }

        // 验证缓存名称格式
        if (!cacheName.matches("^[a-zA-Z0-9_\\-.:]+$")) {
            throw new IllegalArgumentException(
                    "Invalid cache name format: '" + cacheName + "'. Only alphanumeric characters, underscore, hyphen, dot and colon are allowed");
        }
    }

    private void validateTierConfiguration() {
        if (!enableL1 && !enableL2) {
            throw new IllegalStateException("At least one cache tier (L1 or L2) must be enabled for cache: " + cacheName);
        }

        if (enableL1) {
            validateL1Configuration();
        }

        if (enableL2) {
            validateL2Configuration();
        }
    }

    private void validateL1Configuration() {
        if (l1Config.getMaximumSize() <= 0) {
            throw new IllegalArgumentException("L1 cache maximum size must be positive for cache: " + cacheName);
        }

        if (l1Config.getExpireAfterWrite() != null && l1Config.getExpireAfterWrite().isNegative()) {
            throw new IllegalArgumentException("L1 cache expireAfterWrite duration cannot be negative for cache: " + cacheName);
        }

        if (l1Config.getExpireAfterAccess() != null && l1Config.getExpireAfterAccess().isNegative()) {
            throw new IllegalArgumentException("L1 cache expireAfterAccess duration cannot be negative for cache: " + cacheName);
        }
    }

    private void validateL2Configuration() {
        if (l2Config.getDefaultTtl() != null && l2Config.getDefaultTtl().isNegative()) {
            throw new IllegalArgumentException("L2 cache default TTL cannot be negative for cache: " + cacheName);
        }

        if (l2Config.getBatchSize() <= 0) {
            throw new IllegalArgumentException("L2 cache batch size must be positive for cache: " + cacheName);
        }
    }

    private void validateRedisConfiguration() {
        if (enableL2 && redissonClient == null) {
            throw new IllegalStateException("RedissonClient is required for L2 cache: " + cacheName);
        }
    }

    private void validateProtectionConfiguration() {
        if (enableProtection) {
            if (bloomFilter != null && redissonClient == null) {
                log.warn("Bloom filter protection requires RedissonClient for cache: {}", cacheName);
            }

            if (distributedLock != null && redissonClient == null) {
                log.warn("Distributed lock protection requires RedissonClient for cache: {}", cacheName);
            }
        }
    }

    private void validateSyncConfiguration() {
        if (enableSync) {
            if (redissonClient == null) {
                log.warn("Distributed sync requires RedissonClient for cache: {}", cacheName);
            }

            if (syncTimeout != null && syncTimeout.isNegative()) {
                throw new IllegalArgumentException("Sync timeout cannot be negative for cache: " + cacheName);
            }

            if (syncTopic == null || syncTopic.trim().isEmpty()) {
                throw new IllegalArgumentException("Sync topic cannot be null or empty for cache: " + cacheName);
            }
        }
    }

    private void validateRefreshConfiguration() {
        if (enableAutoRefresh) {
            if (cacheLoader == null && !autoDiscoverLoader) {
                log.warn("Auto refresh requires CacheLoader for cache: {}", cacheName);
            }

            if (refreshInterval != null && refreshInterval.isNegative()) {
                throw new IllegalArgumentException("Refresh interval cannot be negative for cache: " + cacheName);
            }

            if (refreshInterval != null && refreshInterval.toMillis() < 1000) {
                log.warn("Refresh interval is less than 1 second for cache: {}, this may cause performance issues", cacheName);
            }
        }
    }

    /**
     * 尝试从Spring容器获取RedissonClient
     */
    private static RedissonClient getRedissonClientFromSpring() {
        if (applicationContext == null) {
            return null;
        }
        try {
            return applicationContext.getBean(RedissonClient.class);
        } catch (Exception e) {
            return null;
        }
    }


    /**
     * 启用/禁用自动发现CacheLoader
     */
    public UnifiedCacheBuilder<K, V> autoDiscoverLoader(boolean enabled) {
        this.autoDiscoverLoader = enabled;
        return this;
    }

    // ==================== 监控配置 ====================

    /**
     * 设置监控管理器
     */
    public UnifiedCacheBuilder<K, V> withMonitoringManager(UnifiedMonitoringManager monitoringManager) {
        this.monitoringManager = monitoringManager;
        return this;
    }

    /**
     * 从CascadeCacheConfiguration应用配置
     */
    private void applyConfiguration(CascadeCacheConfiguration config) {
        log.debug("Applying configuration for cache: {}", cacheName);

        try {
            applyCommonConfig(config);
            applyL1Config(config);
            applyL2Config(config);
            applySyncConfig(config);
            applyProtectionConfig(config);

            log.debug("Configuration applied successfully for cache: {}", cacheName);
        } catch (Exception e) {
            log.error("Failed to apply configuration for cache: {}: {}", cacheName, e.getMessage(), e);
            throw new RuntimeException("Configuration application failed", e);
        }
    }

    private void applyCommonConfig(CascadeCacheConfiguration config) {
        if (config.getCommon() == null) return;

        var common = config.getCommon();
        CascadeCacheConfiguration.L1Config l1 = config.getL1();
        if (l1.getMaximumSize() != 0) {
            this.l1Config.setMaximumSize(l1.getMaximumSize());
        } else {
            this.l1Config.setMaximumSize(common.getMaximumSize());
        }
        this.l1Config.setRecordStats(common.isRecordStats());

        if (common.getExpireAfterWrite() != null) {
            this.l1Config.setExpireAfterWrite(common.getExpireAfterWrite());
        }
        if (common.getExpireAfterAccess() != null) {
            this.l1Config.setExpireAfterAccess(common.getExpireAfterAccess());
        }
        if (common.getRefreshAfterWrite() != null) {
            this.l1Config.setRefreshAfterWrite(common.getRefreshAfterWrite());
        }
        if (common.getExecutor() != null) {
            executor(common.getExecutor());
        }
    }

    private void applyL1Config(CascadeCacheConfiguration config) {
        if (config.getL1() == null || !config.getL1().isEnabled()) return;

        var l1 = config.getL1();
        enableL1(true);
        if (l1.getMaximumSize() != 0) {
            this.l1Config.setMaximumSize(l1.getMaximumSize());
        }
        this.l1Config.setRecordStats(l1.isRecordStats());

        if (l1.getExpireAfterWrite() != null) {
            this.l1Config.setExpireAfterWrite(l1.getExpireAfterWrite());
        }
        if (l1.getExpireAfterAccess() != null) {
            this.l1Config.setExpireAfterAccess(l1.getExpireAfterAccess());
        }
    }

    private void applyL2Config(CascadeCacheConfiguration config) {
        if (config.getL2() == null || !config.getL2().isEnabled()) return;

        var l2 = config.getL2();
        enableL2(true);
        keyPrefix(l2.getKeyPrefix());

        if (l2.getDefaultTtl() != null) {
            this.l2Config.setDefaultTtl(l2.getDefaultTtl());
        }
        if (l2.getRedissonClient() != null) {
            withRedis(l2.getRedissonClient());
        }
    }

    private void applySyncConfig(CascadeCacheConfiguration config) {
        if (config.getSync() == null || !config.getSync().isEnabled()) return;

        var sync = config.getSync();
        enableSync(true);
        syncTopic(sync.getTopic());
        asyncSync(sync.isAsync());

        if (sync.getTimeout() != null) {
            syncTimeout(sync.getTimeout());
        }
    }

    private void applyProtectionConfig(CascadeCacheConfiguration config) {
        if (config.getProtection() == null || !config.getProtection().isEnabled()) return;

        var protection = config.getProtection();
        enableProtection(true);

        applyBloomFilterConfig(protection.getBloomFilter());
        applyRandomTtlConfig(protection.getRandomTtl());
        applyDistributedLockConfig(protection.getDistributedLock());
    }

    private void applyBloomFilterConfig(CascadeCacheConfiguration.ProtectionConfig.BloomFilterConfig bloomConfig) {
        if (bloomConfig != null && bloomConfig.isEnabled()) {
            bloomFilter(bloomConfig.getExpectedElements(), bloomConfig.getFalsePositiveRate());
        }
    }

    private void applyRandomTtlConfig(CascadeCacheConfiguration.ProtectionConfig.RandomTtlConfig randomTtlConfig) {
        if (randomTtlConfig != null && randomTtlConfig.isEnabled()) {
            Duration baseTtl = randomTtlConfig.getBaseTtl() != null ?
                    randomTtlConfig.getBaseTtl() : Duration.ofMinutes(30);
            randomTtl(baseTtl, randomTtlConfig.getJitterRatio());
        }
    }

    private void applyDistributedLockConfig(CascadeCacheConfiguration.ProtectionConfig.DistributedLockConfig lockConfig) {
        if (lockConfig != null && lockConfig.isEnabled()) {
            Duration lockTimeout = lockConfig.getLockTimeout() != null ?
                    lockConfig.getLockTimeout() : Duration.ofSeconds(30);
            distributedLock(lockTimeout);
        }
    }


}