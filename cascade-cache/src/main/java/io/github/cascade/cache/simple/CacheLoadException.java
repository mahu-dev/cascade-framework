package io.github.cascade.cache.simple;

import io.github.cascade.cache.exception.CacheException;

/**
 * 缓存加载异常
 * <p>
 * 用于表示缓存加载过程中发生的各种错误，
 * 统一继承CacheException以保持异常体系一致。
 *
 * @author cascade
 */
public class CacheLoadException extends CacheException {
    
    public CacheLoadException(String message) {
        super(message);
    }
    
    public CacheLoadException(String message, Throwable cause) {
        super(message, cause);
    }
    
    public CacheLoadException(String cacheName, String operation, String message, Throwable cause) {
        super(cacheName, operation, message, cause);
    }
}