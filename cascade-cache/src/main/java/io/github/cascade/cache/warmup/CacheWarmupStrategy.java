package io.github.cascade.cache.warmup;

import io.github.cascade.cache.api.Cache;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 缓存预热策略接口
 * 定义缓存预热的标准操作
 *
 * @param <K> 键类型
 * @param <V> 值类型
 * @author Cascade Framework
 */
public interface CacheWarmupStrategy<K, V> {

    /**
     * 执行缓存预热
     *
     * @param cache 目标缓存
     * @return 预热结果
     */
    WarmupResult warmup(Cache<K, V> cache);

    /**
     * 异步执行缓存预热
     *
     * @param cache 目标缓存
     * @param executor 执行器
     * @return 异步预热结果
     */
    default CompletableFuture<WarmupResult> warmupAsync(Cache<K, V> cache, Executor executor) {
        return CompletableFuture.supplyAsync(() -> warmup(cache), executor);
    }

    /**
     * 异步执行缓存预热（使用默认执行器）
     *
     * @param cache 目标缓存
     * @return 异步预热结果
     */
    default CompletableFuture<WarmupResult> warmupAsync(Cache<K, V> cache) {
        return CompletableFuture.supplyAsync(() -> warmup(cache));
    }

    /**
     * 获取预热策略名称
     *
     * @return 策略名称
     */
    String getStrategyName();

    /**
     * 获取预热配置
     *
     * @return 预热配置
     */
    WarmupConfig getWarmupConfig();

    /**
     * 是否支持增量预热
     *
     * @return true表示支持增量预热
     */
    default boolean supportsIncrementalWarmup() {
        return false;
    }

    /**
     * 是否支持并行预热
     *
     * @return true表示支持并行预热
     */
    default boolean supportsParallelWarmup() {
        return true;
    }

    /**
     * 获取预热优先级
     * 数值越小优先级越高
     *
     * @return 优先级
     */
    default int getPriority() {
        return 100;
    }

    /**
     * 预热结果
     */
    class WarmupResult {
        private final boolean success;
        private final long loadedCount;
        private final long failedCount;
        private final Duration duration;
        private final String message;
        private final Throwable error;

        public WarmupResult(boolean success, long loadedCount, long failedCount, 
                           Duration duration, String message, Throwable error) {
            this.success = success;
            this.loadedCount = loadedCount;
            this.failedCount = failedCount;
            this.duration = duration;
            this.message = message;
            this.error = error;
        }

        public static WarmupResult success(long loadedCount, Duration duration) {
            return new WarmupResult(true, loadedCount, 0, duration, "Warmup completed successfully", null);
        }

        public static WarmupResult success(long loadedCount, long failedCount, Duration duration, String message) {
            return new WarmupResult(true, loadedCount, failedCount, duration, message, null);
        }

        public static WarmupResult failure(Duration duration, String message, Throwable error) {
            return new WarmupResult(false, 0, 0, duration, message, error);
        }

        public boolean isSuccess() { return success; }
        public long getLoadedCount() { return loadedCount; }
        public long getFailedCount() { return failedCount; }
        public Duration getDuration() { return duration; }
        public String getMessage() { return message; }
        public Throwable getError() { return error; }

        @Override
        public String toString() {
            return String.format("WarmupResult{success=%s, loaded=%d, failed=%d, duration=%s, message='%s'}",
                success, loadedCount, failedCount, duration, message);
        }
    }

    /**
     * 预热配置
     */
    class WarmupConfig {
        private final int batchSize;
        private final Duration timeout;
        private final int maxRetries;
        private final Duration retryDelay;
        private final boolean failFast;
        private final int parallelism;

        public WarmupConfig(int batchSize, Duration timeout, int maxRetries, 
                           Duration retryDelay, boolean failFast, int parallelism) {
            this.batchSize = batchSize;
            this.timeout = timeout;
            this.maxRetries = maxRetries;
            this.retryDelay = retryDelay;
            this.failFast = failFast;
            this.parallelism = parallelism;
        }

        public static WarmupConfig defaultConfig() {
            return new WarmupConfig(
                100,                        // batchSize
                Duration.ofMinutes(10),     // timeout
                3,                          // maxRetries
                Duration.ofSeconds(1),      // retryDelay
                false,                      // failFast
                Runtime.getRuntime().availableProcessors() // parallelism
            );
        }

        public static Builder builder() {
            return new Builder();
        }

        public int getBatchSize() { return batchSize; }
        public Duration getTimeout() { return timeout; }
        public int getMaxRetries() { return maxRetries; }
        public Duration getRetryDelay() { return retryDelay; }
        public boolean isFailFast() { return failFast; }
        public int getParallelism() { return parallelism; }

        public static class Builder {
            private int batchSize = 100;
            private Duration timeout = Duration.ofMinutes(10);
            private int maxRetries = 3;
            private Duration retryDelay = Duration.ofSeconds(1);
            private boolean failFast = false;
            private int parallelism = Runtime.getRuntime().availableProcessors();

            public Builder batchSize(int batchSize) {
                this.batchSize = batchSize;
                return this;
            }

            public Builder timeout(Duration timeout) {
                this.timeout = timeout;
                return this;
            }

            public Builder maxRetries(int maxRetries) {
                this.maxRetries = maxRetries;
                return this;
            }

            public Builder retryDelay(Duration retryDelay) {
                this.retryDelay = retryDelay;
                return this;
            }

            public Builder failFast(boolean failFast) {
                this.failFast = failFast;
                return this;
            }

            public Builder parallelism(int parallelism) {
                this.parallelism = parallelism;
                return this;
            }

            public WarmupConfig build() {
                return new WarmupConfig(batchSize, timeout, maxRetries, retryDelay, failFast, parallelism);
            }
        }
    }
}