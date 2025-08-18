package io.github.cascade.cache.event;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 缓存事件监听器注册表
 * 统一管理和配置缓存事件监听器
 */
@Component
public class CacheEventListenerRegistry {
    
    private static final Logger log = LoggerFactory.getLogger(CacheEventListenerRegistry.class);
    
    private final List<CacheEventListener<? extends CacheEvent>> registeredListeners = new CopyOnWriteArrayList<>();
    private final CacheEventManager eventManager;
    
    @Autowired(required = false)
    private MeterRegistry meterRegistry;
    
    // 预定义的监听器
    private CacheStatisticsListener statisticsListener;
    private CacheLoggingListener loggingListener;
    private CachePerformanceMonitorListener performanceListener;
    private CacheMetricsEventListener metricsListener;
    
    public CacheEventListenerRegistry() {
        this.eventManager = CacheEventManager.getInstance();
    }
    
    public CacheEventListenerRegistry(CacheEventManager eventManager) {
        this.eventManager = eventManager;
    }
    
    @PostConstruct
    public void initialize() {
        log.info("Initializing cache event listener registry");
        
        // 注册默认监听器
        registerDefaultListeners();
        
        log.info("Cache event listener registry initialized with {} listeners", 
            registeredListeners.size());
    }
    
    @PreDestroy
    public void shutdown() {
        log.info("Shutting down cache event listener registry");
        
        // 清理所有注册的监听器
        for (CacheEventListener<? extends CacheEvent> listener : registeredListeners) {
            try {
                eventManager.removeListener(listener);
                if (listener instanceof AutoCloseable) {
                    ((AutoCloseable) listener).close();
                }
            } catch (Exception e) {
                log.warn("Error removing listener: {}", listener.getName(), e);
            }
        }
        
        registeredListeners.clear();
        log.info("Cache event listener registry shutdown complete");
    }
    
    /**
     * 注册默认监听器
     */
    private void registerDefaultListeners() {
        // 1. 统计监听器（总是启用）
        statisticsListener = new CacheStatisticsListener();
        registerListener(statisticsListener);
        
        // 2. 性能监控监听器（总是启用）
        performanceListener = new CachePerformanceMonitorListener();
        registerListener(performanceListener);
        
        // 3. 日志监听器（可选）
        loggingListener = new CacheLoggingListener();
        // 默认只记录错误和慢操作
        loggingListener.setLogDetails(false);
        loggingListener.setLogSlowOperations(true);
        loggingListener.setSlowOperationThreshold(1000);
        registerListener(loggingListener);
        
        // 4. 指标监听器（如果MeterRegistry可用）
        if (meterRegistry != null) {
            metricsListener = new CacheMetricsEventListener(meterRegistry);
            registerListener(metricsListener);
            log.info("Registered metrics listener with MeterRegistry: {}", meterRegistry.getClass().getSimpleName());
        } else {
            log.info("MeterRegistry not available, skipping metrics listener registration");
        }
    }
    
    /**
     * 注册监听器
     */
    public <T extends CacheEvent> void registerListener(CacheEventListener<T> listener) {
        if (listener == null) {
            log.warn("Attempted to register null listener");
            return;
        }
        
        try {
            eventManager.registerListener(listener);
            registeredListeners.add(listener);
            log.info("Registered cache event listener: {} (priority: {})", 
                listener.getName(), listener.getPriority());
        } catch (Exception e) {
            log.error("Failed to register cache event listener: {}", listener.getName(), e);
            throw new RuntimeException("Failed to register listener: " + listener.getName(), e);
        }
    }
    
    /**
     * 移除监听器
     */
    public void removeListener(CacheEventListener<? extends CacheEvent> listener) {
        if (listener == null) {
            return;
        }
        
        try {
            eventManager.removeListener(listener);
            registeredListeners.remove(listener);
            log.info("Removed cache event listener: {}", listener.getName());
        } catch (Exception e) {
            log.warn("Error removing cache event listener: {}", listener.getName(), e);
        }
    }
    
