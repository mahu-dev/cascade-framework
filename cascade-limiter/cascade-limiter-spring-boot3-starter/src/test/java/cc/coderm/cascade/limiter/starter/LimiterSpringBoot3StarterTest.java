package cc.coderm.cascade.limiter.starter;

import cc.coderm.cascade.idempotent.IdempotentTemplate;
import cc.coderm.cascade.idempotent.aspect.IdempotentAspect;
import cc.coderm.cascade.limiter.CascadeLimiter;
import cc.coderm.cascade.limiter.aspect.RateLimitAspect;
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

class LimiterSpringBoot3StarterTest {

    @Test
    void shouldAutoConfigureLimiterBeansViaStarter() {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(TestApplication.class)
                .web(WebApplicationType.NONE)
                .run(
                        "--spring.main.banner-mode=off",
                        "--cascade.idempotent.wait-pub-sub-enabled=false"
                )) {
            AssertableApplicationContext assertable = AssertableApplicationContext.get(() -> context);
            assertThat(assertable).hasBean("rateLimiterFactory");
            assertThat(assertable).hasSingleBean(CascadeLimiter.class);
            assertThat(assertable).hasSingleBean(RateLimitAspect.class);
            assertThat(assertable).hasSingleBean(IdempotentTemplate.class);
            assertThat(assertable).hasSingleBean(IdempotentAspect.class);
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
                LimiterSpringBoot3StarterTest.class.getClassLoader(),
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
