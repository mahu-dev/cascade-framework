package io.github.cascade.cache.metrics;

import io.github.cascade.cache.metrics.CacheMonitoringManager.AlertRule;
import io.github.cascade.cache.metrics.CacheMonitoringManager.AlertSeverity;

/**
 * 常用告警规则
 * 提供一些预定义的缓存监控告警规则
 *
 * @author Cascade Framework
 */
public class CommonAlertRules {

    /**
     * 低命中率告警规则
     *
     * @param threshold 命中率阈值（0.0-1.0）
     * @param severity 告警严重程度
     * @return 告警规则
     */
    public static AlertRule lowHitRateRule(double threshold, AlertSeverity severity) {
        return new AlertRule() {
            @Override
            public String getName() {
                return "low-hit-rate";
            }

            @Override
            public boolean shouldAlert(String cacheName, CacheStats stats) {
                return stats.getRequestCount() > 100 && stats.getHitRate() < threshold;
            }

            @Override
            public String getMessage(CacheStats stats) {
                return String.format("Cache hit rate is %.2f%%, below threshold %.2f%%",
                    stats.getHitRate() * 100, threshold * 100);
            }

            @Override
            public AlertSeverity getSeverity() {
                return severity;
            }
        };
    }

    /**
     * 高加载时间告警规则
     *
     * @param thresholdMs 加载时间阈值（毫秒）
     * @param severity 告警严重程度
     * @return 告警规则
     */
    public static AlertRule highLoadTimeRule(double thresholdMs, AlertSeverity severity) {
        return new AlertRule() {
            @Override
            public String getName() {
                return "high-load-time";
            }

            @Override
            public boolean shouldAlert(String cacheName, CacheStats stats) {
                return stats.getLoadCount() > 10 && stats.getAverageLoadTimeMillis() > thresholdMs;
            }

            @Override
            public String getMessage(CacheStats stats) {
                return String.format("Average load time is %.2fms, above threshold %.2fms",
                    stats.getAverageLoadTimeMillis(), thresholdMs);
            }

            @Override
            public AlertSeverity getSeverity() {
                return severity;
            }
        };
    }

    /**
     * 高驱逐率告警规则
     *
     * @param threshold 驱逐率阈值（0.0-1.0）
     * @param severity 告警严重程度
     * @return 告警规则
     */
    public static AlertRule highEvictionRateRule(double threshold, AlertSeverity severity) {
        return new AlertRule() {
            @Override
            public String getName() {
                return "high-eviction-rate";
            }

            @Override
            public boolean shouldAlert(String cacheName, CacheStats stats) {
                long totalOperations = stats.getPutCount() + stats.getLoadCount();
                if (totalOperations == 0) return false;
                
                double evictionRate = (double) stats.getEvictionCount() / totalOperations;
                return evictionRate > threshold;
            }

            @Override
            public String getMessage(CacheStats stats) {
                long totalOperations = stats.getPutCount() + stats.getLoadCount();
                double evictionRate = totalOperations > 0 ? (double) stats.getEvictionCount() / totalOperations : 0;
                return String.format("Eviction rate is %.2f%%, above threshold %.2f%%",
                    evictionRate * 100, threshold * 100);
            }

            @Override
            public AlertSeverity getSeverity() {
                return severity;
            }
        };
    }

    /**
     * 缓存大小告警规则
     *
     * @param maxSize 最大大小阈值
     * @param severity 告警严重程度
     * @return 告警规则
     */
    public static AlertRule cacheSizeRule(long maxSize, AlertSeverity severity) {
        return new AlertRule() {
            @Override
            public String getName() {
                return "cache-size";
            }

            @Override
            public boolean shouldAlert(String cacheName, CacheStats stats) {
                return stats.getCurrentSize() > maxSize;
            }

            @Override
            public String getMessage(CacheStats stats) {
                return String.format("Cache size is %d, above threshold %d",
                    stats.getCurrentSize(), maxSize);
            }

            @Override
            public AlertSeverity getSeverity() {
                return severity;
            }
        };
    }

    /**
     * 缓存使用率告警规则
     *
     * @param threshold 使用率阈值（0.0-1.0）
     * @param severity 告警严重程度
     * @return 告警规则
     */
    public static AlertRule cacheUsageRule(double threshold, AlertSeverity severity) {
        return new AlertRule() {
            @Override
            public String getName() {
                return "cache-usage";
            }

            @Override
            public boolean shouldAlert(String cacheName, CacheStats stats) {
                if (stats.getMaxSize() <= 0) return false;
                
                double usage = (double) stats.getCurrentSize() / stats.getMaxSize();
                return usage > threshold;
            }

            @Override
            public String getMessage(CacheStats stats) {
                double usage = stats.getMaxSize() > 0 ? (double) stats.getCurrentSize() / stats.getMaxSize() : 0;
                return String.format("Cache usage is %.2f%%, above threshold %.2f%%",
                    usage * 100, threshold * 100);
            }

            @Override
            public AlertSeverity getSeverity() {
                return severity;
            }
        };
    }

