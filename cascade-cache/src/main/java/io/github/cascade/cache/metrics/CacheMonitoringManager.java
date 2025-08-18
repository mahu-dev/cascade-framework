package io.github.cascade.cache.metrics;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.List;
import java.util.ArrayList;
import java.util.Collection;
import java.time.Duration;
import java.util.function.Consumer;

/**
 * 缓存监控管理器
 * 统一管理所有缓存的监控和指标收集
 *
 * @author Cascade Framework
 */
public class CacheMonitoringManager {

    private final Map<String, CacheMetricsCollector> collectors;
    private final Set<MonitoringListener> listeners;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean started;
    private final MonitoringConfig config;
    private final MicrometerMetricsExporter micrometerExporter;
    
    // 告警管理
    private final Map<String, AlertRule> alertRules;
    private final Set<AlertHandler> alertHandlers;
    
    // 健康检查
    private final Map<String, HealthChecker> healthCheckers;

    public CacheMonitoringManager() {
        this(new MonitoringConfig());
    }

    public CacheMonitoringManager(MonitoringConfig config) {
        this.collectors = new ConcurrentHashMap<>();
        this.listeners = new CopyOnWriteArraySet<>();
        this.scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "cache-monitoring");
            t.setDaemon(true);
            return t;
        });
        this.started = new AtomicBoolean(false);
        this.config = config;
        this.micrometerExporter = config.getMicrometerRegistry() != null ? 
            new MicrometerMetricsExporter(config.getMicrometerRegistry()) : null;
        
        this.alertRules = new ConcurrentHashMap<>();
        this.alertHandlers = new CopyOnWriteArraySet<>();
        this.healthCheckers = new ConcurrentHashMap<>();
    }

    /**
     * 启动监控管理器
     */
    public void start() {
        if (started.compareAndSet(false, true)) {
            // 启动定期报告任务
            if (config.getReportInterval().toMillis() > 0) {
                scheduler.scheduleAtFixedRate(
                    this::generateReport,
                    config.getReportInterval().toMillis(),
                    config.getReportInterval().toMillis(),
                    TimeUnit.MILLISECONDS
                );
            }
            
            // 启动告警检查任务
            if (config.getAlertCheckInterval().toMillis() > 0) {
                scheduler.scheduleAtFixedRate(
                    this::checkAlerts,
                    config.getAlertCheckInterval().toMillis(),
                    config.getAlertCheckInterval().toMillis(),
                    TimeUnit.MILLISECONDS
                );
            }
            
            // 启动健康检查任务
            if (config.getHealthCheckInterval().toMillis() > 0) {
                scheduler.scheduleAtFixedRate(
                    this::performHealthChecks,
                    config.getHealthCheckInterval().toMillis(),
                    config.getHealthCheckInterval().toMillis(),
                    TimeUnit.MILLISECONDS
                );
            }
        }
    }

    /**
     * 停止监控管理器
     */
    public void stop() {
        if (started.compareAndSet(true, false)) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
            
            if (micrometerExporter != null) {
                micrometerExporter.close();
            }
        }
    }

    /**
     * 注册缓存指标收集器
     *
     * @param collector 指标收集器
     */
    public void registerCollector(CacheMetricsCollector collector) {
        String cacheName = collector.getStats().getCacheName();
        collectors.put(cacheName, collector);
        
        if (micrometerExporter != null) {
            micrometerExporter.registerCollector(collector);
        }
        
        // 通知监听器
        notifyListeners(listener -> listener.onCollectorRegistered(cacheName, collector));
    }

    /**
     * 注销缓存指标收集器
     *
     * @param cacheName 缓存名称
     */
    public void unregisterCollector(String cacheName) {
        CacheMetricsCollector collector = collectors.remove(cacheName);
        if (collector != null) {
            if (micrometerExporter != null) {
                micrometerExporter.unregisterCollector(cacheName);
            }
            
            // 通知监听器
            notifyListeners(listener -> listener.onCollectorUnregistered(cacheName, collector));
        }
    }

    /**
     * 获取指定缓存的统计信息
     *
     * @param cacheName 缓存名称
     * @return 统计信息
     */
    public CacheStats getStats(String cacheName) {
        CacheMetricsCollector collector = collectors.get(cacheName);
        return collector != null ? collector.getStats() : null;
    }

    /**
     * 获取所有缓存的统计信息
     *
     * @return 统计信息映射
     */
    public Map<String, CacheStats> getAllStats() {
        Map<String, CacheStats> stats = new ConcurrentHashMap<>();
        for (Map.Entry<String, CacheMetricsCollector> entry : collectors.entrySet()) {
            stats.put(entry.getKey(), entry.getValue().getStats());
        }
        return stats;
    }

    /**
     * 重置指定缓存的统计信息
     *
     * @param cacheName 缓存名称
     */
    public void resetStats(String cacheName) {
        CacheMetricsCollector collector = collectors.get(cacheName);
        if (collector != null) {
            collector.reset();
        }
    }

    /**
     * 重置所有缓存的统计信息
     */
    public void resetAllStats() {
        collectors.values().forEach(CacheMetricsCollector::reset);
    }

    /**
     * 添加监控监听器
     *
     * @param listener 监听器
     */
    public void addListener(MonitoringListener listener) {
        listeners.add(listener);
    }

    /**
     * 移除监控监听器
     *
     * @param listener 监听器
     */
    public void removeListener(MonitoringListener listener) {
        listeners.remove(listener);
    }

    /**
     * 添加告警规则
     *
     * @param rule 告警规则
     */
    public void addAlertRule(AlertRule rule) {
        alertRules.put(rule.getName(), rule);
    }

    /**
     * 移除告警规则
     *
     * @param ruleName 规则名称
     */
    public void removeAlertRule(String ruleName) {
        alertRules.remove(ruleName);
    }

    /**
     * 添加告警处理器
     *
     * @param handler 告警处理器
     */
    public void addAlertHandler(AlertHandler handler) {
        alertHandlers.add(handler);
    }

    /**
     * 移除告警处理器
     *
     * @param handler 告警处理器
     */
    public void removeAlertHandler(AlertHandler handler) {
        alertHandlers.remove(handler);
    }

    /**
     * 添加健康检查器
     *
     * @param cacheName 缓存名称
     * @param checker 健康检查器
     */
    public void addHealthChecker(String cacheName, HealthChecker checker) {
        healthCheckers.put(cacheName, checker);
    }

    /**
     * 移除健康检查器
     *
     * @param cacheName 缓存名称
     */
    public void removeHealthChecker(String cacheName) {
        healthCheckers.remove(cacheName);
    }

    /**
     * 生成监控报告
     */
    private void generateReport() {
        try {
            MonitoringReport report = new MonitoringReport(getAllStats());
            notifyListeners(listener -> listener.onReportGenerated(report));
        } catch (Exception e) {
            // 记录日志但不影响正常流程
        }
    }

    /**
     * 检查告警
     */
    private void checkAlerts() {
        try {
            Map<String, CacheStats> allStats = getAllStats();
            
            for (AlertRule rule : alertRules.values()) {
                for (Map.Entry<String, CacheStats> entry : allStats.entrySet()) {
                    String cacheName = entry.getKey();
                    CacheStats stats = entry.getValue();
                    
                    if (rule.shouldAlert(cacheName, stats)) {
                        Alert alert = new Alert(rule.getName(), cacheName, rule.getMessage(stats), 
                            rule.getSeverity(), System.currentTimeMillis());
                        
                        for (AlertHandler handler : alertHandlers) {
                            try {
                                handler.handleAlert(alert);
                            } catch (Exception e) {
                                // 记录日志但继续处理其他处理器
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            // 记录日志但不影响正常流程
        }
    }

    /**
     * 执行健康检查
     */
    private void performHealthChecks() {
        try {
            for (Map.Entry<String, HealthChecker> entry : healthCheckers.entrySet()) {
                String cacheName = entry.getKey();
                HealthChecker checker = entry.getValue();
                
                try {
                    HealthStatus status = checker.checkHealth(cacheName);
                    notifyListeners(listener -> listener.onHealthCheckCompleted(cacheName, status));
                } catch (Exception e) {
                    HealthStatus errorStatus = new HealthStatus(false, "Health check failed: " + e.getMessage());
                    notifyListeners(listener -> listener.onHealthCheckCompleted(cacheName, errorStatus));
                }
            }
        } catch (Exception e) {
            // 记录日志但不影响正常流程
        }
    }

    /**
     * 通知监听器
     */
    private void notifyListeners(Consumer<MonitoringListener> action) {
        for (MonitoringListener listener : listeners) {
            try {
                action.accept(listener);
            } catch (Exception e) {
                // 记录日志但不影响正常流程
            }
        }
    }

    /**
     * 监控配置
     */
    public static class MonitoringConfig {
        private Duration reportInterval = Duration.ofMinutes(5);
        private Duration alertCheckInterval = Duration.ofMinutes(1);
        private Duration healthCheckInterval = Duration.ofMinutes(2);
        private MicrometerMetricsExporter.MeterRegistry micrometerRegistry;
        private boolean enableDetailedMetrics = true;
        private boolean enableTimeWindowMetrics = false;
        private boolean enableTierMetrics = true;

        public Duration getReportInterval() { return reportInterval; }
        public MonitoringConfig setReportInterval(Duration reportInterval) {
            this.reportInterval = reportInterval;
            return this;
        }

        public Duration getAlertCheckInterval() { return alertCheckInterval; }
        public MonitoringConfig setAlertCheckInterval(Duration alertCheckInterval) {
            this.alertCheckInterval = alertCheckInterval;
            return this;
        }

        public Duration getHealthCheckInterval() { return healthCheckInterval; }
        public MonitoringConfig setHealthCheckInterval(Duration healthCheckInterval) {
            this.healthCheckInterval = healthCheckInterval;
            return this;
        }

        public MicrometerMetricsExporter.MeterRegistry getMicrometerRegistry() { return micrometerRegistry; }
        public MonitoringConfig setMicrometerRegistry(MicrometerMetricsExporter.MeterRegistry micrometerRegistry) {
            this.micrometerRegistry = micrometerRegistry;
            return this;
        }

        public boolean isEnableDetailedMetrics() { return enableDetailedMetrics; }
        public MonitoringConfig setEnableDetailedMetrics(boolean enableDetailedMetrics) {
            this.enableDetailedMetrics = enableDetailedMetrics;
            return this;
        }

        public boolean isEnableTimeWindowMetrics() { return enableTimeWindowMetrics; }
        public MonitoringConfig setEnableTimeWindowMetrics(boolean enableTimeWindowMetrics) {
            this.enableTimeWindowMetrics = enableTimeWindowMetrics;
            return this;
        }

        public boolean isEnableTierMetrics() { return enableTierMetrics; }
        public MonitoringConfig setEnableTierMetrics(boolean enableTierMetrics) {
            this.enableTierMetrics = enableTierMetrics;
            return this;
        }
    }

    /**
     * 监控监听器
     */
    public interface MonitoringListener {
        default void onCollectorRegistered(String cacheName, CacheMetricsCollector collector) {}
        default void onCollectorUnregistered(String cacheName, CacheMetricsCollector collector) {}
        default void onReportGenerated(MonitoringReport report) {}
        default void onHealthCheckCompleted(String cacheName, HealthStatus status) {}
    }

    /**
     * 监控报告
     */
    public static class MonitoringReport {
        private final Map<String, CacheStats> cacheStats;
        private final long timestamp;
        private final Summary summary;

        public MonitoringReport(Map<String, CacheStats> cacheStats) {
            this.cacheStats = new ConcurrentHashMap<>(cacheStats);
            this.timestamp = System.currentTimeMillis();
            this.summary = calculateSummary(cacheStats.values());
        }

        public Map<String, CacheStats> getCacheStats() { return new ConcurrentHashMap<>(cacheStats); }
        public long getTimestamp() { return timestamp; }
        public Summary getSummary() { return summary; }

        private Summary calculateSummary(Collection<CacheStats> stats) {
            long totalRequests = 0;
            long totalHits = 0;
            long totalLoads = 0;
            long totalEvictions = 0;
            long totalSize = 0;
            double totalLoadTime = 0;
            int cacheCount = stats.size();

            for (CacheStats stat : stats) {
                totalRequests += stat.getRequestCount();
                totalHits += stat.getHitCount();
                totalLoads += stat.getLoadCount();
                totalEvictions += stat.getEvictionCount();
                totalSize += stat.getCurrentSize();
                totalLoadTime += stat.getAverageLoadTimeMillis();
            }

            double overallHitRate = totalRequests > 0 ? (double) totalHits / totalRequests : 0.0;
            double averageLoadTime = cacheCount > 0 ? totalLoadTime / cacheCount : 0.0;

            return new Summary(cacheCount, totalRequests, totalHits, overallHitRate,
                totalLoads, totalEvictions, totalSize, averageLoadTime);
        }

        public static class Summary {
            private final int cacheCount;
            private final long totalRequests;
            private final long totalHits;
            private final double overallHitRate;
            private final long totalLoads;
            private final long totalEvictions;
            private final long totalSize;
            private final double averageLoadTime;

            public Summary(int cacheCount, long totalRequests, long totalHits, double overallHitRate,
                         long totalLoads, long totalEvictions, long totalSize, double averageLoadTime) {
                this.cacheCount = cacheCount;
                this.totalRequests = totalRequests;
                this.totalHits = totalHits;
                this.overallHitRate = overallHitRate;
                this.totalLoads = totalLoads;
                this.totalEvictions = totalEvictions;
                this.totalSize = totalSize;
                this.averageLoadTime = averageLoadTime;
            }

            public int getCacheCount() { return cacheCount; }
            public long getTotalRequests() { return totalRequests; }
            public long getTotalHits() { return totalHits; }
            public double getOverallHitRate() { return overallHitRate; }
            public long getTotalLoads() { return totalLoads; }
            public long getTotalEvictions() { return totalEvictions; }
            public long getTotalSize() { return totalSize; }
            public double getAverageLoadTime() { return averageLoadTime; }
        }
    }

    /**
     * 告警规则
     */
    public interface AlertRule {
        String getName();
        boolean shouldAlert(String cacheName, CacheStats stats);
        String getMessage(CacheStats stats);
        AlertSeverity getSeverity();
    }

    /**
     * 告警处理器
     */
    public interface AlertHandler {
        void handleAlert(Alert alert);
    }

    /**
     * 告警
     */
    public static class Alert {
        private final String ruleName;
        private final String cacheName;
        private final String message;
        private final AlertSeverity severity;
        private final long timestamp;

        public Alert(String ruleName, String cacheName, String message, AlertSeverity severity, long timestamp) {
            this.ruleName = ruleName;
            this.cacheName = cacheName;
            this.message = message;
            this.severity = severity;
            this.timestamp = timestamp;
        }

        public String getRuleName() { return ruleName; }
        public String getCacheName() { return cacheName; }
        public String getMessage() { return message; }
        public AlertSeverity getSeverity() { return severity; }
        public long getTimestamp() { return timestamp; }
    }

    /**
     * 告警严重程度
     */
    public enum AlertSeverity {
        LOW, MEDIUM, HIGH, CRITICAL
    }

    /**
     * 健康检查器
     */
    public interface HealthChecker {
        HealthStatus checkHealth(String cacheName);
    }

    /**
     * 健康状态
     */
    public static class HealthStatus {
        private final boolean healthy;
        private final String message;
        private final Map<String, Object> details;

        public HealthStatus(boolean healthy, String message) {
            this(healthy, message, new ConcurrentHashMap<>());
        }

        public HealthStatus(boolean healthy, String message, Map<String, Object> details) {
            this.healthy = healthy;
            this.message = message;
            this.details = new ConcurrentHashMap<>(details);
        }

        public boolean isHealthy() { return healthy; }
        public String getMessage() { return message; }
        public Map<String, Object> getDetails() { return new ConcurrentHashMap<>(details); }
    }
}