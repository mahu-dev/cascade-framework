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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;
import java.util.concurrent.Executor;

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

    public static final String BLOOM_FILTER_INITIALIZATION_EXECUTOR_BEAN_NAME = "bloomFilterInitializationExecutor";

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
            ObjectProvider<BloomFilterInitializer> initializerProvider,
            @Qualifier(BLOOM_FILTER_INITIALIZATION_EXECUTOR_BEAN_NAME) Executor initializationExecutor) {

        List<BloomFilterInitializer> initializers = initializerProvider.orderedStream().toList();
        return new BloomFilterStartupInitializer(
                bloomFilterManager,
                properties,
                initializers,
                initializationExecutor
        );
    }

    @Bean(name = BLOOM_FILTER_INITIALIZATION_EXECUTOR_BEAN_NAME)
    @ConditionalOnMissingBean(name = BLOOM_FILTER_INITIALIZATION_EXECUTOR_BEAN_NAME)
    public Executor bloomFilterInitializationExecutor(BloomFilterProperties properties) {
        BloomFilterProperties.InitializerExecutorProperties executorProperties = properties.getInitializerExecutor();

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(executorProperties.getCorePoolSize());
        executor.setMaxPoolSize(executorProperties.getMaxPoolSize());
        executor.setQueueCapacity(executorProperties.getQueueCapacity());
        executor.setKeepAliveSeconds(executorProperties.getKeepAliveSeconds());
        executor.setAllowCoreThreadTimeOut(executorProperties.isAllowCoreThreadTimeOut());
        executor.setThreadNamePrefix(executorProperties.getThreadNamePrefix());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(executorProperties.getAwaitTerminationSeconds());
        executor.initialize();
        return executor;
    }
}