    /**
     * 获取所有注册的监听器
     */
    public List<CacheEventListener<? extends CacheEvent>> getRegisteredListeners() {
        return new ArrayList<>(registeredListeners);
    }
    
    /**
     * 获取统计监听器
     */
    public CacheStatisticsListener getStatisticsListener() {
        return statisticsListener;
    }
    
    /**
     * 获取日志监听器
     */
    public CacheLoggingListener getLoggingListener() {
        return loggingListener;
    }
    
    /**
     * 获取性能监控监听器
     */
    public CachePerformanceMonitorListener getPerformanceListener() {
        return performanceListener;
    }
    
    /**
     * 获取指标监听器
     */
    public CacheMetricsEventListener getMetricsListener() {
        return metricsListener;
    }
    
    /**
     * 启用所有监听器
     */
    public void enableAllListeners() {
        log.info("Enabling all cache event listeners");
        for (CacheEventListener<? extends CacheEvent> listener : registeredListeners) {
            try {
                if (listener instanceof CacheStatisticsListener) {
                    ((CacheStatisticsListener) listener).enable();
                } else if (listener instanceof CacheLoggingListener) {
                    ((CacheLoggingListener) listener).enable();
                } else if (listener instanceof CachePerformanceMonitorListener) {
                    ((CachePerformanceMonitorListener) listener).enable();
                } else if (listener instanceof CacheMetricsEventListener) {
                    ((CacheMetricsEventListener) listener).enable();
                }
            } catch (Exception e) {
                log.warn("Error enabling listener: {}", listener.getName(), e);
            }
        }
    }
    
    /**
     * 禁用所有监听器
     */
    public void disableAllListeners() {
        log.info("Disabling all cache event listeners");
        for (CacheEventListener<? extends CacheEvent> listener : registeredListeners) {
            try {
                if (listener instanceof CacheStatisticsListener) {
                    ((CacheStatisticsListener) listener).disable();
                } else if (listener instanceof CacheLoggingListener) {
                    ((CacheLoggingListener) listener).disable();
                } else if (listener instanceof CachePerformanceMonitorListener) {
                    ((CachePerformanceMonitorListener) listener).disable();
                } else if (listener instanceof CacheMetricsEventListener) {
                    ((CacheMetricsEventListener) listener).disable();
                }
            } catch (Exception e) {
                log.warn("Error disabling listener: {}", listener.getName(), e);
            }
        }
    }
    
    /**
     * 配置日志监听器
     */
    public void configureLogging(boolean logDetails, boolean logSlowOperations, long slowThreshold) {
        if (loggingListener != null) {
            loggingListener.setLogDetails(logDetails);
            loggingListener.setLogSlowOperations(logSlowOperations);
            loggingListener.setSlowOperationThreshold(slowThreshold);
            log.info("Configured logging listener: details={}, slowOps={}, threshold={}ms",
                logDetails, logSlowOperations, slowThreshold);
        }
    }
    
    /**
     * 配置性能监控
     */
    public void configurePerformanceMonitoring(long slowThreshold, double lowHitRateThreshold, long highErrorThreshold) {
        if (performanceListener != null) {
            performanceListener.setSlowOperationThreshold(slowThreshold);
            performanceListener.setLowHitRateThreshold(lowHitRateThreshold);
            performanceListener.setHighErrorRateThreshold(highErrorThreshold);
            log.info("Configured performance monitoring: slowThreshold={}ms, hitRateThreshold={}, errorThreshold={}",
                slowThreshold, lowHitRateThreshold, highErrorThreshold);
        }
    }
    
    /**
     * 获取监听器数量
     */
    public int getListenerCount() {
        return registeredListeners.size();
    }
    
    /**
     * 获取激活的监听器数量
     */
    public int getActiveListenerCount() {
        return (int) registeredListeners.stream()
            .filter(CacheEventListener::isActive)
            .count();
    }
    
    /**
     * 获取事件管理器
     */
    public CacheEventManager getEventManager() {
        return eventManager;
    }
}