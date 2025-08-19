package io.github.cascade.cache.manager;

import io.github.cascade.api.HealthStatus;
import io.github.cascade.cache.api.Cache;
import io.github.cascade.cache.api.CacheManager;
import io.github.cascade.cache.config.CachePropertiesProvider;
import io.github.cascade.cache.config.unified.CascadeCacheConfiguration;
import io.github.cascade.cache.core.unified.UnifiedCacheBuilder;
import io.github.cascade.cache.util.TypeInference;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Spring Boot 集成缓存管理器
 * 简化版本，专注于核心缓存管理功能
 *
 * @author cascade
 */
public class SpringBootCacheManager implements CacheManager {

    private static final Logger logger = LoggerFactory.getLogger(SpringBootCacheManager.class);

    private final CascadeCacheConfiguration defaultConfig;
    private final CachePropertiesProvider cachePropertiesProvider;
    /**
     * -- GETTER --
     * 获取RedissonClient
     */
    @Getter
    private final RedissonClient redissonClient;
    private final Map<String, Cache<?, ?>> caches = new ConcurrentHashMap<>();

    // 简化的状态管理
    private final AtomicBoolean closed = new AtomicBoolean(false);

    // CacheManager 字段
    private Function<String, Cache<?, ?>> cacheFactory;

    public SpringBootCacheManager(CascadeCacheConfiguration defaultConfig,
                                  RedissonClient redissonClient) {
        this.defaultConfig = defaultConfig;
        this.redissonClient = redissonClient;
        this.cachePropertiesProvider = null;
    }

    public SpringBootCacheManager(CascadeCacheConfiguration defaultConfig,
                                  RedissonClient redissonClient,
                                  CachePropertiesProvider cachePropertiesProvider) {
        this.defaultConfig = defaultConfig;
        this.redissonClient = redissonClient;
        this.cachePropertiesProvider = cachePropertiesProvider;
    }

    /**
     * 默认构造器，用于Spring自动装配
     */
    public SpringBootCacheManager() {
        this.defaultConfig = null;
        this.redissonClient = null;
        this.cachePropertiesProvider = null;
    }

    // ==================== Spring 生命周期管理 ====================

    @PostConstruct
    private void init() {
        logger.info("SpringBootCacheManager initialized by Spring container");
    }

    @PreDestroy
    private void cleanup() {
        close();
    }

    // ==================== 核心缓存管理方法 ====================

