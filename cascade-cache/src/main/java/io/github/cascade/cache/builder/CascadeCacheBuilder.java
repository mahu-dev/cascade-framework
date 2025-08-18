package io.github.cascade.cache.builder;

import io.github.cascade.cache.api.Cache;
import io.github.cascade.cache.api.CacheBuilder;
import io.github.cascade.cache.api.CacheLoader;
import io.github.cascade.cache.api.CacheManager;
import io.github.cascade.cache.config.unified.CascadeCacheConfiguration;
import io.github.cascade.cache.core.impl.EnhancedDistributedTieredCache;
import io.github.cascade.cache.core.impl.SimpleCascadeCache;
import io.github.cascade.cache.core.management.CacheLoadingManagerFactory;
import io.github.cascade.cache.core.properties.DistributedTieredCacheProperties;
import io.github.cascade.cache.manager.SpringBootCacheManager;
import io.github.cascade.cache.protection.*;
import io.github.cascade.cache.sync.CacheSyncManager;
import io.github.cascade.cache.sync.RedissonCacheSyncManager;
import io.github.cascade.cache.tier.RedissonRemoteTier;
import io.github.cascade.cache.tier.SimpleCaffeineLocalTier;
import lombok.Getter;
import lombok.Setter;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;

import java.time.Duration;

/**
 * Cascade缓存构建器
 * 基于统一配置类CascadeCacheConfiguration，提供简洁的配置API
 *
 * @param <K> 键类型
 * @param <V> 值类型
 */
public class CascadeCacheBuilder<K, V> implements CacheBuilder<K, V> {

    private static final Logger logger = LoggerFactory.getLogger(CascadeCacheBuilder.class);

    /**
     * Spring应用上下文，用于自动获取RedissonClient
     * -- SETTER --
     * 设置Spring应用上下文
     */
    @Setter
    private static ApplicationContext applicationContext;

    /**
     * 关联的缓存管理器，用于注册创建的缓存
     */
    private final CacheManager cacheManager;

    /**
     * 统一配置对象，包含所有缓存配置
     */
    private final CascadeCacheConfiguration config;

    /**
     * 缓存数据加载器
     */
    private CacheLoader<K, V> cacheLoader;

    /**
     * 类型信息
     */
    private Class<K> keyType;
    private Class<V> valueType;

    /**
     * 构造函数
     */
    public CascadeCacheBuilder(String cacheName, CacheManager cacheManager) {
        this.cacheManager = cacheManager;
        this.config = new CascadeCacheConfiguration()
                .setName(cacheName)
                .setEnabled(true);
    }

    /**
     * 构造函数 - 使用现有配置
     */
    public CascadeCacheBuilder(CascadeCacheConfiguration config, CacheManager cacheManager) {
        this.cacheManager = cacheManager;
        this.config = config;
    }


    // ==================== 配置方法 ====================

    /**
     * 获取配置对象，用于链式配置
     */
    public CascadeCacheConfiguration config() {
        return config;
    }

    // ==================== CacheBuilder接口实现 ====================

    @Override
    public CascadeCacheBuilder<K, V> loader(CacheLoader<K, V> loader) {
        this.cacheLoader = loader;
        return this;
    }

    @Override
    public CascadeCacheBuilder<K, V> executor(java.util.concurrent.Executor executor) {
        config.getCommon().setExecutor(executor);
        return this;
    }

    @Override
    public CascadeCacheBuilder<K, V> maximumSize(long maximumSize) {
        config.getCommon().setMaximumSize(maximumSize);
        config.getL1().setMaximumSize(maximumSize);
        return this;
    }

    @Override
    public CascadeCacheBuilder<K, V> expireAfterWrite(Duration duration) {
        config.getCommon().setExpireAfterWrite(duration);
        return this;
    }

    @Override
    public CascadeCacheBuilder<K, V> expireAfterAccess(Duration duration) {
        config.getCommon().setExpireAfterAccess(duration);
        return this;
    }

