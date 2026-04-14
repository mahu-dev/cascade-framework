package cc.coderm.cascade.bloom.starter;

import cc.coderm.cascade.bloom.aspect.BloomFilterAspect;
import cc.coderm.cascade.bloom.core.BloomFilterManager;
import cc.coderm.cascade.bloom.core.BloomFilterTemplate;
import cc.coderm.cascade.bloom.initializer.BloomFilterStartupInitializer;
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

class BloomSpringBoot3StarterTest {

    @Test
    void shouldAutoConfigureBloomBeansViaStarter() {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(TestApplication.class)
                .web(WebApplicationType.NONE)
                .run("--spring.main.banner-mode=off")) {
            AssertableApplicationContext assertable = AssertableApplicationContext.get(() -> context);
            assertThat(assertable).hasSingleBean(BloomFilterManager.class);
            assertThat(assertable).hasSingleBean(BloomFilterTemplate.class);
            assertThat(assertable).hasSingleBean(BloomFilterAspect.class);
            assertThat(assertable).hasSingleBean(BloomFilterStartupInitializer.class);
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
                BloomSpringBoot3StarterTest.class.getClassLoader(),
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
