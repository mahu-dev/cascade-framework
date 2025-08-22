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
            
            // 2. 创建并配置构建器
            UnifiedCacheBuilder<K, V> builder = createConfiguredBuilder(cacheName, config, keyType, valueType);
            
            // 3. 构建缓存
            Cache<K, V> cache = builder.build(config);
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
     * 创建和配置缓存构建器
     */
    private <K, V> UnifiedCacheBuilder<K, V> createConfiguredBuilder(String cacheName,
                                                                     CascadeCacheConfiguration cacheConfig,
                                                                     Class<K> keyType,
                                                                     Class<V> valueType) {
        UnifiedCacheBuilder<K, V> builder = UnifiedCacheBuilder.newBuilder(cacheName, keyType, valueType);
        
        // 设置监控管理器
        if (monitoringManager != null) {
            builder = builder.withMonitoringManager(monitoringManager);
        }
        
        // 应用分层配置
        builder = configureTiers(builder, cacheConfig);
        
        // 应用增强功能配置
        builder = configureEnhancements(builder, cacheConfig);
        
        return builder;
    }
    
    /**
     * 配置缓存分层（L1/L2）
     */
    private <K, V> UnifiedCacheBuilder<K, V> configureTiers(UnifiedCacheBuilder<K, V> builder,
                                                            CascadeCacheConfiguration cacheConfig) {
        // L1配置
        if (cacheConfig.getL1().isEnabled()) {
            builder.configL1(cacheConfig.getL1());
            logger.debug("已启用L1缓存层");
        }
        
        // L2配置
        if (cacheConfig.getL2().isEnabled() && redissonClient != null) {
            builder = builder.withRedis(redissonClient);
            builder.configL2(cacheConfig.getL2());
            logger.debug("已启用L2缓存层");
        }
        return builder;
    }
    
    /**
     * 配置增强功能（防护、同步等）
     */
    private <K, V> UnifiedCacheBuilder<K, V> configureEnhancements(UnifiedCacheBuilder<K, V> builder,
                                                                   CascadeCacheConfiguration cacheConfig) {
        // 同步配置
        if (cacheConfig.getSync().isEnabled()) {
            builder = builder.withSync();
            logger.debug("已启用缓存同步");
        }
        
        // 防护配置
        if (cacheConfig.getProtection().isEnabled()) {
            builder = configureProtection(builder, cacheConfig.getProtection());
        }
        
        // 自动刷新配置
        if (cacheConfig.getRefresh().isEnabled()) {
            builder = builder.withAutoRefresh(cacheConfig.getRefresh().getDefaultRefreshInterval());
        }
        
        // CacheLoader自动发现配置
        builder = builder.autoDiscoverLoader(true);
        
        return builder;
    }
    
    /**
     * 配置缓存防护功能
     */
    private <K, V> UnifiedCacheBuilder<K, V> configureProtection(UnifiedCacheBuilder<K, V> builder,
                                                                 CascadeCacheConfiguration.ProtectionConfig protectionConfig) {
        builder = builder.enableProtection(true);
        logger.debug("已启用缓存防护");
        
        // 布隆过滤器
        if (protectionConfig.getBloomFilter().isEnabled()) {
            var bloomFilterConfig = protectionConfig.getBloomFilter();
            builder = builder.bloomFilter(
                    bloomFilterConfig.getExpectedElements(),
                    bloomFilterConfig.getFalsePositiveRate()
            );
            logger.debug("已配置布隆过滤器: expectedElements={}, fpp={}",
                    bloomFilterConfig.getExpectedElements(),
                    bloomFilterConfig.getFalsePositiveRate());
        }
        
        // 随机TTL
        if (protectionConfig.getRandomTtl().isEnabled()) {
            var randomTtlConfig = protectionConfig.getRandomTtl();
            builder = builder.randomTtl(randomTtlConfig);
            logger.debug("已配置随机TTL防护");
        }
        
        // 分布式锁
        if (protectionConfig.getDistributedLock().isEnabled()) {
            var lockConfig = protectionConfig.getDistributedLock();
            builder.distributedLock(lockConfig);
            logger.debug("已配置分布式锁防护");
        }
        
        return builder;
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