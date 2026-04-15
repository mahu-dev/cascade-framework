package io.github.cascade.cache.v2.engine;

import io.github.cascade.cache.v2.policy.CachePolicy;
import io.github.cascade.cache.v2.policy.RefreshExecutionOptions;
import io.github.cascade.cache.v2.store.l1.L1CacheStore;
import io.github.cascade.cache.v2.store.l2.L2CacheStore;
import io.github.cascade.cache.v2.store.model.CacheRecord;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.*;

/**
 * EngineBackedCache 刷新与热点追踪逻辑。
 */
final class EngineBackedCacheRefresh<K, V> {

    private final Logger logger;
    private final String cacheName;
    private final CachePolicy policy;
    private final RefreshExecutionOptions refreshOptions;
    /**
     * 周期扫描热点 key 的单线程调度器。
     */
    private final ScheduledExecutorService refreshScheduler;
    /**
     * 实际执行刷新加载的线程池。
     */
    private final ThreadPoolExecutor refreshExecutor;
    private final RefreshPipeline refreshPipeline;
    private final L1CacheStore<K, V> l1Store;
    private final L2CacheStore<K, V> l2Store;
    /**
     * 刷新调度是否已经启动，避免重复 schedule。
     */
    private final AtomicBoolean refreshStarted;
    /**
     * 已纳入自动刷新的热点 key 集。
     */
    private final Set<K> trackedKeys;
    /**
     * 正在刷新中的 key 集，用于同 key 去重。
     */
    private final Set<K> refreshingKeys;
    /**
     * 热点识别用访问计数。
     */
    private final com.github.benmanes.caffeine.cache.Cache<K, AtomicLong> readCounter;
    private final Supplier<Function<K, V>> loaderSupplier;
    private final Function<K, Optional<CacheRecord<V>>> readFromL1;
    private final Function<K, Optional<CacheRecord<V>>> readFromL2;
    private final Predicate<CacheRecord<V>> isHardExpired;
    private final BiConsumer<K, Boolean> pruneTrackingState;
    private final BiFunction<K, Long, V> invokeLoaderAndWrite;
    private final Runnable markRefreshSuccess;
    private final Runnable markRefreshFail;

    EngineBackedCacheRefresh(Logger logger,
                             String cacheName,
                             CachePolicy policy,
                             RefreshExecutionOptions refreshOptions,
                             ScheduledExecutorService refreshScheduler,
                             ThreadPoolExecutor refreshExecutor,
                             RefreshPipeline refreshPipeline,
                             L1CacheStore<K, V> l1Store,
                             L2CacheStore<K, V> l2Store,
                             AtomicBoolean refreshStarted,
                             Set<K> trackedKeys,
                             Set<K> refreshingKeys,
                             com.github.benmanes.caffeine.cache.Cache<K, AtomicLong> readCounter,
                             Supplier<Function<K, V>> loaderSupplier,
                             Function<K, Optional<CacheRecord<V>>> readFromL1,
                             Function<K, Optional<CacheRecord<V>>> readFromL2,
                             Predicate<CacheRecord<V>> isHardExpired,
                             BiConsumer<K, Boolean> pruneTrackingState,
                             BiFunction<K, Long, V> invokeLoaderAndWrite,
                             Runnable markRefreshSuccess,
                             Runnable markRefreshFail) {
        this.logger = logger;
        this.cacheName = cacheName;
        this.policy = policy;
        this.refreshOptions = refreshOptions;
        this.refreshScheduler = refreshScheduler;
        this.refreshExecutor = refreshExecutor;
        this.refreshPipeline = refreshPipeline;
        this.l1Store = l1Store;
        this.l2Store = l2Store;
        this.refreshStarted = refreshStarted;
        this.trackedKeys = trackedKeys;
        this.refreshingKeys = refreshingKeys;
        this.readCounter = readCounter;
        this.loaderSupplier = loaderSupplier;
        this.readFromL1 = readFromL1;
        this.readFromL2 = readFromL2;
        this.isHardExpired = isHardExpired;
        this.pruneTrackingState = pruneTrackingState;
        this.invokeLoaderAndWrite = invokeLoaderAndWrite;
        this.markRefreshSuccess = markRefreshSuccess;
        this.markRefreshFail = markRefreshFail;
    }

