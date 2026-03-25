package io.github.cascade.cache.core;

import io.github.cascade.cache.configuration.CascadeCacheProperties;
import io.github.cascade.cache.api.Cache;
import io.github.cascade.cache.api.CacheLoader;
import io.github.cascade.cache.api.CacheRefresher;
import io.github.cascade.cache.common.exception.CacheException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 基于调度器的缓存刷新实现 (优化版)
 * <p>
 * 设计原则：
 * 1. 资源优化：使用 DelayQueue + 单线程 替代每key一个ScheduledTask，支持海量key
 * 2. 灵活调度：支持每个键有不同的刷新间隔
 * 3. 并行刷新：任务调度与执行分离，刷新动作提交到线程池执行
 * 4. 懒惰清理：移除key时不扫描队列，而是在任务取出时检查有效性
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
    private final ExecutorService refreshExecutor;
    private final boolean ownsExecutors; // 是否拥有线程池（用于资源管理）
    private final CascadeCacheProperties config;

    // 配置参数
    private volatile long defaultRefreshIntervalSeconds;
    private volatile boolean parallelRefresh = true;

    // 资源管理配置
    private final long shutdownTimeoutSeconds = 10L;

    // 核心组件：监控的键和刷新间隔
    // 使用 Map 存储当前有效的键及其刷新间隔
    private final ConcurrentMap<K, Long> monitoredKeys = new ConcurrentHashMap<>();

    // 核心组件：延时队列
    private final DelayQueue<RefreshTask> delayQueue = new DelayQueue<>();

    // 调度工作线程
    private final Thread workerThread;

    // 状态管理
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * 构造器 - 使用默认线程池
     */
    public ScheduledCacheRefresher(String cacheName, Cache<K, V> cache, CacheLoader<K, V> loader,
            CascadeCacheProperties config) {
        this(cacheName, cache, loader, config,
                ForkJoinPool.commonPool(),
                false);
    }

    /**
     * 构造器 - 使用自定义线程池
     * 注意：不再需要 ScheduledExecutorService，因为内部使用 DelayQueue + Thread 实现调度
     */
    public ScheduledCacheRefresher(String cacheName, Cache<K, V> cache, CacheLoader<K, V> loader,
            CascadeCacheProperties config,
            ExecutorService refreshExecutor,
            boolean ownsExecutors) {
        this.cacheName = cacheName;
        this.cache = cache;
        this.loader = loader;
        this.defaultRefreshIntervalSeconds = config.getRefreshIntervalSeconds();
        this.config = config;
        this.refreshExecutor = refreshExecutor;
        this.ownsExecutors = ownsExecutors;

        if (cache == null) {
            throw new IllegalArgumentException("缓存不能为null");
        }
        if (loader == null) {
            throw new IllegalArgumentException("缓存加载器不能为null");
        }

        // 初始化工作线程
        this.workerThread = new Thread(this::runWorker, "cache-refresher-worker-" + cacheName);
        this.workerThread.setDaemon(true);

        LOGGER.info("创建缓存刷新器(DelayQueue版): cache={}, 默认间隔={}s, 并行刷新={}",
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
        // 使用 monitoredKeys 快照
        return refreshAll(new HashSet<>(monitoredKeys.keySet()));
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

        // 更新或添加监控键
        Long oldInterval = monitoredKeys.put(key, refreshIntervalSeconds);

        // 只有当是新key或者间隔改变时，我们才需要立即干预队列
        // 但由于 DelayQueue 移除开销大，我们策略是：
        // 总是添加一个新的 Task。旧的 Task 在执行时会检查 monitoredKeys 中的间隔
        // 如果旧 Task 的间隔与 monitoredKeys 不一致（针对同一个key），可以视为过时任务被忽略
        // 或者简单点：Task只存储key，执行时去 map 查最新间隔。
        // 这里采用：Task 存储 triggerTime。执行时检查 key 是否还在 map 中。
        // 如果我们添加了新任务，旧任务到期后执行一次也无伤大雅（或者可以检查执行时间是否合理？）

        // 简单策略：直接添加新任务
        // 计算触发时间
        long triggerTime = System.currentTimeMillis() + (refreshIntervalSeconds * 1000);
        delayQueue.put(new RefreshTask(key, triggerTime));

        if (oldInterval == null) {
            LOGGER.info("➕ 添加新刷新键: cache={}, key={}, interval={}s", cacheName, key, refreshIntervalSeconds);
        } else if (oldInterval != refreshIntervalSeconds) {
            LOGGER.info("🔄 更新刷新键间隔: cache={}, key={}, old={}s, new={}s", cacheName, key, oldInterval,
                    refreshIntervalSeconds);
        }
    }

    @Override
    public void removeKey(K key) {
        // 仅从 Map 移除，DelayQueue 中的任务在取出时会因检查不到 Map 中的记录而被丢弃
        // 这样避免了 O(N) 的队列扫描
        if (monitoredKeys.remove(key) != null) {
            LOGGER.debug("移除刷新键: cache={}, key={}", cacheName, key);
        }
    }

    @Override
    public Set<K> getMonitoredKeys() {
        return new HashSet<>(monitoredKeys.keySet());
    }

    @Override
    public void clearMonitoredKeys() {
        monitoredKeys.clear();
        delayQueue.clear(); // DelayQueue.clear() 还是比较快的
        LOGGER.info("清空所有刷新键: cache={}", cacheName);
    }

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            try {
                workerThread.start();
                LOGGER.info("缓存刷新器已启动(DelayQueue模式): cache={}, 监控键数={}", cacheName, monitoredKeys.size());
            } catch (Exception e) {
                running.set(false);
                throw new CacheException("启动缓存刷新器失败", e);
            }
        }
    }

    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) {
            LOGGER.info("正在停止缓存刷新器: cache={}", cacheName);

            // 中断工作线程
            workerThread.interrupt();

            // 清理
            delayQueue.clear();

            // 关闭线程池（如果拥有）
            if (ownsExecutors && refreshExecutor != null) {
                shutdownExecutor("refreshExecutor", refreshExecutor);
            }

            LOGGER.info("缓存刷新器已停止: cache={}", cacheName);
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
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

    // ==================== 内部工作线程逻辑 ====================

    private void runWorker() {
        LOGGER.info("刷新器工作线程开始运行: {}", Thread.currentThread().getName());
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                // 从队列获取到期任务
                RefreshTask task = delayQueue.take();

                // 处理任务
                processTask(task);

            } catch (InterruptedException e) {
                LOGGER.info("刷新器工作线程被中断，即将退出");
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                LOGGER.error("刷新器工作线程发生异常", e);
            }
        }
    }

    private void processTask(RefreshTask task) {
        K key = task.key;

        // 1. 检查 Key 是否仍然被监控
        Long intervalSeconds = monitoredKeys.get(key);
        if (intervalSeconds == null) {
            // Key 已被移除，忽略此任务
            return;
        }

        // 2. 执行刷新 (异步提交)
        CompletableFuture<V> refreshFuture = refresh(key);

        // 3. 刷新完成后，如果 Key 仍需监控，则重新加入队列
        refreshFuture.whenComplete((val, ex) -> {
            if (ex != null) {
                LOGGER.warn("定时刷新异常: cache={}, key={}, error={}", cacheName, key, ex.getMessage());
            }

            // 再次检查是否还在监控列表中 (可能在刷新期间被移除了)
            Long currentInterval = monitoredKeys.get(key);
            if (currentInterval != null && running.get()) {
                // 计算下次触发时间
                long nextTriggerTime = System.currentTimeMillis() + (currentInterval * 1000);
                delayQueue.put(new RefreshTask(key, nextTriggerTime));
            }
        });
    }

    private void shutdownExecutor(String name, ExecutorService executor) {
        try {
            executor.shutdown();
            if (!executor.awaitTermination(shutdownTimeoutSeconds, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ==================== 内部类：延时任务 ====================

    /**
     * 延时刷新任务
     */
    private class RefreshTask implements Delayed {
        private final K key;
        private final long triggerTime; // 绝对时间戳 (ms)

        public RefreshTask(K key, long triggerTime) {
            this.key = key;
            this.triggerTime = triggerTime;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            long diff = triggerTime - System.currentTimeMillis();
            return unit.convert(diff, TimeUnit.MILLISECONDS);
        }

        @Override
        public int compareTo(Delayed o) {
            if (this == o)
                return 0;
            long diff = getDelay(TimeUnit.MILLISECONDS) - o.getDelay(TimeUnit.MILLISECONDS);
            return (diff == 0) ? 0 : ((diff < 0) ? -1 : 1);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;
            RefreshTask that = (RefreshTask) o;
            return getKey().equals(that.getKey());
        }

        @Override
        public int hashCode() {
            return getKey().hashCode();
        }

        public K getKey() {
            return key;
        }
    }

    // ==================== 扩展方法 ====================

    /**
     * 获取统计信息
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("cacheName", cacheName);
        stats.put("running", running.get());
        stats.put("monitoredKeysCount", monitoredKeys.size());
        stats.put("queuedTasksCount", delayQueue.size()); // 注意：size()操作在DelayQueue上可能不准确或耗时
        stats.put("defaultRefreshIntervalSeconds", defaultRefreshIntervalSeconds);
        stats.put("parallelRefresh", parallelRefresh);
        return stats;
    }

    @Override
    public String toString() {
        return String.format("ScheduledCacheRefresher{cache=%s, running=%s, keys=%d, queue=%d}",
                cacheName, running.get(), monitoredKeys.size(), delayQueue.size());
    }
}