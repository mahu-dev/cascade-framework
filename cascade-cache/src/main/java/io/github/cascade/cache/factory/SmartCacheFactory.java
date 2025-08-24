package io.github.cascade.cache.factory;

import io.github.cascade.cache.api.Cache;
import io.github.cascade.cache.config.CascadeCacheConfiguration;
import io.github.cascade.cache.core.unified.UnifiedCacheBuilder;
import io.github.cascade.cache.metrics.UnifiedMonitoringManager;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 智能缓存工厂实现
 * 负责创建和配置统一缓存实例
 * 
 * @author cascade
 */
public class SmartCacheFactory implements CacheFactory {
    
    private static final Logger logger = LoggerFactory.getLogger(SmartCacheFactory.class);
    private static final String UNKNOWN_TYPE = "Unknown";
    private static final String UNIFIED_CACHE_TYPE = "unified";
    
    private final RedissonClient redissonClient;
    private final UnifiedMonitoringManager monitoringManager;
    
    public SmartCacheFactory(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
        this.monitoringManager = null;
    }
    
    public SmartCacheFactory(RedissonClient redissonClient, UnifiedMonitoringManager monitoringManager) {
        this.redissonClient = redissonClient;
        this.monitoringManager = monitoringManager;
    }
    
    @Override
    public <K, V> Cache<K, V> createCache(String cacheName, Class<K> keyType, Class<V> valueType) {
        CascadeCacheConfiguration defaultConfig = createDefaultConfiguration(cacheName);
        return createCache(cacheName, defaultConfig, keyType, valueType);
    }
    
    @Override
    public <K, V> Cache<K, V> createCache(String cacheName, CascadeCacheConfiguration config, Class<K> keyType, Class<V> valueType) {
        logger.info("创建智能缓存 '{}' - Key: {}, Value: {}", cacheName,
                keyType != null ? keyType.getSimpleName() : UNKNOWN_TYPE,
                valueType != null ? valueType.getSimpleName() : UNKNOWN_TYPE);
        
        try {
            // 1. 准备和验证配置
            config = prepareConfiguration(cacheName, config);
            
            // 2. 创建缓存
            Cache<K, V> cache = createConfiguredCache(cacheName, config, keyType, valueType);
            logger.debug("智能缓存 '{}' 创建成功", cacheName);
            return cache;
            
        } catch (Exception e) {
            logger.error("创建缓存 '{}' 失败: {}", cacheName, e.getMessage(), e);
            throw new RuntimeException("缓存创建失败: " + cacheName, e);
        }
    }
    
    @Override
    public boolean supports(String cacheType) {
        return UNIFIED_CACHE_TYPE.equals(cacheType) || cacheType == null;
    }
    
    /**
     * 准备和验证缓存配置
     */
    private CascadeCacheConfiguration prepareConfiguration(String cacheName, CascadeCacheConfiguration cacheConfig) {
        // 使用默认配置（如果需要）
        if (cacheConfig == null) {
            cacheConfig = createDefaultConfiguration(cacheName);
            logger.debug("缓存 '{}' 使用默认配置", cacheName);
        }
        
        // 设置Redis客户端（如果L2启用）
        if (redissonClient != null && cacheConfig.getL2().isEnabled()) {
            cacheConfig.getL2().setRedissonClient(redissonClient);
        }
        
        // 验证配置合理性
        validateCacheConfiguration(cacheConfig);
        
        return cacheConfig;
    }
    
    /**
     * 创建和配置缓存
     */
    private <K, V> Cache<K, V> createConfiguredCache(String cacheName,
                                                     CascadeCacheConfiguration cacheConfig,
                                                     Class<K> keyType,
                                                     Class<V> valueType) {
        // 判断缓存层级配置并使用新的分段构建器
        if (cacheConfig.getL1().isEnabled() && cacheConfig.getL2().isEnabled() && redissonClient != null) {
            // L1 + L2 配置
            return UnifiedCacheBuilder.forCache(cacheName, keyType, valueType)
                    .withL1AndL2(cacheConfig.getL1(), cacheConfig.getL2(), redissonClient)
                    .enableProtection(cacheConfig.getProtection())
                    .enableCacheSync(cacheConfig.getSync())
                    .enableAutoRefresh(cacheConfig.getRefresh())
                    .build(cacheConfig);
        } else if (cacheConfig.getL1().isEnabled()) {
            // 仅L1配置
            return UnifiedCacheBuilder.forCache(cacheName, keyType, valueType)
                    .withL1Only(cacheConfig.getL1())
                    .enableProtection(cacheConfig.getProtection())
                    .enableCacheSync(cacheConfig.getSync())
                    .enableAutoRefresh(cacheConfig.getRefresh())
                    .build(cacheConfig);
        } else {
            throw new IllegalArgumentException("至少需要启用一个缓存层级（L1或L2）");
        }
    }
    
    
    /**
     * 验证缓存配置的合理性
     */
    private void validateCacheConfiguration(CascadeCacheConfiguration config) {
        if (!config.getL1().isEnabled() && !config.getL2().isEnabled()) {
            throw new IllegalArgumentException("至少需要启用一个缓存层级（L1或L2）");
        }
        
        if (config.getL2().isEnabled() && redissonClient == null) {
            logger.warn("L2缓存已启用但RedissonClient为null，将禁用L2");
            config.getL2().setEnabled(false);
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
}