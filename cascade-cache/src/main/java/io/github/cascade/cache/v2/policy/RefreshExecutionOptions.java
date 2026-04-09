package io.github.cascade.cache.v2.policy;

/**
 * 自动刷新执行参数。
 * <p>
 * 语义约定：
 * - allowConcurrentRefresh=false: 多 key 刷新串行执行（同 key 仍去重）
 * - allowConcurrentRefresh=true: 多 key 刷新并发执行，受 threadPoolSize 限制
 */
public record RefreshExecutionOptions(
        int threadPoolSize,
        int queueCapacity,
        boolean allowConcurrentRefresh,
        long refreshTimeoutSeconds,
        int maxRetries,
        long retryIntervalSeconds,
        boolean startOnInit,
        long shutdownTimeoutSeconds
) {

    public static final int DEFAULT_THREAD_POOL_SIZE = 2;
    public static final int DEFAULT_QUEUE_CAPACITY = 1000;
    public static final boolean DEFAULT_ALLOW_CONCURRENT_REFRESH = false;
    public static final long DEFAULT_REFRESH_TIMEOUT_SECONDS = 30L;
    public static final int DEFAULT_MAX_RETRIES = 3;
    public static final long DEFAULT_RETRY_INTERVAL_SECONDS = 5L;
    public static final boolean DEFAULT_START_ON_INIT = true;
    public static final long DEFAULT_SHUTDOWN_TIMEOUT_SECONDS = 10L;

    public RefreshExecutionOptions {
        if (threadPoolSize <= 0) {
            throw new IllegalArgumentException("threadPoolSize必须大于0");
        }
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException("queueCapacity必须大于0");
        }
        if (refreshTimeoutSeconds <= 0) {
            throw new IllegalArgumentException("refreshTimeoutSeconds必须大于0");
        }
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries不能小于0");
        }
        if (retryIntervalSeconds < 0) {
            throw new IllegalArgumentException("retryIntervalSeconds不能小于0");
        }
        if (shutdownTimeoutSeconds < 0) {
            throw new IllegalArgumentException("shutdownTimeoutSeconds不能小于0");
        }
    }

    public static RefreshExecutionOptions defaults() {
        return new RefreshExecutionOptions(
                DEFAULT_THREAD_POOL_SIZE,
                DEFAULT_QUEUE_CAPACITY,
                DEFAULT_ALLOW_CONCURRENT_REFRESH,
                DEFAULT_REFRESH_TIMEOUT_SECONDS,
                DEFAULT_MAX_RETRIES,
                DEFAULT_RETRY_INTERVAL_SECONDS,
                DEFAULT_START_ON_INIT,
                DEFAULT_SHUTDOWN_TIMEOUT_SECONDS
        );
    }

    public int effectiveThreadPoolSize() {
        return allowConcurrentRefresh ? threadPoolSize : 1;
    }
}
