package io.github.cascade.lock;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RCountDownLatch;
import org.redisson.api.RedissonClient;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Redisson倒计时锁存器工具类
 * 提供便利的倒计时锁存器操作方法，简化Redisson CountDownLatch的使用
 * 
 * @author cascade
 */
@Slf4j
public class RedissonCountDownLatchUtils {

    /**
     * 等待倒计时锁存器完成
     * 
     * @param redissonClient RedissonClient实例
     * @param latchName 锁存器名称
     * @param initialCount 初始计数
     */
    public static void await(RedissonClient redissonClient, String latchName, long initialCount) {
        RCountDownLatch latch = redissonClient.getCountDownLatch(latchName);
        
        // 尝试设置初始计数（如果锁存器不存在）
        try {
            latch.trySetCount(initialCount);
        } catch (Exception e) {
            log.debug("CountDownLatch {} may already exist", latchName);
        }

        try {
            latch.await();
            log.debug("CountDownLatch {} completed", latchName);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("CountDownLatch await interrupted: " + latchName, e);
        } catch (Exception e) {
            log.error("Error during CountDownLatch await: {}", latchName, e);
            throw new RuntimeException("CountDownLatch await failed: " + latchName, e);
        }
    }

    /**
     * 等待倒计时锁存器完成（带超时）
     * 
     * @param redissonClient RedissonClient实例
     * @param latchName 锁存器名称
     * @param initialCount 初始计数
     * @param timeout 超时时间
     * @param unit 时间单位
     * @return 是否在超时前完成
     */
    public static boolean await(RedissonClient redissonClient, String latchName, long initialCount,
                               long timeout, TimeUnit unit) {
        RCountDownLatch latch = redissonClient.getCountDownLatch(latchName);
        
        // 尝试设置初始计数
        try {
            latch.trySetCount(initialCount);
        } catch (Exception e) {
            log.debug("CountDownLatch {} may already exist", latchName);
        }

        try {
            boolean completed = latch.await(timeout, unit);
            log.debug("CountDownLatch {} await result: {} (timeout: {}{})", 
                     latchName, completed, timeout, unit);
            return completed;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("CountDownLatch await interrupted: " + latchName, e);
        } catch (Exception e) {
            log.error("Error during CountDownLatch await with timeout: {}", latchName, e);
            return false;
        }
    }

    /**
     * 倒计时减一
     * 
     * @param redissonClient RedissonClient实例
     * @param latchName 锁存器名称
     */
    public static void countDown(RedissonClient redissonClient, String latchName) {
        try {
            RCountDownLatch latch = redissonClient.getCountDownLatch(latchName);
            latch.countDown();
            
            long currentCount = latch.getCount();
            log.debug("CountDownLatch {} counted down, current count: {}", latchName, currentCount);
            
            if (currentCount == 0) {
                log.info("CountDownLatch {} reached zero, releasing all waiting threads", latchName);
            }
        } catch (Exception e) {
            log.error("Failed to count down latch: {}", latchName, e);
            throw new RuntimeException("CountDownLatch countDown failed: " + latchName, e);
        }
    }

    /**
     * 倒计时减指定数量
     * 
     * @param redissonClient RedissonClient实例
     * @param latchName 锁存器名称
     * @param count 要减少的数量
     */
    public static void countDown(RedissonClient redissonClient, String latchName, long count) {
        if (count <= 0) {
            throw new IllegalArgumentException("Count must be positive");
        }
        
        try {
            RCountDownLatch latch = redissonClient.getCountDownLatch(latchName);
            
            // Redisson的countDown()只支持递减1，所以需要循环调用
            for (long i = 0; i < count; i++) {
                latch.countDown();
            }
            
            long currentCount = latch.getCount();
            log.debug("CountDownLatch {} counted down by {}, current count: {}", latchName, count, currentCount);
            
            if (currentCount == 0) {
                log.info("CountDownLatch {} reached zero, releasing all waiting threads", latchName);
            }
        } catch (Exception e) {
            log.error("Failed to count down latch {} by {}", latchName, count, e);
            throw new RuntimeException("CountDownLatch countDown failed: " + latchName, e);
        }
    }

    /**
     * 等待倒计时锁存器完成并执行操作
     * 
     * @param redissonClient RedissonClient实例
     * @param latchName 锁存器名称
     * @param initialCount 初始计数
     * @param action 倒计时完成后要执行的操作
     * @return 执行结果
     */
    public static <T> T awaitAndExecute(RedissonClient redissonClient, String latchName, 
                                       long initialCount, Supplier<T> action) {
        await(redissonClient, latchName, initialCount);
        return action.get();
    }

    /**
     * 等待倒计时锁存器完成并执行操作（带超时）
     * 
     * @param redissonClient RedissonClient实例
     * @param latchName 锁存器名称
     * @param initialCount 初始计数
     * @param timeout 超时时间
     * @param unit 时间单位
     * @param action 倒计时完成后要执行的操作
     * @param onTimeout 超时时要执行的操作
     * @return 执行结果
     */
    public static <T> T awaitAndExecute(RedissonClient redissonClient, String latchName, 
                                       long initialCount, long timeout, TimeUnit unit,
                                       Supplier<T> action, Supplier<T> onTimeout) {
        boolean completed = await(redissonClient, latchName, initialCount, timeout, unit);
        if (completed) {
            return action.get();
        } else {
            return onTimeout.get();
        }
    }

