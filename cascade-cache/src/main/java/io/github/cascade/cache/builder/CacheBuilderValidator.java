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
            throw new IllegalArgumentException("缓存名称不能为空");
        }

        // 验证缓存名称格式
        if (!cacheName.matches("^[a-zA-Z0-9_\\-.:]+$")) {
            throw new IllegalArgumentException(
                    "无效的缓存名称格式: '" + cacheName + "'。只允许字母、数字、下划线、连字符、点和冒号");
        }
    }
    
    // ==================== 层级配置验证 ====================
    
    /**
     * 验证层级配置
     */
    public void validateTierConfiguration(boolean enableL1, boolean enableL2) {
        if (!enableL1 && !enableL2) {
            throw new IllegalStateException("缓存 '" + cacheName + "' 至少需要启用一个缓存层级（L1或L2）");
        }
    }
    
    /**
     * 验证L1配置
     */
    public void validateL1Configuration(CaffeineConfig<?, ?> l1Config) {
        if (l1Config == null) {
            throw new IllegalArgumentException("缓存 '" + cacheName + "' 的L1配置不能为空");
        }
        
        if (l1Config.getMaximumSize() <= 0) {
            throw new IllegalArgumentException("缓存 '" + cacheName + "' 的L1缓存最大容量必须为正数");
        }

        if (l1Config.getExpireAfterWrite() != null && l1Config.getExpireAfterWrite().isNegative()) {
            throw new IllegalArgumentException("缓存 '" + cacheName + "' 的L1缓存写入后过期时间不能为负数");
        }

        if (l1Config.getExpireAfterAccess() != null && l1Config.getExpireAfterAccess().isNegative()) {
            throw new IllegalArgumentException("缓存 '" + cacheName + "' 的L1缓存访问后过期时间不能为负数");
        }
        
        if (l1Config.getInitialCapacity() < 0) {
            throw new IllegalArgumentException("缓存 '" + cacheName + "' 的L1缓存初始容量不能为负数");
        }
    }
    
    /**
     * 验证L2配置
     */
    public void validateL2Configuration(RedisConfig l2Config) {
        if (l2Config == null) {
            throw new IllegalArgumentException("缓存 '" + cacheName + "' 的L2配置不能为空");
        }
        
        if (l2Config.getDefaultTtl() != null && l2Config.getDefaultTtl().isNegative()) {
            throw new IllegalArgumentException("缓存 '" + cacheName + "' 的L2缓存默认TTL不能为负数");
        }

        if (l2Config.getBatchSize() <= 0) {
            throw new IllegalArgumentException("缓存 '" + cacheName + "' 的L2缓存批处理大小必须为正数");
        }
    }
    
    // ==================== Redis配置验证 ====================
    
    /**
     * 验证Redis配置
     */
    public void validateRedisConfiguration(boolean enableL2, RedissonClient redissonClient) {
        if (enableL2 && redissonClient == null) {
            throw new IllegalStateException("缓存 '" + cacheName + "' 的L2缓存需要RedissonClient");
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
                log.warn("缓存 '{}' 的布隆过滤器防护需要RedissonClient", cacheName);
            }
            
            if (bloomConfig.getExpectedElements() <= 0) {
                throw new IllegalArgumentException("缓存 '" + cacheName + "' 的布隆过滤器预期元素数量必须为正数");
            }
            
            if (bloomConfig.getFalsePositiveRate() <= 0 || bloomConfig.getFalsePositiveRate() >= 1) {
                throw new IllegalArgumentException("缓存 '" + cacheName + "' 的布隆过滤器误判率必须在0和1之间");
            }
        }
        
        // 验证随机TTL配置
        if (protectionConfig.getRandomTtl() != null && protectionConfig.getRandomTtl().isEnabled()) {
            var randomTtlConfig = protectionConfig.getRandomTtl();
            
            if (randomTtlConfig.getBaseTtl() != null && randomTtlConfig.getBaseTtl().isNegative()) {
                throw new IllegalArgumentException("缓存 '" + cacheName + "' 的随机TTL基础时间不能为负数");
            }
            
            if (randomTtlConfig.getJitterRatio() < 0 || randomTtlConfig.getJitterRatio() > 1) {
                throw new IllegalArgumentException("缓存 '" + cacheName + "' 的随机TTL抖动比例必须在0和1之间");
            }
        }
        
        // 验证分布式锁配置
        if (protectionConfig.getDistributedLock() != null && protectionConfig.getDistributedLock().isEnabled()) {
            var lockConfig = protectionConfig.getDistributedLock();
            
            if (redissonClient == null) {
                log.warn("缓存 '{}' 的分布式锁防护需要RedissonClient", cacheName);
            }
            
            if (lockConfig.getLockTimeout() != null && lockConfig.getLockTimeout().isNegative()) {
                throw new IllegalArgumentException("缓存 '" + cacheName + "' 的分布式锁超时时间不能为负数");
            }
            
            if (lockConfig.getWaitTimeout() != null && lockConfig.getWaitTimeout().isNegative()) {
                throw new IllegalArgumentException("缓存 '" + cacheName + "' 的分布式锁等待超时时间不能为负数");
            }
            
            if (lockConfig.getMaxRetries() < 0) {
                throw new IllegalArgumentException("缓存 '" + cacheName + "' 的分布式锁最大重试次数不能为负数");
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
            log.warn("缓存 '{}' 的分布式同步需要RedissonClient", cacheName);
        }

        if (syncConfig.getTimeout() != null && syncConfig.getTimeout().isNegative()) {
            throw new IllegalArgumentException("缓存 '" + cacheName + "' 的同步超时时间不能为负数");
        }

        if (syncConfig.getTopic() == null || syncConfig.getTopic().trim().isEmpty()) {
            throw new IllegalArgumentException("缓存 '" + cacheName + "' 的同步主题不能为空");
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
            log.warn("缓存 '{}' 的自动刷新需要缓存加载器", cacheName);
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
        
        log.debug("正在验证缓存 '{}' 的配置", cacheName);
        
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
        
        log.debug("缓存 '{}' 的配置验证完成", cacheName);
    }
}