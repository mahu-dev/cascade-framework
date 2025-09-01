package io.github.cascade.cache.exception;

/**
 * 缓存操作异常基类
 * 
 * @author cascade
 */
public class CacheException extends RuntimeException {
    
    private final String cacheName;
    private final String operation;
    
    public CacheException(String message) {
        super(message);
        this.cacheName = null;
        this.operation = null;
    }
    
    public CacheException(String message, Throwable cause) {
        super(message, cause);
        this.cacheName = null;
        this.operation = null;
    }
    
    public CacheException(String cacheName, String operation, String message, Throwable cause) {
        super(buildMessage(cacheName, operation, message), cause);
        this.cacheName = cacheName;
        this.operation = operation;
    }
    
    private static String buildMessage(String cacheName, String operation, String message) {
        StringBuilder sb = new StringBuilder();
        if (cacheName != null) {
            sb.append("缓存[").append(cacheName).append("] ");
        }
        if (operation != null) {
            sb.append("操作[").append(operation).append("] ");
        }
        if (message != null) {
            sb.append(message);
        }
        return sb.toString();
    }
    
    public String getCacheName() {
        return cacheName;
    }
    
    public String getOperation() {
        return operation;
    }
}