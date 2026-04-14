package cc.coderm.cascade.bloom.config;

import cc.coderm.cascade.bloom.aspect.BloomFilterAspect;
import cc.coderm.cascade.bloom.core.BloomFilterManager;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.lang.reflect.Proxy;

import static org.assertj.core.api.Assertions.assertThat;

class BloomFilterAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(BloomFilterAutoConfiguration.class));

    @Test
    void shouldRegisterBloomInfrastructureWhenRedissonClientExists() {
        contextRunner
                .withBean(RedissonClient.class, BloomFilterAutoConfigurationTest::redissonClientStub)
                .run(context -> {
                    assertThat(context).hasSingleBean(BloomFilterManager.class);
                    assertThat(context).hasSingleBean(BloomFilterAspect.class);
                    assertThat(context).hasBean("bloomFilterTemplate");
                    assertThat(context).hasBean("bloomFilterStartupInitializer");
                });
    }

    @Test
    void shouldSkipAutoConfigurationWhenRedissonClientMissing() {
        contextRunner.run(context -> assertThat(context).doesNotHaveBean(BloomFilterManager.class));
    }

    @Test
    void shouldExposePropertiesBean() {
        contextRunner
                .withBean(RedissonClient.class, BloomFilterAutoConfigurationTest::redissonClientStub)
                .withPropertyValues(
                        "cascade.bloom.key-prefix=test:bloom:",
                        "cascade.bloom.default-expected-insertions=4096",
                        "cascade.bloom.default-false-probability=0.01"
                )
                .run(context -> {
                    BloomFilterProperties properties = context.getBean(BloomFilterProperties.class);
                    assertThat(properties.getKeyPrefix()).isEqualTo("test:bloom:");
                    assertThat(properties.getDefaultExpectedInsertions()).isEqualTo(4096L);
                    assertThat(properties.getDefaultFalseProbability()).isEqualTo(0.01d);
                });
    }

    private static RedissonClient redissonClientStub() {
        return (RedissonClient) Proxy.newProxyInstance(
                BloomFilterAutoConfigurationTest.class.getClassLoader(),
                new Class<?>[]{RedissonClient.class},
                (proxy, method, args) -> {
                    Class<?> returnType = method.getReturnType();
                    if (returnType == boolean.class) {
                        return false;
                    }
                    if (returnType == byte.class) {
                        return (byte) 0;
                    }
                    if (returnType == short.class) {
                        return (short) 0;
                    }
                    if (returnType == int.class) {
                        return 0;
                    }
                    if (returnType == long.class) {
                        return 0L;
                    }
                    if (returnType == float.class) {
                        return 0f;
                    }
                    if (returnType == double.class) {
                        return 0d;
                    }
                    if (returnType == char.class) {
                        return '\0';
                    }
                    return null;
                }
        );
    }
}
