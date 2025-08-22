package io.github.cascade.cache.manager;

import io.github.cascade.api.HealthStatus;
import io.github.cascade.cache.api.Cache;
import io.github.cascade.cache.api.CacheManager;
import io.github.cascade.cache.config.CachePropertiesProvider;
import io.github.cascade.cache.config.CascadeCacheConfiguration;
import io.github.cascade.cache.factory.CacheFactory;
import io.github.cascade.cache.factory.SmartCacheFactory;
import io.github.cascade.cache.metrics.UnifiedMonitoringManager;
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

/**
 * Cascade 缓存管理器
 * 整合Spring Boot自动配置和统一缓存架构，提供完整的缓存管理功能
 *
 * @author cascade
 */
public class CascadeCacheManager implements CacheManager {

    private static final Logger logger = LoggerFactory.getLogger(CascadeCacheManager.class);
    private static final String UNKNOWN_TYPE = "Unknown";

    private final CachePropertiesProvider cachePropertiesProvider;
    /**
     * -- GETTER --
     * 获取RedissonClient
     */
    @Getter
    private final RedissonClient redissonClient;
    private final Map<String, Cache<?, ?>> caches = new ConcurrentHashMap<>();

    // 监控管理器 - 可选依赖
    private final UnifiedMonitoringManager monitoringManager;

    // 缓存工厂 - 负责缓存创建
    private final CacheFactory cacheFactory;

    // 简化的状态管理
    private final AtomicBoolean closed = new AtomicBoolean(false);



    public CascadeCacheManager(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
        this.cachePropertiesProvider = null;
        this.monitoringManager = null;
        this.cacheFactory = new SmartCacheFactory(redissonClient);
    }

    public CascadeCacheManager(RedissonClient redissonClient,
                               CachePropertiesProvider cachePropertiesProvider) {
        this.redissonClient = redissonClient;
        this.cachePropertiesProvider = cachePropertiesProvider;
        this.monitoringManager = null;
        this.cacheFactory = new SmartCacheFactory(redissonClient);
    }

    public CascadeCacheManager(RedissonClient redissonClient,
                               CachePropertiesProvider cachePropertiesProvider,
                               UnifiedMonitoringManager monitoringManager) {
        this.redissonClient = redissonClient;
        this.cachePropertiesProvider = cachePropertiesProvider;
        this.monitoringManager = monitoringManager;
        this.cacheFactory = new SmartCacheFactory(redissonClient, monitoringManager);
    }

    public CascadeCacheManager(RedissonClient redissonClient,
                               CachePropertiesProvider cachePropertiesProvider,
                               UnifiedMonitoringManager monitoringManager,
                               CacheFactory cacheFactory) {
        this.redissonClient = redissonClient;
        this.cachePropertiesProvider = cachePropertiesProvider;
        this.monitoringManager = monitoringManager;
        this.cacheFactory = cacheFactory != null ? cacheFactory : new SmartCacheFactory(redissonClient, monitoringManager);
    }


    // ==================== Spring 生命周期管理 ====================

    @PostConstruct
    private void init() {
        logger.debug("Cascade缓存管理器已由Spring容器初始化");

        // 启动监控管理器
        if (monitoringManager != null) {
            monitoringManager.start();
            logger.info("统一监控管理器已启动");
        }
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
        return getOrCreateCacheInternal(cacheName, String.class, valueType);
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
        return getOrCreateCacheInternal(cacheName, keyType, valueType);
    }

    /**
     * 从配置创建缓存的统一方法
     * 委托给CacheFactory处理缓存创建
     */
    private <K, V> Cache<K, V> createCacheFromConfig(String cacheName, CascadeCacheConfiguration cacheConfig,
                                                     Class<K> keyType, Class<V> valueType) {
        logger.debug("委托缓存工厂创建缓存: {}", cacheName);

        try {
            return cacheFactory.createCache(cacheName, cacheConfig, keyType, valueType);
        } catch (Exception e) {
            logger.error("缓存工厂创建缓存 '{}' 失败: {}", cacheName, e.getMessage(), e);
            throw new CacheCreationException("缓存创建失败: " + cacheName, e);
        }
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
        Cache<?, ?> c = caches.computeIfAbsent(cacheName, k -> {
            // 向监控管理器注册缓存
            if (monitoringManager != null) {
                monitoringManager.registerCache(cacheName);
                logger.debug("缓存 '{}' 已注册到监控管理器", cacheName);
            }
            return cache;
        });
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

        // 向监控管理器注册缓存
        if (monitoringManager != null) {
            monitoringManager.registerCache(cacheName);
            logger.debug("缓存 '{}' 已注册到监控管理器", cacheName);
        }

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
            // 从监控管理器注销缓存
            if (monitoringManager != null) {
                monitoringManager.unregisterCache(cacheName);
                logger.debug("缓存 '{}' 已从监控管理器注销", cacheName);
            }
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

            // 停止监控管理器
            if (monitoringManager != null) {
                monitoringManager.stop();
                logger.info("统一监控管理器已停止");
            }

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

    /**
     * 获取缓存工厂实例
     */
    public CacheFactory getCacheFactoryInstance() {
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

    /**
     * 统一的获取或创建缓存内部方法 - 消除重复代码，支持双检锁优化
     */
    private <K, V> Cache<K, V> getOrCreateCacheInternal(String cacheName, Class<K> keyType, Class<V> valueType) {
        if (closed.get()) {
            logger.warn("尝试从已关闭的管理器获取或创建缓存 '{}'", cacheName);
            return null;
        }

        // 第一次检查：快速路径，无锁检查
        Cache<K, V> existingCache = getCache(cacheName);
        if (existingCache != null) {
            logger.debug("快速获取已存在缓存: {}", cacheName);
            return existingCache;
        }

        // 使用computeIfAbsent实现双检锁语义，确保只有一个线程创建缓存
        @SuppressWarnings("unchecked")
        Cache<K, V> cache = (Cache<K, V>) caches.computeIfAbsent(cacheName, k -> {
            logger.info("正在创建新缓存: {} - Key: {}, Value: {}", cacheName,
                    keyType != null ? keyType.getSimpleName() : UNKNOWN_TYPE,
                    valueType != null ? valueType.getSimpleName() : UNKNOWN_TYPE);

            try {
                // 准备配置
                CascadeCacheConfiguration cacheConfig = cachePropertiesProvider != null
                        ? cachePropertiesProvider.toCascadeCacheConfiguration(cacheName)
                        : createDefaultConfiguration(cacheName);

                // 创建缓存
                Cache<K, V> newCache = createCacheFromConfig(cacheName, cacheConfig, keyType, valueType);

                // 注册到监控管理器
                if (monitoringManager != null) {
                    monitoringManager.registerCache(cacheName);
                    logger.debug("缓存 '{}' 已注册到监控管理器", cacheName);
                }

                logger.info("成功创建缓存: {}", cacheName);
                return newCache;

            } catch (Exception e) {
                logger.error("创建缓存失败: {} - {}", cacheName, e.getMessage(), e);
                // 重新抛出异常，让computeIfAbsent不会存储null值
                if (e instanceof RuntimeException) {
                    throw (RuntimeException) e;
                }
                throw new CacheCreationException("缓存创建失败: " + cacheName, e);
            }
        });

        return cache;
    }

    /**
     * 自定义缓存创建异常
     */
    public static class CacheCreationException extends RuntimeException {
        public CacheCreationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

}