    @Override
    public CascadeCacheBuilder<K, V> enableL1Cache(boolean enabled) {
        config.getL1().setEnabled(enabled);
        return this;
    }

    @Override
    public CascadeCacheBuilder<K, V> l1MaximumSize(long size) {
        config.getL1().setMaximumSize(size);
        return this;
    }

    @Override
    public CascadeCacheBuilder<K, V> l1ExpireAfterWrite(Duration duration) {
        config.getL1().setExpireAfterWrite(duration);
        return this;
    }

    @Override
    public CascadeCacheBuilder<K, V> l1ExpireAfterAccess(Duration duration) {
        config.getL1().setExpireAfterAccess(duration);
        return this;
    }

    @Override
    public CascadeCacheBuilder<K, V> l1RecordStats(boolean recordStats) {
        config.getL1().setRecordStats(recordStats);
        return this;
    }

    @Override
    public CascadeCacheBuilder<K, V> enableL2Cache(boolean enabled, RedissonClient redissonClient) {
        config.getL2().setEnabled(enabled).setRedissonClient(redissonClient);
        return this;
    }

    @Override
    public CascadeCacheBuilder<K, V> l2KeyPrefix(String keyPrefix) {
        config.getL2().setKeyPrefix(keyPrefix);
        return this;
    }

    @Override
    public CascadeCacheBuilder<K, V> l2DefaultTtl(Duration ttl) {
        config.getL2().setDefaultTtl(ttl);
        return this;
    }

    @Override
    public CascadeCacheBuilder<K, V> enableSync(boolean enabled) {
        config.getSync().setEnabled(enabled);
        return this;
    }

    @Override
    public CascadeCacheBuilder<K, V> enableProtection(boolean enabled) {
        config.getProtection().setEnabled(enabled);
        return this;
    }

    @Override
    public CascadeCacheBuilder<K, V> bloomFilter(long expectedElements, double falsePositiveRate) {
        config.getProtection().getBloomFilter()
                .setEnabled(true)
                .setExpectedElements(expectedElements)
                .setFalsePositiveRate(falsePositiveRate);
        return this;
    }

    @Override
    public CascadeCacheBuilder<K, V> randomTtl(boolean enabled, double jitterRatio) {
        config.getProtection().getRandomTtl()
                .setEnabled(enabled)
                .setJitterRatio(jitterRatio);
        return this;
    }

    @Override
    public CascadeCacheBuilder<K, V> distributedLock(boolean enabled, Duration lockTimeout, String lockKeyPrefix) {
        config.getProtection().getDistributedLock()
                .setEnabled(enabled)
                .setLockTimeout(lockTimeout)
                .setKeyPrefix(lockKeyPrefix);
        return this;
    }

    @Override
    public CascadeCacheBuilder<K, V> enableWarmup(boolean enabled) {
        // 预热功能暂时不支持，但提供接口实现
        logger.debug("Warmup feature is not implemented yet in CascadeCacheBuilder");
        return this;
    }

    // ==================== 便捷配置方法 ====================

    /**
     * 一键配置Redis多级缓存
     */
    public CascadeCacheBuilder<K, V> withRedis() {
        RedissonClient redissonClient = getRedissonClientFromSpring();
        if (redissonClient == null) {
            throw new IllegalStateException("RedissonClient not found in Spring context");
        }
        return withRedis(redissonClient);
    }

    /**
     * 一键配置Redis多级缓存
     */
    public CascadeCacheBuilder<K, V> withRedis(RedissonClient redissonClient) {
        config.getL2()
                .setEnabled(true)
                .setRedissonClient(redissonClient);
        return this;
    }

    /**
     * 一键启用防护机制
     */
    public CascadeCacheBuilder<K, V> withProtection() {
        config.getProtection().setEnabled(true);
        return this;
    }

