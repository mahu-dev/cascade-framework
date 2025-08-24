package io.github.cascade.cache.builder;

import io.github.cascade.cache.config.CascadeCacheConfiguration;
import org.redisson.api.RedissonClient;

/**
 * 缓存层级配置步骤
 * 定义缓存构建过程中的第一步：选择缓存层级架构
 * 
 * @param <K> 键类型
 * @param <V> 值类型
 * @author cascade
 */
public interface TierConfigurationStep<K, V> {
    
    /**
     * 配置单级缓存（仅L1）
     * 使用指定的L1配置
     */
    EnhancementConfigurationStep<K, V> withL1Only(CascadeCacheConfiguration.L1Config l1Config);
    
    /**
     * 配置单级缓存（仅L1）
     * 使用默认L1配置
     */
    EnhancementConfigurationStep<K, V> withL1Only();
    
    /**
     * 配置多级缓存（L1 + L2）
     * 使用完整的L1和L2配置
     */
    EnhancementConfigurationStep<K, V> withL1AndL2(CascadeCacheConfiguration.L1Config l1Config, 
                                                    CascadeCacheConfiguration.L2Config l2Config,
                                                    RedissonClient redissonClient);
    
    /**
     * 配置多级缓存（L1 + L2）
     * 使用默认L1配置和指定L2配置
     */
    EnhancementConfigurationStep<K, V> withL1AndL2(CascadeCacheConfiguration.L2Config l2Config, 
                                                    RedissonClient redissonClient);
    
    /**
     * 配置多级缓存（L1 + L2）
     * 自动发现RedissonClient，适用于Spring环境
     */
    EnhancementConfigurationStep<K, V> withL1AndL2(CascadeCacheConfiguration.L1Config l1Config, 
                                                    CascadeCacheConfiguration.L2Config l2Config);
    
    /**
     * 快速配置多级缓存
     * 使用默认配置和自动发现的RedissonClient
     */
    EnhancementConfigurationStep<K, V> withL1AndL2();
}