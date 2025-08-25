package io.github.cascade.cache.exception;

import lombok.extern.slf4j.Slf4j;

import java.util.function.Supplier;

/**
 * 缓存异常处理器
 * 提供分层异常处理策略，根据错误严重程度采用不同的处理方式
 *
 * @author cascade
 */
@Slf4j
public class CacheExceptionHandler {

    /**
     * 错误严重程度枚举
     */
    public enum ErrorSeverity {
        /**
         * 忽略级别 - 仅记录调试日志，返回默认值
         */
        IGNORE,
        
        /**
         * 警告级别 - 记录警告日志，返回默认值
         */
        WARN,
        
        /**
         * 错误级别 - 记录错误日志，抛出CacheOperationException
         */
        ERROR,
        
        /**
         * 致命级别 - 记录致命错误日志，抛出CacheFatalException
         */
        FATAL
    }

    /**
     * 单例实例
     */
    private static final CacheExceptionHandler INSTANCE = new CacheExceptionHandler();

    /**
     * 获取单例实例
     */
    public static CacheExceptionHandler getInstance() {
        return INSTANCE;
    }

    /**
     * 处理异常的核心方法
     *
     * @param operation     操作名称
     * @param supplier      执行的操作
     * @param severity      错误严重程度
     * @param fallbackValue 失败时的默认返回值
     * @param <T>           返回值类型
     * @return 操作结果或默认值
     */
    public <T> T handleException(String operation, Supplier<T> supplier, 
                                ErrorSeverity severity, T fallbackValue) {
        try {
            return supplier.get();
        } catch (Exception e) {
            return handleExceptionInternal(operation, e, severity, fallbackValue);
        }
    }

    /**
     * 处理异常的核心方法（无返回值）
     *
     * @param operation 操作名称
     * @param runnable  执行的操作
     * @param severity  错误严重程度
     */
    public void handleException(String operation, Runnable runnable, ErrorSeverity severity) {
        try {
            runnable.run();
        } catch (Exception e) {
            handleExceptionInternal(operation, e, severity, null);
        }
    }

    /**
     * 直接处理已知异常
     *
     * @param operation     操作名称
     * @param exception     已知异常
     * @param severity      错误严重程度
     * @param fallbackValue 失败时的默认返回值
     * @param <T>           返回值类型
     * @return 默认值（如果不抛出异常）
     */
    public <T> T handleKnownException(String operation, Exception exception, 
                                     ErrorSeverity severity, T fallbackValue) {
        return handleExceptionInternal(operation, exception, severity, fallbackValue);
    }

    /**
     * 内部异常处理逻辑
     */
    private <T> T handleExceptionInternal(String operation, Exception e,
                                         ErrorSeverity severity, T fallbackValue) {
        return switch (severity) {
            case IGNORE -> {
                log.debug("Cache operation '{}' failed (ignored): {}", operation, e.getMessage());
                yield fallbackValue;
            }
            case WARN -> {
                log.warn("Cache operation '{}' failed: {}", operation, e.getMessage());
                yield fallbackValue;
            }
            case ERROR -> {
                log.error("Cache operation '{}' failed: {}", operation, e.getMessage(), e);
                throw new CacheOperationException(operation, e);
            }
            case FATAL -> {
                log.error("Fatal error in cache operation '{}'", operation, e);
                throw new CacheFatalException(operation, e);
            }
            default -> throw new IllegalArgumentException("Unknown error severity: " + severity);
        };
    }

    /**
     * 便捷方法：忽略级别异常处理
     */
    public <T> T ignore(String operation, Supplier<T> supplier, T fallbackValue) {
        return handleException(operation, supplier, ErrorSeverity.IGNORE, fallbackValue);
    }

    /**
     * 便捷方法：警告级别异常处理
     */
    public <T> T warn(String operation, Supplier<T> supplier, T fallbackValue) {
        return handleException(operation, supplier, ErrorSeverity.WARN, fallbackValue);
    }

    /**
     * 便捷方法：错误级别异常处理
     */
    public <T> T error(String operation, Supplier<T> supplier) {
        return handleException(operation, supplier, ErrorSeverity.ERROR, null);
    }

    /**
     * 便捷方法：致命级别异常处理
     */
    public <T> T fatal(String operation, Supplier<T> supplier) {
        return handleException(operation, supplier, ErrorSeverity.FATAL, null);
    }

    /**
     * 便捷方法：忽略级别异常处理（无返回值）
     */
    public void ignoreVoid(String operation, Runnable runnable) {
        handleException(operation, runnable, ErrorSeverity.IGNORE);
    }

    /**
     * 便捷方法：警告级别异常处理（无返回值）
     */
    public void warnVoid(String operation, Runnable runnable) {
        handleException(operation, runnable, ErrorSeverity.WARN);
    }

    /**
     * 便捷方法：错误级别异常处理（无返回值）
     */
    public void errorVoid(String operation, Runnable runnable) {
        handleException(operation, runnable, ErrorSeverity.ERROR);
    }

    /**
     * 便捷方法：致命级别异常处理（无返回值）
     */
    public void fatalVoid(String operation, Runnable runnable) {
        handleException(operation, runnable, ErrorSeverity.FATAL);
    }
}