    /**
     * 一键配置同步
     */
    public CascadeCacheBuilder<K, V> withSync() {
        config.getSync().setEnabled(true);
        return this;
    }

    /**
     * 一键配置缓存大小和过期时间
     */
    public CascadeCacheBuilder<K, V> withSize(long size, Duration expireAfter) {
        config.getCommon()
                .setMaximumSize(size)
                .setExpireAfterWrite(expireAfter);
        return this;
    }

    /**
     * 一键配置LoadingCache
     */
    public CascadeCacheBuilder<K, V> withLoader(CacheLoader<K, V> loader) {
        this.cacheLoader = loader;
        return this;
    }

    /**
     * 设置Key类型
     */
    public CascadeCacheBuilder<K, V> keyType(Class<K> keyType) {
        this.keyType = keyType;
        return this;
    }

    /**
     * 设置Value类型
     */
    public CascadeCacheBuilder<K, V> valueType(Class<V> valueType) {
        this.valueType = valueType;
        return this;
    }

    /**
     * 同时设置Key和Value类型
     */
    public CascadeCacheBuilder<K, V> types(Class<K> keyType, Class<V> valueType) {
        this.keyType = keyType;
        this.valueType = valueType;
        return this;
    }

    // ==================== 静态工厂方法 - 整合TypedCacheBuilder功能 ====================

    /**
     * 创建类型化缓存构建器的静态入口
     *
     * @param cacheName    缓存名称
     * @param cacheManager 缓存管理器
     * @param keyType      Key类型
     * @param valueType    Value类型
     * @return 类型化的构建器
     */
    public static <K, V> CascadeCacheBuilder<K, V> create(String cacheName, CacheManager cacheManager,
                                                          Class<K> keyType, Class<V> valueType) {
        return new CascadeCacheBuilder<K, V>(cacheName, cacheManager)
                .types(keyType, valueType);
    }

    public static <K, V> CascadeCacheBuilder<K, V> createBuilder(CascadeCacheConfiguration config, CacheManager cacheManager,
                                                                 RedissonClient redissonClient, Class<K> keyType, Class<V> valueType) {
        CascadeCacheBuilder<K, V> builder = new CascadeCacheBuilder<K, V>(config, cacheManager)
                .types(keyType, valueType);
        // 如果有RedissonClient，启用L2缓存
        if (redissonClient != null) {
            builder.withRedis(redissonClient);
        }
        return builder;
    }

    /**
     * 创建String Key类型的缓存构建器
     */
    public static <V> CascadeCacheBuilder<String, V> stringKey(String cacheName, CacheManager cacheManager,
                                                               Class<V> valueType) {
        return create(cacheName, cacheManager, String.class, valueType);
    }

    public static <V> CascadeCacheBuilder<String, V> getStringKey(CascadeCacheConfiguration config,
                                                                  CacheManager cacheManager,
                                                                  RedissonClient redissonClient, Class<V> valueType) {
        return createBuilder(config, cacheManager, redissonClient, String.class, valueType);
    }

    /**
     * 创建Long Key类型的缓存构建器
     */
    public static <V> CascadeCacheBuilder<Long, V> longKey(String cacheName, CacheManager cacheManager,
                                                           Class<V> valueType) {
        return create(cacheName, cacheManager, Long.class, valueType);
    }

    /**
     * 创建Integer Key类型的缓存构建器
     */
    public static <V> CascadeCacheBuilder<Integer, V> intKey(String cacheName, CacheManager cacheManager,
                                                             Class<V> valueType) {
        return create(cacheName, cacheManager, Integer.class, valueType);
    }

    /**
     * 流式构建入口 - 替代TypedCacheBuilder.named()
     */
    public static CacheNameBuilder named(String cacheName) {
        return new CacheNameBuilder(cacheName);
    }

    /**
     * 快速创建简单的类型化缓存 - 使用默认配置
     */
    public static <K, V> Cache<K, V> quickCache(String cacheName, CacheManager cacheManager,
                                                Class<K> keyType, Class<V> valueType) {
        return create(cacheName, cacheManager, keyType, valueType).build();
    }

