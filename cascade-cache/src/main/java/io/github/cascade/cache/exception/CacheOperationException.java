package io.github.cascade.cache.exception;

/**
 * 缓存操作异常
 * 用于ERROR级别的异常处理
 * 
 * @author cascade
 */
public class CacheOperationException extends RuntimeException {

    private final String operation;

    public CacheOperationException(String operation, Throwable cause) {
        super(String.format("Cache operation '%s' failed: %s", operation, cause.getMessage()), cause);
        this.operation = operation;
    }

    public CacheOperationException(String operation, String message) {
        super(String.format("Cache operation '%s' failed: %s", operation, message));
        this.operation = operation;
    }

    public CacheOperationException(String operation, String message, Throwable cause) {
        super(String.format("Cache operation '%s' failed: %s", operation, message), cause);
        this.operation = operation;
    }

    public String getOperation() {
        return operation;
    }
}