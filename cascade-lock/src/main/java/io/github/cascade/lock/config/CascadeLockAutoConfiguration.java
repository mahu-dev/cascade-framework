package io.github.cascade.lock.config;

import io.github.cascade.lock.aspect.DistributedLockAspect;
import io.github.cascade.lock.core.DefaultLockTemplate;
import io.github.cascade.lock.core.LockExecutor;
import io.github.cascade.lock.core.LockTemplate;
import io.github.cascade.lock.core.RedissonLockExecutor;
import io.github.cascade.lock.factory.LockFactory;
import io.github.cascade.lock.key.KeyGenerator;
import io.github.cascade.lock.key.SpelKeyGenerator;
import io.github.cascade.lock.listener.LockEventListener;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
@EnableConfigurationProperties(CascadeLockProperties.class)
@ConditionalOnProperty(prefix = "cascade.lock", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnBean(RedissonClient.class)
public class CascadeLockAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public KeyGenerator lockKeyGenerator() {
        return new SpelKeyGenerator();
    }

    @Bean
    @ConditionalOnMissingBean
    public LockFactory lockFactory(RedissonClient redissonClient) {
        return new LockFactory(redissonClient);
    }

    @Bean
    @ConditionalOnMissingBean
    public LockExecutor lockExecutor(LockFactory lockFactory,
                                     List<LockEventListener> listeners) {
        return new RedissonLockExecutor(lockFactory, listeners);
    }

    @Bean
    @ConditionalOnMissingBean
    public LockTemplate lockTemplate(LockExecutor lockExecutor) {
        return new DefaultLockTemplate(lockExecutor);
    }

    @Bean
    public DistributedLockAspect distributedLockAspect(LockExecutor lockExecutor,
                                                       KeyGenerator keyGenerator,
                                                       CascadeLockProperties properties) {
        return new DistributedLockAspect(lockExecutor, keyGenerator, properties);
    }
}