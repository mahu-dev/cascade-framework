package cc.coderm.cascade.bloom.config;


import cc.coderm.cascade.bloom.aspect.BloomFilterAspect;
import cc.coderm.cascade.bloom.core.BloomFilterManager;
import cc.coderm.cascade.bloom.core.BloomFilterTemplate;
import cc.coderm.cascade.bloom.impl.DefaultBloomFilterTemplate;
import cc.coderm.cascade.bloom.impl.RedissonBloomFilterManager;
import cc.coderm.cascade.bloom.initializer.BloomFilterInitializer;
import cc.coderm.cascade.bloom.initializer.BloomFilterStartupInitializer;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.List;

/**
 * cascade-bloom 自动装配配置类
 * <p>
 * 在引入 {@code cascade-bloom} 依赖后自动生效，无需手动配置。
 * 可通过 {@code cascade.bloom.enabled=false} 关闭整个模块。
 *
 * @author lionel lionelk@163.com
 * =============================
 * Date: 2025-01-10
 * Time: 16:00:00
 * =============================
 */
@AutoConfiguration(afterName = {
        "org.redisson.spring.starter.RedissonAutoConfigurationV2",
        "org.redisson.spring.starter.RedissonAutoConfiguration"
})
@EnableConfigurationProperties(BloomFilterProperties.class)
@ConditionalOnProperty(prefix = "cascade.bloom", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnBean(RedissonClient.class)
public class BloomFilterAutoConfiguration {

    /**
     * 注册布隆过滤器管理器
     */
    @Bean
    @ConditionalOnMissingBean(BloomFilterManager.class)
    public BloomFilterManager bloomFilterManager(RedissonClient redissonClient,
                                                 BloomFilterProperties properties) {
        return new RedissonBloomFilterManager(redissonClient, properties);
    }

    /**
     * 注册布隆过滤器操作模板
     */
    @Bean
    @ConditionalOnMissingBean(BloomFilterTemplate.class)
    public BloomFilterTemplate bloomFilterTemplate(BloomFilterManager bloomFilterManager) {
        return new DefaultBloomFilterTemplate(bloomFilterManager);
    }

    /**
     * 注册 AOP 切面（拦截 @BloomFilter 注解）
     */
    @Bean
    @ConditionalOnMissingBean(BloomFilterAspect.class)
    public BloomFilterAspect bloomFilterAspect(BloomFilterManager bloomFilterManager,
                                                BloomFilterProperties properties) {
        return new BloomFilterAspect(bloomFilterManager, properties);
    }

    /**
     * 注册启动初始化器（监听 ApplicationReadyEvent）
     * <p>
     * 自动收集容器中所有 {@link BloomFilterInitializer} Bean 并在启动后执行。
     */
    @Bean
    @ConditionalOnMissingBean(BloomFilterStartupInitializer.class)
    public BloomFilterStartupInitializer bloomFilterStartupInitializer(
            BloomFilterManager bloomFilterManager,
            BloomFilterProperties properties,
            ObjectProvider<BloomFilterInitializer> initializerProvider) {

        List<BloomFilterInitializer> initializers = initializerProvider.orderedStream().toList();
        return new BloomFilterStartupInitializer(bloomFilterManager, properties, initializers);
    }
}
