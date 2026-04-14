package io.github.cascade.lock.starter;

import io.github.cascade.lock.aspect.DistributedLockAspect;
import io.github.cascade.lock.core.LockTemplate;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

import java.lang.reflect.Proxy;

import static org.assertj.core.api.Assertions.assertThat;

class LockSpringBoot4StarterTest {

    @Test
    void shouldAutoConfigureLockBeansViaStarter() {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(TestApplication.class)
                .web(WebApplicationType.NONE)
                .run("--spring.main.banner-mode=off")) {
            AssertableApplicationContext assertable = AssertableApplicationContext.get(() -> context);
            assertThat(assertable).hasBean("lockFactory");
            assertThat(assertable).hasBean("lockExecutor");
            assertThat(assertable).hasSingleBean(LockTemplate.class);
            assertThat(assertable).hasSingleBean(DistributedLockAspect.class);
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {

        @Bean
        RedissonClient redissonClient() {
            return redissonClientStub();
        }
    }

    private static RedissonClient redissonClientStub() {
        return (RedissonClient) Proxy.newProxyInstance(
                LockSpringBoot4StarterTest.class.getClassLoader(),
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
