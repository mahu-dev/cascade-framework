package io.github.cascade.cache.manager;

import io.github.cascade.api.HealthStatus;
import io.github.cascade.cache.api.Cache;
import io.github.cascade.cache.api.CacheManager;
import io.github.cascade.cache.config.CachePropertiesProvider;
import io.github.cascade.cache.config.CascadeCacheConfiguration;
import io.github.cascade.cache.core.unified.UnifiedCacheBuilder;
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
 * Cascade 缓存管理器
 * 整合Spring Boot自动配置和统一缓存架构，提供完整的缓存管理功能
 *
 * @author cascade
 */
public class CascadeCacheManager implements CacheManager {

    private static final Logger logger = LoggerFactory.getLogger(CascadeCacheManager.class);

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


    public CascadeCacheManager(CascadeCacheConfiguration defaultConfig,
                               RedissonClient redissonClient) {
        this.defaultConfig = defaultConfig;
        this.redissonClient = redissonClient;
        this.cachePropertiesProvider = null;
    }

    public CascadeCacheManager(RedissonClient redissonClient,
                               CachePropertiesProvider cachePropertiesProvider) {
        this.defaultConfig = null;
        this.redissonClient = redissonClient;
        this.cachePropertiesProvider = cachePropertiesProvider;
    }


    // ==================== Spring 生命周期管理 ====================

    @PostConstruct
    private void init() {
        logger.debug("Cascade缓存管理器已由Spring容器初始化");
    }

    @PreDestroy
    private void cleanup() {
        close();
    }

    // ==================== 核心缓存管理方法 ====================

    @Override
    public <K, V> Cache<K, V> getCache(String cacheName) {
        if (closed.get()) {
            logger.warn("尝试从已关闭的管理器获取缓存 '{}'", cacheName);
            return null;
        }
        @SuppressWarnings("unchecked")
        Cache<K, V> cache = (Cache<K, V>) caches.get(cacheName);
        return cache;
    }

    /**
     * 获取或创建缓存，支持显式类型传递
     *
     * @param cacheName 缓存名称
     * @param valueType Value类型，可为null
     * @return 缓存实例
     */
    @Override
    public <V> Cache<String, V> getOrCreateCache(String cacheName, Class<V> valueType) {
        // 创建缓存的时候 要支持全部高级配置项，最终创建 EnhancedDistributedTieredCache
        if (closed.get()) {
            logger.warn("尝试从已关闭的管理器获取或创建缓存 '{}'", cacheName);
            return null;
        }

        // 先尝试获取已存在的缓存
        Cache<String, V> existingCache = getCache(cacheName);
        if (existingCache != null) {
            logger.debug("CacheManager 获取到缓存 {} ", cacheName);
            return existingCache;
        }

        // 缓存不存在，创建新缓存
        logger.info("CacheManager 创建新缓存 {} ", cacheName);

        try {
            // 复制默认配置并设置缓存名称
            CascadeCacheConfiguration cacheConfig = null;
            if (cachePropertiesProvider != null) {
                cacheConfig = cachePropertiesProvider.toCascadeCacheConfiguration(cacheName);
            } else {
                cacheConfig = createDefaultConfiguration(cacheName);
            }

            // 使用统一方法创建缓存
            Cache<String, V> newCache = createCacheFromConfig(cacheName, cacheConfig, String.class, valueType);

            // 注册到管理器
            if (registerCache(cacheName, newCache)) {
                logger.info("成功创建并注册了增强型缓存 {}", cacheName);
                return newCache;
            } else {
                logger.warn("注册缓存失败 {}, 返回现有缓存", cacheName);
                return getCache(cacheName);
            }

        } catch (Exception e) {
            logger.error("创建缓存失败 {} : {}", cacheName, e.getMessage(), e);
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
            logger.warn("尝试从已关闭的管理器获取或创建缓存 {}", cacheName);
            return null;
        }
        // 先尝试获取已存在的缓存
        Cache<K, V> existingCache = getCache(cacheName);
        if (existingCache != null) {
            logger.debug("找到已存在的缓存 {}", cacheName);
            return existingCache;
        }
        // 缓存不存在，创建新缓存
        logger.info("正在创建具有增强功能的新缓存 {}", cacheName);

        try {
            // 复制默认配置并设置缓存名称
            CascadeCacheConfiguration cacheConfig = null;
            if (cachePropertiesProvider != null) {
                cacheConfig = cachePropertiesProvider.toCascadeCacheConfiguration(cacheName);
            } else {
                cacheConfig = createDefaultConfiguration(cacheName);
            }

            // 使用统一方法创建缓存
            Cache<K, V> newCache = createCacheFromConfig(cacheName, cacheConfig, keyType, valueType);

            // 注册到管理器
            if (registerCache(cacheName, newCache)) {
                logger.info("成功创建并注册了增强型缓存 '{}'", cacheName);
                return newCache;
            } else {
                logger.warn("注册缓存失败 {}, 返回现有缓存", cacheName);
                return getCache(cacheName);
            }

        } catch (Exception e) {
            logger.error("创建缓存失败 {} : {}", cacheName, e.getMessage(), e);
            return null;
        }
    }

