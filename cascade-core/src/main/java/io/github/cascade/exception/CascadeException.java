package io.github.cascade.exception;

/**
 * Cascade框架基础异常
 */
public class CascadeException extends RuntimeException {
    
    private final String errorCode;
    
    public CascadeException(String message) {
        super(message);
        this.errorCode = null;
    }
    
    public CascadeException(String message, Throwable cause) {
        super(message, cause);
        this.errorCode = null;
    }
    
    public CascadeException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
    
    public CascadeException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }
    
    public String getErrorCode() {
        return errorCode;
    }
}