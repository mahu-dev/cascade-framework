package io.github.cascade.cache.exception;

/**
 * 缓存致命异常
 * 用于FATAL级别的异常处理，通常表示系统级错误
 * 
 * @author cascade
 */
public class CacheFatalException extends RuntimeException {

    private final String operation;

    public CacheFatalException(String operation, Throwable cause) {
        super(String.format("Fatal error in cache operation '%s': %s", operation, cause.getMessage()), cause);
        this.operation = operation;
    }

    public CacheFatalException(String operation, String message) {
        super(String.format("Fatal error in cache operation '%s': %s", operation, message));
        this.operation = operation;
    }

    public CacheFatalException(String operation, String message, Throwable cause) {
        super(String.format("Fatal error in cache operation '%s': %s", operation, message), cause);
        this.operation = operation;
    }

    public String getOperation() {
        return operation;
    }
}