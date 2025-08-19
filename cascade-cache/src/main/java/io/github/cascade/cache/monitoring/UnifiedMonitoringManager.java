package io.github.cascade.cache.monitoring;

import io.github.cascade.cache.event.unified.UnifiedCacheEvent;
import io.github.cascade.cache.event.unified.UnifiedEventProcessor;
import io.github.cascade.cache.metrics.CacheMetricsCollector;
import io.github.cascade.cache.metrics.DetailedCacheMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 统一监控管理器
 * 整合事件处理、指标收集、性能监控等功能
 *
 * @author Cascade Framework
 */
public class UnifiedMonitoringManager {

    private static final Logger log = LoggerFactory.getLogger(UnifiedMonitoringManager.class);

    private final UnifiedEventProcessor eventProcessor;
    private final Map<String, CacheMetricsCollector> metricsCollectors;
    private final Map<String, CacheMonitor> cacheMonitors;
    private final ScheduledExecutorService scheduler;
    private final MonitoringConfiguration config;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public UnifiedMonitoringManager() {
        this(new MonitoringConfiguration());
    }

    public UnifiedMonitoringManager(MonitoringConfiguration config) {
        this.config = config;
        this.eventProcessor = new UnifiedEventProcessor(config.enableAsyncEventProcessing);
        this.metricsCollectors = new ConcurrentHashMap<>();
        this.cacheMonitors = new ConcurrentHashMap<>();
        this.scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "unified-monitoring");
            t.setDaemon(true);
            return t;
        });

        setupEventHandlers();
    }

    /**
     * 启动监控管理器
     */
    public void start() {
        if (running.compareAndSet(false, true)) {
            eventProcessor.start();
            
            // 启动定期监控任务
            if (config.enablePeriodicMonitoring) {
                scheduler.scheduleAtFixedRate(
                    this::performPeriodicMonitoring,
                    config.monitoringInterval.toMillis(),
                    config.monitoringInterval.toMillis(),
                    TimeUnit.MILLISECONDS
                );
            }
            
            log.info("Unified monitoring manager started");
        }
    }

    /**
     * 停止监控管理器
     */
    public void stop() {
        if (running.compareAndSet(true, false)) {
            eventProcessor.stop();
            scheduler.shutdown();
            
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
            
            log.info("Unified monitoring manager stopped");
        }
    }

    /**
     * 注册缓存监控
     */
    public void registerCache(String cacheId) {
        if (!cacheMonitors.containsKey(cacheId)) {
            CacheMetricsCollector metricsCollector = new CacheMetricsCollector(cacheId);
            CacheMonitor monitor = new CacheMonitor(cacheId, metricsCollector, this);
            
            metricsCollectors.put(cacheId, metricsCollector);
            cacheMonitors.put(cacheId, monitor);
            
            log.info("Registered monitoring for cache: {}", cacheId);
        }
    }

    /**
     * 注销缓存监控
     */
    public void unregisterCache(String cacheId) {
        CacheMonitor monitor = cacheMonitors.remove(cacheId);
        if (monitor != null) {
            monitor.close();
        }
        
        CacheMetricsCollector collector = metricsCollectors.remove(cacheId);
        if (collector != null) {
            // 清理资源
        }
        
        log.info("Unregistered monitoring for cache: {}", cacheId);
    }

    /**
     * 发布事件
     */
    public void publishEvent(UnifiedCacheEvent event) {
        eventProcessor.publishEvent(event);
    }

    /**
     * 获取缓存指标
     */
    public DetailedCacheMetrics getCacheMetrics(String cacheId) {
        CacheMetricsCollector collector = metricsCollectors.get(cacheId);
        return collector != null ? collector.getStats() : null;
    }

    /**
     * 获取所有缓存指标
     */
    public Map<String, DetailedCacheMetrics> getAllCacheMetrics() {
        Map<String, DetailedCacheMetrics> result = new ConcurrentHashMap<>();
        metricsCollectors.forEach((cacheId, collector) -> 
            result.put(cacheId, collector.getStats()));
        return result;
    }

    /**
     * 设置事件处理器
     */
    private void setupEventHandlers() {
        // 注册日志处理器
        if (config.enableEventLogging) {
            eventProcessor.registerHandler(new UnifiedEventProcessor.LoggingEventHandler());
        }
        
        // 注册指标收集处理器
        eventProcessor.registerHandler(new MetricsCollectionHandler());
        
        // 注册同步处理器
        if (config.enableSyncEventHandling) {
            eventProcessor.registerHandler(new UnifiedEventProcessor.SyncEventHandler());
        }
    }

    /**
     * 执行定期监控
     */
    private void performPeriodicMonitoring() {
        try {
            for (CacheMonitor monitor : cacheMonitors.values()) {
                monitor.performHealthCheck();
            }
        } catch (Exception e) {
            log.error("Error during periodic monitoring", e);
        }
    }

    /**
     * 指标收集事件处理器
     */
    private class MetricsCollectionHandler implements UnifiedEventProcessor.EventHandler {
        
        @Override
        public boolean canHandle(UnifiedCacheEvent event) {
            return event.isMonitoringEvent() || event.isOperationEvent();
        }
        
        @Override
        public void handleEvent(UnifiedCacheEvent event) {
            CacheMetricsCollector collector = metricsCollectors.get(event.getCacheId());
            if (collector != null) {
                // 根据事件类型更新指标
                switch (event.getType()) {
                    case HIT:
                        collector.recordHit();
                        break;
                    case MISS:
                        collector.recordMiss();
                        break;
                    case PUT:
                        collector.recordPut();
                        break;
                    case EVICT:
                        collector.recordEviction();
                        break;
                    case LOAD_SUCCESS:
                        if (event.getDuration() != null) {
                            collector.recordLoad(event.getDuration().toNanos(), true);
                        }
                        break;
                    case LOAD_FAILURE:
                        collector.recordLoad(0, false);
                        break;
                    case ERROR:
                        // 记录错误可以通过其他方式处理，例如记录到事件监听器
                        break;
                }
            }
        }
    }

    /**
     * 缓存监控器
     */
    public static class CacheMonitor {
        private final String cacheId;
        private final CacheMetricsCollector metricsCollector;
        private final UnifiedMonitoringManager monitoringManager;
        
        public CacheMonitor(String cacheId, CacheMetricsCollector metricsCollector, 
                           UnifiedMonitoringManager monitoringManager) {
            this.cacheId = cacheId;
            this.metricsCollector = metricsCollector;
            this.monitoringManager = monitoringManager;
        }
        
        /**
         * 执行健康检查
         */
        public void performHealthCheck() {
            DetailedCacheMetrics metrics = metricsCollector.getStats();
            
            // 检查命中率
            if (metrics.getHitRate() < 0.5 && metrics.getRequestCount() > 100) {
                monitoringManager.publishEvent(
                    UnifiedCacheEvent.builder(cacheId, UnifiedCacheEvent.Type.ERROR)
                        .metadata("issue", "low_hit_rate")
                        .metadata("hit_rate", metrics.getHitRate())
                        .build()
                );
            }
            
            // 检查错误率
            if (metrics.getLoadExceptionRate() > 0.1 && metrics.getLoadCount() > 10) {
                monitoringManager.publishEvent(
                    UnifiedCacheEvent.builder(cacheId, UnifiedCacheEvent.Type.ERROR)
                        .metadata("issue", "high_error_rate")
                        .metadata("error_rate", metrics.getLoadExceptionRate())
                        .build()
                );
            }
        }
        
        public void close() {
            // 清理监控资源
        }
    }

    /**
     * 监控配置
     */
    public static class MonitoringConfiguration {
        public boolean enableAsyncEventProcessing = true;
        public boolean enableEventLogging = true;
        public boolean enableSyncEventHandling = true;
        public boolean enablePeriodicMonitoring = true;
        public Duration monitoringInterval = Duration.ofMinutes(1);
        
        public MonitoringConfiguration enableAsyncEventProcessing(boolean enable) {
            this.enableAsyncEventProcessing = enable;
            return this;
        }
        
        public MonitoringConfiguration enableEventLogging(boolean enable) {
            this.enableEventLogging = enable;
            return this;
        }
        
        public MonitoringConfiguration enableSyncEventHandling(boolean enable) {
            this.enableSyncEventHandling = enable;
            return this;
        }
        
        public MonitoringConfiguration enablePeriodicMonitoring(boolean enable) {
            this.enablePeriodicMonitoring = enable;
            return this;
        }
        
        public MonitoringConfiguration monitoringInterval(Duration interval) {
            this.monitoringInterval = interval;
            return this;
        }
    }

    /**
     * 是否正在运行
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * 获取监控的缓存数量
     */
    public int getMonitoredCacheCount() {
        return cacheMonitors.size();
    }
}