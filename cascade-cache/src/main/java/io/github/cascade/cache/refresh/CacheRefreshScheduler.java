package io.github.cascade.cache.refresh;

import io.github.cascade.cache.api.CacheLoader;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 缓存刷新调度器 - 支持动态线程池调整
 * 负责管理L2缓存的定时刷新任务，根据负载动态调整线程池大小
 */
public class CacheRefreshScheduler<K, V> {

    private static final Logger log = LoggerFactory.getLogger(CacheRefreshScheduler.class);

    // 动态线程池配置
    private final ThreadPoolExecutor dynamicExecutor;
    private final ScheduledExecutorService scheduler;
    private final RefreshConfig config;

    // 任务管理
    private final ConcurrentHashMap<K, ScheduledFuture<?>> scheduledRefreshes = new ConcurrentHashMap<>();
    private final RefreshCallback<K, V> refreshCallback;
    private final CacheLoader<K, V> cacheLoader;
    private final Duration refreshInterval;

    // 统计信息
    private final AtomicInteger refreshCounter = new AtomicInteger(0);
    private final AtomicLong successCount = new AtomicLong(0);
    private final AtomicLong failureCount = new AtomicLong(0);
    private volatile Instant lastAdjustment = Instant.now();

    // 监控任务
    private final ScheduledFuture<?> monitorTask;

    /**
     * 刷新回调接口
     */
    @FunctionalInterface
    public interface RefreshCallback<K, V> {
        void refresh(K key, V newValue);
    }

    /**
     * 构造器 - 使用默认配置
     */
    public CacheRefreshScheduler(RefreshCallback<K, V> refreshCallback,
                                 CacheLoader<K, V> cacheLoader,
                                 Duration refreshInterval) {
        this(refreshCallback, cacheLoader, refreshInterval, RefreshConfig.defaultConfig());
    }

