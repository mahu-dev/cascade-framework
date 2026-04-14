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
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * cascade-bloom 自动装配配置类。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BloomFilterProperties.class)
@AutoConfigureAfter(name = {
        "org.redisson.spring.starter.RedissonAutoConfiguration",
        "org.redisson.spring.starter.RedissonAutoConfigurationV2"
})
@ConditionalOnProperty(prefix = "cascade.bloom", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnBean(RedissonClient.class)
public class BloomFilterAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(BloomFilterManager.class)
    public BloomFilterManager bloomFilterManager(RedissonClient redissonClient,
            BloomFilterProperties properties) {
        return new RedissonBloomFilterManager(redissonClient, properties);
    }

    @Bean
    @ConditionalOnMissingBean(BloomFilterTemplate.class)
    public BloomFilterTemplate bloomFilterTemplate(BloomFilterManager bloomFilterManager) {
        return new DefaultBloomFilterTemplate(bloomFilterManager);
    }

    @Bean
    @ConditionalOnMissingBean(BloomFilterAspect.class)
    public BloomFilterAspect bloomFilterAspect(BloomFilterManager bloomFilterManager,
            BloomFilterProperties properties) {
        return new BloomFilterAspect(bloomFilterManager, properties);
    }

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
