package io.github.cascade.cache.common.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * @author lionel lionelk@163.com
 * =============================
 * Date: 2025/10/29
 * Time: 17:20
 * =============================
 */

/**
 * 缓存异常处理器
 * <p>
 * 提供统一的异常处理策略，包括异常分类、重试决策、日志记录等
 *
 * @author cascade
 */
public final class CacheExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(CacheExceptionHandler.class);

    private CacheExceptionHandler() {
        // 工具类不允许实例化
    }

    // ==================== 异常分类方法 ====================

    /**
     * 判断异常是否可重试
     */
    public static boolean isRetryable(Throwable throwable) {
        if (throwable == null) {
            return false;
        }

        // 连接异常和超时异常通常可重试
        return throwable instanceof CacheConnectionException ||
               throwable instanceof CacheTimeoutException ||
               (throwable instanceof CacheLoadException &&
                ((CacheLoadException) throwable).isTimeout()) ||
               isRetryableCause(throwable.getCause());
    }

    /**
     * 判断异常是否为客户端错误（不可重试）
     */
    public static boolean isClientError(Throwable throwable) {
        if (throwable == null) {
            return false;
        }

        // 序列化异常通常是客户端错误
        return throwable instanceof CacheSerializationException ||
               isClientErrorCause(throwable.getCause());
    }

    /**
     * 判断异常是否为服务器端错误（可能可重试）
     */
    public static boolean isServerError(Throwable throwable) {
        if (throwable == null) {
            return false;
        }

        // 配置异常通常是服务器端错误
        return throwable instanceof CacheConfigurationException ||
               isServerErrorCause(throwable.getCause());
    }

    // ==================== 异常处理方法 ====================

    /**
     * 安全执行操作，异常时返回默认值
     */
    public static <T> T safeExecute(Supplier<T> operation, T defaultValue) {
        try {
            return operation.get();
        } catch (CacheException e) {
            logException(e);
            return defaultValue;
        } catch (Exception e) {
            logUnexpectedException(e);
            return defaultValue;
        }
    }

    /**
     * 安全执行函数式操作，异常时返回默认值
     */
    public static <T, R> R safeExecute(Function<T, R> function, T input, R defaultValue) {
        try {
            return function.apply(input);
        } catch (CacheException e) {
            logException(e);
            return defaultValue;
        } catch (Exception e) {
            logUnexpectedException(e);
            return defaultValue;
        }
    }

    /**
     * 包装异常为CacheException
     */
    public static CacheException wrapException(String operation, String cacheName, Throwable cause) {
        if (cause instanceof CacheException) {
            return (CacheException) cause;
        }

        String message = String.format("操作失败: operation=%s, cache=%s", operation, cacheName);
        return new CacheException(cacheName, operation, message, cause);
    }

    /**
     * 包装运行时异常为CacheException
     */
    public static CacheLoadException wrapLoadException(String cacheName, Object key, Throwable cause) {
        if (cause instanceof CacheLoadException) {
            return (CacheLoadException) cause;
        }

        String message = "数据加载失败";
        return new CacheLoadException(key, message, cause);
    }

    // ==================== 重试相关方法 ====================

    /**
     * 创建重试函数
     */
    public static <T> Supplier<T> withRetry(Supplier<T> operation, int maxRetries, long baseDelayMs) {
        return () -> {
            CacheException lastException = null;
            for (int i = 0; i <= maxRetries; i++) {
                try {
                    return operation.get();
                } catch (CacheException e) {
                    lastException = e;
                    if (i < maxRetries && isRetryable(e)) {
                        long delayMs = baseDelayMs * (1L << i); // 指数退避
                        LOGGER.warn("操作失败，准备重试: attempt={}, delay={}ms, error={}",
                            i + 1, delayMs, e.getMessage());
                        try {
                            Thread.sleep(delayMs);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new CacheException("重试被中断", ie);
                        }
                    } else {
                        break;
                    }
                }
            }
            throw lastException;
        };
    }

    // ==================== 私有方法 ====================

    private static boolean isRetryableCause(Throwable cause) {
        return cause instanceof java.net.SocketTimeoutException ||
               cause instanceof java.util.concurrent.TimeoutException ||
               cause instanceof java.net.ConnectException ||
               (cause != null && isRetryable(cause.getCause()));
    }

    private static boolean isClientErrorCause(Throwable cause) {
        return cause instanceof IllegalArgumentException ||
               cause instanceof ClassCastException ||
               cause instanceof java.io.InvalidClassException ||
               (cause != null && isClientError(cause.getCause()));
    }

    private static boolean isServerErrorCause(Throwable cause) {
        return cause instanceof IllegalStateException ||
               cause instanceof UnsupportedOperationException ||
               (cause != null && isServerError(cause.getCause()));
    }

    private static void logException(CacheException e) {
        if (e instanceof CacheConnectionException) {
            LOGGER.warn("缓存连接异常: {}", e.getMessage());
        } else if (e instanceof CacheTimeoutException) {
            LOGGER.warn("缓存超时异常: {}", e.getMessage());
        } else if (e instanceof CacheLoadException) {
            LOGGER.warn("缓存加载异常: {}", e.getMessage());
        } else if (e instanceof CacheSerializationException) {
            LOGGER.error("缓存序列化异常: {}", e.getMessage());
        } else {
            LOGGER.warn("缓存操作异常: {}", e.getMessage());
        }
    }

    private static void logUnexpectedException(Exception e) {
        LOGGER.error("未预期的异常: {}", e.getMessage(), e);
    }
}