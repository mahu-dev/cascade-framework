package io.github.cascade.cache.builder;

import io.github.cascade.cache.api.CacheLoader;
import io.github.cascade.cache.config.CascadeCacheConfiguration;
import io.github.cascade.cache.core.unified.CaffeineEngine.CaffeineConfig;
import io.github.cascade.cache.core.unified.RedisEngine.RedisConfig;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;

import java.time.Duration;

/**
 * 缓存构建验证器
 * 负责验证缓存构建过程中各种配置的正确性
 * 
 * @author cascade
 */
@Slf4j
public class CacheBuilderValidator {
    
    private final String cacheName;
    
    public CacheBuilderValidator(String cacheName) {
        this.cacheName = cacheName;
    }
    
    // ==================== 基础验证 ====================
    
    /**
     * 验证缓存名称
     */
    public void validateCacheName() {
        if (cacheName == null || cacheName.trim().isEmpty()) {
            throw new IllegalArgumentException("Cache name cannot be null or empty");
        }

        // 验证缓存名称格式
        if (!cacheName.matches("^[a-zA-Z0-9_\\-.:]+$")) {
            throw new IllegalArgumentException(
                    "Invalid cache name format: '" + cacheName + "'. Only alphanumeric characters, underscore, hyphen, dot and colon are allowed");
        }
    }
    
    // ==================== 层级配置验证 ====================
    
    /**
     * 验证层级配置
     */
    public void validateTierConfiguration(boolean enableL1, boolean enableL2) {
        if (!enableL1 && !enableL2) {
            throw new IllegalStateException("At least one cache tier (L1 or L2) must be enabled for cache: " + cacheName);
        }
    }
    
    /**
     * 验证L1配置
     */
    public void validateL1Configuration(CaffeineConfig<?, ?> l1Config) {
        if (l1Config == null) {
            throw new IllegalArgumentException("L1 configuration cannot be null for cache: " + cacheName);
        }
        
        if (l1Config.getMaximumSize() <= 0) {
            throw new IllegalArgumentException("L1 cache maximum size must be positive for cache: " + cacheName);
        }

        if (l1Config.getExpireAfterWrite() != null && l1Config.getExpireAfterWrite().isNegative()) {
            throw new IllegalArgumentException("L1 cache expireAfterWrite duration cannot be negative for cache: " + cacheName);
        }

        if (l1Config.getExpireAfterAccess() != null && l1Config.getExpireAfterAccess().isNegative()) {
            throw new IllegalArgumentException("L1 cache expireAfterAccess duration cannot be negative for cache: " + cacheName);
        }
        
        if (l1Config.getInitialCapacity() < 0) {
            throw new IllegalArgumentException("L1 cache initial capacity cannot be negative for cache: " + cacheName);
        }
    }
    
    /**
     * 验证L2配置
     */
    public void validateL2Configuration(RedisConfig l2Config) {
        if (l2Config == null) {
            throw new IllegalArgumentException("L2 configuration cannot be null for cache: " + cacheName);
        }
        
        if (l2Config.getDefaultTtl() != null && l2Config.getDefaultTtl().isNegative()) {
            throw new IllegalArgumentException("L2 cache default TTL cannot be negative for cache: " + cacheName);
        }

        if (l2Config.getBatchSize() <= 0) {
            throw new IllegalArgumentException("L2 cache batch size must be positive for cache: " + cacheName);
        }
    }
    
    // ==================== Redis配置验证 ====================
    
    /**
     * 验证Redis配置
     */
    public void validateRedisConfiguration(boolean enableL2, RedissonClient redissonClient) {
        if (enableL2 && redissonClient == null) {
            throw new IllegalStateException("RedissonClient is required for L2 cache: " + cacheName);
        }
    }
    
    // ==================== 防护配置验证 ====================
    