    /**
     * 快速创建String Key的缓存
     */
    public static <V> Cache<String, V> quickStringCache(String cacheName, CacheManager cacheManager,
                                                        Class<V> valueType) {
        return stringKey(cacheName, cacheManager, valueType).build();
    }

    // ==================== 内嵌流式构建器 ====================

    /**
     * 缓存名称构建器 - 第一步指定名称
     */
    public static class CacheNameBuilder {
        private final String cacheName;

        private CacheNameBuilder(String cacheName) {
            this.cacheName = cacheName;
        }

        /**
         * 指定Key和Value类型
         */
        public <K, V> CacheTypeBuilder<K, V> withTypes(Class<K> keyType, Class<V> valueType) {
            return new CacheTypeBuilder<>(cacheName, keyType, valueType);
        }

        /**
         * String Key类型的快捷方法
         */
        public <V> CacheTypeBuilder<String, V> withStringKey(Class<V> valueType) {
            return withTypes(String.class, valueType);
        }

        /**
         * Long Key类型的快捷方法
         */
        public <V> CacheTypeBuilder<Long, V> withLongKey(Class<V> valueType) {
            return withTypes(Long.class, valueType);
        }

        /**
         * Integer Key类型的快捷方法
         */
        public <V> CacheTypeBuilder<Integer, V> withIntKey(Class<V> valueType) {
            return withTypes(Integer.class, valueType);
        }
    }

    /**
     * 类型化构建器 - 第二步配置和构建
     */
    public static class CacheTypeBuilder<K, V> {
        private final String cacheName;
        private final Class<K> keyType;
        private final Class<V> valueType;

        private CacheTypeBuilder(String cacheName, Class<K> keyType, Class<V> valueType) {
            this.cacheName = cacheName;
            this.keyType = keyType;
            this.valueType = valueType;
        }

        /**
         * 快速构建 - 使用默认CacheManager和配置
         */
        public Cache<K, V> build() {
            // 需要有默认的CacheManager
            SpringBootCacheManager defaultManager = getDefaultCacheManager();
            if (defaultManager == null) {
                throw new IllegalStateException("No default CacheManager set. Use build(cacheManager) or setDefaultCacheManager()");
            }
            return build(defaultManager);
        }

        /**
         * 使用指定CacheManager构建
         */
        public Cache<K, V> build(CacheManager cacheManager) {
            return create(cacheName, cacheManager, keyType, valueType).build();
        }

        /**
         * 转换为完整的构建器以进行详细配置
         */
        public CascadeCacheBuilder<K, V> configure() {
            SpringBootCacheManager defaultManager = getDefaultCacheManager();
            if (defaultManager == null) {
                throw new IllegalStateException("No default CacheManager set. Use configure(cacheManager) or setDefaultCacheManager()");
            }
            return configure(defaultManager);
        }

        /**
         * 转换为完整的构建器以进行详细配置
         */
        public CascadeCacheBuilder<K, V> configure(CacheManager cacheManager) {
            return create(cacheName, cacheManager, keyType, valueType);
        }
    }

    // ==================== 统一门面功能 ====================

    /**
     * -- SETTER --
     * 设置默认的CacheManager，用于流式API
     * -- GETTER --
     * 获取默认的CacheManager
     */
    @Getter
    @Setter
    private static SpringBootCacheManager defaultCacheManager;

    /**
     * 获取已存在的缓存
     */
    @SuppressWarnings("unchecked")
    public static <K, V> Cache<K, V> getCache(String cacheName) {
        SpringBootCacheManager manager = getDefaultCacheManager();
        if (manager == null) {
            throw new IllegalStateException("No default CacheManager set. Use getCache(cacheName, cacheManager) or setDefaultCacheManager()");
        }
        return (Cache<K, V>) manager.getCache(cacheName);
    }

