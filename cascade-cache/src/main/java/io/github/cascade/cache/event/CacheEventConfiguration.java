package io.github.cascade.cache.event;

import io.github.cascade.cache.event.unified.UnifiedEventProcessor;
import io.github.cascade.cache.monitoring.UnifiedMonitoringManager;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 缓存事件配置类
 * 提供统一的缓存事件处理配置
 *
 * @author cascade
 */
@Configuration
@ConditionalOnProperty(prefix = "cascade.cache.events", name = "enabled", havingValue = "true", matchIfMissing = false)
public class CacheEventConfiguration {

    /**
     * 统一事件处理器
     */
    @Bean
    @ConditionalOnMissingBean
    public UnifiedEventProcessor unifiedEventProcessor() {
        return new UnifiedEventProcessor(true);
    }

    /**
     * 统一监控管理器
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "cascade.cache.monitoring", name = "enabled", havingValue = "true", matchIfMissing = true)
    public UnifiedMonitoringManager unifiedMonitoringManager() {
        UnifiedMonitoringManager.MonitoringConfiguration config = 
            new UnifiedMonitoringManager.MonitoringConfiguration()
                .enableAsyncEventProcessing(true)
                .enableEventLogging(true)
                .enablePeriodicMonitoring(true);
        
        return new UnifiedMonitoringManager(config);
    }
}