    /**
     * 验证防护配置
     */
    public void validateProtectionConfiguration(CascadeCacheConfiguration.ProtectionConfig protectionConfig, 
                                              RedissonClient redissonClient) {
        if (protectionConfig == null || !protectionConfig.isEnabled()) {
            return;
        }
        
        // 验证布隆过滤器配置
        if (protectionConfig.getBloomFilter() != null && protectionConfig.getBloomFilter().isEnabled()) {
            var bloomConfig = protectionConfig.getBloomFilter();
            
            if (redissonClient == null) {
                log.warn("Bloom filter protection requires RedissonClient for cache: {}", cacheName);
            }
            
            if (bloomConfig.getExpectedElements() <= 0) {
                throw new IllegalArgumentException("Bloom filter expected elements must be positive for cache: " + cacheName);
            }
            
            if (bloomConfig.getFalsePositiveRate() <= 0 || bloomConfig.getFalsePositiveRate() >= 1) {
                throw new IllegalArgumentException("Bloom filter false positive rate must be between 0 and 1 for cache: " + cacheName);
            }
        }
        
        // 验证随机TTL配置
        if (protectionConfig.getRandomTtl() != null && protectionConfig.getRandomTtl().isEnabled()) {
            var randomTtlConfig = protectionConfig.getRandomTtl();
            
            if (randomTtlConfig.getBaseTtl() != null && randomTtlConfig.getBaseTtl().isNegative()) {
                throw new IllegalArgumentException("Random TTL base duration cannot be negative for cache: " + cacheName);
            }
            
            if (randomTtlConfig.getJitterRatio() < 0 || randomTtlConfig.getJitterRatio() > 1) {
                throw new IllegalArgumentException("Random TTL jitter ratio must be between 0 and 1 for cache: " + cacheName);
            }
        }
        
        // 验证分布式锁配置
        if (protectionConfig.getDistributedLock() != null && protectionConfig.getDistributedLock().isEnabled()) {
            var lockConfig = protectionConfig.getDistributedLock();
            
            if (redissonClient == null) {
                log.warn("Distributed lock protection requires RedissonClient for cache: {}", cacheName);
            }
            
            if (lockConfig.getLockTimeout() != null && lockConfig.getLockTimeout().isNegative()) {
                throw new IllegalArgumentException("Distributed lock timeout cannot be negative for cache: " + cacheName);
            }
            
            if (lockConfig.getWaitTimeout() != null && lockConfig.getWaitTimeout().isNegative()) {
                throw new IllegalArgumentException("Distributed lock wait timeout cannot be negative for cache: " + cacheName);
            }
            
            if (lockConfig.getMaxRetries() < 0) {
                throw new IllegalArgumentException("Distributed lock max retries cannot be negative for cache: " + cacheName);
            }
        }
    }
    
    // ==================== 同步配置验证 ====================
    
    /**
     * 验证同步配置
     */
    public void validateSyncConfiguration(CascadeCacheConfiguration.SyncConfig syncConfig, RedissonClient redissonClient) {
        if (syncConfig == null || !syncConfig.isEnabled()) {
            return;
        }
        
        if (redissonClient == null) {
            log.warn("Distributed sync requires RedissonClient for cache: {}", cacheName);
        }

        if (syncConfig.getTimeout() != null && syncConfig.getTimeout().isNegative()) {
            throw new IllegalArgumentException("Sync timeout cannot be negative for cache: " + cacheName);
        }

        if (syncConfig.getTopic() == null || syncConfig.getTopic().trim().isEmpty()) {
            throw new IllegalArgumentException("Sync topic cannot be null or empty for cache: " + cacheName);
        }
    }
    
    // ==================== 刷新配置验证 ====================
    
    /**
     * 验证刷新配置
     */
    public void validateRefreshConfiguration(CascadeCacheConfiguration.RefreshConfig refreshConfig, 
                                           CacheLoader<?, ?> cacheLoader,
                                           boolean autoDiscoverLoader) {
        if (refreshConfig == null || !refreshConfig.isEnabled()) {
            return;
        }
        
        if (cacheLoader == null && !autoDiscoverLoader) {
            log.warn("Auto refresh requires CacheLoader for cache: {}", cacheName);
        }
        
        // 注意：这里由于RefreshConfig的具体结构不确定，暂时不做详细验证
        // 实际实现时需要根据RefreshConfig的具体字段进行验证
    }
    
    // ==================== 综合验证方法 ====================
    
    /**
     * 执行完整的配置验证
     */
    public void validateConfiguration(boolean enableL1, boolean enableL2,
                                    CaffeineConfig<?, ?> l1Config, RedisConfig l2Config,
                                    RedissonClient redissonClient,
                                    CascadeCacheConfiguration.ProtectionConfig protectionConfig,
                                    CascadeCacheConfiguration.SyncConfig syncConfig,
                                    CascadeCacheConfiguration.RefreshConfig refreshConfig,
                                    CacheLoader<?, ?> cacheLoader,
                                    boolean autoDiscoverLoader) {
        
        log.debug("Validating configuration for cache: {}", cacheName);
        
        // 基础验证
        validateCacheName();
        validateTierConfiguration(enableL1, enableL2);
        
        // L1配置验证
        if (enableL1) {
            validateL1Configuration(l1Config);
        }
        
        // L2配置验证
        if (enableL2) {
            validateL2Configuration(l2Config);
            validateRedisConfiguration(enableL2, redissonClient);
        }
        
        // 增强功能验证
        validateProtectionConfiguration(protectionConfig, redissonClient);
        validateSyncConfiguration(syncConfig, redissonClient);
        validateRefreshConfiguration(refreshConfig, cacheLoader, autoDiscoverLoader);
        
        log.debug("Configuration validation completed for cache: {}", cacheName);
    }
}