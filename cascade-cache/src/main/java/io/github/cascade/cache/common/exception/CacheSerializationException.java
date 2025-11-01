package io.github.cascade.cache.common.exception;

/**
 * @author lionel lionelk@163.com
 * =============================
 * Date: 2025/10/29
 * Time: 17:18
 * =============================
 */

/**
 * 缓存序列化异常
 * <p>
 * 当缓存数据的序列化或反序列化过程中发生错误时抛出此异常
 * 通常表示数据格式不兼容或序列化器配置问题
 *
 * @author cascade
 */
public class CacheSerializationException extends CacheException {

    private final String dataType;
    private final String serializerType;
    private final Class<?> targetClass;

    public CacheSerializationException(String message) {
        super(message);
        this.dataType = null;
        this.serializerType = null;
        this.targetClass = null;
    }

    public CacheSerializationException(String message, Throwable cause) {
        super(message, cause);
        this.dataType = null;
        this.serializerType = null;
        this.targetClass = null;
    }

    public CacheSerializationException(String cacheName, String message, Throwable cause) {
        super(cacheName, "序列化", message, cause);
        this.dataType = null;
        this.serializerType = null;
        this.targetClass = null;
    }

    public CacheSerializationException(String dataType, String serializerType, Class<?> targetClass, String message, Throwable cause) {
        super(null, null, buildMessage(dataType, serializerType, targetClass, message), cause);
        this.dataType = dataType;
        this.serializerType = serializerType;
        this.targetClass = targetClass;
    }

    private static String buildMessage(String dataType, String serializerType, Class<?> targetClass, String message) {
        StringBuilder sb = new StringBuilder();
        if (dataType != null) {
            sb.append("数据类型[").append(dataType).append("] ");
        }
        if (serializerType != null) {
            sb.append("序列化器[").append(serializerType).append("] ");
        }
        if (targetClass != null) {
            sb.append("目标类[").append(targetClass.getSimpleName()).append("] ");
        }
        if (message != null) {
            sb.append(message);
        }
        return sb.toString();
    }

    public String getDataType() {
        return dataType;
    }

    public String getSerializerType() {
        return serializerType;
    }

    public Class<?> getTargetClass() {
        return targetClass;
    }
}