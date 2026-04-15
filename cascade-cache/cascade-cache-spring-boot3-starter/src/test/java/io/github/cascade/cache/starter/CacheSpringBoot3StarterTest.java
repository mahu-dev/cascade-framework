package io.github.cascade.cache.starter;

import io.github.cascade.cache.v2.api.CacheManager;
import io.github.cascade.cache.v2.facade.CacheAspectSupport;
import io.github.cascade.cache.v2.facade.CacheEvictAspect;
import io.github.cascade.cache.v2.facade.CacheInvocationSnapshotSupport;
import io.github.cascade.cache.v2.facade.CachePutAspect;
import io.github.cascade.cache.v2.facade.CacheableAspect;
import io.github.cascade.cache.v2.facade.FunctionalCacheManager;
import io.github.cascade.cache.v2.loader.CacheLoaderResolver;
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

class CacheSpringBoot3StarterTest {

    @Test
    void shouldAutoConfigureCacheBeansViaStarter() {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(TestApplication.class)
                .web(WebApplicationType.NONE)
                .run("--spring.main.banner-mode=off")) {
            AssertableApplicationContext assertable = AssertableApplicationContext.get(() -> context);
            assertThat(assertable).hasSingleBean(CacheLoaderResolver.class);
            assertThat(assertable).hasSingleBean(FunctionalCacheManager.class);
            assertThat(assertable).hasSingleBean(CacheManager.class);
            assertThat(assertable).hasSingleBean(CacheInvocationSnapshotSupport.class);
            assertThat(assertable).hasSingleBean(CacheAspectSupport.class);
            assertThat(assertable).hasSingleBean(CacheableAspect.class);
            assertThat(assertable).hasSingleBean(CachePutAspect.class);
            assertThat(assertable).hasSingleBean(CacheEvictAspect.class);
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
                CacheSpringBoot3StarterTest.class.getClassLoader(),
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
