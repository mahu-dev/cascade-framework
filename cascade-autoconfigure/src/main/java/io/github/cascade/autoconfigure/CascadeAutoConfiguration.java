package io.github.cascade.autoconfigure;

import io.github.cascade.cache.api.CacheManager;
import io.github.cascade.cache.config.unified.CascadeCacheConfiguration;
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
import org.springframework.context.annotation.Primary;

import java.time.Duration;

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
        CascadeCacheConfiguration defaultConfig = createConfigurationFromProperties(properties);

        // 尝试获取RedissonClient
        RedissonClient redissonClient = null;
        try {
            redissonClient = applicationContext.getBean(RedissonClient.class);
        } catch (Exception e) {
            // RedissonClient不存在时忽略
        }

        return new SpringBootCacheManager(defaultConfig, redissonClient);
    }

    /**
     * 从Spring Boot配置属性转换为内部配置对象
     */
    private CascadeCacheConfiguration createConfigurationFromProperties(CascadeCacheProperties properties) {
        CascadeCacheConfiguration config = new CascadeCacheConfiguration()
                .setName(properties.getDefaultCacheName())
                .setEnabled(properties.isEnabled());

        // L1缓存配置
        if (properties.getDefaultSize() > 0) {
            config.getL1()
                    .setMaximumSize(properties.getDefaultSize())
                    .setExpireAfterWrite(Duration.ofSeconds(properties.getL1ExpireAfterWriteSeconds()))
                    .setEnabled(true);
        }

        // L2缓存配置
        config.getL2()
                .setEnabled(properties.isEnableRedis())
                .setDefaultTtl(Duration.ofSeconds(properties.getL2ExpireAfterWriteSeconds()));

        // 同步配置
        config.getSync()
                .setEnabled(properties.isEnableSync());

        // 防护配置
        config.getProtection()
                .setEnabled(properties.isEnableProtection());

        // 布隆过滤器配置
        if (properties.isEnableBloomFilter()) {
            config.getProtection().getBloomFilter()
                    .setEnabled(true)
                    .setExpectedElements(properties.getBloomFilterExpectedInsertions())
                    .setFalsePositiveRate(properties.getBloomFilterFpp());
        }

        // 随机TTL配置
        if (properties.isEnableRandomTtl()) {
            config.getProtection().getRandomTtl()
                    .setEnabled(true)
                    .setJitterRange(Duration.ofSeconds(properties.getRandomTtlRangeSeconds()));
        }

        return config;
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