    @Override
    @SuppressWarnings("unchecked")
    public <K, V> Cache<K, V> getCache(String cacheName) {
        if (closed.get()) {
            logger.warn("Attempted to get cache '{}' from closed manager", cacheName);
            return null;
        }
        return (Cache<K, V>) caches.get(cacheName);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <K, V> Cache<K, V> getOrCreateCache(String cacheName) {
        return getOrCreateCache(cacheName, null, null);
    }

    /**
     * 获取或创建缓存，支持显式类型传递
     *
     * @param cacheName 缓存名称
     * @param valueType Value类型，可为null
     * @return 缓存实例
     */
    public <V> Cache<String, V> getOrCreateCache(String cacheName, Class<V> valueType) {
        // 创建缓存的时候 要支持全部高级配置项，最终创建 EnhancedDistributedTieredCache
        if (closed.get()) {
            logger.warn("Attempted to get or create cache '{}' from closed manager", cacheName);
            return null;
        }

        // 先尝试获取已存在的缓存
        Cache<String, V> existingCache = getCache(cacheName);
        if (existingCache != null) {
            logger.debug("Found existing cache '{}'", cacheName);
            return existingCache;
        }

        // 缓存不存在，创建新缓存
        logger.info("Creating new cache '{}' with enhanced features", cacheName);

        try {
            // 复制默认配置并设置缓存名称
            CascadeCacheConfiguration cacheConfig = copyConfigWithName(defaultConfig, cacheName);

            // 使用统一方法创建缓存
            Cache<String, V> newCache = createCacheFromConfig(cacheName, cacheConfig, String.class, valueType);

            // 注册到管理器
            if (registerCache(cacheName, newCache)) {
                logger.info("Successfully created and registered enhanced cache '{}'", cacheName);
                return newCache;
            } else {
                logger.warn("Failed to register cache '{}', returning existing cache", cacheName);
                return getCache(cacheName);
            }

        } catch (Exception e) {
            logger.error("Failed to create cache '{}': {}", cacheName, e.getMessage(), e);
            return null;
        }
    }

    /**
     * 获取或创建缓存，支持显式类型传递
     *
     * @param cacheName 缓存名称
     * @param keyType   Key类型，可为null
     * @param valueType Value类型，可为null
     * @return 缓存实例
     */
    public <K, V> Cache<K, V> getOrCreateCache(String cacheName, Class<K> keyType, Class<V> valueType) {
        // 创建缓存的时候 要支持全部高级配置项，最终创建 EnhancedDistributedTieredCache
        if (closed.get()) {
            logger.warn("Attempted to get or create cache '{}' from closed manager", cacheName);
            return null;
        }

        // 先尝试获取已存在的缓存
        Cache<K, V> existingCache = getCache(cacheName);
        if (existingCache != null) {
            logger.debug("Found existing cache '{}'", cacheName);
            return existingCache;
        }

        // 缓存不存在，创建新缓存
        logger.info("Creating new cache '{}' with enhanced features", cacheName);

        try {
            // 复制默认配置并设置缓存名称
            CascadeCacheConfiguration cacheConfig = copyConfigWithName(defaultConfig, cacheName);

            // 使用统一方法创建缓存
            Cache<K, V> newCache = createCacheFromConfig(cacheName, cacheConfig, keyType, valueType);

            // 注册到管理器
            if (registerCache(cacheName, newCache)) {
                logger.info("Successfully created and registered enhanced cache '{}'", cacheName);
                return newCache;
            } else {
                logger.warn("Failed to register cache '{}', returning existing cache", cacheName);
                return getCache(cacheName);
            }

        } catch (Exception e) {
            logger.error("Failed to create cache '{}': {}", cacheName, e.getMessage(), e);
            return null;
        }
    }

    /**
     * 从配置创建缓存的统一方法
     */
    @SuppressWarnings("unchecked")
    private <K, V> Cache<K, V> createCacheFromConfig(String cacheName, CascadeCacheConfiguration cacheConfig,
                                                     Class<K> keyType, Class<V> valueType) {
        logger.info("Creating cache '{}' with key type: {}, value type: {}", cacheName, keyType, valueType);

        // 如果没有传入配置，从Properties生成配置
        if (cacheConfig == null && cachePropertiesProvider != null) {
            cacheConfig = cachePropertiesProvider.toCascadeCacheConfiguration(cacheName);
            logger.debug("Generated config from Properties for cache '{}'", cacheName);
        }

        // 如果仍然没有配置，使用默认配置
        if (cacheConfig == null) {
            cacheConfig = createDefaultConfiguration(cacheName);
            logger.debug("Using default configuration for cache '{}'", cacheName);
        }

        // 设置Redis客户端
        if (redissonClient != null && cacheConfig.getL2().isEnabled()) {
            cacheConfig.getL2().setRedissonClient(redissonClient);
        }

        // 使用UnifiedCacheBuilder创建统一架构缓存，应用配置
        UnifiedCacheBuilder<K, V> builder = UnifiedCacheBuilder.newBuilder(cacheName, keyType, valueType);
        // 应用L1配置
        if (cacheConfig.getL1().isEnabled()) {
            builder = builder.enableL1(true)
                    .maximumSize(cacheConfig.getL1().getMaximumSize())
                    .recordStats(cacheConfig.getL1().isRecordStats());

            if (cacheConfig.getL1().getExpireAfterWrite() != null) {
                builder = builder.expireAfterWrite(cacheConfig.getL1().getExpireAfterWrite());
            }
            if (cacheConfig.getL1().getExpireAfterAccess() != null) {
                builder = builder.expireAfterAccess(cacheConfig.getL1().getExpireAfterAccess());
            }
        }

        // 应用L2配置
        if (cacheConfig.getL2().isEnabled() && redissonClient != null) {
            builder = builder.withRedis(redissonClient)
                    .enableL2(true)
                    .keyPrefix(cacheConfig.getL2().getKeyPrefix())
                    .defaultTtl(cacheConfig.getL2().getDefaultTtl());
        }

        // 应用防护配置
        if (cacheConfig.getProtection().isEnabled()) {
            builder = builder.enableProtection(true);

            if (cacheConfig.getProtection().getBloomFilter().isEnabled()) {
                builder = builder.bloomFilter(
                        cacheConfig.getProtection().getBloomFilter().getExpectedElements(),
                        cacheConfig.getProtection().getBloomFilter().getFalsePositiveRate()
                );
            }

            if (cacheConfig.getProtection().getRandomTtl().isEnabled()) {
                builder = builder.randomTtl(
                        cacheConfig.getL2().getDefaultTtl(),
                        0.1 // 默认10%的抖动
                );
            }
        }

        return builder.build();
    }

    /**
     * 创建默认配置
     */
    private CascadeCacheConfiguration createDefaultConfiguration(String cacheName) {
        CascadeCacheConfiguration config = new CascadeCacheConfiguration();
        config.setName(cacheName).setEnabled(true);

        // 默认L1配置
        config.getL1().setEnabled(true)
                .setMaximumSize(10000)
                .setExpireAfterWrite(java.time.Duration.ofMinutes(30))
                .setRecordStats(true);

        // 默认L2配置（如果有Redis）
        if (redissonClient != null) {
            config.getL2().setEnabled(true)
                    .setDefaultTtl(java.time.Duration.ofHours(1));
        }

        return config;
    }

    private CascadeCacheConfiguration copyConfigWithName(CascadeCacheConfiguration defaultConfig, String cacheName) {

        if (defaultConfig == null) {
            // 如果没有默认配置，创建一个基础配置
            return new CascadeCacheConfiguration()
                    .setName(cacheName)
                    .setEnabled(true);
        }

        // 创建新配置并复制关键配置项
        CascadeCacheConfiguration newConfig = new CascadeCacheConfiguration()
                .setName(cacheName)
                .setEnabled(true);

        // 复制L1配置
        newConfig.getL1()
                .setEnabled(defaultConfig.getL1().isEnabled())
                .setMaximumSize(defaultConfig.getL1().getMaximumSize())
                .setExpireAfterWrite(defaultConfig.getL1().getExpireAfterWrite())
                .setExpireAfterAccess(defaultConfig.getL1().getExpireAfterAccess())
                .setRecordStats(defaultConfig.getL1().isRecordStats());

        // 复制L2配置
        newConfig.getL2()
                .setEnabled(defaultConfig.getL2().isEnabled())
                .setDefaultTtl(defaultConfig.getL2().getDefaultTtl())
                .setKeyPrefix(defaultConfig.getL2().getKeyPrefix())
                .setRedissonClient(redissonClient);

        // 复制同步配置
        newConfig.getSync()
                .setEnabled(defaultConfig.getSync().isEnabled());

        // 复制防护配置
        newConfig.getProtection()
                .setEnabled(defaultConfig.getProtection().isEnabled());

        // 复制布隆过滤器配置
        newConfig.getProtection().getBloomFilter()
                .setEnabled(defaultConfig.getProtection().getBloomFilter().isEnabled())
                .setExpectedElements(defaultConfig.getProtection().getBloomFilter().getExpectedElements())
                .setFalsePositiveRate(defaultConfig.getProtection().getBloomFilter().getFalsePositiveRate());

        // 复制随机TTL配置
        newConfig.getProtection().getRandomTtl()
                .setEnabled(defaultConfig.getProtection().getRandomTtl().isEnabled())
                .setBaseTtl(defaultConfig.getProtection().getRandomTtl().getBaseTtl())
                .setJitterRange(defaultConfig.getProtection().getRandomTtl().getJitterRange());

        return newConfig;
    }

    @Override
    public <K, V> boolean registerCache(String cacheName, Cache<K, V> cache) {
        if (closed.get()) {
            logger.warn("Attempted to register cache '{}' to closed manager", cacheName);
            return false;
        }

        if (caches.containsKey(cacheName)) {
            logger.warn("Cache '{}' already exists", cacheName);
            return false;
        }

        caches.put(cacheName, cache);
        logger.info("Cache '{}' registered successfully", cacheName);
        return true;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <K, V> Cache<K, V> forceRegisterCache(String cacheName, Cache<K, V> cache) {
        if (closed.get()) {
            logger.warn("Attempted to force register cache '{}' to closed manager", cacheName);
            return null;
        }

        Cache<K, V> oldCache = (Cache<K, V>) caches.put(cacheName, cache);
        logger.info("Cache '{}' force registered successfully", cacheName);
        return oldCache;
    }

    @Override
    public Cache<?, ?> removeCache(String cacheName) {
        if (closed.get()) {
            logger.warn("Attempted to remove cache '{}' from closed manager", cacheName);
            return null;
        }

        Cache<?, ?> removedCache = caches.remove(cacheName);
        if (removedCache != null) {
            logger.info("Cache '{}' removed successfully", cacheName);
        } else {
            logger.warn("Cache '{}' not found for removal", cacheName);
        }
        return removedCache;
    }

    @Override
    public Collection<String> getCacheNames() {
        return caches.keySet();
    }

    @Override
    public Map<String, Cache<?, ?>> getAllCaches() {
        return new HashMap<>(caches);
    }

    @Override
    public void clearAllCaches() {
        if (closed.get()) {
            logger.warn("Attempted to clear caches from closed manager");
            return;
        }

        caches.values().forEach(cache -> {
            try {
                cache.clear();
            } catch (Exception e) {
                logger.warn("Error clearing cache: {}", e.getMessage());
            }
        });
        logger.info("All caches cleared");
    }

    @Override
    public boolean containsCache(String cacheName) {
        return caches.containsKey(cacheName);
    }

    // ==================== 生命周期管理 ====================

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            logger.info("Closing SpringBootCacheManager...");

            // 清理所有缓存
            caches.values().forEach(cache -> {
                try {
                    if (cache instanceof AutoCloseable) {
                        ((AutoCloseable) cache).close();
                    }
                } catch (Exception e) {
                    logger.warn("Error closing cache: {}", e.getMessage());
                }
            });

            caches.clear();
            logger.info("SpringBootCacheManager closed");
        }
    }

