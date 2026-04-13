package cc.coderm.cascade.bloom.config;

import cc.coderm.cascade.bloom.aspect.BloomFilterAspect;
import cc.coderm.cascade.bloom.core.BloomFilterManager;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

class BloomFilterAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(BloomFilterAutoConfiguration.class));

    @Test
    void shouldRegisterBloomInfrastructureWhenRedissonClientExists() {
        contextRunner
                .withBean(RedissonClient.class, () -> mock(RedissonClient.class))
                .run(context -> {
                    context.getBean(BloomFilterManager.class);
                    context.getBean(BloomFilterAspect.class);
                    context.getBean("bloomFilterTemplate");
                    context.getBean("bloomFilterStartupInitializer");
                });
    }

    @Test
    void shouldSkipAutoConfigurationWhenRedissonClientMissing() {
        contextRunner.run(context -> assertFalse(context.containsBean("bloomFilterManager")));
    }

    @Test
    void shouldRunAfterRedissonAutoConfiguration() {
        AutoConfiguration annotation = BloomFilterAutoConfiguration.class.getAnnotation(AutoConfiguration.class);

        assertNotNull(annotation);
        assertArrayEquals(new String[]{
                        "org.redisson.spring.starter.RedissonAutoConfigurationV2",
                        "org.redisson.spring.starter.RedissonAutoConfiguration"
                },
                annotation.afterName());
    }
}
