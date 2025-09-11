package io.github.cascade.cache.simple;

import io.github.cascade.cache.config.CascadeCacheProperties;
import io.github.cascade.cache.exception.CacheException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;

/**
 * 基于调度器的缓存刷新实现
 * <p>
 * 设计原则：
 * 1. 灵活调度：支持每个键有不同的刷新间隔
 * 2. 并行刷新：支持并行和串行两种刷新模式
 * 3. 异常隔离：单个键的刷新失败不影响其他键
 * 4. 资源管理：合理管理线程池资源
 *
 * @param <K> 键类型
 * @param <V> 值类型
 * @author cascade
 */
public class ScheduledCacheRefresher<K, V> implements CacheRefresher<K, V> {

    private static final Logger LOGGER = LoggerFactory.getLogger(ScheduledCacheRefresher.class);

    private final String cacheName;
    private final Cache<K, V> cache;
    private final CacheLoader<K, V> loader;
    private final ScheduledExecutorService scheduler;
    private final ExecutorService refreshExecutor;
    private final boolean ownsExecutors; // 是否拥有线程池（用于资源管理）
    private final CascadeCacheProperties config; // 添加配置属性

    // 配置参数
    private volatile long defaultRefreshIntervalSeconds;
    private volatile boolean parallelRefresh;

    // 资源管理配置
    private final long shutdownTimeoutSeconds = 10L; // 关闭超时时间
    private final Object stateLock = new Object(); // 状态锁

    // 监控的键和调度任务
    private final ConcurrentMap<K, KeyRefreshInfo> monitoredKeys = new ConcurrentHashMap<>();
    private final ConcurrentMap<K, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();

    // 状态管理
    private volatile boolean running = true;

    /**
     * 构造器 - 使用默认线程池
     */
    public ScheduledCacheRefresher(String cacheName, Cache<K, V> cache, CacheLoader<K, V> loader,
                                   CascadeCacheProperties config) {
        this(cacheName, cache, loader, config,
                Executors.newScheduledThreadPool(2, r -> {
                    Thread t = new Thread(r, "cache-refresher-" + cacheName);
                    t.setDaemon(true);
                    return t;
                }),
                ForkJoinPool.commonPool(),
                true);
    }

    /**
     * 构造器 - 使用自定义线程池，支持配置
     */
    public ScheduledCacheRefresher(String cacheName, Cache<K, V> cache, CacheLoader<K, V> loader,
                                   CascadeCacheProperties config,
                                   ScheduledExecutorService scheduler,
                                   ExecutorService refreshExecutor,
                                   boolean ownsExecutors) {
        this.cacheName = cacheName;
        this.cache = cache;
        this.loader = loader;
        this.defaultRefreshIntervalSeconds = config.getRefreshIntervalSeconds();
        this.config = config;
        this.scheduler = scheduler;
        this.refreshExecutor = refreshExecutor;
        this.ownsExecutors = ownsExecutors;
        this.parallelRefresh = true; // 默认启用并行刷新

        if (cache == null) {
            throw new IllegalArgumentException("缓存不能为null");
        }
        if (loader == null) {
            throw new IllegalArgumentException("缓存加载器不能为null");
        }

        LOGGER.info("创建缓存刷新器: cache={}, 默认间隔={}s, 并行刷新={}",
                cacheName, defaultRefreshIntervalSeconds, parallelRefresh);
    }


    // ==================== CacheRefresher接口实现 ====================

    @Override
    public CompletableFuture<V> refresh(K key) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                V newValue = loader.apply(key);

                if (newValue != null) {
                    // 使用配置中的L2默认TTL
                    if (config != null) {
                        long ttl = config.getL2DefaultTtlSeconds();
                        if (ttl > 0) {
                            cache.put(key, newValue, ttl);
                        } else {
                            cache.put(key, newValue);
                        }
                    } else {
                        cache.put(key, newValue);
                    }
                    LOGGER.trace("缓存刷新完成: cache={}, key={}", cacheName, key);
                }

