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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * EngineBackedCache 刷新与热点追踪逻辑。
 */
final class EngineBackedCacheRefresh<K, V> {

    private final Logger logger;
    private final String cacheName;
    private final CachePolicy policy;
    private final RefreshExecutionOptions refreshOptions;
    private final ScheduledExecutorService refreshScheduler;
    private final ThreadPoolExecutor refreshExecutor;
    private final RefreshPipeline refreshPipeline;
    private final L1CacheStore<K, V> l1Store;
    private final L2CacheStore<K, V> l2Store;
    private final AtomicBoolean refreshStarted;
    private final Set<K> trackedKeys;
    private final Set<K> refreshingKeys;
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

    void startRefreshIfNeeded() {
        if (!policy.isAutoRefreshEnabled() || !refreshOptions.startOnInit()) {
            return;
        }
        ensureRefreshSchedulerStarted();
    }

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

    void triggerRefreshIfSoftExpired(K key, CacheRecord<V> record) {
        ensureRefreshSchedulerStarted();
        if (refreshPipeline.shouldRefresh(policy.isAutoRefreshEnabled(), record, System.currentTimeMillis())) {
            triggerRefresh(key);
        }
    }

    void triggerRefresh(K key) {
        if (loaderSupplier.get() == null || key == null) {
            return;
        }
        if (!refreshingKeys.add(key)) {
            return;
        }
        long startNanos = System.nanoTime();
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
    }

    CompletableFuture<V> runRefreshWithRetry(K key, int attempt) {
        if (loaderSupplier.get() == null) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<V> refreshAttempt = CompletableFuture.supplyAsync(
                        () -> invokeLoaderAndWrite.apply(key, policy.getHardTtlSeconds()),
                        refreshExecutor
                )
                .orTimeout(Math.max(1L, refreshOptions.refreshTimeoutSeconds()), TimeUnit.SECONDS);

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
        }).thenCompose(java.util.function.Function.identity());
    }

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