    /**
     * 根据策略决定是否启动刷新调度器。
     * <p>
     * 当 autoRefresh 关闭，或配置为惰性启动时，不会在初始化阶段启动。
     */
    void startRefreshIfNeeded() {
        if (!policy.isAutoRefreshEnabled() || !refreshOptions.startOnInit()) {
            return;
        }
        ensureRefreshSchedulerStarted();
    }

    /**
     * 真正启动热点刷新扫描任务。
     * <p>
     * 调度器固定周期扫描 trackedKeys，但单个 key 的刷新仍会经过去重和重试逻辑。
     */
    void ensureRefreshSchedulerStarted() {
        if (!policy.isAutoRefreshEnabled()) {
            return;
        }
        if (!refreshStarted.compareAndSet(false, true)) {
            return;
        }
        long interval = Math.max(1L, policy.getRefreshIntervalSeconds());
        try {
            refreshScheduler.scheduleWithFixedDelay(this::refreshTrackedKeysSafely, interval, interval, TimeUnit.SECONDS);
        } catch (RejectedExecutionException e) {
            refreshStarted.set(false);
            logger.warn("刷新调度启动失败: cache={}, error={}", cacheName, e.getMessage());
        }
    }

    void refreshTrackedKeysSafely() {
        try {
            refreshTrackedKeys();
        } catch (Exception e) {
            logger.debug("刷新任务执行失败: cache={}, error={}", cacheName, e.getMessage());
        }
    }

    /**
     * 扫描热点 key 并在满足软过期条件时触发刷新。
     * <p>
     * 同时负责在扫描期清理已经硬过期或已不存在的追踪 key。
     */
    void refreshTrackedKeys() {
        if (!policy.isAutoRefreshEnabled() || trackedKeys.isEmpty()) {
            return;
        }

        for (K key : new ArrayList<>(trackedKeys)) {
            Optional<CacheRecord<V>> record = readFromL1.apply(key);
            if (record.isEmpty()) {
                record = readFromL2.apply(key);
            }
            if (record.isEmpty()) {
                pruneTrackingState.accept(key, true);
                continue;
            }
            CacheRecord<V> resolved = record.get();
            if (isHardExpired.test(resolved)) {
                if (l1Store != null) {
                    l1Store.evict(key);
                }
                if (l2Store != null) {
                    l2Store.evict(key);
                }
                pruneTrackingState.accept(key, true);
                continue;
            }
            Function<K, V> loader = loaderSupplier.get();
            if (loader != null
                    && refreshPipeline.shouldRefresh(policy.isAutoRefreshEnabled(), resolved, System.currentTimeMillis())) {
                triggerRefresh(key);
            }
        }
    }

    /**
     * 读路径发现软过期时触发按 key 刷新。
     * <p>
     * 这让热点 key 即使还未进入定时扫描，也能在首个读请求上被及时刷新。
     */
    void triggerRefreshIfSoftExpired(K key, CacheRecord<V> record) {
        ensureRefreshSchedulerStarted();
        if (refreshPipeline.shouldRefresh(policy.isAutoRefreshEnabled(), record, System.currentTimeMillis())) {
            triggerRefresh(key);
        }
    }

