package io.github.cascade.exception;

/**
 * 组件异常
 */
public class ComponentException extends CascadeException {
    
    public ComponentException(String message) {
        super("COMPONENT_ERROR", message);
    }
    
    public ComponentException(String message, Throwable cause) {
        super("COMPONENT_ERROR", message, cause);
    }
}