package io.github.cascade.cache.serialization;

/**
 * 序列化异常
 * 在序列化或反序列化过程中发生错误时抛出
 */
public class SerializationException extends Exception {
    
    public SerializationException(String message) {
        super(message);
    }
    
    public SerializationException(String message, Throwable cause) {
        super(message, cause);
    }
    
    public SerializationException(Throwable cause) {
        super(cause);
    }
}