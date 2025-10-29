package io.github.cascade.cache.exception;

/**
 * @author lionel lionelk@163.com
 * =============================
 * Date: 2025/10/29
 * Time: 17:17
 * =============================
 */

/**
 * 缓存连接异常
 * <p>
 * 当缓存系统连接失败时抛出此异常，如Redis连接失败、数据库连接问题等
 *
 * @author cascade
 */
public class CacheConnectionException extends CacheException {

    private final String connectionType;
    private final String endpoint;
    private final long timeoutMs;

    public CacheConnectionException(String message) {
        super(message);
        this.connectionType = null;
        this.endpoint = null;
        this.timeoutMs = -1;
    }

    public CacheConnectionException(String message, Throwable cause) {
        super(message, cause);
        this.connectionType = null;
        this.endpoint = null;
        this.timeoutMs = -1;
    }

    public CacheConnectionException(String cacheName, String message, Throwable cause) {
        super(cacheName, "连接", message, cause);
        this.connectionType = null;
        this.endpoint = null;
        this.timeoutMs = -1;
    }

    public CacheConnectionException(String connectionType, String endpoint, String message, Throwable cause) {
        super(null, null, buildMessage(connectionType, endpoint, message), cause);
        this.connectionType = connectionType;
        this.endpoint = endpoint;
        this.timeoutMs = -1;
    }

    public CacheConnectionException(String connectionType, String endpoint, long timeoutMs, String message, Throwable cause) {
        super(null, null, buildMessage(connectionType, endpoint, message), cause);
        this.connectionType = connectionType;
        this.endpoint = endpoint;
        this.timeoutMs = timeoutMs;
    }

    private static String buildMessage(String connectionType, String endpoint, String message) {
        StringBuilder sb = new StringBuilder();
        if (connectionType != null) {
            sb.append("连接类型[").append(connectionType).append("] ");
        }
        if (endpoint != null) {
            sb.append("端点[").append(endpoint).append("] ");
        }
        if (message != null) {
            sb.append(message);
        }
        return sb.toString();
    }

    public String getConnectionType() {
        return connectionType;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }

    public boolean isTimeout() {
        return timeoutMs > 0 && (getCause() instanceof java.util.concurrent.TimeoutException ||
               getCause() instanceof java.net.SocketTimeoutException);
    }
}