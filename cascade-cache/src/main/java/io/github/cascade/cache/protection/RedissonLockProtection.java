package io.github.cascade.cache.protection;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 基于Redisson的分布式锁防护
 * 直接使用Redisson的RLock，避免过度封装
 * 
 * @author Cascade Framework
 */
public class RedissonLockProtection {
    
    private static final Logger log = LoggerFactory.getLogger(RedissonLockProtection.class);
    
    private final RedissonClient redissonClient;
    private final String lockKeyPrefix;
    private final Duration lockTimeout;
    private final Duration waitTimeout;
    private final int maxRetries;
    private final Duration retryDelay;
    
    // 统计信息
    private final AtomicLong lockAcquiredCount = new AtomicLong(0);
    private final AtomicLong lockFailedCount = new AtomicLong(0);
    private final AtomicLong lockTimeoutCount = new AtomicLong(0);
    private final AtomicLong lockRetriesCount = new AtomicLong(0);
    
    public RedissonLockProtection(RedissonClient redissonClient,
                                 String lockKeyPrefix,
                                 Duration lockTimeout,
                                 Duration waitTimeout,
                                 int maxRetries,
                                 Duration retryDelay) {
        this.redissonClient = redissonClient;
        this.lockKeyPrefix = lockKeyPrefix != null ? lockKeyPrefix : "cascade:lock:";
        this.lockTimeout = lockTimeout != null ? lockTimeout : Duration.ofSeconds(30);
        this.waitTimeout = waitTimeout != null ? waitTimeout : Duration.ofSeconds(10);
        this.maxRetries = Math.max(0, maxRetries);
        this.retryDelay = retryDelay != null ? retryDelay : Duration.ofMillis(100);
    }
    
