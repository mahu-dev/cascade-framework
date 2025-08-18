package io.github.cascade.cache.protection;

import java.util.Set;

/**
 * 缓存防护接口
 *
 * @param <K> 键类型
 * @param <V> 值类型
 * @author cascade
 */
public interface CacheProtection<K, V> {

    /**
     * 检查键是否可能存在（布隆过滤器）
     *
     * @param key 键
     * @return 是否可能存在
     */
    boolean mightContain(K key);

    /**
     * 添加键到布隆过滤器
     *
     * @param key 键
     */
    void put(K key);

    /**
     * 批量添加键到布隆过滤器
     *
     * @param keys 键集合
     */
    void putAll(Set<K> keys);

    /**
     * 重置布隆过滤器
     */
    void reset();

    /**
     * 获取布隆过滤器统计信息
     *
     * @return 统计信息
     */
    BloomFilterStats getBloomFilterStats();

    /**
     * 尝试获取分布式锁
     *
     * @param key 锁键
     * @return 是否获取成功
     */
    boolean tryLock(K key);

    /**
     * 尝试获取分布式锁（带超时）
     *
     * @param key 锁键
     * @param timeoutMs 超时时间（毫秒）
     * @return 是否获取成功
     */
    boolean tryLock(K key, long timeoutMs);

    /**
     * 释放分布式锁
     *
     * @param key 锁键
     */
    void unlock(K key);

    /**
     * 检查是否持有锁
     *
     * @param key 锁键
     * @return 是否持有锁
     */
    boolean isLocked(K key);

    /**
     * 获取分布式锁统计信息
     *
     * @return 统计信息
     */
    DistributedLockStats getLockStats();

    /**
     * 检查熔断器状态
     *
     * @param operation 操作类型
     * @return 是否允许执行
     */
    boolean isCircuitBreakerOpen(String operation);

    /**
     * 记录操作成功
     *
     * @param operation 操作类型
     */
    void recordSuccess(String operation);

    /**
     * 记录操作失败
     *
     * @param operation 操作类型
     */
    void recordFailure(String operation);

    /**
     * 获取熔断器统计信息
     *
     * @return 统计信息
     */
    CircuitBreakerStats getCircuitBreakerStats();

    /**
     * 检查限流器是否允许请求
     *
     * @param operation 操作类型
     * @return 是否允许
     */
    boolean isRateLimitAllowed(String operation);

    /**
     * 获取限流器统计信息
     *
     * @return 统计信息
     */
    RateLimiterStats getRateLimiterStats();

    /**
     * 生成随机TTL（防雪崩）
     *
     * @param baseTtlMs 基础TTL（毫秒）
     * @return 随机TTL（毫秒）
     */
    long generateRandomTtl(long baseTtlMs);

    /**
     * 获取防护统计信息
     *
     * @return 统计信息
     */
    ProtectionStats getProtectionStats();

    /**
     * 布隆过滤器统计信息
     */
    interface BloomFilterStats {
        long getExpectedInsertions();
        double getFalsePositiveProbability();
        long getApproximateElementCount();
        boolean isInitialized();
    }

    /**
     * 分布式锁统计信息
     */
    interface DistributedLockStats {
        long getLockAcquisitionCount();
        long getLockAcquisitionFailureCount();
        long getLockReleaseCount();
        double getAverageLockHoldTime();
        long getCurrentLockedKeys();
    }

    /**
     * 熔断器统计信息
     */
    interface CircuitBreakerStats {
        String getState(); // CLOSED, OPEN, HALF_OPEN
        long getSuccessCount();
        long getFailureCount();
        double getFailureRate();
        long getLastStateChangeTime();
    }

    /**
     * 限流器统计信息
     */
    interface RateLimiterStats {
        double getPermitsPerSecond();
        long getAllowedRequestCount();
        long getDeniedRequestCount();
        double getCurrentQps();
    }

    /**
     * 防护统计信息
     */
    interface ProtectionStats {
        BloomFilterStats getBloomFilterStats();
        DistributedLockStats getLockStats();
        CircuitBreakerStats getCircuitBreakerStats();
        RateLimiterStats getRateLimiterStats();
        long getRandomTtlGenerationCount();
    }
}