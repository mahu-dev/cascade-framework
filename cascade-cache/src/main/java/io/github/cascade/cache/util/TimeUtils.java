package io.github.cascade.cache.util;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 时间工具类
 * 用于统一处理时间单位转换，支持从Duration到long秒的迁移
 *
 * @author cascade
 */
public final class TimeUtils {

    private TimeUtils() {
        // 工具类不允许实例化
    }

    // ==================== Duration 到 long 秒转换 ====================

    /**
     * 将Duration转换为秒数
     * 
     * @param duration Duration对象，可以为null
     * @return 秒数，如果duration为null则返回-1表示永不过期
     */
    public static long toSeconds(Duration duration) {
        if (duration == null) {
            return -1; // 永不过期
        }
        if (duration.isZero()) {
            return 0; // 立即过期
        }
        if (duration.isNegative()) {
            return -1; // 负值表示永不过期
        }
        return duration.getSeconds();
    }

    /**
     * 将秒数转换为Duration
     * 
     * @param seconds 秒数，-1表示永不过期，0表示立即过期
     * @return Duration对象
     */
    public static Duration toDuration(long seconds) {
        if (seconds < 0) {
            return null; // 永不过期
        }
        if (seconds == 0) {
            return Duration.ZERO; // 立即过期
        }
        return Duration.ofSeconds(seconds);
    }

    /**
     * 将Duration转换为毫秒数
     * 
     * @param duration Duration对象
     * @return 毫秒数，如果duration为null则返回-1
     */
    public static long toMilliseconds(Duration duration) {
        if (duration == null) {
            return -1;
        }
        return duration.toMillis();
    }

    /**
     * 将秒数转换为毫秒数
     * 
     * @param seconds 秒数
     * @return 毫秒数
     */
    public static long secondsToMillis(long seconds) {
        if (seconds < 0) {
            return -1;
        }
        return seconds * 1000L;
    }

    /**
     * 将毫秒数转换为秒数
     * 
     * @param millis 毫秒数
     * @return 秒数（向上取整）
     */
    public static long millisToSeconds(long millis) {
        if (millis < 0) {
            return -1;
        }
        return (millis + 999) / 1000; // 向上取整
    }

    // ==================== 格式化方法 ====================

    /**
     * 格式化秒数为可读字符串
     * 
     * @param seconds 秒数
     * @return 格式化的字符串
     */
    public static String formatSeconds(long seconds) {
        if (seconds < 0) {
            return "Never expires";
        }
        if (seconds == 0) {
            return "Immediately";
        }
        if (seconds < 60) {
            return seconds + "s";
        }
        if (seconds < 3600) {
            return String.format("%dm %ds", seconds / 60, seconds % 60);
        }
        if (seconds < 86400) {
            return String.format("%dh %dm %ds", seconds / 3600, (seconds % 3600) / 60, seconds % 60);
        }
        return String.format("%dd %dh %dm", seconds / 86400, (seconds % 86400) / 3600, (seconds % 3600) / 60);
    }

    // ==================== 验证方法 ====================

    /**
     * 验证TTL秒数是否有效
     * 
     * @param ttlSeconds TTL秒数
     * @return 是否有效
     */
    public static boolean isValidTtlSeconds(long ttlSeconds) {
        return ttlSeconds >= -1; // -1表示永不过期，0表示立即过期，正数表示具体TTL
    }

    /**
     * 验证TTL秒数是否为永不过期
     * 
     * @param ttlSeconds TTL秒数
     * @return 是否永不过期
     */
    public static boolean isNeverExpire(long ttlSeconds) {
        return ttlSeconds < 0;
    }

    /**
     * 验证TTL秒数是否为立即过期
     * 
     * @param ttlSeconds TTL秒数
     * @return 是否立即过期
     */
    public static boolean isImmediateExpire(long ttlSeconds) {
        return ttlSeconds == 0;
    }

    // ==================== 兼容性转换方法 ====================

    /**
     * 为Caffeine转换TTL
     * 
     * @param ttlSeconds TTL秒数
     * @param unit 时间单位
     * @return 转换后的时间值
     */
    public static long forCaffeine(long ttlSeconds, TimeUnit unit) {
        if (ttlSeconds < 0) {
            return Long.MAX_VALUE; // Caffeine中表示永不过期
        }
        return unit.convert(ttlSeconds, TimeUnit.SECONDS);
    }

    /**
     * 为Redis转换TTL（毫秒）
     * 
     * @param ttlSeconds TTL秒数
     * @return 毫秒数，-1表示永不过期
     */
    public static long forRedis(long ttlSeconds) {
        if (ttlSeconds < 0) {
            return -1; // Redis中-1表示永不过期
        }
        return ttlSeconds * 1000L;
    }

    // ==================== 配置迁移辅助方法 ====================

    /**
     * 从配置字符串解析TTL秒数
     * 支持格式: "30s", "5m", "2h", "1d"
     * 
     * @param configValue 配置值
     * @return TTL秒数
     */
    public static long parseConfigSeconds(String configValue) {
        if (configValue == null || configValue.trim().isEmpty()) {
            return -1; // 默认永不过期
        }
        
        configValue = configValue.trim().toLowerCase();
        
        if ("never".equals(configValue) || "infinite".equals(configValue)) {
            return -1;
        }
        
        if (configValue.matches("\\d+")) {
            // 纯数字，假设为秒
            return Long.parseLong(configValue);
        }
        
        if (configValue.matches("\\d+[smhd]")) {
            long value = Long.parseLong(configValue.substring(0, configValue.length() - 1));
            char unit = configValue.charAt(configValue.length() - 1);
            
            return switch (unit) {
                case 's' -> value;
                case 'm' -> value * 60;
                case 'h' -> value * 3600;
                case 'd' -> value * 86400;
                default -> throw new IllegalArgumentException("Invalid time unit: " + unit);
            };
        }
        
        throw new IllegalArgumentException("Invalid time format: " + configValue);
    }

    /**
     * 将TTL秒数转换为配置字符串
     * 
     * @param ttlSeconds TTL秒数
     * @return 配置字符串
     */
    public static String toConfigString(long ttlSeconds) {
        if (ttlSeconds < 0) {
            return "never";
        }
        if (ttlSeconds == 0) {
            return "0s";
        }
        
        if (ttlSeconds % 86400 == 0) {
            return (ttlSeconds / 86400) + "d";
        }
        if (ttlSeconds % 3600 == 0) {
            return (ttlSeconds / 3600) + "h";
        }
        if (ttlSeconds % 60 == 0) {
            return (ttlSeconds / 60) + "m";
        }
        return ttlSeconds + "s";
    }
}