    /**
     * 从配置创建缓存的统一方法
     */
    private <K, V> Cache<K, V> createCacheFromConfig(String cacheName, CascadeCacheConfiguration cacheConfig,
                                                     Class<K> keyType, Class<V> valueType) {
        logger.info("正在创建缓存 '{}' - Key类型: {}, Value类型: {}", cacheName, keyType, valueType);

        // 如果没有传入配置，使用默认配置
        if (cacheConfig == null) {
            cacheConfig = createDefaultConfiguration(cacheName);
            logger.debug("为缓存 '{}' 使用默认配置", cacheName);
        }

        // 设置Redis客户端
        if (redissonClient != null && cacheConfig.getL2().isEnabled()) {
            cacheConfig.getL2().setRedissonClient(redissonClient);
        }

        // 使用UnifiedCacheBuilder创建统一架构缓存，应用配置
        UnifiedCacheBuilder<K, V> builder = UnifiedCacheBuilder.newBuilder(cacheName, keyType, valueType);
        // 应用L1配置
        if (cacheConfig.getL1().isEnabled()) {
            builder.configL1(cacheConfig.getL1());
        }

        // 应用L2配置
        if (cacheConfig.getL2().isEnabled() && redissonClient != null) {
            builder = builder.withRedis(redissonClient);
            builder.configL2(cacheConfig.getL2());
        }

        // 应用同步配置
        if (cacheConfig.getSync().isEnabled()) {
            builder.withSync();
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
                CascadeCacheConfiguration.ProtectionConfig.RandomTtlConfig randomTtlConfig = cacheConfig.getProtection().getRandomTtl();
                builder = builder.randomTtl(randomTtlConfig);
                // 默认10%的抖动
                builder = builder.randomTtl(cacheConfig.getL2().getDefaultTtl(), 0.1);
            }
            // 分布式锁
            if (cacheConfig.getProtection().getDistributedLock().isEnabled()) {
                CascadeCacheConfiguration.ProtectionConfig.DistributedLockConfig distributedLockConfig = cacheConfig.getProtection().getDistributedLock();
                builder.distributedLock(distributedLockConfig);
            }

        }

        return builder.build(cacheConfig);
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


    @Override
    public <K, V> boolean registerCache(String cacheName, Cache<K, V> cache) {
        if (closed.get()) {
            logger.warn("尝试向已关闭的管理器注册缓存 '{}'", cacheName);
            return false;
        }
        Cache<?, ?> c = caches.computeIfAbsent(cacheName, k -> cache);
        return c != null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <K, V> Cache<K, V> forceRegisterCache(String cacheName, Cache<K, V> cache) {
        if (closed.get()) {
            logger.warn("尝试向已关闭的管理器强制注册缓存 '{}'", cacheName);
            return null;
        }
        Cache<K, V> oldCache = (Cache<K, V>) caches.put(cacheName, cache);
        logger.info("缓存 '{}' 强制注册成功", cacheName);
        return oldCache;
    }

    @Override
    public Cache<?, ?> removeCache(String cacheName) {
        if (closed.get()) {
            logger.warn("尝试从已关闭的管理器移除缓存 '{}'", cacheName);
            return null;
        }

        Cache<?, ?> removedCache = caches.remove(cacheName);
        if (removedCache != null) {
            logger.info("缓存 '{}' 移除成功", cacheName);
        } else {
            logger.warn("未找到要移除的缓存 '{}'", cacheName);
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
            logger.warn("尝试从已关闭的管理器清理缓存");
            return;
        }

        caches.values().forEach(cache -> {
            try {
                cache.clear();
            } catch (Exception e) {
                logger.warn("清理缓存时发生错误: {}", e.getMessage());
            }
        });
        logger.info("所有缓存已清理");
    }

    @Override
    public boolean containsCache(String cacheName) {
        return caches.containsKey(cacheName);
    }

    // ==================== 生命周期管理 ====================

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            logger.info("正在关闭Cascade缓存管理器...");

            // 清理所有缓存
            caches.values().forEach(cache -> {
                try {
                    if (cache instanceof AutoCloseable autoCloseable) {
                        autoCloseable.close();
                    }
                } catch (Exception e) {
                    logger.warn("关闭缓存时发生错误: {}", e.getMessage());
                }
            });

            caches.clear();
            logger.info("Cascade缓存管理器已关闭");
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
    public <K, V> Cache<K, V> newCache(String cacheName, Class<K> keyType, Class<V> valueType) {
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

    // ==================== 私有辅助方法 ====================


    // ==================== 工具方法 ====================

    /**
     * 获取默认的CascadeCacheConfiguration配置
     * 供CascadeCacheBuilder使用
     */
    public CascadeCacheConfiguration getDefaultCascadeCacheConfig() {
        return defaultConfig;
    }

}