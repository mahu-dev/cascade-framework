package io.github.cascade.lock;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RPermitExpirableSemaphore;
import org.redisson.api.RSemaphore;
import org.redisson.api.RedissonClient;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Redisson信号量工具类
 * 提供便利的信号量操作方法，简化Redisson信号量的使用
 * 
 * @author cascade
 */
@Slf4j
public class RedissonSemaphoreUtils {

    /**
     * 执行带信号量的操作
     * 
     * @param redissonClient RedissonClient实例
     * @param semaphoreName 信号量名称
     * @param permits 初始许可数
     * @param action 要执行的操作
     * @return 执行结果
     */
    public static <T> T withSemaphore(RedissonClient redissonClient, String semaphoreName, 
                                     int permits, Supplier<T> action) {
        return withSemaphore(redissonClient, semaphoreName, permits, 1, -1, TimeUnit.SECONDS, action);
    }

    /**
     * 执行带信号量的操作（指定获取许可数）
     * 
     * @param redissonClient RedissonClient实例
     * @param semaphoreName 信号量名称
     * @param totalPermits 总许可数
     * @param acquirePermits 要获取的许可数
     * @param action 要执行的操作
     * @return 执行结果
     */
    public static <T> T withSemaphore(RedissonClient redissonClient, String semaphoreName, 
                                     int totalPermits, int acquirePermits, Supplier<T> action) {
        return withSemaphore(redissonClient, semaphoreName, totalPermits, acquirePermits, -1, TimeUnit.SECONDS, action);
    }

