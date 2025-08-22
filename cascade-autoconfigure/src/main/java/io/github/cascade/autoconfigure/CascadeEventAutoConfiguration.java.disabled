package io.github.cascade.autoconfigure;

import io.github.cascade.cache.event.UnifiedEventProcessor;
import io.github.cascade.cache.metrics.UnifiedMonitoringManager;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Cascade缓存事件自动配置类
 * 提供统一的缓存事件处理和监控配置
 *
 * @author cascade
 */
@AutoConfiguration
@ConditionalOnClass({UnifiedEventProcessor.class, UnifiedMonitoringManager.class})
@ConditionalOnProperty(prefix = "cascade.cache.events", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CascadeEventAutoConfiguration {

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