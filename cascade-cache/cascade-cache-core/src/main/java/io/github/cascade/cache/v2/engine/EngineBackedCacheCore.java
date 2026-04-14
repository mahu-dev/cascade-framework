package io.github.cascade.cache.v2.engine;

import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.cascade.cache.v2.policy.RefreshExecutionOptions;
import org.slf4j.Logger;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * EngineBackedCache 基础运行时能力（线程池/异步降级/本地计数缓存）。
 */
final class EngineBackedCacheCore {

    private EngineBackedCacheCore() {
    }

    static ExecutorService createBoundedAsyncExecutor(String cacheName,
                                                      AtomicInteger asyncThreadCounter,
                                                      int poolSize,
                                                      int queueCapacity) {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                poolSize,
                poolSize,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(queueCapacity),
                runnable -> {
                    Thread thread = new Thread(
                            runnable,
                            "cascade-async-" + cacheName + "-" + asyncThreadCounter.incrementAndGet()
                    );
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
        executor.prestartAllCoreThreads();
        return executor;
    }

    static ThreadPoolExecutor createRefreshExecutor(String cacheName,
                                                    RefreshExecutionOptions options,
                                                    AtomicInteger refreshThreadCounter) {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                options.effectiveThreadPoolSize(),
                options.effectiveThreadPoolSize(),
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(options.queueCapacity()),
                runnable -> {
                    Thread thread = new Thread(
                            runnable,
                            "cascade-refresh-worker-" + cacheName + "-" + refreshThreadCounter.incrementAndGet()
                    );
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
        executor.prestartAllCoreThreads();
        return executor;
    }

    static <K> com.github.benmanes.caffeine.cache.Cache<K, AtomicLong> createReadCounterCache(int maxTrackedKeys,
                                                                                                int maxFactor,
                                                                                                long expireAfterAccessMinutes) {
        long maxSize = Math.max(1_000L, (long) Math.max(1, maxTrackedKeys) * maxFactor);
        return Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterAccess(expireAfterAccessMinutes, TimeUnit.MINUTES)
                .build();
    }

    static <K> com.github.benmanes.caffeine.cache.Cache<K, Long> createLocalVersionCache(int maxTrackedKeys,
                                                                                           long minMaxSize,
                                                                                           int maxFactor,
                                                                                           long expireAfterAccessMinutes) {
        long maxSize = Math.max(
                minMaxSize,
                (long) Math.max(1, maxTrackedKeys) * maxFactor
        );
        return Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterAccess(expireAfterAccessMinutes, TimeUnit.MINUTES)
                .build();
    }

    static <T> CompletableFuture<T> supplyAsyncSafely(ExecutorService asyncExecutor,
                                                       Logger logger,
                                                       String cacheName,
                                                       Supplier<T> supplier) {
        try {
            return CompletableFuture.supplyAsync(supplier, asyncExecutor);
        } catch (RejectedExecutionException e) {
            logger.debug("异步任务提交被拒绝，降级为同步执行: cache={}, error={}", cacheName, e.getMessage());
            try {
                return CompletableFuture.completedFuture(supplier.get());
            } catch (Exception ex) {
                return CompletableFuture.failedFuture(ex);
            }
        }
    }

    static CompletableFuture<Void> runAsyncSafely(ExecutorService asyncExecutor,
                                                   Logger logger,
                                                   String cacheName,
                                                   Runnable runnable) {
        return supplyAsyncSafely(asyncExecutor, logger, cacheName, () -> {
            runnable.run();
            return null;
        });
    }

    static void shutdownExecutorGracefully(ExecutorService executor, long timeoutSeconds) {
        if (executor == null) {
            return;
        }
        long waitSeconds = Math.max(0L, timeoutSeconds);
        try {
            executor.shutdown();
            if (waitSeconds == 0L || !executor.awaitTermination(waitSeconds, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        } catch (Exception ignored) {
            executor.shutdownNow();
        }
    }

    static <T> void completeFuture(CompletableFuture<T> target, T value, Throwable throwable) {
        if (throwable == null) {
            target.complete(value);
        } else {
            target.completeExceptionally(throwable);
        }
    }

    static boolean isTimeout(Throwable throwable) {
        Throwable root = rootCause(throwable);
        return root instanceof TimeoutException;
    }

    static String rootCauseMessage(Throwable throwable) {
        Throwable root = rootCause(throwable);
        return root == null ? "unknown" : root.getMessage();
    }

    static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current != null && current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }
}