    /**
     * 提交单个 key 的异步刷新任务。
     * <p>
     * 同 key 在刷新完成前只允许存在一个在途任务。
     */
    void triggerRefresh(K key) {
        if (loaderSupplier.get() == null || key == null) {
            return;
        }
        if (!refreshingKeys.add(key)) {
            return;
        }
        long startNanos = System.nanoTime();
        try {
            runRefreshWithRetry(key, 0)
                    .whenComplete((value, throwable) -> {
                        refreshingKeys.remove(key);
                        if (throwable == null && value != null) {
                            markRefreshSuccess.run();
                            return;
                        }
                        markRefreshFail.run();
                        logger.warn("刷新失败: cache={}, key={}, error={}", cacheName, key,
                                throwable != null ? EngineBackedCacheCore.rootCauseMessage(throwable) : "loader返回null");
                        logger.debug("刷新耗时(ms): cache={}, key={}, duration={}", cacheName, key,
                                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos));
                    });
        } catch (Exception e) {
            refreshingKeys.remove(key);
            markRefreshFail.run();
            logger.warn("刷新提交失败: cache={}, key={}, error={}", cacheName, key, e.getMessage());
        }
    }

    /**
     * 执行带重试机制的刷新加载操作。
     * <p>
     * 使用 loader 从数据源加载最新值并写入缓存，失败时根据重试策略决定是否延迟重试。
     * 单次刷新操作会设置超时时间，避免长时间阻塞。
     * <p>
     * 线程池拒绝提交时返回 failedFuture，确保异常进入 handle 链路走重试逻辑，
     * 而非同步抛出导致调用方的 whenComplete 无法注册。
     *
     * @param key     需要刷新的缓存键
     * @param attempt 当前重试次数，从 0 开始
     * @return 包含加载结果的 CompletableFuture，成功时返回加载的值，失败或用尽重试后返回 null
     */
    CompletableFuture<V> runRefreshWithRetry(K key, int attempt) {
        if (loaderSupplier.get() == null) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<V> refreshAttempt;
        try {
            refreshAttempt = CompletableFuture.supplyAsync(
                            () -> invokeLoaderAndWrite.apply(key, policy.getHardTtlSeconds()),
                            refreshExecutor
                    )
                    .orTimeout(Math.max(1L, refreshOptions.refreshTimeoutSeconds()), TimeUnit.SECONDS);
        } catch (RejectedExecutionException e) {
            logger.debug("刷新任务提交被线程池拒绝: cache={}, key={}, attempt={}", cacheName, key, attempt);
            refreshAttempt = CompletableFuture.failedFuture(e);
        }

        return refreshAttempt.handle((value, throwable) -> {
            if (throwable == null) {
                return CompletableFuture.completedFuture(value);
            }
            logger.warn("刷新尝试失败: cache={}, key={}, attempt={}, error={}",
                    cacheName, key, attempt + 1, EngineBackedCacheCore.rootCauseMessage(throwable));
            if (EngineBackedCacheCore.isTimeout(throwable)) {
                logger.debug("刷新超时: cache={}, key={}, timeoutSeconds={}",
                        cacheName, key, refreshOptions.refreshTimeoutSeconds());
            }
            return retryLaterOrComplete(key, attempt);
        }).thenCompose(Function.identity());
    }

    /**
     * 决定是否延迟重试或直接结束刷新流程。
     * <p>
     * 当未达到最大重试次数时，创建延迟任务并在指定间隔后再次尝试刷新。
     * 若已达到最大重试次数，则返回已完成的 Future，结束重试流程。
     * 延迟重试使用调度器异步执行，避免阻塞当前线程。
     *
     * @param key    需要刷新的缓存键
     * @param attempt 当前重试次数
     * @return 延迟执行的 CompletableFuture，或已完成的 Future（达到最大重试次数时）
     */
    CompletableFuture<V> retryLaterOrComplete(K key, int attempt) {
        if (attempt >= Math.max(0, refreshOptions.maxRetries())) {
            return CompletableFuture.completedFuture(null);
        }

        CompletableFuture<V> delayed = new CompletableFuture<>();
        long delay = Math.max(0L, refreshOptions.retryIntervalSeconds());
        try {
            refreshScheduler.schedule(
                    () -> runRefreshWithRetry(key, attempt + 1).whenComplete((value, throwable) ->
                            EngineBackedCacheCore.completeFuture(delayed, value, throwable)
                    ),
                    delay,
                    TimeUnit.SECONDS
            );
        } catch (RejectedExecutionException e) {
            delayed.completeExceptionally(e);
        }
        return delayed;
    }

    /**
     * 在读路径上记录访问，并在阈值达到后把 key 纳入热点跟踪集合。
     */
    void trackKey(K key) {
        if (policy.isAutoRefreshEnabled() && key != null) {
            ensureRefreshSchedulerStarted();
            long accessCount = readCounter.asMap().computeIfAbsent(key, ignored -> new AtomicLong(0L)).incrementAndGet();
            if (refreshPipeline.shouldTrack(
                    policy.isAutoRefreshEnabled(),
                    accessCount,
                    policy.getHotKeyAccessThreshold(),
                    trackedKeys.size(),
                    policy.getMaxTrackedKeys())) {
                trackedKeys.add(key);
            }
        }
    }
}
