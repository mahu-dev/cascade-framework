package io.github.cascade.cache.v2.common.exception;

/**
 * @author lionel lionelk@163.com
 * =============================
 * Date: 2025/10/29
 * Time: 17:15
 * =============================
 */

/**
 * 缓存配置异常
 * <p>
 * 当缓存配置不正确或不完整时抛出此异常
 *
 * @author cascade
 */
public class CacheConfigurationException extends CacheException {

    private final String configKey;
    private final Object configValue;

    public CacheConfigurationException(String message) {
        super(message);
        this.configKey = null;
        this.configValue = null;
    }

    public CacheConfigurationException(String message, Throwable cause) {
        super(message, cause);
        this.configKey = null;
        this.configValue = null;
    }

    public CacheConfigurationException(String configKey, Object configValue, String message, Throwable cause) {
        super(null, null, buildMessage(configKey, configValue, message), cause);
        this.configKey = configKey;
        this.configValue = configValue;
    }

    private static String buildMessage(String configKey, Object configValue, String message) {
        StringBuilder sb = new StringBuilder();
        if (configKey != null) {
            sb.append("配置项[").append(configKey).append("]");
            if (configValue != null) {
                sb.append("值[").append(configValue).append("]");
            }
            sb.append(" ");
        }
        if (message != null) {
            sb.append(message);
        }
        return sb.toString();
    }

    public String getConfigKey() {
        return configKey;
    }

    public Object getConfigValue() {
        return configValue;
    }
}