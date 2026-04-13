package io.github.cascade.cache.configuration;

import io.github.cascade.cache.v2.api.CacheManager;
import io.github.cascade.cache.v2.facade.CacheAspect;
import io.github.cascade.cache.v2.facade.FunctionalCacheManager;
import io.github.cascade.cache.v2.loader.CacheLoaderResolver;
import io.micrometer.core.instrument.MeterRegistry;
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

/**
 * @author lionel lionelk@163.com
 * =============================
 * Date: 2025/7/10
 * Time: 13:23
 * =============================
 */

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
 * <p>
 * P0级重构优化（2025-10-29）：
 * - 修复泛型类型推导问题
 * - 统一Logger命名规范
 * - 简化Bean创建逻辑
 */
@AutoConfiguration
@ConditionalOnClass({CacheManager.class, FunctionalCacheManager.class})
@ConditionalOnProperty(prefix = "cascade", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties({CascadeCacheProperties.class, SyncProperties.class})
public class CacheAutoConfiguration {

    private static final Logger LOGGER = LoggerFactory.getLogger(CacheAutoConfiguration.class);

    /**
     * CacheLoader自动解析器
     */
    @Bean
    @ConditionalOnMissingBean
    public CacheLoaderResolver cacheLoaderResolver(ApplicationContext applicationContext,
                                                   CascadeCacheProperties defaultConfig) {
        LOGGER.info("创建CacheLoaderResolver用于自动发现CacheLoader实现");
        CacheLoaderResolver resolver = new CacheLoaderResolver();
        resolver.setAutoDiscoverEnabled(defaultConfig.getLoader().isAutoDiscover());
        resolver.setApplicationContext(applicationContext);

        LOGGER.info("CacheLoaderResolver已创建并设置ApplicationContext");
        return resolver;
    }

    /**
     * 函数式缓存管理器配置
     * P0级重构修复：移除方法级别泛型约束
     */
    @Bean
    @Primary
    @ConditionalOnMissingBean
    public FunctionalCacheManager functionalCacheManager(
            @Autowired(required = false) RedissonClient redissonClient,
            CascadeCacheProperties defaultConfig,
            @Autowired(required = false) CacheLoaderResolver cacheLoaderResolver,
            @Autowired(required = false) MeterRegistry meterRegistry) {
        LOGGER.info("配置函数式缓存管理器");

        FunctionalCacheManager cacheManager = new FunctionalCacheManager(
                redissonClient,
                defaultConfig,
                cacheLoaderResolver,
                meterRegistry
        );

        LOGGER.info("✅ 函数式缓存管理器配置完成");
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
    public CacheAspect cacheAspect(CacheManager cacheManager, CascadeCacheProperties defaultConfig) {
        LOGGER.info("创建缓存切面");
        return new CacheAspect(cacheManager, defaultConfig);
    }
}
