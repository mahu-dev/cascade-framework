package io.github.cascade.cache.api;

import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.concurrent.Executor;

/**
 * 缓存构建器接口
 * 提供流式API来配置和构建缓存
 * 
 * @param <K> 键类型
 * @param <V> 值类型
 * @author cascade
 */
public interface CacheBuilder<K, V> {
    
    // ==================== 基础配置 ====================
    
    /**
     * 设置缓存加载器
     */
    CacheBuilder<K, V> loader(CacheLoader<K, V> loader);
    
    /**
     * 设置异步执行器
     */
    CacheBuilder<K, V> executor(Executor executor);
    
    /**
     * 设置最大缓存大小
     */
    CacheBuilder<K, V> maximumSize(long maximumSize);
    
    /**
     * 设置写入后过期时间
     */
    CacheBuilder<K, V> expireAfterWrite(Duration duration);
    
    /**
     * 设置访问后过期时间
     */
    CacheBuilder<K, V> expireAfterAccess(Duration duration);
    
    /**
     * 设置自动刷新时间间隔
     * 与expireAfterWrite不同，refreshAfterWrite不会删除过期数据，而是在后台异步刷新
     */
    CacheBuilder<K, V> refreshAfterWrite(Duration duration);
    
    // ==================== L1缓存配置 ====================
    
    /**
     * 启用L1本地缓存
     */
    CacheBuilder<K, V> enableL1Cache(boolean enabled);
    
    /**
     * 设置L1缓存最大大小
     */
    CacheBuilder<K, V> l1MaximumSize(long size);
    
    /**
     * 设置L1缓存写入后过期时间
     */
    CacheBuilder<K, V> l1ExpireAfterWrite(Duration duration);
    
    /**
     * 设置L1缓存访问后过期时间
     */
    CacheBuilder<K, V> l1ExpireAfterAccess(Duration duration);
    
    /**
     * 设置L1缓存是否记录统计
     */
    CacheBuilder<K, V> l1RecordStats(boolean recordStats);
    
    // ==================== L2缓存配置 ====================
    
    /**
     * 启用L2远程缓存
     */
    CacheBuilder<K, V> enableL2Cache(boolean enabled, RedissonClient redissonClient);
    
    /**
     * 设置L2缓存键前缀
     */
    CacheBuilder<K, V> l2KeyPrefix(String keyPrefix);
    
    /**
     * 设置L2缓存默认TTL
     */
    CacheBuilder<K, V> l2DefaultTtl(Duration ttl);
    
    // ==================== 同步配置 ====================
    
    /**
     * 启用缓存同步
     */
    CacheBuilder<K, V> enableSync(boolean enabled);
    
    // ==================== 防护配置 ====================
    
    /**
     * 启用缓存防护
     */
    CacheBuilder<K, V> enableProtection(boolean enabled);
    
    /**
     * 配置布隆过滤器
     */
    CacheBuilder<K, V> bloomFilter(long expectedElements, double falsePositiveRate);
    
    /**
     * 配置随机TTL
     */
    CacheBuilder<K, V> randomTtl(boolean enabled, double jitterRatio);
    
    /**
     * 配置分布式锁
     */
    CacheBuilder<K, V> distributedLock(boolean enabled, Duration lockTimeout, String lockKeyPrefix);
    
    // ==================== 预热配置 ====================
    
    /**
     * 启用预热
     */
    CacheBuilder<K, V> enableWarmup(boolean enabled);
    
    // ==================== 构建方法 ====================
    
    /**
     * 构建缓存实例并自动注册到管理器
     * 
     * @return 构建的缓存实例
     */
    Cache<K, V> build();
    
    /**
     * 构建缓存实例但不注册到管理器
     * 
     * @return 构建的缓存实例
     */
    Cache<K, V> buildWithoutRegister();
}