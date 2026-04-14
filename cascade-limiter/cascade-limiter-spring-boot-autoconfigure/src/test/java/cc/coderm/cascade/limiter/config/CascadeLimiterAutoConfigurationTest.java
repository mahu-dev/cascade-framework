package cc.coderm.cascade.limiter.config;

import cc.coderm.cascade.limiter.CascadeLimiter;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.lang.reflect.Proxy;

import static org.assertj.core.api.Assertions.assertThat;

class CascadeLimiterAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CascadeLimiterAutoConfiguration.class))
            .withUserConfiguration(MockRedissonConfiguration.class);

    @Test
    void shouldRegisterCascadeLimiterBeanViaAutoConfiguration() {
        contextRunner.run(context -> assertThat(context).hasSingleBean(CascadeLimiter.class));
    }

    @Configuration
    static class MockRedissonConfiguration {
        @Bean
        RedissonClient redissonClient() {
            return (RedissonClient) Proxy.newProxyInstance(
                    RedissonClient.class.getClassLoader(),
                    new Class<?>[]{RedissonClient.class},
                    (proxy, method, args) -> {
                        if ("toString".equals(method.getName())) {
                            return "RedissonClientStub";
                        }
                        Class<?> returnType = method.getReturnType();
                        if (returnType == void.class) {
                            return null;
                        }
                        if (returnType == boolean.class) {
                            return false;
                        }
                        if (returnType == int.class || returnType == short.class || returnType == byte.class) {
                            return 0;
                        }
                        if (returnType == long.class) {
                            return 0L;
                        }
                        if (returnType == float.class) {
                            return 0F;
                        }
                        if (returnType == double.class) {
                            return 0D;
                        }
                        if (returnType == char.class) {
                            return '\0';
                        }
                        return null;
                    }
            );
        }
    }
}