    /**
     * 获取已存在的缓存 - 使用指定管理器
     */
    @SuppressWarnings("unchecked")
    public static <K, V> Cache<K, V> getCache(String cacheName, CacheManager cacheManager) {
        return (Cache<K, V>) cacheManager.getCache(cacheName);
    }

    /**
     * 创建缓存构建器 - 使用默认配置（统一门面入口）
     */
    public static <K, V> CascadeCacheBuilder<K, V> cache(String cacheName) {
        SpringBootCacheManager manager = getDefaultCacheManager();
        if (manager == null) {
            manager = new SpringBootCacheManager();
            setDefaultCacheManager(manager);
        }
        return new CascadeCacheBuilder<>(cacheName, manager);
    }

    /**
     * 创建缓存构建器 - 使用指定管理器
     */
    public static <K, V> CascadeCacheBuilder<K, V> cache(String cacheName, CacheManager cacheManager) {
        return new CascadeCacheBuilder<>(cacheName, cacheManager);
    }

    /**
     * 创建配置构建器
     */
    public static ConfigurationBuilder config(String cacheName) {
        return new ConfigurationBuilder(cacheName);
    }

    // ==================== 整合的配置构建器 ====================

    /**
     * 配置构建器
     * 提供流式API创建统一配置
     */
    public static class ConfigurationBuilder {
        private final CascadeCacheConfiguration config;

        public ConfigurationBuilder(String cacheName) {
            this.config = new CascadeCacheConfiguration()
                    .setName(cacheName)
                    .setEnabled(true);
        }

        /**
         * 启用L2缓存
         */
        public ConfigurationBuilder enableL2() {
            config.getL2().setEnabled(true);
            return this;
        }

        /**
         * 配置L2缓存键前缀
         */
        public ConfigurationBuilder l2KeyPrefix(String prefix) {
            config.getL2().setKeyPrefix(prefix);
            return this;
        }

        /**
         * 启用防护机制
         */
        public ConfigurationBuilder enableProtection() {
            config.getProtection().setEnabled(true);
            return this;
        }

        /**
         * 启用同步
         */
        public ConfigurationBuilder enableSync() {
            config.getSync().setEnabled(true);
            return this;
        }

        /**
         * 配置缓存大小
         */
        public ConfigurationBuilder size(long size) {
            config.getCommon().setMaximumSize(size);
            config.getL1().setMaximumSize(size);
            return this;
        }

        /**
         * 配置过期时间
         */
        public ConfigurationBuilder expireAfterWrite(Duration duration) {
            config.getCommon().setExpireAfterWrite(duration);
            return this;
        }

        /**
         * 配置刷新时间
         */
        public ConfigurationBuilder refreshAfterWrite(Duration duration) {
            config.getCommon().setRefreshAfterWrite(duration);
            return this;
        }

        /**
         * 启用统计
         */
        public ConfigurationBuilder recordStats() {
            config.getCommon().setRecordStats(true);
            return this;
        }

        /**
         * 配置布隆过滤器
         */
        public ConfigurationBuilder bloomFilter(long expectedElements, double falsePositiveRate) {
            config.getProtection().getBloomFilter()
                    .setEnabled(true)
                    .setExpectedElements(expectedElements)
                    .setFalsePositiveRate(falsePositiveRate);
            return this;
        }

        /**
         * 构建配置
         */
        public CascadeCacheConfiguration build() {
            return config;
        }

        /**
         * 构建缓存构建器
         */
        public <K, V> CascadeCacheBuilder<K, V> buildCacheBuilder() {
            SpringBootCacheManager manager = getDefaultCacheManager();
            if (manager == null) {
                manager = new SpringBootCacheManager();
                setDefaultCacheManager(manager);
            }
            return new CascadeCacheBuilder<>(config, manager);
        }

