package io.github.cascade.cache.exception;

/**
 * 缓存连接异常 - 可重试
 * 
 * @author cascade
 */
public class CacheConnectionException extends CacheException {
    
    public CacheConnectionException(String cacheName, String message, Throwable cause) {
        super(cacheName, "连接", message, cause);
    }
}