    @Override
    public boolean isClosed() {
        return closed.get();
    }

    // ==================== 监控和统计 ====================

    @Override
    public HealthStatus getHealthStatus() {
        if (closed.get()) {
            return HealthStatus.down();
        }
        return HealthStatus.up();
    }

    @Override
    public int getCacheCount() {
        return caches.size();
    }

    @Override
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("cacheCount", getCacheCount());
        stats.put("cacheNames", getCacheNames());
        stats.put("closed", isClosed());
        return stats;
    }

    // ==================== 工厂方法 ====================

    @Override
    public void setCacheFactory(Function<String, Cache<?, ?>> cacheFactory) {
        this.cacheFactory = cacheFactory;
    }

    @Override
    public Function<String, Cache<?, ?>> getCacheFactory() {
        return cacheFactory;
    }

    // ==================== 优雅的类型化缓存获取方法 ====================

    /**
     * 优雅地创建类型化缓存 - 无需TypeReference匿名类
     *
     * @param cacheName 缓存名称
     * @param keyType   Key类型
     * @param valueType Value类型
     * @return 类型化缓存
     */
    public <K, V> Cache<K, V> cache(String cacheName, Class<K> keyType, Class<V> valueType) {
        return getOrCreateCache(cacheName, keyType, valueType);
    }

    /**
     * 创建String类型Key的缓存 - 最常用场景
     */
    public <V> Cache<String, V> stringCache(String cacheName, Class<V> valueType) {
        return getOrCreateCache(cacheName, String.class, valueType);
    }

    /**
     * 创建Long类型Key的缓存 - ID场景
     */
    public <V> Cache<Long, V> longCache(String cacheName, Class<V> valueType) {
        return getOrCreateCache(cacheName, Long.class, valueType);
    }

    /**
     * 创建Integer类型Key的缓存
     */
    public <V> Cache<Integer, V> intCache(String cacheName, Class<V> valueType) {
        return getOrCreateCache(cacheName, Integer.class, valueType);
    }

    /**
     * 智能推断类型的缓存创建 - 实验性功能
     * 尝试从调用上下文和缓存名称推断类型
     */
    @SuppressWarnings("unchecked")
    public <K, V> Cache<K, V> smartCache(String cacheName) {
        // 尝试从调用栈推断类型
        Class<?>[] inferredTypes = TypeInference.inferCacheTypes();

        if (inferredTypes != null && inferredTypes.length == 2) {
            logger.debug("Inferred types from call stack: K={}, V={}", inferredTypes[0], inferredTypes[1]);
            return getOrCreateCache(cacheName,
                    (Class<K>) inferredTypes[0], (Class<V>) inferredTypes[1]);
        }

        // 尝试从缓存名称推断类型
        inferredTypes = TypeInference.inferFromVariableName(cacheName);
        if (inferredTypes != null && inferredTypes.length == 2) {
            logger.debug("Inferred types from cache name '{}': K={}, V={}", cacheName, inferredTypes[0], inferredTypes[1]);
            return getOrCreateCache(cacheName,
                    (Class<K>) inferredTypes[0], (Class<V>) inferredTypes[1]);
        }

        // 推断失败，使用默认行为
        logger.debug("Type inference failed for cache '{}', using default behavior", cacheName);
        return getOrCreateCache(cacheName);
    }

    // ==================== 工具方法 ====================

    /**
     * 获取默认的CascadeCacheConfiguration配置
     * 供CascadeCacheBuilder使用
     */
    public CascadeCacheConfiguration getDefaultCascadeCacheConfig() {
        return defaultConfig;
    }

}