        /**
         * 构建缓存构建器 - 使用指定管理器
         */
        public <K, V> CascadeCacheBuilder<K, V> buildCacheBuilder(CacheManager cacheManager) {
            return new CascadeCacheBuilder<>(config, cacheManager);
        }
    }

    // ==================== 工具方法 ====================

    /**
     * 创建配置的副本
     */
    public static CascadeCacheConfiguration copyConfig(CascadeCacheConfiguration source) {
        // 简单复制实现
        CascadeCacheConfiguration copy = new CascadeCacheConfiguration()
                .setName(source.getName())
                .setEnabled(source.isEnabled());

        // 复制各个配置项
        copy.getCommon()
                .setMaximumSize(source.getCommon().getMaximumSize())
                .setExpireAfterWrite(source.getCommon().getExpireAfterWrite())
                .setExpireAfterAccess(source.getCommon().getExpireAfterAccess());

        copy.getL1()
                .setEnabled(source.getL1().isEnabled())
                .setMaximumSize(source.getL1().getMaximumSize());

        copy.getL2()
                .setEnabled(source.getL2().isEnabled())
                .setKeyPrefix(source.getL2().getKeyPrefix());

        return copy;
    }

    // ==================== 构建方法 ====================

    @Override
    public Cache<K, V> build() {
        // 根据配置决定构建简化版还是完整版缓存
        if (cacheManager instanceof SpringBootCacheManager &&
                !config.getL2().isEnabled() &&
                !config.getSync().isEnabled() &&
                !config.getProtection().isEnabled()) {
            return buildSimplified();
        }
        return buildFull();
    }

    @Override
    public Cache<K, V> buildWithoutRegister() {
        return buildFull();
    }

    /**
     * 构建简化版缓存
     */
    private Cache<K, V> buildSimplified() {
        validateConfig();

        SimpleCascadeCache<K, V> cache = new SimpleCascadeCache<>(config.getName());

        // 设置CacheLoader
        if (cacheLoader != null) {
            cache.setLoader(cacheLoader);
            logger.info("Created simplified cache '{}' with loader support", config.getName());
        } else {
            logger.info("Created simplified cache '{}' without loader", config.getName());
        }

        // 如果启用了防护机制，配置防护管理器
        if (config.getProtection().isEnabled()) {
            try {
                SimplifiedCacheProtectionManager protectionManager = configureSimplifiedProtection();
                cache.setProtectionManager(protectionManager);
                logger.info("Enabled protection for simplified cache '{}'", config.getName());
            } catch (Exception e) {
                logger.warn("Failed to configure protection for simplified cache '{}': {}", config.getName(), e.getMessage());
            }
        }

        // cacheManager 负责管理缓存
//        registerCache(cache);
        return cache;
    }

    /**
     * 构建完整的多级缓存
     */
    private Cache<K, V> buildFull() {
        validateConfig();

        // 构建L1缓存
        SimpleCaffeineLocalTier<K, V> l1Cache = buildL1Cache();

        // 构建L2缓存
        RedissonRemoteTier<K, V> l2Cache = null;
        if (config.getL2().isEnabled()) {
            l2Cache = buildL2Cache();
        }

        // 构建同步管理器
        CacheSyncManager syncManager = null;
        if (config.getSync().isEnabled() && config.getL2().isEnabled()) {
            syncManager = buildSyncManager();
        }

        // 创建配置对象
        DistributedTieredCacheProperties properties = createProperties();

        // 创建加载管理器工厂
        CacheLoadingManagerFactory loadingManagerFactory = new CacheLoadingManagerFactory(properties);

        // 创建多级缓存 - 传递类型信息
        EnhancedDistributedTieredCache<K, V> cache = new EnhancedDistributedTieredCache<>(
                config.getName(), l1Cache, l2Cache, properties, loadingManagerFactory, syncManager,
                applicationContext, keyType, valueType
        );

        // 设置CacheLoader
        if (cacheLoader != null) {
            cache.setLoader(cacheLoader);
        }

        registerCache(cache);
        return cache;
    }

