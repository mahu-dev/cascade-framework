package io.github.cascade.cache.config;

import io.github.cascade.cache.simple.CacheLoaderResolver;
import io.github.cascade.cache.simple.SimpleCacheManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

/**
 * CacheLoader自动配置
 * 负责配置CacheLoader的自动发现机制
 *
 * @author Cascade Framework
 */
@Configuration
@ConditionalOnClass({CacheLoaderResolver.class, SimpleCacheManager.class})
@ConditionalOnProperty(prefix = "cascade.cache.loader", name = "auto-discover", havingValue = "true", matchIfMissing = true)
public class CacheLoaderAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(CacheLoaderAutoConfiguration.class);

    private final ApplicationContext applicationContext;

    public CacheLoaderAutoConfiguration(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    /**
     * 确保在应用启动完成后将CacheLoaderResolver配置到SimpleCacheManager
     */
    @EventListener(ApplicationReadyEvent.class)
    public void configureCacheLoaderResolverToManagers(ApplicationReadyEvent event) {
        log.debug("应用启动完成，配置CacheLoaderResolver到缓存管理器");

        try {
            // 查找CacheLoaderResolver
            CacheLoaderResolver resolver = applicationContext.getBean(CacheLoaderResolver.class);
            resolver.setApplicationContext(applicationContext);

            // 查找所有SimpleCacheManager实例并设置CacheLoaderResolver
            applicationContext.getBeansOfType(SimpleCacheManager.class).forEach((name, cacheManager) -> {
                cacheManager.setCacheLoaderResolver(resolver);
                log.info("✅ 已将CacheLoaderResolver配置到缓存管理器: {}", name);
            });

            log.info("✅ CacheLoaderResolver配置完成");
        } catch (Exception e) {
            log.warn("配置CacheLoaderResolver时出现异常: {}", e.getMessage());
        }
    }
}