    /**
     * 构造器 - 使用自定义配置
     */
    public CacheRefreshScheduler(RefreshCallback<K, V> refreshCallback,
                                 CacheLoader<K, V> cacheLoader,
                                 Duration refreshInterval,
                                 RefreshConfig config) {
        this.refreshCallback = refreshCallback;
        this.cacheLoader = cacheLoader;
        this.refreshInterval = refreshInterval;
        this.config = config;

        // 创建动态线程池
        this.dynamicExecutor = createDynamicThreadPool();

        // 创建调度器（用于定时任务）
        this.scheduler = Executors.newScheduledThreadPool(
                2, // 只需要少量线程用于调度
                r -> {
                    Thread t = new Thread(r, "CacheRefreshScheduler-" + refreshCounter.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                }
        );

        // 启动监控任务
        this.monitorTask = scheduler.scheduleWithFixedDelay(
                this::adjustThreadPoolSize,
                config.getMonitorInterval().toMillis(),
                config.getMonitorInterval().toMillis(),
                TimeUnit.MILLISECONDS
        );

        log.info("Created dynamic cache refresh scheduler with config: {}", config);
    }

    /**
     * 调度键的刷新任务
     */
    public void scheduleRefresh(K key) {
        log.debug("Scheduling refresh for key {}", key);
        if (key == null || refreshInterval == null || cacheLoader == null) {
            return;
        }

        // 取消现有的刷新任务
        ScheduledFuture<?> existing = scheduledRefreshes.get(key);
        if (existing != null && !existing.isDone()) {
            existing.cancel(false);
        }

        // 创建新的刷新任务（使用动态线程池执行）
        ScheduledFuture<?> future = scheduler.schedule(
                () -> dynamicExecutor.submit(() -> performRefresh(key)),
                refreshInterval.toMillis(),
                TimeUnit.MILLISECONDS
        );
        log.info("Scheduled refresh for key {} in {}", key, refreshInterval.toMillis());

        scheduledRefreshes.put(key, future);

        log.debug("Scheduled refresh for key {} in {}", key, refreshInterval.toSeconds());
    }

    /**
     * 取消键的刷新任务
     */
    public void cancelRefresh(K key) {
        ScheduledFuture<?> future = scheduledRefreshes.remove(key);
        if (future != null && !future.isDone()) {
            future.cancel(false);
            log.debug("Cancelled refresh for key {}", key);
        }
    }

    /**
     * 执行刷新操作
     */
    private void performRefresh(K key) {
        try {
            log.debug("Performing scheduled refresh for key {}", key);

            // 从数据源加载新值
            V newValue = cacheLoader.load(key);

            if (newValue != null) {
                // 回调更新缓存
                refreshCallback.refresh(key, newValue);

                successCount.incrementAndGet();

                // 重新调度下次刷新
                scheduleRefresh(key);

                log.debug("Successfully refreshed key {} with new value", key);
            } else {
                log.debug("Refresh returned null for key {}, removing from cache", key);
                // 数据不存在时，不需要重新调度
                scheduledRefreshes.remove(key);
            }

        } catch (Exception e) {
            log.warn("Failed to refresh key {}: {}", key, e.getMessage());

            failureCount.incrementAndGet();

            // 刷新失败时，延长间隔重试（使用动态线程池执行）
            ScheduledFuture<?> retryFuture = scheduler.schedule(
                    () -> dynamicExecutor.submit(() -> performRefresh(key)),
                    Math.min(refreshInterval.toMillis() * 2, Duration.ofMinutes(30).toMillis()),
                    TimeUnit.MILLISECONDS
            );

            scheduledRefreshes.put(key, retryFuture);
        }
    }

    /**
     * 获取活跃刷新任务数量
     */
    public int getActiveRefreshCount() {
        return (int) scheduledRefreshes.values().stream()
                .filter(future -> !future.isDone())
                .count();
    }

    /**
     * 关闭调度器
     */
    public void shutdown() {
        log.info("Shutting down cache refresh scheduler");

        // 取消所有任务
        scheduledRefreshes.values().forEach(future -> {
            if (!future.isDone()) {
                future.cancel(false);
            }
        });

        scheduledRefreshes.clear();

        // 取消监控任务
        if (monitorTask != null && !monitorTask.isDone()) {
            monitorTask.cancel(false);
        }

        // 关闭动态线程池
        dynamicExecutor.shutdown();
        try {
            if (!dynamicExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                dynamicExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            dynamicExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // 关闭调度器
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 获取刷新统计信息
     */
    public RefreshStats getStats() {
        return new RefreshStats(
                scheduledRefreshes.size(),
                getActiveRefreshCount(),
                refreshInterval
        );
    }

    /**
     * 刷新统计信息
     */
    @Getter
    public static class RefreshStats {
        private final int totalScheduled;
        private final int activeRefreshes;
        private final Duration refreshInterval;

        public RefreshStats(int totalScheduled, int activeRefreshes, Duration refreshInterval) {
            this.totalScheduled = totalScheduled;
            this.activeRefreshes = activeRefreshes;
            this.refreshInterval = refreshInterval;
        }

        @Override
        public String toString() {
            return String.format("RefreshStats[total=%d, active=%d, interval=%s]",
                    totalScheduled, activeRefreshes, refreshInterval);
        }
    }

    // ==================== 动态线程池相关方法 ====================

    /**
     * 创建动态线程池
     */
    private ThreadPoolExecutor createDynamicThreadPool() {
        // 创建有界队列
        BlockingQueue<Runnable> workQueue = new ArrayBlockingQueue<>(config.getQueueCapacity());

        // 创建线程工厂
        ThreadFactory threadFactory = r -> {
            Thread t = new Thread(r, "DynamicCacheRefresh-" + refreshCounter.incrementAndGet());
            t.setDaemon(true);
            return t;
        };

        // 自定义拒绝策略：记录日志并丢弃任务
        RejectedExecutionHandler rejectedHandler = (r, executor) -> {
            log.warn("Cache refresh task rejected due to thread pool exhaustion. " +
                            "Consider increasing thread pool size. Active threads: {}, Queue size: {}",
                    executor.getActiveCount(), executor.getQueue().size());
        };

        // 创建线程池
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                config.getCorePoolSize(),
                config.getMaxPoolSize(),
                config.getKeepAliveTime(),
                TimeUnit.SECONDS,
                workQueue,
                threadFactory,
                rejectedHandler
        );

        // 允许核心线程超时
        executor.allowCoreThreadTimeOut(true);

        log.info("Created dynamic thread pool - Core: {}, Max: {}, Queue: {}",
                config.getCorePoolSize(), config.getMaxPoolSize(), config.getQueueCapacity());

        return executor;
    }

    /**
     * 动态调整线程池大小
     */
    private void adjustThreadPoolSize() {
        try {
            ThreadPoolExecutor threadPool = dynamicExecutor;

            int currentCoreSize = threadPool.getCorePoolSize();
            int currentMaxSize = threadPool.getMaximumPoolSize();
            int activeThreads = threadPool.getActiveCount();
            int queueSize = threadPool.getQueue().size();
            long completedTasks = threadPool.getCompletedTaskCount();

            // 计算成功率和负载
            long totalTasks = successCount.get() + failureCount.get();
            double successRate = totalTasks > 0 ? (double) successCount.get() / totalTasks : 1.0;
            double loadFactor = (double) (activeThreads + queueSize) / currentMaxSize;

            // 调整策略
            int newCoreSize = calculateOptimalCoreSize(activeThreads, queueSize, loadFactor, successRate);
            int newMaxSize = calculateOptimalMaxSize(newCoreSize, loadFactor);

            // 应用调整
            if (newCoreSize != currentCoreSize || newMaxSize != currentMaxSize) {
                threadPool.setCorePoolSize(newCoreSize);
                threadPool.setMaximumPoolSize(newMaxSize);

                lastAdjustment = Instant.now();

                log.info("Adjusted thread pool size - Core: {} -> {}, Max: {} -> {}, " +
                                "Load: {}, Success Rate: {}%, Active: {}, Queue: {}",
                        currentCoreSize, newCoreSize, currentMaxSize, newMaxSize,
                        loadFactor, successRate * 100, activeThreads, queueSize);
            }

        } catch (Exception e) {
            log.warn("Failed to adjust thread pool size", e);
        }
    }

    /**
     * 计算最优核心线程数
     */
    private int calculateOptimalCoreSize(int activeThreads, int queueSize, double loadFactor, double successRate) {
        int currentCore = dynamicExecutor.getCorePoolSize();

        // 高负载且成功率高：增加核心线程
        if (loadFactor > config.getHighLoadThreshold() && successRate > config.getSuccessThreshold()) {
            return Math.min(currentCore + 1, config.getMaxPoolSize());
        }

        // 低负载：减少核心线程
        if (loadFactor < config.getLowLoadThreshold() && activeThreads < currentCore * 0.5) {
            return Math.max(currentCore - 1, config.getCorePoolSize());
        }

        return currentCore;
    }

    /**
     * 计算最优最大线程数
     */
    private int calculateOptimalMaxSize(int coreSize, double loadFactor) {
        // 确保最大线程数至少是核心线程数的1.5倍
        int minMaxSize = Math.max(coreSize * 3 / 2, config.getCorePoolSize() * 2);

        // 根据负载调整
        if (loadFactor > config.getHighLoadThreshold()) {
            return Math.min(config.getMaxPoolSize(), minMaxSize + 2);
        }

        return Math.max(minMaxSize, coreSize + 1);
    }

    /**
     * 获取线程池状态
     */
    public ThreadPoolStats getThreadPoolStats() {
        ThreadPoolExecutor threadPool = dynamicExecutor;

        return new ThreadPoolStats(
                threadPool.getCorePoolSize(),
                threadPool.getMaximumPoolSize(),
                threadPool.getActiveCount(),
                threadPool.getQueue().size(),
                threadPool.getCompletedTaskCount(),
                successCount.get(),
                failureCount.get()
        );
    }

    /**
     * 线程池统计信息
     */
    @Getter
    public static class ThreadPoolStats {
        // Getters
        private final int corePoolSize;
        private final int maxPoolSize;
        private final int activeThreads;
        private final int queueSize;
        private final long completedTasks;
        private final long successCount;
        private final long failureCount;

        public ThreadPoolStats(int corePoolSize, int maxPoolSize, int activeThreads,
                               int queueSize, long completedTasks, long successCount, long failureCount) {
            this.corePoolSize = corePoolSize;
            this.maxPoolSize = maxPoolSize;
            this.activeThreads = activeThreads;
            this.queueSize = queueSize;
            this.completedTasks = completedTasks;
            this.successCount = successCount;
            this.failureCount = failureCount;
        }

        public double getSuccessRate() {
            long total = successCount + failureCount;
            return total > 0 ? (double) successCount / total : 1.0;
        }

        public double getLoadFactor() {
            return maxPoolSize > 0 ? (double) (activeThreads + queueSize) / maxPoolSize : 0.0;
        }

        @Override
        public String toString() {
            return String.format("ThreadPoolStats[core=%d, max=%d, active=%d, queue=%d, " +
                            "completed=%d, success=%.2f%%, load=%.2f]",
                    corePoolSize, maxPoolSize, activeThreads, queueSize, completedTasks,
                    getSuccessRate() * 100, getLoadFactor());
        }
    }

    // ==================== 配置类 ====================

    /**
     * 刷新调度器配置
     */
    @Getter
    public static class RefreshConfig {
        // Getters and setters
        private int corePoolSize = 2;
        private int maxPoolSize = 8;
        private int queueCapacity = 100;
        private int keepAliveTime = 60; // seconds
        private Duration monitorInterval = Duration.ofMinutes(1);

        // 调整阈值
        private double highLoadThreshold = 0.8;
        private double lowLoadThreshold = 0.3;
        private double successThreshold = 0.9;

        public static RefreshConfig defaultConfig() {
            return new RefreshConfig();
        }

        public RefreshConfig setCorePoolSize(int corePoolSize) {
            this.corePoolSize = corePoolSize;
            return this;
        }

        public RefreshConfig setMaxPoolSize(int maxPoolSize) {
            this.maxPoolSize = maxPoolSize;
            return this;
        }

        public RefreshConfig setQueueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
            return this;
        }

        public RefreshConfig setKeepAliveTime(int keepAliveTime) {
            this.keepAliveTime = keepAliveTime;
            return this;
        }

        public RefreshConfig setMonitorInterval(Duration monitorInterval) {
            this.monitorInterval = monitorInterval;
            return this;
        }

        public RefreshConfig setHighLoadThreshold(double highLoadThreshold) {
            this.highLoadThreshold = highLoadThreshold;
            return this;
        }

        public RefreshConfig setLowLoadThreshold(double lowLoadThreshold) {
            this.lowLoadThreshold = lowLoadThreshold;
            return this;
        }

        public RefreshConfig setSuccessThreshold(double successThreshold) {
            this.successThreshold = successThreshold;
            return this;
        }

        @Override
        public String toString() {
            return String.format("RefreshConfig[core=%d, max=%d, queue=%d, monitor=%s]",
                    corePoolSize, maxPoolSize, queueCapacity, monitorInterval);
        }
    }
}