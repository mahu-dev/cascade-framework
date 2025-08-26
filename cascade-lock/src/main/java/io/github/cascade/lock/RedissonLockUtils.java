package io.github.cascade.lock;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RReadWriteLock;
import org.redisson.api.RedissonClient;
import org.redisson.RedissonMultiLock;
import org.redisson.RedissonRedLock;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Redisson分布式锁工具类
 * 提供便利的锁操作方法，简化Redisson锁的使用
 * 
 * @author cascade
 */
@Slf4j
public class RedissonLockUtils {

    /**
     * 执行带锁的操作（可重入锁）
     * 
     * @param redissonClient RedissonClient实例
     * @param lockName 锁名称
     * @param action 要执行的操作
     * @return 执行结果
     */
    public static <T> T withLock(RedissonClient redissonClient, String lockName, Supplier<T> action) {
        return withLock(redissonClient, lockName, -1, -1, TimeUnit.SECONDS, action);
    }

    /**
     * 执行带锁的操作（可重入锁，带超时）
     * 
     * @param redissonClient RedissonClient实例
     * @param lockName 锁名称
     * @param waitTime 等待时间
     * @param unit 时间单位
     * @param action 要执行的操作
     * @return 执行结果
     */
    public static <T> T withLock(RedissonClient redissonClient, String lockName, 
                                long waitTime, TimeUnit unit, Supplier<T> action) {
        return withLock(redissonClient, lockName, waitTime, -1, unit, action);
    }

    /**
     * 执行带锁的操作（可重入锁，带等待时间和租期）
     * 
     * @param redissonClient RedissonClient实例
     * @param lockName 锁名称
     * @param waitTime 等待时间
     * @param leaseTime 租期时间
     * @param unit 时间单位
     * @param action 要执行的操作
     * @return 执行结果
     */
    public static <T> T withLock(RedissonClient redissonClient, String lockName, 
                                long waitTime, long leaseTime, TimeUnit unit, Supplier<T> action) {
        RLock lock = redissonClient.getLock(lockName);
        return executeWithLock(lock, waitTime, leaseTime, unit, action);
    }

    /**
     * 执行带锁的操作（无返回值）
     */
    public static void withLock(RedissonClient redissonClient, String lockName, Runnable action) {
        withLock(redissonClient, lockName, () -> {
            action.run();
            return null;
        });
    }

    /**
     * 执行带锁的操作（无返回值，带超时）
     */
    public static void withLock(RedissonClient redissonClient, String lockName, 
                               long waitTime, TimeUnit unit, Runnable action) {
        withLock(redissonClient, lockName, waitTime, unit, () -> {
            action.run();
            return null;
        });
    }

    /**
     * 执行带锁的操作（无返回值，带等待时间和租期）
     */
    public static void withLock(RedissonClient redissonClient, String lockName, 
                               long waitTime, long leaseTime, TimeUnit unit, Runnable action) {
        withLock(redissonClient, lockName, waitTime, leaseTime, unit, () -> {
            action.run();
            return null;
        });
    }

    /**
     * 执行带公平锁的操作
     */
    public static <T> T withFairLock(RedissonClient redissonClient, String lockName, Supplier<T> action) {
        return withFairLock(redissonClient, lockName, -1, -1, TimeUnit.SECONDS, action);
    }

    /**
     * 执行带公平锁的操作（带超时）
     */
    public static <T> T withFairLock(RedissonClient redissonClient, String lockName, 
                                    long waitTime, TimeUnit unit, Supplier<T> action) {
        return withFairLock(redissonClient, lockName, waitTime, -1, unit, action);
    }

    /**
     * 执行带公平锁的操作（带等待时间和租期）
     */
    public static <T> T withFairLock(RedissonClient redissonClient, String lockName, 
                                    long waitTime, long leaseTime, TimeUnit unit, Supplier<T> action) {
        RLock fairLock = redissonClient.getFairLock(lockName);
        return executeWithLock(fairLock, waitTime, leaseTime, unit, action);
    }

    /**
     * 执行带公平锁的操作（无返回值）
     */
    public static void withFairLock(RedissonClient redissonClient, String lockName, Runnable action) {
        withFairLock(redissonClient, lockName, () -> {
            action.run();
            return null;
        });
    }

