package io.github.cascade.cache.synchronization;

import io.github.cascade.cache.api.CacheSync;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author lionel lionelk@163.com
 * =============================
 * Date: 2025/10/29
 * Time: 17:05
 * =============================
 *
 * 缓存锁管理器 - 统一管理锁对象的创建和生命周期
 * <p>
 * 设计原则：
 * 1. 单例管理：全局统一的锁对象注册表
 * 2. 内存安全：避免锁对象无限增长
 * 3. 性能优化：相同key使用相同锁对象
 * 4. 监控支持：提供锁对象使用情况统计
 * <p>
 * 使用场景：
 * - 缓存防击穿的锁管理
 * - 批量操作的并发控制
 * - 缓存刷新的同步控制
 */
public final class CacheLockManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(CacheLockManager.class);

    // 全局锁对象注册表
    private static final Map<Object, Object> LOCK_REGISTRY = new ConcurrentHashMap<>();

    // 锁对象使用计数，用于监控和清理
    private static final Map<Object, Long> LOCK_USAGE_COUNT = new ConcurrentHashMap<>();

    // 最大锁对象数量限制，防止内存泄漏
    private static final int MAX_LOCK_COUNT = 10000;

    private CacheLockManager() {
        // 工具类不允许实例化
    }

    /**
     * 获取指定key的锁对象
     * <p>
     * 保证相同key始终返回相同的锁对象，实现细粒度的并发控制
     *
     * @param key 键对象，用作锁对象的标识
     * @return 锁对象
     */
    public static Object getLock(Object key) {
        if (key == null) {
            throw new IllegalArgumentException("锁键不能为null");
        }

        // 检查锁对象数量限制
        if (LOCK_REGISTRY.size() >= MAX_LOCK_COUNT) {
            LOGGER.warn("锁对象数量已达到上限: {}, 建议进行清理", MAX_LOCK_COUNT);
        }

        // 使用computeIfAbsent保证原子性操作
        Object lock = LOCK_REGISTRY.computeIfAbsent(key, k -> {
            LOGGER.debug("创建新的锁对象: key={}", k);
            return new Object();
        });

        // 更新使用计数
        LOCK_USAGE_COUNT.merge(key, 1L, Long::sum);

        return lock;
    }

    /**
     * 移除指定key的锁对象
     * <p>
     * 当不再需要某个锁对象时，可以调用此方法进行清理，释放内存
     *
     * @param key 键对象
     * @return 如果成功移除返回true，否则返回false
     */
    public static boolean removeLock(Object key) {
        if (key == null) {
            return false;
        }

        Object removed = LOCK_REGISTRY.remove(key);
        LOCK_USAGE_COUNT.remove(key);

        if (removed != null) {
            LOGGER.debug("锁对象已移除: key={}", key);
            return true;
        }

        return false;
    }

    /**
     * 清理所有锁对象
     * <p>
     * 谨慎使用此方法，通常只在应用关闭时调用
     */
    public static void clearAllLocks() {
        int count = LOCK_REGISTRY.size();
        LOCK_REGISTRY.clear();
        LOCK_USAGE_COUNT.clear();
        LOGGER.info("所有锁对象已清理，共清理: {} 个", count);
    }

    /**
     * 获取当前锁对象数量
     *
     * @return 锁对象数量
     */
    public static int getLockCount() {
        return LOCK_REGISTRY.size();
    }

    /**
     * 获取指定锁对象的使用次数
     *
     * @param key 键对象
     * @return 使用次数，如果key不存在返回0
     */
    public static long getLockUsageCount(Object key) {
        return LOCK_USAGE_COUNT.getOrDefault(key, 0L);
    }

    /**
     * 获取锁对象使用统计信息
     *
     * @return 锁统计信息
     */
    public static LockStats getLockStats() {
        return LockStats.builder()
                .totalLockCount(LOCK_REGISTRY.size())
                .totalUsage(LOCK_USAGE_COUNT.values().stream().mapToLong(Long::longValue).sum())
                .averageUsage(LOCK_USAGE_COUNT.isEmpty() ? 0.0 :
                    LOCK_USAGE_COUNT.values().stream().mapToLong(Long::longValue).average().orElse(0.0))
                .maxUsage(LOCK_USAGE_COUNT.values().stream().mapToLong(Long::longValue).max().orElse(0L))
                .build();
    }

    /**
     * 检查指定key是否存在锁对象
     *
     * @param key 键对象
     * @return 如果存在返回true，否则返回false
     */
    public static boolean hasLock(Object key) {
        return LOCK_REGISTRY.containsKey(key);
    }

    /**
     * 清理使用频率较低的锁对象
     * <p>
     * 释放长时间未使用的锁对象，防止内存泄漏
     *
     * @param minUsageCount 最小使用次数，低于此值的锁对象将被清理
     * @return 清理的锁对象数量
     */
    public static int cleanupLowUsageLocks(long minUsageCount) {
        int removedCount = 0;

        LOCK_USAGE_COUNT.entrySet().removeIf(entry -> {
            if (entry.getValue() < minUsageCount) {
                LOCK_REGISTRY.remove(entry.getKey());
                LOGGER.debug("清理低使用率锁对象: key={}, usage={}", entry.getKey(), entry.getValue());
                return true;
            }
            return false;
        });

        removedCount = LOCK_REGISTRY.size();
        LOGGER.info("低使用率锁对象清理完成，清理阈值: {}, 清理数量: {}", minUsageCount, removedCount);
        return removedCount;
    }

    /**
     * 锁统计信息
     */
    public static class LockStats {
        private final int totalLockCount;
        private final long totalUsage;
        private final double averageUsage;
        private final long maxUsage;

        private LockStats(Builder builder) {
            this.totalLockCount = builder.totalLockCount;
            this.totalUsage = builder.totalUsage;
            this.averageUsage = builder.averageUsage;
            this.maxUsage = builder.maxUsage;
        }

        public static Builder builder() {
            return new Builder();
        }

        // Getters
        public int getTotalLockCount() {
            return totalLockCount;
        }

        public long getTotalUsage() {
            return totalUsage;
        }

        public double getAverageUsage() {
            return averageUsage;
        }

        public long getMaxUsage() {
            return maxUsage;
        }

        @Override
        public String toString() {
            return String.format("LockStats{totalLockCount=%d, totalUsage=%d, averageUsage=%.2f, maxUsage=%d}",
                    totalLockCount, totalUsage, averageUsage, maxUsage);
        }

        public static class Builder {
            private int totalLockCount;
            private long totalUsage;
            private double averageUsage;
            private long maxUsage;

            public Builder totalLockCount(int totalLockCount) {
                this.totalLockCount = totalLockCount;
                return this;
            }

            public Builder totalUsage(long totalUsage) {
                this.totalUsage = totalUsage;
                return this;
            }

            public Builder averageUsage(double averageUsage) {
                this.averageUsage = averageUsage;
                return this;
            }

            public Builder maxUsage(long maxUsage) {
                this.maxUsage = maxUsage;
                return this;
            }

            public LockStats build() {
                return new LockStats(this);
            }
        }
    }
}