    // ==================== 私有方法 ====================

    /**
     * 尝试从Spring容器中获取RedissonClient
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
     * 验证配置
     */
    private void validateConfig() {
        if (config.getName() == null || config.getName().trim().isEmpty()) {
            throw new IllegalStateException("Cache name cannot be null or empty");
        }
        if (config.getCommon().getMaximumSize() <= 0) {
            throw new IllegalStateException("Maximum size must be positive");
        }
        if (config.getL2().isEnabled() && config.getL2().getRedissonClient() == null) {
            throw new IllegalStateException("L2 cache enabled but no RedissonClient provided");
        }
        if (config.getSync().isEnabled() && !config.getL2().isEnabled()) {
            throw new IllegalStateException("Cache sync requires L2 cache to be enabled");
        }
    }

    /**
     * 构建L1缓存
     */
    private SimpleCaffeineLocalTier<K, V> buildL1Cache() {
        CascadeCacheConfiguration.L1Config l1Config = config.getL1();
        CascadeCacheConfiguration.CommonConfig commonConfig = config.getCommon();

        SimpleCaffeineLocalTier.LocalTierConfig tierConfig = new SimpleCaffeineLocalTier.LocalTierConfig();
        tierConfig.setMaximumSize(l1Config.getMaximumSize());
        tierConfig.setExpireAfterWrite(l1Config.getExpireAfterWrite() != null ?
                l1Config.getExpireAfterWrite() : commonConfig.getExpireAfterWrite());
        tierConfig.setExpireAfterAccess(l1Config.getExpireAfterAccess() != null ?
                l1Config.getExpireAfterAccess() : commonConfig.getExpireAfterAccess());
        tierConfig.setRefreshAfterWrite(commonConfig.getRefreshAfterWrite());
        tierConfig.setRecordStats(commonConfig.isRecordStats() || l1Config.isRecordStats());

        if (commonConfig.getRefreshAfterWrite() != null && cacheLoader != null) {
            tierConfig.setCacheLoader(cacheLoader);
        }

        return new SimpleCaffeineLocalTier<>("l1", tierConfig);
    }

    /**
     * 构建L2缓存
     */
    private RedissonRemoteTier<K, V> buildL2Cache() {
        CascadeCacheConfiguration.L2Config l2Config = config.getL2();

        if (l2Config.getRedissonClient() == null) {
            throw new IllegalStateException("RedissonClient is required for L2 cache");
        }

        RedissonRemoteTier.RemoteTierConfig tierConfig = new RedissonRemoteTier.RemoteTierConfig();
        tierConfig.setKeyPrefix(l2Config.getKeyPrefix());
        tierConfig.setDefaultTtl(l2Config.getDefaultTtl());
        tierConfig.setRefreshAfterWrite(l2Config.getRefreshAfterWrite());
        tierConfig.setEnableBatch(l2Config.isEnableBatch());
        tierConfig.setBatchSize(l2Config.getBatchSize());

        RedissonRemoteTier<K, V> l2Cache = new RedissonRemoteTier<>("l2", l2Config.getRedissonClient(), tierConfig);

        if (cacheLoader != null) {
            l2Cache.setLoader(cacheLoader);
        }

        return l2Cache;
    }

    /**
     * 构建同步管理器
     */
    private CacheSyncManager buildSyncManager() {
        return new RedissonCacheSyncManager(
                config.getL2().getRedissonClient(),
                config.getSync().getTopic()
        );
    }

