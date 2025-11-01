package io.github.cascade.cache.common.exception;

/**
 * @author lionel lionelk@163.com
 * =============================
 * Date: 2025/10/29
 * Time: 17:16
 * =============================
 */

/**
 * 缓存加载异常
 * <p>
 * 当从数据源加载缓存数据时发生错误抛出此异常
 *
 * @author cascade
 */
public class CacheLoadException extends CacheException {

    private final Object key;
    private final long loadTimeMs;

    public CacheLoadException(String message) {
        super(message);
        this.key = null;
        this.loadTimeMs = -1;
    }

    public CacheLoadException(String message, Throwable cause) {
        super(message, cause);
        this.key = null;
        this.loadTimeMs = -1;
    }

    public CacheLoadException(Object key, String message, Throwable cause) {
        super(null, null, buildMessage(key, message), cause);
        this.key = key;
        this.loadTimeMs = -1;
    }

    public CacheLoadException(Object key, long loadTimeMs, String message, Throwable cause) {
        super(null, null, buildMessage(key, message), cause);
        this.key = key;
        this.loadTimeMs = loadTimeMs;
    }

    private static String buildMessage(Object key, String message) {
        StringBuilder sb = new StringBuilder();
        if (key != null) {
            sb.append("键[").append(key).append("] ");
        }
        if (message != null) {
            sb.append(message);
        }
        return sb.toString();
    }

    public Object getKey() {
        return key;
    }

    public long getLoadTimeMs() {
        return loadTimeMs;
    }

    public boolean isTimeout() {
        return loadTimeMs > 0 && getCause() instanceof java.util.concurrent.TimeoutException;
    }
}