    /**
     * 加载异常率告警规则
     *
     * @param threshold 异常率阈值（0.0-1.0）
     * @param severity 告警严重程度
     * @return 告警规则
     */
    public static AlertRule loadExceptionRateRule(double threshold, AlertSeverity severity) {
        return new AlertRule() {
            @Override
            public String getName() {
                return "load-exception-rate";
            }

            @Override
            public boolean shouldAlert(String cacheName, CacheStats stats) {
                if (stats.getLoadCount() == 0) return false;
                
                double exceptionRate = (double) stats.getLoadExceptionCount() / stats.getLoadCount();
                return exceptionRate > threshold;
            }

            @Override
            public String getMessage(CacheStats stats) {
                double exceptionRate = stats.getLoadCount() > 0 ? 
                    (double) stats.getLoadExceptionCount() / stats.getLoadCount() : 0;
                return String.format("Load exception rate is %.2f%%, above threshold %.2f%%",
                    exceptionRate * 100, threshold * 100);
            }

            @Override
            public AlertSeverity getSeverity() {
                return severity;
            }
        };
    }

    /**
     * 缓存不可用告警规则
     *
     * @param severity 告警严重程度
     * @return 告警规则
     */
    public static AlertRule cacheUnavailableRule(AlertSeverity severity) {
        return new AlertRule() {
            @Override
            public String getName() {
                return "cache-unavailable";
            }

            @Override
            public boolean shouldAlert(String cacheName, CacheStats stats) {
                // 如果缓存运行时间很长但没有任何请求，可能表示缓存不可用
                long uptimeSeconds = stats.getUptime().getSeconds();
                long totalRequests = stats.getRequestCount();
                
                // 如果运行超过10分钟但没有任何请求，认为可能不可用
                return uptimeSeconds > 600 && totalRequests == 0;
            }

            @Override
            public String getMessage(CacheStats stats) {
                long uptimeMinutes = stats.getUptime().toMinutes();
                return String.format("Cache has been running for %d minutes but received no requests", uptimeMinutes);
            }

            @Override
            public AlertSeverity getSeverity() {
                return severity;
            }
        };
    }

    /**
     * 创建默认告警规则集合
     *
     * @return 默认告警规则数组
     */
    public static AlertRule[] createDefaultRules() {
        return new AlertRule[] {
            lowHitRateRule(0.8, AlertSeverity.MEDIUM),
            highLoadTimeRule(1000.0, AlertSeverity.HIGH),
            highEvictionRateRule(0.1, AlertSeverity.MEDIUM),
            cacheUsageRule(0.9, AlertSeverity.HIGH),
            loadExceptionRateRule(0.05, AlertSeverity.HIGH),
            cacheUnavailableRule(AlertSeverity.CRITICAL)
        };
    }

    /**
     * 创建严格的告警规则集合
     *
     * @return 严格告警规则数组
     */
    public static AlertRule[] createStrictRules() {
        return new AlertRule[] {
            lowHitRateRule(0.9, AlertSeverity.HIGH),
            highLoadTimeRule(500.0, AlertSeverity.HIGH),
            highEvictionRateRule(0.05, AlertSeverity.HIGH),
            cacheUsageRule(0.8, AlertSeverity.MEDIUM),
            loadExceptionRateRule(0.01, AlertSeverity.CRITICAL),
            cacheUnavailableRule(AlertSeverity.CRITICAL)
        };
    }

    /**
     * 创建宽松的告警规则集合
     *
     * @return 宽松告警规则数组
     */
    public static AlertRule[] createLenientRules() {
        return new AlertRule[] {
            lowHitRateRule(0.6, AlertSeverity.LOW),
            highLoadTimeRule(2000.0, AlertSeverity.MEDIUM),
            highEvictionRateRule(0.2, AlertSeverity.LOW),
            cacheUsageRule(0.95, AlertSeverity.MEDIUM),
            loadExceptionRateRule(0.1, AlertSeverity.MEDIUM),
            cacheUnavailableRule(AlertSeverity.HIGH)
        };
    }
}