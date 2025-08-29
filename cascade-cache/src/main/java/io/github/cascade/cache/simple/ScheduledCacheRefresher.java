package io.github.cascade.cache.simple;

import io.github.cascade.cache.config.CascadeCacheProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

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

    private static final Logger log = LoggerFactory.getLogger(ScheduledCacheRefresher.class);

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

    // 监控的键和调度任务
    private final ConcurrentMap<K, KeyRefreshInfo> monitoredKeys = new ConcurrentHashMap<>();
    private final ConcurrentMap<K, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();

    // 状态管理
    private volatile boolean running = false;

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

        log.info("创建定时缓存刷新器: cache={}, 默认间隔={}s, 并行刷新={}, 配置L2TTL={}s",
                cacheName, defaultRefreshIntervalSeconds, parallelRefresh,
                config != null ? config.getL2DefaultTtlSeconds() : "null");
    }


    // ==================== CacheRefresher接口实现 ====================

    @Override
    public CompletableFuture<V> refresh(K key) {
        if (loader == null) {
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                log.debug("开始刷新缓存: cache={}, key={}", cacheName, key);
                V newValue = loader.apply(key);

                if (newValue != null) {
                    // 使用配置中的L2默认TTL
                    if (config != null) {
                        long ttl = config.getL2DefaultTtlSeconds();
                        if (ttl > 0) {
                            cache.put(key, newValue, ttl);
                            log.debug("缓存刷新成功: cache={}, key={}, ttl={}s", cacheName, key, ttl);
                        } else {
                            cache.put(key, newValue);
                            log.debug("缓存刷新成功: cache={}, key={}, ttl=永不过期", cacheName, key);
                        }
                    } else {
                        cache.put(key, newValue);
                        log.debug("缓存刷新成功: cache={}, key={}, ttl=默认", cacheName, key);
                    }
                } else {
                    log.debug("缓存刷新返回null: cache={}, key={}", cacheName, key);
                }

                return newValue;
            } catch (Exception e) {
                log.error("缓存刷新失败: cache={}, key={}, error={}", cacheName, key, e.getMessage());
                throw new CompletionException("缓存刷新失败: " + key, e);
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
                    .collect(Collectors.toList());

            return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .exceptionally(throwable -> {
                        log.warn("部分键刷新失败: cache={}, error={}", cacheName, throwable.getMessage());
                        return null;
                    });
        } else {
            // 串行刷新
            return CompletableFuture.runAsync(() -> {
                for (K key : keys) {
                    try {
                        refresh(key).join();
                    } catch (Exception e) {
                        log.error("串行刷新失败: cache={}, key={}, error={}",
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

        KeyRefreshInfo info = new KeyRefreshInfo(key, refreshIntervalSeconds);
        monitoredKeys.put(key, info);

        if (running) {
            scheduleKeyRefresh(key, info);
        }

        log.info("添加刷新键: cache={}, key={}, interval={}s", cacheName, key, refreshIntervalSeconds);
    }

    @Override
    public void removeKey(K key) {
        monitoredKeys.remove(key);

        ScheduledFuture<?> task = scheduledTasks.remove(key);
        if (task != null) {
            task.cancel(false);
        }

        log.debug("移除刷新键: cache={}, key={}", cacheName, key);
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

        log.info("清空所有刷新键: cache={}", cacheName);
    }

    @Override
    public void start() {
        if (!running) {
            running = true;

            // 为所有监控的键创建调度任务
            monitoredKeys.forEach(this::scheduleKeyRefresh);

            log.info("缓存刷新器已启动: cache={}, 监控键数={}", cacheName, monitoredKeys.size());
        }
    }

    @Override
    public void stop() {
        if (running) {
            running = false;

            // 取消所有调度任务
            scheduledTasks.values().forEach(task -> task.cancel(false));
            scheduledTasks.clear();

            // 关闭线程池（如果拥有的话）
            if (ownsExecutors) {
                scheduler.shutdown();
                if (refreshExecutor != ForkJoinPool.commonPool()) {
                    refreshExecutor.shutdown();
                }

                try {
                    if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                        scheduler.shutdownNow();
                    }
                    if (refreshExecutor != ForkJoinPool.commonPool() &&
                            !refreshExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                        refreshExecutor.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            log.info("缓存刷新器已停止: cache={}", cacheName);
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
        if (existingTask != null) {
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

        log.info("调度键刷新任务已创建: cache={}, key={}, interval={}s", cacheName, key, info.refreshIntervalSeconds);
    }

    /**
     * 安静地刷新键（捕获所有异常）
     */
    private void refreshKeyQuietly(K key) {
        try {
            refresh(key).join();
        } catch (Exception e) {
            log.error("定时刷新失败: cache={}, key={}, error={}", cacheName, key, e.getMessage());
        }
    }

    // ==================== 内部类 ====================

    /**
     * 键刷新信息
     */
    private static class KeyRefreshInfo {
        final Object key;
        final long refreshIntervalSeconds;

        KeyRefreshInfo(Object key, long refreshIntervalSeconds) {
            this.key = key;
            this.refreshIntervalSeconds = refreshIntervalSeconds;
        }
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