                return newValue;
            } catch (RuntimeException e) {
                throw new CompletionException(new CacheException(cacheName, "刷新", "刷新键失败: " + key, e));
            }
        }, refreshExecutor);
    }

    @Override
    public CompletableFuture<Void> refreshAll(Set<K> keys) {
        if (keys == null || keys.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        if (parallelRefresh) {
            // 并行刷新
            List<CompletableFuture<V>> futures = keys.stream()
                    .map(this::refresh)
                    .toList();

            return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .exceptionally(throwable -> {
                        LOGGER.warn("部分键刷新失败: cache={}, error={}", cacheName, throwable.getMessage());
                        return null;
                    });
        } else {
            // 串行刷新
            return CompletableFuture.runAsync(() -> {
                for (K key : keys) {
                    try {
                        refresh(key).join();
                    } catch (RuntimeException e) {
                        LOGGER.error("串行刷新失败: cache={}, key={}, error={}",
                                cacheName, key, e.getMessage());
                    }
                }
            }, refreshExecutor);
        }
    }

    @Override
    public CompletableFuture<Void> refreshAll() {
        Set<K> keys = new HashSet<>(monitoredKeys.keySet());
        return refreshAll(keys);
    }

    @Override
    public void addKey(K key) {
        addKey(key, defaultRefreshIntervalSeconds);
    }

    @Override
    public void addKey(K key, long refreshIntervalSeconds) {
        if (key == null) {
            throw new IllegalArgumentException("键不能为null");
        }
        if (refreshIntervalSeconds <= 0) {
            throw new IllegalArgumentException("刷新间隔必须大于0");
        }

        KeyRefreshInfo newInfo = new KeyRefreshInfo(key, refreshIntervalSeconds);
        
        // 使用同步块确保线程安全
        synchronized (stateLock) {
            KeyRefreshInfo existingInfo = monitoredKeys.get(key);
            
            LOGGER.info("🔍 [调试] 检查key是否已存在: cache={}, key={}, existingInfo={}", 
                    cacheName, key, existingInfo != null ? "存在" : "不存在");
            
            if (existingInfo != null) {
                // key已存在，检查刷新间隔是否相同
                LOGGER.debug("🔍 [调试] 现有间隔={}s, 新间隔={}s", existingInfo.refreshIntervalSeconds(), refreshIntervalSeconds);
                
                if (existingInfo.refreshIntervalSeconds() == refreshIntervalSeconds) {
                    LOGGER.info("⏭️ 刷新键已存在且间隔相同，跳过: cache={}, key={}, interval={}s", 
                            cacheName, key, refreshIntervalSeconds);
                    return;
                } else {
                    // 间隔不同，需要更新
                    monitoredKeys.put(key, newInfo);
                    if (running) {
                        scheduleKeyRefresh(key, newInfo);
                    }
                    LOGGER.info("🔄 更新刷新键间隔: cache={}, key={}, oldInterval={}s, newInterval={}s", 
                            cacheName, key, existingInfo.refreshIntervalSeconds(), refreshIntervalSeconds);
                    return;
                }
            }
            
            // key不存在，添加新的
            monitoredKeys.put(key, newInfo);
            if (running) {
                scheduleKeyRefresh(key, newInfo);
            }
            LOGGER.info("➕ 添加新刷新键: cache={}, key={}, interval={}s", cacheName, key, refreshIntervalSeconds);
        }
    }

    @Override
    public void removeKey(K key) {
        monitoredKeys.remove(key);

        ScheduledFuture<?> task = scheduledTasks.remove(key);
        if (task != null) {
            task.cancel(false);
        }

        LOGGER.debug("移除刷新键: cache={}, key={}", cacheName, key);
    }

    @Override
    public Set<K> getMonitoredKeys() {
        return new HashSet<>(monitoredKeys.keySet());
    }

    @Override
    public void clearMonitoredKeys() {
        // 取消所有调度任务
        scheduledTasks.values().forEach(task -> task.cancel(false));
        scheduledTasks.clear();
        monitoredKeys.clear();

        LOGGER.info("清空所有刷新键: cache={}", cacheName);
    }

    @Override
    public void start() {
        synchronized (stateLock) {
            if (running) {
                LOGGER.debug("缓存刷新器已经在运行: cache={}", cacheName);
                return;
            }

            try {
                running = true;

                // 为所有监控的键创建调度任务
                monitoredKeys.forEach(this::scheduleKeyRefresh);

                LOGGER.info("缓存刷新器已启动: cache={}, 监控键数={}", cacheName, monitoredKeys.size());
            } catch (RuntimeException e) {
                running = false; // 回滚状态
                throw new CacheException("启动缓存刷新器失败", e);
            }
        }
    }

    @Override
    public void stop() {
        synchronized (stateLock) {
            if (!running) {
                LOGGER.debug("缓存刷新器已经停止: cache={}", cacheName);
                return;
            }

            running = false;
            LOGGER.info("正在停止缓存刷新器: cache={}", cacheName);

            try {
                // 取消所有调度任务
                LOGGER.debug("取消调度任务: cache={}, 任务数={}", cacheName, scheduledTasks.size());
                scheduledTasks.values().forEach(task -> {
                    try {
                        task.cancel(false);
                    } catch (RuntimeException e) {
                        LOGGER.warn("取消调度任务失败: cache={}, error={}", cacheName, e.getMessage());
                    }
                });
                scheduledTasks.clear();

                // 关闭线程池（如果拥有的话）
                if (ownsExecutors) {
                    shutdownExecutors();
                }

                LOGGER.info("缓存刷新器已停止: cache={}", cacheName);
            } catch (RuntimeException e) {
                LOGGER.error("停止缓存刷新器时出现异常: cache={}, error={}", cacheName, e.getMessage(), e);
            }
        }
    }

    /**
     * 安全关闭执行器
     */
    private void shutdownExecutors() {
        // 优雅关闭调度器
        shutdownExecutor("scheduler", scheduler);

        // 优雅关闭刷新执行器（如果不是公共池）
        if (refreshExecutor != ForkJoinPool.commonPool()) {
            shutdownExecutor("refreshExecutor", refreshExecutor);
        }
    }

    /**
     * 安全关闭单个执行器
     */
    private void shutdownExecutor(String name, ExecutorService executor) {
        try {
            LOGGER.debug("正在关闭执行器: cache={}, executor={}", cacheName, name);
            executor.shutdown();

            if (!executor.awaitTermination(shutdownTimeoutSeconds, TimeUnit.SECONDS)) {
                LOGGER.warn("执行器未在超时时间内关闭，强制关闭: cache={}, executor={}, timeout={}s",
                        cacheName, name, shutdownTimeoutSeconds);
                executor.shutdownNow();

                // 再等待一段时间确认强制关闭生效
                if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                    LOGGER.error("执行器强制关闭失败: cache={}, executor={}", cacheName, name);
                }
            } else {
                LOGGER.debug("执行器已成功关闭: cache={}, executor={}", cacheName, name);
            }
        } catch (InterruptedException e) {
            LOGGER.warn("等待执行器关闭被中断: cache={}, executor={}", cacheName, name);
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        } catch (RuntimeException e) {
            LOGGER.error("关闭执行器失败: cache={}, executor={}, error={}", cacheName, name, e.getMessage());
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public long getDefaultRefreshIntervalSeconds() {
        return defaultRefreshIntervalSeconds;
    }

    @Override
    public void setDefaultRefreshIntervalSeconds(long seconds) {
        if (seconds <= 0) {
            throw new IllegalArgumentException("刷新间隔必须大于0");
        }
        this.defaultRefreshIntervalSeconds = seconds;
    }

    @Override
    public boolean isParallelRefresh() {
        return parallelRefresh;
    }

    @Override
    public void setParallelRefresh(boolean parallel) {
        this.parallelRefresh = parallel;
    }

    // ==================== 私有方法 ====================

    /**
     * 为键创建调度任务
     */
    private void scheduleKeyRefresh(K key, KeyRefreshInfo info) {
        // 取消已存在的任务
        ScheduledFuture<?> existingTask = scheduledTasks.get(key);
        LOGGER.debug("检查已存在的任务: cache={}, key={},task = {}", cacheName, key, existingTask);
        LOGGER.debug("task keys = {}", scheduledTasks.keySet());
        if (existingTask != null) {
            LOGGER.debug("取消已存在的任务: cache={}, key={}", cacheName, key);
            existingTask.cancel(false);
        }

        // 创建新的调度任务
        ScheduledFuture<?> newTask = scheduler.scheduleWithFixedDelay(
                () -> refreshKeyQuietly(key),
                info.refreshIntervalSeconds, // 初始延迟
                info.refreshIntervalSeconds, // 间隔
                TimeUnit.SECONDS
        );

        scheduledTasks.put(key, newTask);

        LOGGER.debug("调度键刷新任务已创建: cache={}, key={}, interval={}s", cacheName, key, info.refreshIntervalSeconds);
    }

    /**
     * 安静地刷新键（捕获所有异常）
     */
    private void refreshKeyQuietly(K key) {
        try {
            refresh(key).join();
        } catch (RuntimeException e) {
            LOGGER.error("定时刷新失败: cache={}, key={}, error={}", cacheName, key, e.getMessage());
        }
    }

    // ==================== 内部类 ====================

    /**
     * 键刷新信息
     */
    private record KeyRefreshInfo(Object key, long refreshIntervalSeconds) {
    }

    // ==================== 扩展方法 ====================

    /**
     * 获取统计信息
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("cacheName", cacheName);
        stats.put("running", running);
        stats.put("monitoredKeysCount", monitoredKeys.size());
        stats.put("scheduledTasksCount", scheduledTasks.size());
        stats.put("defaultRefreshIntervalSeconds", defaultRefreshIntervalSeconds);
        stats.put("parallelRefresh", parallelRefresh);
        return stats;
    }

    @Override
    public String toString() {
        return String.format("ScheduledCacheRefresher{cache=%s, running=%s, keys=%d, interval=%ds}",
                cacheName, running, monitoredKeys.size(), defaultRefreshIntervalSeconds);
    }
}