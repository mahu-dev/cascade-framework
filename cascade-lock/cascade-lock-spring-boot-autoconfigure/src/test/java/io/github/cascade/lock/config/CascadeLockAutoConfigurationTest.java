package io.github.cascade.lock.config;

import io.github.cascade.lock.aspect.DistributedLockAspect;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.lang.reflect.Proxy;

class CascadeLockAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CascadeLockAutoConfiguration.class));

    @Test
    void shouldRegisterAspectWhenRedissonClientExists() {
        contextRunner
                .withBean(RedissonClient.class, CascadeLockAutoConfigurationTest::redissonClientStub)
                .run(context -> {
                    context.getBean(DistributedLockAspect.class);
                    context.getBean("lockExecutor");
                    context.getBean("lockTemplate");
                });
    }

    @Test
    void shouldSkipAutoConfigurationWhenRedissonClientMissing() {
        contextRunner.run(context -> {
            assertNotNull(context);
            assertFalse(context.containsBean("distributedLockAspect"));
        });
    }

    private static RedissonClient redissonClientStub() {
        return (RedissonClient) Proxy.newProxyInstance(
                CascadeLockAutoConfigurationTest.class.getClassLoader(),
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
