package io.github.cascade.autoconfigure.annotation;

import io.github.cascade.cache.annotation.CascadeCacheAspect;
import io.github.cascade.cache.api.CacheManager;
import io.github.cascade.cache.core.CacheLoaderResolver;
import io.github.cascade.cache.event.UnifiedEventProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

/**
 * Cascade缓存注解自动配置类
 * 负责配置缓存注解切面和相关组件
 * 
 * @author cascade
 */
@AutoConfiguration
@ConditionalOnClass({CascadeCacheAspect.class, CacheManager.class})
@ConditionalOnProperty(prefix = "cascade.cache.annotation", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableAspectJAutoProxy
public class CascadeCacheAnnotationAutoConfiguration {

    /**
     * 配置缓存注解切面
     */
    @Bean
    @ConditionalOnMissingBean
    public CascadeCacheAspect cascadeCacheAspect(ApplicationContext applicationContext,
                                               CacheManager cacheManager,
                                               UnifiedEventProcessor eventProcessor,
                                               CacheLoaderResolver cacheLoaderResolver) {
        return new CascadeCacheAspect(applicationContext, cacheManager, eventProcessor, cacheLoaderResolver);
    }

    /**
     * 配置事件处理器（如果不存在）
     */
    @Bean
    @ConditionalOnMissingBean
    public UnifiedEventProcessor unifiedEventProcessor() {
        return new UnifiedEventProcessor();
    }

    /**
     * 配置缓存加载器解析器（如果不存在）
     */
    @Bean
    @ConditionalOnMissingBean
    public CacheLoaderResolver cacheLoaderResolver() {
        return new CacheLoaderResolver();
    }
}