    /**
     * 创建DistributedTieredCacheProperties配置对象
     */
    private DistributedTieredCacheProperties createProperties() {
        DistributedTieredCacheProperties properties = new DistributedTieredCacheProperties();

        // 基本配置
        properties.setEnableSync(config.getSync().isEnabled());
        properties.setEnableProtection(config.getProtection().isEnabled());

        // 防护配置
        DistributedTieredCacheProperties.ProtectionConfig protectionConfig = properties.getProtection();
        protectionConfig.setEnableBloomFilter(config.getProtection().isEnabled());
        protectionConfig.setEnableRandomTtl(config.getProtection().isEnabled());
        protectionConfig.setEnableDistributedLock(config.getProtection().isEnabled());

        // 监控配置
        DistributedTieredCacheProperties.MonitoringConfig monitoringConfig = properties.getMonitoring();
        monitoringConfig.setEnableMetrics(config.getMonitoring().isEnableMetrics());

        return properties;
    }

    /**
     * 配置简化版防护机制
     */
    private SimplifiedCacheProtectionManager configureSimplifiedProtection() {
        CascadeCacheConfiguration.ProtectionConfig protectionConfig = config.getProtection();
        SimplifiedCacheProtectionManager.Builder protectionBuilder = SimplifiedCacheProtectionManager.builder();

        // 配置布隆过滤器
        if (protectionConfig.getBloomFilter().isEnabled()) {
            CascadeBloomFilter bloomFilter = protectionConfig.getBloomFilter().getCustomFilter();
            if (bloomFilter == null && config.getL2().getRedissonClient() != null) {
                String filterName = config.getName() + "_bloom_filter";
                bloomFilter = RedissonBloomFilterProtection.builder(config.getL2().getRedissonClient())
                        .filterName(filterName)
                        .expectedElements(protectionConfig.getBloomFilter().getExpectedElements())
                        .falsePositiveRate(protectionConfig.getBloomFilter().getFalsePositiveRate())
                        .build();
            }
            if (bloomFilter != null) {
                protectionBuilder.bloomFilter(bloomFilter);
            }
        }

        // 配置随机TTL
        if (protectionConfig.getRandomTtl().isEnabled()) {
            CascadeCacheConfiguration.ProtectionConfig.RandomTtlConfig ttlConfig = protectionConfig.getRandomTtl();
            Duration jitterRange = ttlConfig.getJitterRange();
            if (jitterRange == null && ttlConfig.getBaseTtl() != null) {
                jitterRange = Duration.ofMillis(
                        (long) (ttlConfig.getBaseTtl().toMillis() * ttlConfig.getJitterRatio())
                );
            }

            RandomTtlProtection randomTtl = new RandomTtlProtection(
                    ttlConfig.getBaseTtl(), jitterRange, RandomTtlProtection.JitterStrategy.UNIFORM
            );
            protectionBuilder.randomTtl(randomTtl);
        }

        // 配置分布式锁
        if (protectionConfig.getDistributedLock().isEnabled() && config.getL2().isEnabled()) {
            CascadeCacheConfiguration.ProtectionConfig.DistributedLockConfig lockConfig =
                    protectionConfig.getDistributedLock();

            RedissonLockProtection lockProtection = new RedissonLockProtection(
                    config.getL2().getRedissonClient(),
                    lockConfig.getKeyPrefix(),
                    lockConfig.getLockTimeout(),
                    lockConfig.getWaitTimeout(),
                    lockConfig.getMaxRetries(),
                    lockConfig.getRetryDelay()
            );
            protectionBuilder.redissonLock(lockProtection);
        }

        protectionBuilder
                .enablePenetrationProtection(protectionConfig.getBloomFilter().isEnabled())
                .enableAvalancheProtection(protectionConfig.getRandomTtl().isEnabled())
                .enableHotspotProtection(protectionConfig.getDistributedLock().isEnabled());

        return protectionBuilder.build();
    }

    /**
     * 注册缓存到管理器
     */
    private void registerCache(Cache<K, V> cache) {
        try {
            boolean registered = cacheManager.registerCache(config.getName(), cache);
            if (!registered) {
                cacheManager.forceRegisterCache(config.getName(), cache);
            }
        } catch (Exception e) {
            // 不抛出异常，允许缓存正常返回
            logger.warn("Failed to register cache '{}': {}", config.getName(), e.getMessage());
        }
    }
}