    /**
     * 等待倒计时锁存器完成并执行操作（无返回值）
     */
    public static void awaitAndExecute(RedissonClient redissonClient, String latchName, 
                                      long initialCount, Runnable action) {
        awaitAndExecute(redissonClient, latchName, initialCount, () -> {
            action.run();
            return null;
        });
    }

    /**
     * 等待倒计时锁存器完成并执行操作（无返回值，带超时）
     */
    public static void awaitAndExecute(RedissonClient redissonClient, String latchName, 
                                      long initialCount, long timeout, TimeUnit unit,
                                      Runnable action, Runnable onTimeout) {
        awaitAndExecute(redissonClient, latchName, initialCount, timeout, unit, () -> {
            action.run();
            return null;
        }, () -> {
            onTimeout.run();
            return null;
        });
    }

    /**
     * 获取当前计数
     * 
     * @param redissonClient RedissonClient实例
     * @param latchName 锁存器名称
     * @return 当前计数
     */
    public static long getCount(RedissonClient redissonClient, String latchName) {
        try {
            RCountDownLatch latch = redissonClient.getCountDownLatch(latchName);
            return latch.getCount();
        } catch (Exception e) {
            log.warn("Failed to get count for CountDownLatch: {}", latchName, e);
            return -1;
        }
    }

    /**
     * 尝试设置计数
     * 
     * @param redissonClient RedissonClient实例
     * @param latchName 锁存器名称
     * @param count 要设置的计数
     * @return 是否设置成功
     */
    public static boolean trySetCount(RedissonClient redissonClient, String latchName, long count) {
        if (count < 0) {
            throw new IllegalArgumentException("Count cannot be negative");
        }
        
        try {
            RCountDownLatch latch = redissonClient.getCountDownLatch(latchName);
            boolean result = latch.trySetCount(count);
            log.debug("Set CountDownLatch {} count to {}: {}", latchName, count, result);
            return result;
        } catch (Exception e) {
            log.error("Failed to set count for CountDownLatch: {}", latchName, e);
            return false;
        }
    }

    /**
     * 重置倒计时锁存器
     * 
     * @param redissonClient RedissonClient实例
     * @param latchName 锁存器名称
     * @param count 新的计数值
     * @return 是否重置成功
     */
    public static boolean reset(RedissonClient redissonClient, String latchName, long count) {
        if (count < 0) {
            throw new IllegalArgumentException("Count cannot be negative");
        }
        
        try {
            RCountDownLatch latch = redissonClient.getCountDownLatch(latchName);
            
            // 删除现有的锁存器并重新创建
            latch.delete();
            boolean result = latch.trySetCount(count);
            
            log.info("Reset CountDownLatch {} to count {}: {}", latchName, count, result);
            return result;
        } catch (Exception e) {
            log.error("Failed to reset CountDownLatch: {}", latchName, e);
            return false;
        }
    }

    /**
     * 强制完成倒计时（将计数设置为0）
     * 
     * @param redissonClient RedissonClient实例
     * @param latchName 锁存器名称
     * @return 释放的等待线程数（估计值）
     */
    public static int forceCountDown(RedissonClient redissonClient, String latchName) {
        try {
            RCountDownLatch latch = redissonClient.getCountDownLatch(latchName);
            long currentCount = latch.getCount();
            
            if (currentCount > 0) {
                // 直接设置为0来释放所有等待线程
                latch.delete();
                latch.trySetCount(0);
                
                log.warn("CountDownLatch {} force counted down from {} to 0", latchName, currentCount);
                return (int) currentCount; // 估计值
            }
            return 0;
        } catch (Exception e) {
            log.error("Failed to force count down latch: {}", latchName, e);
            return 0;
        }
    }

    /**
     * 检查倒计时锁存器是否已完成
     * 
     * @param redissonClient RedissonClient实例
     * @param latchName 锁存器名称
     * @return 是否已完成（计数为0）
     */
    public static boolean isCompleted(RedissonClient redissonClient, String latchName) {
        return getCount(redissonClient, latchName) == 0;
    }

    /**
     * 删除倒计时锁存器
     * 
     * @param redissonClient RedissonClient实例
     * @param latchName 锁存器名称
     */
    public static void delete(RedissonClient redissonClient, String latchName) {
        try {
            RCountDownLatch latch = redissonClient.getCountDownLatch(latchName);
            latch.delete();
            log.info("Deleted CountDownLatch: {}", latchName);
        } catch (Exception e) {
            log.error("Failed to delete CountDownLatch: {}", latchName, e);
            throw new RuntimeException("Failed to delete CountDownLatch: " + latchName, e);
        }
    }

    /**
     * 获取倒计时锁存器的详细状态信息
     * 
     * @param redissonClient RedissonClient实例
     * @param latchName 锁存器名称
     * @return 状态描述字符串
     */
    public static String getStatus(RedissonClient redissonClient, String latchName) {
        try {
            RCountDownLatch latch = redissonClient.getCountDownLatch(latchName);
            long currentCount = latch.getCount();
            boolean completed = currentCount == 0;
            
            return String.format("CountDownLatch[name=%s, count=%d, completed=%s]", 
                               latchName, currentCount, completed);
        } catch (Exception e) {
            return String.format("CountDownLatch[name=%s, status=ERROR: %s]", latchName, e.getMessage());
        }
    }
}