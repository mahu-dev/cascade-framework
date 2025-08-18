package io.github.cascade.cache.event;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 缓存事件配置类
 * 提供默认的缓存事件监听器配置
 *
 * @author cascade
 */
@Configuration
@ConditionalOnProperty(prefix = "cascade.cache.events", name = "enabled", havingValue = "true", matchIfMissing = false)
public class CacheEventConfiguration {

    /**
     * 缓存统计监听器
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "cascade.cache.events.statistics", name = "enabled", havingValue = "true", matchIfMissing = true)
    public CacheStatisticsListener cacheStatisticsListener() {
        return new CacheStatisticsListener();
    }

    /**
     * 缓存日志监听器
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "cascade.cache.events.logging", name = "enabled", havingValue = "true", matchIfMissing = true)
    public CacheLoggingListener cacheLoggingListener() {
        CacheLoggingListener listener = new CacheLoggingListener();
        // 可以通过配置属性设置日志级别和详细程度
        return listener;
    }
}