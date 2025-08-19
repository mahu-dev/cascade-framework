package io.github.cascade.autoconfigure;

import io.github.cascade.cache.api.CacheManager;
import io.github.cascade.cache.config.CascadeCacheConfiguration;
import io.github.cascade.cache.core.unified.UnifiedCacheBuilder;
import io.github.cascade.cache.manager.SpringBootCacheManager;
import lombok.Getter;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

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
     * SpringBootCacheManager Bean - 具体实现类
     */
    @Bean
    @ConditionalOnMissingBean
    public SpringBootCacheManager springBootCacheManager(CascadeCacheProperties properties,
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
     * CacheManager Bean - 接口类型，指向SpringBootCacheManager
     */
//    @Bean
//    @Primary
//    @ConditionalOnMissingBean(name = "cacheManager")
//    public CacheManager cascadeCacheManager(SpringBootCacheManager springBootCacheManager) {
//        return springBootCacheManager;
//    }

    /**
     * 从Spring Boot配置属性转换为内部配置对象
     */
    private CascadeCacheConfiguration createConfigurationFromProperties(CascadeCacheProperties properties) {
        // 使用新的统一配置转换方法
        // 直接使用 Properties 的 toCascadeCacheConfiguration 方法
        return properties.toCascadeCacheConfiguration(properties.getDefaultCacheName());
    }


    /**
     * 缓存构建器工厂Bean
     */
    @Bean
    @ConditionalOnMissingBean
    public CascadeBuilderFactory cacheBuilderFactory(CacheManager cacheManager) {
        return new CascadeBuilderFactory(cacheManager);
    }

    /**
     * 缓存构建器工厂
     */
    @Getter
    public static class CascadeBuilderFactory {

        /**
         * -- GETTER --
         * 获取缓存管理器
         */
        private final CacheManager cacheManager;

        public CascadeBuilderFactory(CacheManager cacheManager) {
            this.cacheManager = cacheManager;
        }

        /**
         * 创建缓存构建器
         */
        public <K, V> UnifiedCacheBuilder<K, V> createBuilder(String cacheName) {
            return new UnifiedCacheBuilder<>(cacheName);
        }

        /**
         * 创建缓存构建器（使用自定义配置）
         */
        public <K, V> UnifiedCacheBuilder<K, V> createBuilder(String cacheName, CascadeCacheConfiguration config) {
            return new UnifiedCacheBuilder<>(cacheName);
        }

    }

}