package io.github.cascade.autoconfigure;

import io.github.cascade.cache.api.CacheManager;
import io.github.cascade.cache.builder.TierConfigurationStep;
import io.github.cascade.cache.core.unified.UnifiedCacheBuilder;
import io.github.cascade.cache.manager.CascadeCacheManager;
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
     * CascadeCacheManager Bean - 具体实现类
     */
    @Bean
    @ConditionalOnMissingBean
    public CascadeCacheManager cascadeCacheManager(CascadeCacheProperties properties,
                                                         ApplicationContext applicationContext) {
        // 从Spring Boot配置属性创建内部配置

        // 尝试获取RedissonClient
        RedissonClient redissonClient = null;
        try {
            redissonClient = applicationContext.getBean(RedissonClient.class);
        } catch (Exception e) {
            // RedissonClient不存在时忽略
        }

        return new CascadeCacheManager(redissonClient, properties);
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
         * 创建字符串键缓存构建器
         */
        public <V> TierConfigurationStep<String, V> stringCache(String cacheName, Class<V> valueType) {
            return UnifiedCacheBuilder.stringCache(cacheName, valueType);
        }

        /**
         * 创建长整型键缓存构建器
         */
        public <V> TierConfigurationStep<Long, V> longCache(String cacheName, Class<V> valueType) {
            return UnifiedCacheBuilder.longCache(cacheName, valueType);
        }

        /**
         * 创建整型键缓存构建器
         */
        public <V> TierConfigurationStep<Integer, V> intCache(String cacheName, Class<V> valueType) {
            return UnifiedCacheBuilder.intCache(cacheName, valueType);
        }

        /**
         * 创建通用缓存构建器
         */
        public <K, V> TierConfigurationStep<K, V> createBuilder(String cacheName, Class<K> keyType, Class<V> valueType) {
            return UnifiedCacheBuilder.forCache(cacheName, keyType, valueType);
        }

    }

}