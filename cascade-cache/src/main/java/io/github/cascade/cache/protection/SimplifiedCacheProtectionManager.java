package io.github.cascade.cache.protection;

import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 简化的缓存防护管理器
 * 直接使用Redisson锁，避免过度封装
 *
 * @author Cascade Framework
 */
@Getter
public class SimplifiedCacheProtectionManager {

    private static final Logger log = LoggerFactory.getLogger(SimplifiedCacheProtectionManager.class);

    private final CascadeBloomFilter bloomFilter;
    private final RandomTtlProtection randomTtl;
    private final RedissonLockProtection redissonLock;
    private final ProtectionConfig config;

    // 统计计数器
    private final AtomicLong penetrationCounter = new AtomicLong(0);
    private final AtomicLong avalancheCounter = new AtomicLong(0);
    private final AtomicLong hotspotCounter = new AtomicLong(0);
    private final AtomicLong protectedCounter = new AtomicLong(0);

    public SimplifiedCacheProtectionManager(CascadeBloomFilter bloomFilter,
                                            RandomTtlProtection randomTtl,
                                            RedissonLockProtection redissonLock,
                                            ProtectionConfig config) {
        this.bloomFilter = bloomFilter;
        this.randomTtl = randomTtl;
        this.redissonLock = redissonLock;
        this.config = config != null ? config : new ProtectionConfig();
    }

    /**
     * 执行带防护的缓存操作
     *
     * @param key         缓存键
     * @param cacheLoader 缓存加载器
     * @param dataLoader  数据加载器
     * @param <T>         数据类型
     * @return 数据
     * @throws CacheProtectionException 防护异常
     */
    public <T> T executeWithProtection(String key,
                                       Supplier<T> cacheLoader,
                                       Supplier<T> dataLoader) throws CacheProtectionException {
        protectedCounter.incrementAndGet();

        try {
            // 1. 防穿透：检查布隆过滤器
            if (config.isEnablePenetrationProtection() && !checkBloomFilter(key)) {
                penetrationCounter.incrementAndGet();
                if (config.isStrictPenetrationProtection()) {
                    throw new CacheProtectionException("Key not found in bloom filter: " + key);
                }
                return null; // 返回null表示数据不存在
            }

            // 2. 尝试从缓存加载
            T cachedValue = cacheLoader.get();
            if (cachedValue != null) {
                return cachedValue;
            }

            // 3. 防击穿：使用Redisson分布式锁
            if (config.isEnableHotspotProtection() && redissonLock != null) {
                return executeWithRedissonLock(key, dataLoader);
            } else {
                // 直接加载数据
                return dataLoader.get();
            }
        } catch (Exception e) {
            if (e instanceof CacheProtectionException) {
                throw e;
            }
            throw new CacheProtectionException("Cache protection failed for key: " + key, e);
        }
    }