    /**
     * 使用分布式锁执行操作
     * 
     * @param lockKey 锁键
     * @param supplier 要执行的操作
     * @param <T> 返回值类型
     * @return 操作结果
     * @throws LockException 锁异常
     */
    public <T> T executeWithLock(String lockKey, Supplier<T> supplier) throws LockException {
        String fullLockKey = lockKeyPrefix + lockKey;
        RLock lock = redissonClient.getLock(fullLockKey);
        
        int retries = 0;
        while (retries <= maxRetries) {
            try {
                // 尝试获取锁
                boolean acquired = lock.tryLock(waitTimeout.toMillis(), lockTimeout.toMillis(), TimeUnit.MILLISECONDS);
                
                if (acquired) {
                    lockAcquiredCount.incrementAndGet();
                    try {
                        log.debug("Lock acquired for key: {}", lockKey);
                        return supplier.get();
                    } finally {
                        // 确保只有当前线程持有锁时才释放
                        if (lock.isHeldByCurrentThread()) {
                            lock.unlock();
                            log.debug("Lock released for key: {}", lockKey);
                        }
                    }
                } else {
                    lockTimeoutCount.incrementAndGet();
                    if (retries < maxRetries) {
                        retries++;
                        lockRetriesCount.incrementAndGet();
                        log.warn("Failed to acquire lock for key: {}, retrying... ({}/{})", 
                            lockKey, retries, maxRetries);
                        
                        try {
                            Thread.sleep(retryDelay.toMillis());
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new LockException("Interrupted while waiting for retry", e);
                        }
                    } else {
                        lockFailedCount.incrementAndGet();
                        throw new LockException("Failed to acquire lock after " + maxRetries + " retries: " + lockKey);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LockException("Interrupted while acquiring lock: " + lockKey, e);
            } catch (Exception e) {
                lockFailedCount.incrementAndGet();
                throw new LockException("Error acquiring lock: " + lockKey, e);
            }
        }
        
        throw new LockException("Unexpected state in executeWithLock");
    }
    
    /**
     * 尝试获取锁并执行操作，如果获取失败则直接返回null
     * 
     * @param lockKey 锁键
     * @param supplier 要执行的操作
     * @param <T> 返回值类型
     * @return 操作结果，如果获取锁失败则返回null
     */
    public <T> T tryExecuteWithLock(String lockKey, Supplier<T> supplier) {
        String fullLockKey = lockKeyPrefix + lockKey;
        RLock lock = redissonClient.getLock(fullLockKey);
        
        try {
            // 立即尝试获取锁，不等待
            boolean acquired = lock.tryLock(0, lockTimeout.toMillis(), TimeUnit.MILLISECONDS);
            
            if (acquired) {
                lockAcquiredCount.incrementAndGet();
                try {
                    log.debug("Lock acquired immediately for key: {}", lockKey);
                    return supplier.get();
                } finally {
                    if (lock.isHeldByCurrentThread()) {
                        lock.unlock();
                        log.debug("Lock released for key: {}", lockKey);
                    }
                }
            } else {
                log.debug("Could not acquire lock immediately for key: {}", lockKey);
                return null;
            }
        } catch (Exception e) {
            log.warn("Error in tryExecuteWithLock for key: {}", lockKey, e);
            return null;
        }
    }
    
    /**
     * 检查锁是否被持有
     * 
     * @param lockKey 锁键
     * @return 是否被持有
     */
    public boolean isLocked(String lockKey) {
        String fullLockKey = lockKeyPrefix + lockKey;
        RLock lock = redissonClient.getLock(fullLockKey);
        return lock.isLocked();
    }
    
    /**
     * 强制释放锁（谨慎使用）
     * 
     * @param lockKey 锁键
     * @return 是否成功释放
     */
    public boolean forceUnlock(String lockKey) {
        String fullLockKey = lockKeyPrefix + lockKey;
        RLock lock = redissonClient.getLock(fullLockKey);
        try {
            return lock.forceUnlock();
        } catch (Exception e) {
            log.warn("Error force unlocking key: {}", lockKey, e);
            return false;
        }
    }
    
    /**
     * 获取统计信息
     * 
     * @return 锁统计信息
     */
    public LockStats getStats() {
        return new LockStats(
            lockAcquiredCount.get(),
            lockFailedCount.get(),
            lockTimeoutCount.get(),
            lockRetriesCount.get(),
            lockTimeout,
            waitTimeout
        );
    }
    
    /**
     * 锁统计信息
     */
    public static class LockStats {
        private final long acquiredCount;
        private final long failedCount;
        private final long timeoutCount;
        private final long retriesCount;
        private final Duration lockTimeout;
        private final Duration waitTimeout;
        
        public LockStats(long acquiredCount, long failedCount, long timeoutCount, 
                        long retriesCount, Duration lockTimeout, Duration waitTimeout) {
            this.acquiredCount = acquiredCount;
            this.failedCount = failedCount;
            this.timeoutCount = timeoutCount;
            this.retriesCount = retriesCount;
            this.lockTimeout = lockTimeout;
            this.waitTimeout = waitTimeout;
        }
        
        public long getAcquiredCount() { return acquiredCount; }
        public long getFailedCount() { return failedCount; }
        public long getTimeoutCount() { return timeoutCount; }
        public long getRetriesCount() { return retriesCount; }
        public Duration getLockTimeout() { return lockTimeout; }
        public Duration getWaitTimeout() { return waitTimeout; }
        
        public double getSuccessRate() {
            long total = acquiredCount + failedCount;
            return total > 0 ? (double) acquiredCount / total : 0.0;
        }
        
        @Override
        public String toString() {
            return String.format(
                "LockStats{acquired=%d, failed=%d, timeout=%d, retries=%d, successRate=%.2f%%, lockTimeout=%s, waitTimeout=%s}",
                acquiredCount, failedCount, timeoutCount, retriesCount, 
                getSuccessRate() * 100, lockTimeout, waitTimeout
            );
        }
    }
    
    /**
     * 锁异常
     */
    public static class LockException extends Exception {
        public LockException(String message) {
            super(message);
        }
        
        public LockException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}