    /**
     * 执行带读锁的操作
     */
    public static <T> T withReadLock(RedissonClient redissonClient, String lockName, Supplier<T> action) {
        return withReadLock(redissonClient, lockName, -1, -1, TimeUnit.SECONDS, action);
    }

    /**
     * 执行带读锁的操作（带等待时间和租期）
     */
    public static <T> T withReadLock(RedissonClient redissonClient, String lockName, 
                                    long waitTime, long leaseTime, TimeUnit unit, Supplier<T> action) {
        RReadWriteLock readWriteLock = redissonClient.getReadWriteLock(lockName);
        RLock readLock = readWriteLock.readLock();
        return executeWithLock(readLock, waitTime, leaseTime, unit, action);
    }

    /**
     * 执行带读锁的操作（无返回值）
     */
    public static void withReadLock(RedissonClient redissonClient, String lockName, Runnable action) {
        withReadLock(redissonClient, lockName, () -> {
            action.run();
            return null;
        });
    }

    /**
     * 执行带写锁的操作
     */
    public static <T> T withWriteLock(RedissonClient redissonClient, String lockName, Supplier<T> action) {
        return withWriteLock(redissonClient, lockName, -1, -1, TimeUnit.SECONDS, action);
    }

    /**
     * 执行带写锁的操作（带等待时间和租期）
     */
    public static <T> T withWriteLock(RedissonClient redissonClient, String lockName, 
                                     long waitTime, long leaseTime, TimeUnit unit, Supplier<T> action) {
        RReadWriteLock readWriteLock = redissonClient.getReadWriteLock(lockName);
        RLock writeLock = readWriteLock.writeLock();
        return executeWithLock(writeLock, waitTime, leaseTime, unit, action);
    }

    /**
     * 执行带写锁的操作（无返回值）
     */
    public static void withWriteLock(RedissonClient redissonClient, String lockName, Runnable action) {
        withWriteLock(redissonClient, lockName, () -> {
            action.run();
            return null;
        });
    }

    /**
     * 执行带多锁的操作
     */
    public static <T> T withMultiLock(RedissonClient redissonClient, String[] lockNames, Supplier<T> action) {
        return withMultiLock(redissonClient, lockNames, -1, -1, TimeUnit.SECONDS, action);
    }

    /**
     * 执行带多锁的操作（带等待时间和租期）
     */
    public static <T> T withMultiLock(RedissonClient redissonClient, String[] lockNames, 
                                     long waitTime, long leaseTime, TimeUnit unit, Supplier<T> action) {
        RLock[] locks = new RLock[lockNames.length];
        for (int i = 0; i < lockNames.length; i++) {
            locks[i] = redissonClient.getLock(lockNames[i]);
        }
        
        RedissonMultiLock multiLock = new RedissonMultiLock(locks);
        return executeWithLock(multiLock, waitTime, leaseTime, unit, action);
    }

    /**
     * 执行带多锁的操作（无返回值）
     */
    public static void withMultiLock(RedissonClient redissonClient, String[] lockNames, Runnable action) {
        withMultiLock(redissonClient, lockNames, () -> {
            action.run();
            return null;
        });
    }

    /**
     * 执行带红锁的操作
     */
    public static <T> T withRedLock(RedissonClient[] redissonClients, String lockName, Supplier<T> action) {
        return withRedLock(redissonClients, lockName, -1, -1, TimeUnit.SECONDS, action);
    }

    /**
     * 执行带红锁的操作（带等待时间和租期）
     */
    public static <T> T withRedLock(RedissonClient[] redissonClients, String lockName, 
                                   long waitTime, long leaseTime, TimeUnit unit, Supplier<T> action) {
        RLock[] locks = new RLock[redissonClients.length];
        for (int i = 0; i < redissonClients.length; i++) {
            locks[i] = redissonClients[i].getLock(lockName);
        }
        
        RedissonRedLock redLock = new RedissonRedLock(locks);
        return executeWithLock(redLock, waitTime, leaseTime, unit, action);
    }