    /**
     * 异步执行带防护的缓存操作
     */
    public <T> CompletableFuture<T> executeWithProtectionAsync(String key,
                                                               Supplier<T> cacheLoader,
                                                               Supplier<T> dataLoader,
                                                               Executor executor) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return executeWithProtection(key, cacheLoader, dataLoader);
            } catch (CacheProtectionException e) {
                throw new RuntimeException(e);
            }
        }, executor != null ? executor : ForkJoinPool.commonPool());
    }

    /**
     * 使用Redisson分布式锁执行数据加载
     */
    private <T> T executeWithRedissonLock(String key, Supplier<T> dataLoader) throws CacheProtectionException {
        try {
            hotspotCounter.incrementAndGet();
            return redissonLock.executeWithLock(key, dataLoader);
        } catch (RedissonLockProtection.LockException e) {
            throw new CacheProtectionException("Failed to acquire lock for key: " + key, e);
        }
    }

    /**
     * 添加键到布隆过滤器
     */
    public void addToBloomFilter(String key) {
        if (bloomFilter != null && config.isEnablePenetrationProtection()) {
            bloomFilter.add(key);
        }
    }

    /**
     * 批量添加键到布隆过滤器
     */
    public void addAllToBloomFilter(Iterable<String> keys) {
        if (bloomFilter != null && config.isEnablePenetrationProtection()) {
            for (String key : keys) {
                bloomFilter.add(key);
            }
        }
    }

    /**
     * 计算防雪崩TTL
     */
    public Duration calculateAvalancheProtectionTtl(Duration baseTtl) {
        if (randomTtl != null && config.isEnableAvalancheProtection()) {
            avalancheCounter.incrementAndGet();
            return randomTtl.calculateTtl(baseTtl);
        }
        return baseTtl;
    }

    /**
     * 检查布隆过滤器
     */
    private boolean checkBloomFilter(String key) {
        return bloomFilter == null || bloomFilter.mightContain(key);
    }

    /**
     * 公共方法：检查键是否可能存在于布隆过滤器中
     */
    public boolean mightContain(String key) {
        return checkBloomFilter(key);
    }


    /**
     * 获取防护统计信息
     */
    public ProtectionStats getStats() {
        CascadeBloomFilter.BloomFilterStats bloomStats =
                bloomFilter != null ? bloomFilter.getStats() : null;
        RedissonLockProtection.LockStats lockStats =
                redissonLock != null ? redissonLock.getStats() : null;

        return new ProtectionStats(
                protectedCounter.get(),
                penetrationCounter.get(),
                avalancheCounter.get(),
                hotspotCounter.get(),
                bloomStats,
                lockStats
        );
    }

    /**
     * 关闭并清理资源
     */
    public void shutdown() {
        // RedissonLockProtection不需要特别的关闭操作
        // Redisson客户端的生命周期由外部管理
        log.info("SimplifiedCacheProtectionManager shut down");
    }

    /**
     * 防护配置
     */
    public static class ProtectionConfig {
        private boolean enablePenetrationProtection = true;
        private boolean enableAvalancheProtection = true;
        private boolean enableHotspotProtection = true;
        private boolean strictPenetrationProtection = false;

        public boolean isEnablePenetrationProtection() {
            return enablePenetrationProtection;
        }

        public boolean isEnableAvalancheProtection() {
            return enableAvalancheProtection;
        }

        public boolean isEnableHotspotProtection() {
            return enableHotspotProtection;
        }

        public boolean isStrictPenetrationProtection() {
            return strictPenetrationProtection;
        }

        public ProtectionConfig setEnablePenetrationProtection(boolean enable) {
            this.enablePenetrationProtection = enable;
            return this;
        }

        public ProtectionConfig setEnableAvalancheProtection(boolean enable) {
            this.enableAvalancheProtection = enable;
            return this;
        }

        public ProtectionConfig setEnableHotspotProtection(boolean enable) {
            this.enableHotspotProtection = enable;
            return this;
        }

        public ProtectionConfig setStrictPenetrationProtection(boolean strict) {
            this.strictPenetrationProtection = strict;
            return this;
        }
    }

    /**
     * 防护统计信息
     */
    public static class ProtectionStats {
        private final long totalProtectedOperations;
        private final long penetrationProtections;
        private final long avalancheProtections;
        private final long hotspotProtections;
        private final CascadeBloomFilter.BloomFilterStats bloomFilterStats;
        private final RedissonLockProtection.LockStats lockStats;

        public ProtectionStats(long totalProtectedOperations,
                               long penetrationProtections,
                               long avalancheProtections,
                               long hotspotProtections,
                               CascadeBloomFilter.BloomFilterStats bloomFilterStats,
                               RedissonLockProtection.LockStats lockStats) {
            this.totalProtectedOperations = totalProtectedOperations;
            this.penetrationProtections = penetrationProtections;
            this.avalancheProtections = avalancheProtections;
            this.hotspotProtections = hotspotProtections;
            this.bloomFilterStats = bloomFilterStats;
            this.lockStats = lockStats;
        }

        public long getTotalProtectedOperations() {
            return totalProtectedOperations;
        }

        public long getPenetrationProtections() {
            return penetrationProtections;
        }

        public long getAvalancheProtections() {
            return avalancheProtections;
        }

        public long getHotspotProtections() {
            return hotspotProtections;
        }

        public CascadeBloomFilter.BloomFilterStats getBloomFilterStats() {
            return bloomFilterStats;
        }

        public RedissonLockProtection.LockStats getLockStats() {
            return lockStats;
        }

        @Override
        public String toString() {
            return String.format(
                    "ProtectionStats{total=%d, penetration=%d, avalanche=%d, hotspot=%d, bloomFilter=%s, redissonLock=%s}",
                    totalProtectedOperations, penetrationProtections, avalancheProtections, hotspotProtections,
                    bloomFilterStats != null ? bloomFilterStats.toString() : "disabled",
                    lockStats != null ? lockStats.toString() : "disabled"
            );
        }
    }

    /**
     * 缓存防护异常
     */
    public static class CacheProtectionException extends Exception {
        public CacheProtectionException(String message) {
            super(message);
        }

        public CacheProtectionException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * 构建器
     */
    public static class Builder {
        private CascadeBloomFilter bloomFilter;
        private RandomTtlProtection randomTtl;
        private RedissonLockProtection redissonLock;
        private ProtectionConfig config = new ProtectionConfig();

        public Builder bloomFilter(CascadeBloomFilter bloomFilter) {
            this.bloomFilter = bloomFilter;
            return this;
        }

        public Builder randomTtl(RandomTtlProtection randomTtl) {
            this.randomTtl = randomTtl;
            return this;
        }

        public Builder redissonLock(RedissonLockProtection redissonLock) {
            this.redissonLock = redissonLock;
            return this;
        }

        public Builder config(ProtectionConfig config) {
            this.config = config;
            return this;
        }

        public Builder enablePenetrationProtection(boolean enable) {
            this.config.setEnablePenetrationProtection(enable);
            return this;
        }

        public Builder enableAvalancheProtection(boolean enable) {
            this.config.setEnableAvalancheProtection(enable);
            return this;
        }

        public Builder enableHotspotProtection(boolean enable) {
            this.config.setEnableHotspotProtection(enable);
            return this;
        }

        public SimplifiedCacheProtectionManager build() {
            return new SimplifiedCacheProtectionManager(bloomFilter, randomTtl, redissonLock, config);
        }
    }

    public static Builder builder() {
        return new Builder();
    }
}