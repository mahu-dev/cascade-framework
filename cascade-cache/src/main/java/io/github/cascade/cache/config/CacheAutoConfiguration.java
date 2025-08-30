package io.github.cascade.cache.config;

import io.github.cascade.cache.simple.*;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Cascade 框架精简缓存自动配置
 * <p>
 * 基于全新简化架构，提供多级缓存、分布式同步、自动刷新等核心功能
 * <p>
 * 设计原则：
 * 1. 简洁设计：精简的架构和配置
 * 2. 功能完整：支持L1+L2多级缓存、Redis同步、定时刷新
 * 3. 注解支持：@Cacheable、@CacheEvict、@CachePut注解
 * 4. 编程一致：注解式和编程式功能完全一致
 *
 * @author cascade
 */
@AutoConfiguration
@ConditionalOnClass({CacheManager.class, CacheManagerImpl.class})
@ConditionalOnProperty(prefix = "cascade", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(CascadeCacheProperties.class)
public class CacheAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(CacheAutoConfiguration.class);

    /**
     * 新架构缓存管理器配置
     */
    @Bean
    @Primary
    @ConditionalOnMissingBean
    public CacheManagerImpl cacheManagerImpl(
            RedissonClient redissonClient, 
            CascadeCacheProperties defaultConfig,
            @Autowired(required = false) CacheLoaderResolver cacheLoaderResolver) {
        log.info("配置新架构缓存管理器");
        
        // 创建组件
        CacheRegistry cacheRegistry = new DefaultCacheRegistry();
        CacheFactoryRegistry factoryRegistry = new CacheFactoryRegistry();
        LifecycleManager lifecycleManager = new DefaultLifecycleManager();
        
        // 注册工厂
        factoryRegistry.registerFactory(new L1CacheFactory());
        if (redissonClient != null) {
            factoryRegistry.registerFactory(new L2CacheFactory(redissonClient));
            factoryRegistry.registerFactory(new TieredCacheFactory(redissonClient));
        }
        
        CacheManagerImpl cacheManager = new CacheManagerImpl(
                cacheRegistry, 
                factoryRegistry, 
                lifecycleManager,
                defaultConfig,
                cacheLoaderResolver
        );
        
        log.info("✅ 新架构缓存管理器配置完成");
        return cacheManager;
    }

    /**
     * CacheLoader自动解析器
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "cascade.loader", name = "auto-discover", havingValue = "true", matchIfMissing = true)
    public CacheLoaderResolver cacheLoaderResolver() {
        log.info("创建CacheLoaderResolver用于自动发现CacheLoader实现");

        CacheLoaderResolver resolver = new CacheLoaderResolver();

        log.info("CacheLoaderResolver已创建");
        return resolver;
    }

    /**
     * 缓存切面配置
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(name = "org.aspectj.lang.annotation.Aspect")
    @ConditionalOnProperty(prefix = "cascade.cache.annotation", name = "enabled", havingValue = "true", matchIfMissing = true)
    public CacheAspect cacheAspect(CacheManagerImpl cacheManager) {

        log.info("配置缓存切面");

        CacheAspect aspect = new CacheAspect(cacheManager);

        log.info("✅ 缓存切面配置完成");
        return aspect;
    }

    /**
     * 配置信息日志
     */
    @Bean
    @ConditionalOnProperty(prefix = "cascade.cache.logging", name = "config-info", havingValue = "true", matchIfMissing = true)
    public CacheConfigurationInfoLogger cacheConfigurationInfoLogger(
            CacheManagerImpl cacheManager,
            CascadeCacheProperties defaultConfig,
            RedissonClient redissonClient) {

        return new CacheConfigurationInfoLogger(cacheManager, defaultConfig, redissonClient);
    }

    /**
     * 配置信息记录器
     */
    public static class CacheConfigurationInfoLogger {

        private static final Logger log = LoggerFactory.getLogger(CacheConfigurationInfoLogger.class);

        public CacheConfigurationInfoLogger(CacheManagerImpl cacheManager,
                                            CascadeCacheProperties defaultConfig,
                                            RedissonClient redissonClient) {

            log.info("=== Cascade 精简缓存架构配置信息 ===");
            log.info("✅ 缓存管理器: {}", cacheManager.getClass().getSimpleName());
            log.info("✅ 默认配置: {}", defaultConfig);
            log.info("✅ Redis客户端: {}", redissonClient != null ? "已配置" : "未配置");
            log.info("✅ 支持功能: 多级缓存(L1+L2), 分布式同步, 定时刷新, 注解支持, 自动CacheLoader发现");
            log.info("✅ 架构特性: 编程式和注解式功能完全一致, 简洁易用");
            log.info("✅ 缓存类型: L1(Caffeine) + L2(Redisson) + 分布式同步(Redis Pub/Sub)");
            log.info("=====================================");
        }
    }
}