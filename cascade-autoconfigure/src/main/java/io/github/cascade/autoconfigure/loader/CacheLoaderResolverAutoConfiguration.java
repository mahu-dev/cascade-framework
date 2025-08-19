package io.github.cascade.autoconfigure.loader;

import io.github.cascade.cache.core.CacheLoaderResolver;
import io.github.cascade.cache.core.unified.UnifiedCacheBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * CacheLoader自动配置
 * 负责配置CacheLoader的自动发现机制
 *
 * @author Cascade Framework
 */
@Slf4j
@Configuration
@ConditionalOnClass({CacheLoaderResolver.class, UnifiedCacheBuilder.class})
@ConditionalOnProperty(prefix = "cascade.cache.loader", name = "auto-discover", havingValue = "true", matchIfMissing = true)
public class CacheLoaderResolverAutoConfiguration {

    @Autowired
    private ApplicationContext applicationContext;

    /**
     * 创建CacheLoader解析器
     */
    @Bean
    @ConditionalOnMissingBean
    public CacheLoaderResolver cacheLoaderResolver() {
        log.info("Creating CacheLoaderResolver for automatic CacheLoader discovery");
        // 设置应用上下文，让UnifiedCacheBuilder能够访问Spring容器
        UnifiedCacheBuilder.setApplicationContext(applicationContext);
        CacheLoaderResolver cacheLoaderResolver = new CacheLoaderResolver();
        UnifiedCacheBuilder.setCacheLoaderResolver(cacheLoaderResolver);
        return cacheLoaderResolver;
    }

}