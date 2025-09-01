package io.github.cascade.cache.exception;

/**
 * 缓存超时异常 - 可重试
 * 
 * @author cascade
 */
public class CacheTimeoutException extends CacheException {
    
    public CacheTimeoutException(String cacheName, String operation, String message) {
        super(cacheName, operation, message + " (超时)", null);
    }
}