    /**
     * 执行带信号量的操作（带超时）
     * 
     * @param redissonClient RedissonClient实例
     * @param semaphoreName 信号量名称
     * @param totalPermits 总许可数
     * @param acquirePermits 要获取的许可数
     * @param timeout 超时时间
     * @param unit 时间单位
     * @param action 要执行的操作
     * @return 执行结果
     */
    public static <T> T withSemaphore(RedissonClient redissonClient, String semaphoreName, 
                                     int totalPermits, int acquirePermits, long timeout, TimeUnit unit, 
                                     Supplier<T> action) {
        RSemaphore semaphore = redissonClient.getSemaphore(semaphoreName);
        
        // 尝试设置许可数（如果信号量不存在）
        try {
            semaphore.trySetPermits(totalPermits);
        } catch (Exception e) {
            log.debug("Semaphore {} may already exist: {}", semaphoreName, e.getMessage());
        }

        try {
            boolean acquired;
            if (timeout >= 0) {
                acquired = semaphore.tryAcquire(acquirePermits, timeout, unit);
            } else {
                semaphore.acquire(acquirePermits);
                acquired = true;
            }
            
            if (acquired) {
                try {
                    log.debug("Successfully acquired {} permits from semaphore: {}", acquirePermits, semaphoreName);
                    return action.get();
                } finally {
                    try {
                        semaphore.release(acquirePermits);
                        log.debug("Released {} permits to semaphore: {}", acquirePermits, semaphoreName);
                    } catch (Exception e) {
                        log.warn("Failed to release permits to semaphore: {}", semaphoreName, e);
                    }
                }
            } else {
                throw new RuntimeException("Failed to acquire semaphore permits within timeout: " + semaphoreName);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Semaphore acquisition interrupted: " + semaphoreName, e);
        } catch (Exception e) {
            log.error("Error during semaphore operation: {}", semaphoreName, e);
            throw new RuntimeException("Semaphore operation failed: " + semaphoreName, e);
        }
    }

    /**
     * 执行带信号量的操作（无返回值）
     */
    public static void withSemaphore(RedissonClient redissonClient, String semaphoreName, 
                                    int permits, Runnable action) {
        withSemaphore(redissonClient, semaphoreName, permits, () -> {
            action.run();
            return null;
        });
    }

    /**
     * 执行带信号量的操作（无返回值，指定获取许可数）
     */
    public static void withSemaphore(RedissonClient redissonClient, String semaphoreName, 
                                    int totalPermits, int acquirePermits, Runnable action) {
        withSemaphore(redissonClient, semaphoreName, totalPermits, acquirePermits, () -> {
            action.run();
            return null;
        });
    }

    /**
     * 尝试获取信号量许可，如果获取失败执行失败处理
     */
    public static <T> T trySemaphore(RedissonClient redissonClient, String semaphoreName, 
                                    int totalPermits, int acquirePermits,
                                    Supplier<T> action, Supplier<T> onAcquireFailed) {
        return trySemaphore(redissonClient, semaphoreName, totalPermits, acquirePermits, 
                           0, TimeUnit.SECONDS, action, onAcquireFailed);
    }

    /**
     * 尝试获取信号量许可（带超时），如果获取失败执行失败处理
     */
    public static <T> T trySemaphore(RedissonClient redissonClient, String semaphoreName, 
                                    int totalPermits, int acquirePermits, long timeout, TimeUnit unit,
                                    Supplier<T> action, Supplier<T> onAcquireFailed) {
        RSemaphore semaphore = redissonClient.getSemaphore(semaphoreName);
        
        // 尝试设置许可数
        try {
            semaphore.trySetPermits(totalPermits);
        } catch (Exception e) {
            log.debug("Semaphore {} may already exist", semaphoreName);
        }

        try {
            boolean acquired = semaphore.tryAcquire(acquirePermits, timeout, unit);
            
            if (acquired) {
                try {
                    log.debug("Successfully acquired {} permits from semaphore: {}", acquirePermits, semaphoreName);
                    return action.get();
                } finally {
                    try {
                        semaphore.release(acquirePermits);
                        log.debug("Released {} permits to semaphore: {}", acquirePermits, semaphoreName);
                    } catch (Exception e) {
                        log.warn("Failed to release permits to semaphore: {}", semaphoreName, e);
                    }
                }
            } else {
                log.debug("Failed to acquire semaphore permits: {}", semaphoreName);
                return onAcquireFailed.get();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Semaphore acquisition interrupted: {}", semaphoreName);
            return onAcquireFailed.get();
        } catch (Exception e) {
            log.error("Error during semaphore operation: {}", semaphoreName, e);
            throw new RuntimeException("Semaphore operation failed: " + semaphoreName, e);
        }
    }

    /**
     * 执行带可过期许可信号量的操作
     */
    public static <T> T withPermitExpirableSemaphore(RedissonClient redissonClient, String semaphoreName, 
                                                    int totalPermits, long permitTTL, TimeUnit unit, Supplier<T> action) {
        return withPermitExpirableSemaphore(redissonClient, semaphoreName, totalPermits, 
                                           -1, permitTTL, unit, action);
    }

    /**
     * 执行带可过期许可信号量的操作（带等待超时）
     */
    public static <T> T withPermitExpirableSemaphore(RedissonClient redissonClient, String semaphoreName, 
                                                    int totalPermits, long waitTime,
                                                    long permitTTL, TimeUnit unit, Supplier<T> action) {
        RPermitExpirableSemaphore semaphore = redissonClient.getPermitExpirableSemaphore(semaphoreName);
        
        // 尝试设置许可数
        try {
            semaphore.trySetPermits(totalPermits);
        } catch (Exception e) {
            log.debug("PermitExpirableSemaphore {} may already exist", semaphoreName);
        }

        String permitId = null;
        try {
            if (waitTime >= 0) {
                permitId = semaphore.tryAcquire(waitTime, permitTTL, unit);
            } else {
                permitId = semaphore.tryAcquire(permitTTL, unit);
            }
            
            if (permitId != null) {
                try {
                    log.debug("Successfully acquired permit {} from expirable semaphore: {}", permitId, semaphoreName);
                    return action.get();
                } finally {
                    try {
                        semaphore.release(permitId);
                        log.debug("Released permit {} from expirable semaphore: {}", permitId, semaphoreName);
                    } catch (Exception e) {
                        log.warn("Failed to release permit {} from expirable semaphore: {}", permitId, semaphoreName, e);
                    }
                }
            } else {
                throw new RuntimeException("Failed to acquire expirable semaphore permits: " + semaphoreName);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Expirable semaphore acquisition interrupted: " + semaphoreName, e);
        } catch (Exception e) {
            log.error("Error during expirable semaphore operation: {}", semaphoreName, e);
            throw new RuntimeException("Expirable semaphore operation failed: " + semaphoreName, e);
        }
    }

    /**
     * 获取信号量可用许可数
     */
    public static int getAvailablePermits(RedissonClient redissonClient, String semaphoreName) {
        try {
            RSemaphore semaphore = redissonClient.getSemaphore(semaphoreName);
            return semaphore.availablePermits();
        } catch (Exception e) {
            log.warn("Failed to get available permits for semaphore: {}", semaphoreName, e);
            return 0;
        }
    }

    /**
     * 设置信号量许可数
     */
    public static boolean trySetPermits(RedissonClient redissonClient, String semaphoreName, int permits) {
        try {
            RSemaphore semaphore = redissonClient.getSemaphore(semaphoreName);
            boolean result = semaphore.trySetPermits(permits);
            log.info("Set permits for semaphore {}: {} (success: {})", semaphoreName, permits, result);
            return result;
        } catch (Exception e) {
            log.error("Failed to set permits for semaphore: {}", semaphoreName, e);
            return false;
        }
    }

    /**
     * 增加信号量许可数
     */
    public static void addPermits(RedissonClient redissonClient, String semaphoreName, int permits) {
        try {
            RSemaphore semaphore = redissonClient.getSemaphore(semaphoreName);
            semaphore.addPermits(permits);
            log.info("Added {} permits to semaphore: {}", permits, semaphoreName);
        } catch (Exception e) {
            log.error("Failed to add permits to semaphore: {}", semaphoreName, e);
            throw new RuntimeException("Failed to add permits to semaphore: " + semaphoreName, e);
        }
    }

    /**
     * 减少信号量许可数（通过获取许可但不释放的方式）
     */
    public static void reducePermits(RedissonClient redissonClient, String semaphoreName, int permits) {
        try {
            RSemaphore semaphore = redissonClient.getSemaphore(semaphoreName);
            // Redisson没有直接的reducePermits方法，通过获取许可但不释放来实现减少
            boolean acquired = semaphore.tryAcquire(permits);
            if (acquired) {
                log.info("Reduced {} permits from semaphore: {} by acquiring without releasing", permits, semaphoreName);
            } else {
                log.warn("Failed to reduce {} permits from semaphore: {} - insufficient permits", permits, semaphoreName);
                throw new RuntimeException("Insufficient permits to reduce from semaphore: " + semaphoreName);
            }
        } catch (Exception e) {
            log.error("Failed to reduce permits from semaphore: {}", semaphoreName, e);
            throw new RuntimeException("Failed to reduce permits from semaphore: " + semaphoreName, e);
        }
    }

    /**
     * 清空信号量所有许可
     */
    public static int drainPermits(RedissonClient redissonClient, String semaphoreName) {
        try {
            RSemaphore semaphore = redissonClient.getSemaphore(semaphoreName);
            int drained = semaphore.drainPermits();
            log.info("Drained {} permits from semaphore: {}", drained, semaphoreName);
            return drained;
        } catch (Exception e) {
            log.error("Failed to drain permits from semaphore: {}", semaphoreName, e);
            return 0;
        }
    }
}