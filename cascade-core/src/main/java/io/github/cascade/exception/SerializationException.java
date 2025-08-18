package io.github.cascade.exception;

/**
 * 序列化异常
 */
public class SerializationException extends CascadeException {
    
    public SerializationException(String message) {
        super("SERIALIZATION_ERROR", message);
    }
    
    public SerializationException(String message, Throwable cause) {
        super("SERIALIZATION_ERROR", message, cause);
    }
}