    /**
     * 执行带红锁的操作（无返回值）
     */
    public static void withRedLock(RedissonClient[] redissonClients, String lockName, Runnable action) {
        withRedLock(redissonClients, lockName, () -> {
            action.run();
            return null;
        });
    }

    /**
     * 尝试获取锁，如果获取失败执行失败处理
     */
    public static <T> T tryLock(RedissonClient redissonClient, String lockName, 
                               Supplier<T> action, Supplier<T> onLockFailed) {
        return tryLock(redissonClient, lockName, 0, -1, TimeUnit.SECONDS, action, onLockFailed);
    }

    /**
     * 尝试获取锁（带等待时间），如果获取失败执行失败处理
     */
    public static <T> T tryLock(RedissonClient redissonClient, String lockName, 
                               long waitTime, long leaseTime, TimeUnit unit,
                               Supplier<T> action, Supplier<T> onLockFailed) {
        RLock lock = redissonClient.getLock(lockName);
        
        try {
            boolean acquired;
            if (waitTime >= 0 && leaseTime >= 0) {
                acquired = lock.tryLock(waitTime, leaseTime, unit);
            } else if (waitTime >= 0) {
                acquired = lock.tryLock(waitTime, unit);
            } else {
                acquired = lock.tryLock();
            }
            
            if (acquired) {
                try {
                    log.debug("Successfully acquired lock: {}", lockName);
                    return action.get();
                } finally {
                    try {
                        lock.unlock();
                        log.debug("Released lock: {}", lockName);
                    } catch (Exception e) {
                        log.warn("Failed to release lock: {}", lockName, e);
                    }
                }
            } else {
                log.debug("Failed to acquire lock: {}", lockName);
                return onLockFailed.get();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Lock acquisition interrupted: {}", lockName);
            return onLockFailed.get();
        } catch (Exception e) {
            log.error("Error during lock operation: {}", lockName, e);
            throw new RuntimeException("Lock operation failed: " + lockName, e);
        }
    }

    /**
     * 强制释放锁
     */
    public static boolean forceUnlock(RedissonClient redissonClient, String lockName) {
        try {
            RLock lock = redissonClient.getLock(lockName);
            boolean result = lock.forceUnlock();
            log.info("Force unlocked {}: {}", lockName, result);
            return result;
        } catch (Exception e) {
            log.error("Failed to force unlock: {}", lockName, e);
            return false;
        }
    }

    /**
     * 检查锁是否被持有
     */
    public static boolean isLocked(RedissonClient redissonClient, String lockName) {
        try {
            RLock lock = redissonClient.getLock(lockName);
            return lock.isLocked();
        } catch (Exception e) {
            log.warn("Failed to check lock status: {}", lockName, e);
            return false;
        }
    }

    /**
     * 获取锁的剩余时间
     */
    public static long remainTimeToLive(RedissonClient redissonClient, String lockName) {
        try {
            RLock lock = redissonClient.getLock(lockName);
            return lock.remainTimeToLive();
        } catch (Exception e) {
            log.warn("Failed to get lock remain time: {}", lockName, e);
            return -2;
        }
    }

    /**
     * 内部方法：执行带锁的操作
     */
    private static <T> T executeWithLock(RLock lock, long waitTime, long leaseTime, TimeUnit unit, Supplier<T> action) {
        try {
            boolean acquired;
            if (waitTime >= 0 && leaseTime >= 0) {
                acquired = lock.tryLock(waitTime, leaseTime, unit);
            } else if (waitTime >= 0) {
                acquired = lock.tryLock(waitTime, unit);
            } else {
                lock.lock();
                acquired = true;
            }
            
            if (acquired) {
                try {
                    log.debug("Successfully acquired lock");
                    return action.get();
                } finally {
                    try {
                        lock.unlock();
                        log.debug("Released lock");
                    } catch (Exception e) {
                        log.warn("Failed to release lock", e);
                    }
                }
            } else {
                throw new RuntimeException("Failed to acquire lock within timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Lock acquisition interrupted", e);
        } catch (Exception e) {
            log.error("Error during lock operation", e);
            throw new RuntimeException("Lock operation failed", e);
        }
    }
}