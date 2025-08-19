package io.github.cascade.autoconfigure;

import io.github.cascade.cache.api.CacheManager;
import io.github.cascade.cache.config.unified.CascadeCacheConfiguration;
import io.github.cascade.cache.core.unified.UnifiedCacheBuilder;
import io.github.cascade.cache.manager.SpringBootCacheManager;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Cascade框架统一自动配置类
 * 简化版本，专注于核心功能
 *
 * @author cascade
 */
@AutoConfiguration
@ConditionalOnClass({CacheManager.class, UnifiedCacheBuilder.class})
@ConditionalOnProperty(prefix = "cascade", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(CascadeCacheProperties.class)
public class CascadeAutoConfiguration {

    /**
     * 缓存管理器Bean
     */
    @Bean
    @Primary
    @ConditionalOnMissingBean
    public CacheManager cascadeCacheManager(CascadeCacheProperties properties,
                                            ApplicationContext applicationContext) {
        // 从Spring Boot配置属性创建内部配置
//        CascadeCacheConfiguration defaultConfig = createConfigurationFromProperties(properties);

        // 尝试获取RedissonClient
        RedissonClient redissonClient = null;
        try {
            redissonClient = applicationContext.getBean(RedissonClient.class);
        } catch (Exception e) {
            // RedissonClient不存在时忽略
        }

        return new SpringBootCacheManager(redissonClient, properties);
    }

    /**
     * 从Spring Boot配置属性转换为内部配置对象
     */
    private CascadeCacheConfiguration createConfigurationFromProperties(CascadeCacheProperties properties) {
        // 使用新的统一配置转换方法
        // 直接使用 Properties 的 toCascadeCacheConfiguration 方法
        return properties.toCascadeCacheConfiguration(properties.getDefaultCacheName());
    }
}