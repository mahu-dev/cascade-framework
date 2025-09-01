package io.github.cascade.cache.exception;

/**
 * 缓存序列化异常 - 不可重试
 * 
 * @author cascade
 */
public class CacheSerializationException extends CacheException {
    
    public CacheSerializationException(String cacheName, String message, Throwable cause) {
        super(cacheName, "序列化", message, cause);
    }
}