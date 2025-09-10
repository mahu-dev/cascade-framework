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
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.List;

/**
 * Cascade 框架函数式缓存自动配置
 * <p>
 * 基于函数式设计思想的缓存架构，提供多级缓存、分布式同步、自动刷新等核心功能
 * <p>
 * 设计原则：
 * 1. 函数式设计：基于Function组合的缓存管道
 * 2. 简洁优雅：去除复杂的工厂注册中心架构
 * 3. 功能完整：支持L1+L2多级缓存、Redis同步、定时刷新
 * 4. 注解支持：@Cacheable、@CacheEvict、@CachePut注解
 *
 * @author cascade
 */
@AutoConfiguration
@ConditionalOnClass({CacheManager.class, FunctionalCacheManager.class})
@ConditionalOnProperty(prefix = "cascade", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(CascadeCacheProperties.class)
public class CacheAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(CacheAutoConfiguration.class);

    /**
     * CacheLoader自动解析器
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "cascade.loader", name = "auto-discover", havingValue = "true",
            matchIfMissing = true)
    public CacheLoaderResolver<?, ?> cacheLoaderResolver(ApplicationContext applicationContext) {
        log.info("创建CacheLoaderResolver用于自动发现CacheLoader实现");
        CacheLoaderResolver<?, ?> resolver = new CacheLoaderResolver<>();
        resolver.setApplicationContext(applicationContext);

        log.info("CacheLoaderResolver已创建并设置ApplicationContext");
        return resolver;
    }

    /**
     * Redis缓存同步工厂Bean
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(RedissonClient.class)
    public RedisCacheSyncFactory redisCacheSyncFactory(@Autowired(required = false) RedissonClient redissonClient) {
        log.info("创建Redis缓存同步工厂: redisClient={}", redissonClient != null ? "已配置" : "未配置");
        return new RedisCacheSyncFactory(redissonClient);
    }

    /**
     * 函数式缓存管理器配置
     */
    @Bean
    @Primary
    @ConditionalOnMissingBean
    public <K, V> FunctionalCacheManager<K, V> functionalCacheManager(
            @Autowired(required = false) RedissonClient redissonClient,
            CascadeCacheProperties defaultConfig,
            @Autowired(required = false) CacheLoaderResolver<?, ?> cacheLoaderResolver,
            @Autowired(required = false) List<CacheSyncFactory> cacheSyncFactories) {
        log.info("配置函数式缓存管理器");

        FunctionalCacheManager<K, V> cacheManager = new FunctionalCacheManager(
                redissonClient,
                defaultConfig,
                cacheLoaderResolver,
                cacheSyncFactories
        );

        log.info("✅ 函数式缓存管理器配置完成，同步工厂数: {}",
                cacheSyncFactories != null ? cacheSyncFactories.size() : 0);
        return cacheManager;
    }

    /**
     * 缓存切面配置
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(name = "org.aspectj.lang.annotation.Aspect")
    @ConditionalOnProperty(prefix = "cascade.cache.annotation", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public CacheAspect cacheAspect(FunctionalCacheManager cacheManager) {

        log.info("配置缓存切面");

        CacheAspect aspect = new CacheAspect(cacheManager);

        log.info("✅ 缓存切面配置完成");
        return aspect;
    }

    /**
     * 配置信息日志初始化
     */
    @Bean
    @ConditionalOnProperty(prefix = "cascade.cache.logging", name = "config-info",
            havingValue = "true", matchIfMissing = true)
    public Object cacheConfigurationInfoInitializer(
            FunctionalCacheManager cacheManager,
            CascadeCacheProperties defaultConfig,
            @Autowired(required = false) RedissonClient redissonClient) {

        CacheConfigurationInfoLogger.logConfigurationInfo(cacheManager, defaultConfig, redissonClient);
        return new Object(); // 返回一个简单对象满足Bean要求
    }

    /**
     * 配置信息记录器
     */
    public static final class CacheConfigurationInfoLogger {

        private static final Logger LOG = LoggerFactory.getLogger(CacheConfigurationInfoLogger.class);

        private CacheConfigurationInfoLogger() {
            // 工具类不允许实例化
        }

        public static void logConfigurationInfo(FunctionalCacheManager cacheManager,
                                                CascadeCacheProperties defaultConfig,
                                                RedissonClient redissonClient) {

            LOG.info("=== Cascade 函数式缓存架构配置信息 ===");
            LOG.info("✅ 缓存管理器: {}", cacheManager.getClass().getSimpleName());
            LOG.info("✅ 默认配置: {}", defaultConfig);
            LOG.info("✅ Redis客户端: {}", redissonClient != null ? "已配置" : "未配置");
            LOG.info("✅ 支持功能: 函数式管道, 多级缓存(L1+L2), 分布式同步, 定时刷新, 注解支持");
            LOG.info("✅ 架构特性: Function组合设计, orElse管道, 装饰器模式");
            LOG.info("✅ 缓存类型: L1(Caffeine) + L2(Redisson) + 分布式同步(Redis Pub/Sub)");
            LOG.info("=====================================");
        }
    }
}