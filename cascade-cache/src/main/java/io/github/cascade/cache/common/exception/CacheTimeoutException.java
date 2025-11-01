package io.github.cascade.cache.common.exception;

/**
 * @author lionel lionelk@163.com
 * =============================
 * Date: 2025/10/29
 * Time: 17:19
 * =============================
 */

/**
 * 缓存超时异常
 * <p>
 * 当缓存操作超时时抛出此异常，包括读取超时、写入超时、连接超时等
 *
 * @author cascade
 */
public class CacheTimeoutException extends CacheException {

    private final String operation;
    private final long timeoutMs;
    private final long actualTimeMs;

    public CacheTimeoutException(String message) {
        super(message);
        this.operation = null;
        this.timeoutMs = -1;
        this.actualTimeMs = -1;
    }

    public CacheTimeoutException(String message, Throwable cause) {
        super(message, cause);
        this.operation = null;
        this.timeoutMs = -1;
        this.actualTimeMs = -1;
    }

    public CacheTimeoutException(String cacheName, String operation, String message) {
        super(cacheName, operation, message + " (超时)");
        this.operation = operation;
        this.timeoutMs = -1;
        this.actualTimeMs = -1;
    }

    public CacheTimeoutException(String operation, long timeoutMs, long actualTimeMs, String message, Throwable cause) {
        super(null, null, buildMessage(operation, timeoutMs, actualTimeMs, message), cause);
        this.operation = operation;
        this.timeoutMs = timeoutMs;
        this.actualTimeMs = actualTimeMs;
    }

    private static String buildMessage(String operation, long timeoutMs, long actualTimeMs, String message) {
        StringBuilder sb = new StringBuilder();
        if (operation != null) {
            sb.append("操作[").append(operation).append("] ");
        }
        if (timeoutMs > 0) {
            sb.append("超时时间[").append(timeoutMs).append("ms] ");
        }
        if (actualTimeMs > 0) {
            sb.append("实际用时[").append(actualTimeMs).append("ms] ");
        }
        if (message != null) {
            sb.append(message);
        }
        return sb.toString();
    }

    public String getOperation() {
        return operation;
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }

    public long getActualTimeMs() {
        return actualTimeMs;
    }

    public boolean isReadTimeout() {
        return "read".equalsIgnoreCase(operation) || "get".equalsIgnoreCase(operation);
    }

    public boolean isWriteTimeout() {
        return "write".equalsIgnoreCase(operation) || "put".equalsIgnoreCase(operation);
    }

    public boolean isConnectTimeout() {
        return "connect".equalsIgnoreCase(operation) || "connection".equalsIgnoreCase(operation);
    }
}