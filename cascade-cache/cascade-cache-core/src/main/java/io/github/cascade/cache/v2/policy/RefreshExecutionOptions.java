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

    /** 默认刷新线程数。 */
    public static final int DEFAULT_THREAD_POOL_SIZE = 2;
    /** 默认刷新排队容量。 */
    public static final int DEFAULT_QUEUE_CAPACITY = 1000;
    /** 默认串行执行多 key 刷新，降低下游加载压力。 */
    public static final boolean DEFAULT_ALLOW_CONCURRENT_REFRESH = false;
    /** 单次刷新默认超时时间。 */
    public static final long DEFAULT_REFRESH_TIMEOUT_SECONDS = 30L;
    /** 单次刷新失败后的默认最大重试次数。 */
    public static final int DEFAULT_MAX_RETRIES = 3;
    /** 刷新失败后的默认重试间隔。 */
    public static final long DEFAULT_RETRY_INTERVAL_SECONDS = 5L;
    /** 默认在缓存初始化时启动刷新调度。 */
    public static final boolean DEFAULT_START_ON_INIT = true;
    /** 默认关闭等待时间，避免应用停机时无限阻塞。 */
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

    /**
     * 返回默认刷新执行参数。
     * <p>
     * 注解式和编程式都会共享这组默认值。
     */
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

    /**
     * 计算实际生效的刷新并发度。
     * <p>
     * 当 {@code allowConcurrentRefresh=false} 时，无论线程池配置多大，
     * 同一缓存实例的多 key 刷新都会退化为串行执行。
     */
    public int effectiveThreadPoolSize() {
        return allowConcurrentRefresh ? threadPoolSize : 1;
    }
}
