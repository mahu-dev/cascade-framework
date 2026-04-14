package cc.coderm.cascade.idempotent.config;

import cc.coderm.cascade.idempotent.IdempotentTemplate;
import cc.coderm.cascade.idempotent.aspect.IdempotentAspect;
import cc.coderm.cascade.idempotent.aspect.IdempotentHeaderResolver;
import cc.coderm.cascade.idempotent.aspect.ServletRequestIdempotentHeaderResolver;
import cc.coderm.cascade.idempotent.executor.IdempotentExecutor;
import cc.coderm.cascade.idempotent.key.*;
import cc.coderm.cascade.idempotent.metrics.IdempotentMetrics;
import cc.coderm.cascade.idempotent.notifier.IdempotentCompletionNotifier;
import cc.coderm.cascade.idempotent.notifier.NoopIdempotentCompletionNotifier;
import cc.coderm.cascade.idempotent.notifier.RedissonIdempotentCompletionNotifier;
import cc.coderm.cascade.idempotent.serializer.*;
import cc.coderm.cascade.idempotent.store.IdempotentStore;
import cc.coderm.cascade.idempotent.store.RedisIdempotentStore;
import cc.coderm.cascade.idempotent.store.RedissonIdempotentStore;
import io.micrometer.core.instrument.MeterRegistry;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.ClassUtils;

import java.util.Objects;

/**
 * 幂等模块自动装配。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBean(RedissonClient.class)
@EnableConfigurationProperties(CascadeIdempotentProperties.class)
public class CascadeIdempotentAutoConfiguration {

    static final String JACKSON_CLASS = "com.fasterxml.jackson.databind.ObjectMapper";
    static final String FASTJSON2_CLASS = "com.alibaba.fastjson2.JSON";
    static final String GSON_CLASS = "com.google.gson.Gson";

    @Bean
    @ConditionalOnMissingBean
    public ResultSerializer resultSerializer(CascadeIdempotentProperties properties) {
        ClassLoader classLoader = ClassUtils.getDefaultClassLoader();
        if (classLoader == null) {
            classLoader = getClass().getClassLoader();
        }
        return resolveResultSerializer(properties.getResultSerializer(), classLoader);
    }

    @Bean
    @ConditionalOnMissingBean
    public IdempotentStore idempotentStore(RedissonClient redissonClient,
                                           CascadeIdempotentProperties properties) {
        return resolveIdempotentStore(properties.getStoreType(), redissonClient);
    }

    @Bean
    @ConditionalOnClass(MeterRegistry.class)
    @ConditionalOnBean(MeterRegistry.class)
    @ConditionalOnProperty(prefix = "cascade.idempotent", name = "metrics-enabled",
            havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean
    public IdempotentMetrics idempotentMetrics(MeterRegistry registry) {
        return new IdempotentMetrics(registry);
    }

    @Bean
    @ConditionalOnMissingBean
    public IdempotentCompletionNotifier idempotentCompletionNotifier(RedissonClient redissonClient,
                                                                     CascadeIdempotentProperties properties) {
        if (!properties.isWaitPubSubEnabled()) {
            return NoopIdempotentCompletionNotifier.INSTANCE;
        }
        return new RedissonIdempotentCompletionNotifier(redissonClient, properties.getWaitPubSubTopic());
    }

    @Bean
    @ConditionalOnBean(IdempotentMetrics.class)
    public IdempotentExecutor idempotentExecutor(IdempotentStore store,
                                                   ResultSerializer serializer,
                                                   IdempotentMetrics metrics,
                                                   IdempotentCompletionNotifier completionNotifier) {
        return new IdempotentExecutor(store, serializer, metrics, completionNotifier);
    }

    @Bean
    @ConditionalOnMissingBean(IdempotentMetrics.class)
    public IdempotentExecutor idempotentExecutorWithoutMetrics(IdempotentStore store,
                                                                     ResultSerializer serializer,
                                                                     IdempotentCompletionNotifier completionNotifier) {
        return new IdempotentExecutor(store, serializer, null, completionNotifier);
    }

    @Bean
    @ConditionalOnMissingBean
    public IdempotentKeyHasher idempotentKeyHasher(CascadeIdempotentProperties properties) {
        ClassLoader classLoader = ClassUtils.getDefaultClassLoader();
        if (classLoader == null) {
            classLoader = getClass().getClassLoader();
        }
        return resolveIdempotentKeyHasher(properties.getKeyHasher(), classLoader);
    }

    @Bean
    @ConditionalOnMissingBean
    public IdempotentAspect idempotentAspect(IdempotentExecutor executor,
                                             CascadeIdempotentProperties properties,
                                             BeanFactory beanFactory,
                                             IdempotentKeyHasher idempotentKeyHasher,
                                             IdempotentHeaderResolver idempotentHeaderResolver) {
        return new IdempotentAspect(executor, properties, beanFactory, idempotentKeyHasher, idempotentHeaderResolver);
    }

    @Bean
    @ConditionalOnMissingBean
    public IdempotentTemplate idempotentTemplate(IdempotentExecutor executor,
                                                 CascadeIdempotentProperties properties) {
        return new IdempotentTemplate(executor, properties);
    }

    static ResultSerializer resolveResultSerializer(CascadeIdempotentProperties.ResultSerializerType type,
                                                    ClassLoader classLoader) {
        CascadeIdempotentProperties.ResultSerializerType resolvedType = Objects.requireNonNullElse(
                type, CascadeIdempotentProperties.ResultSerializerType.AUTO);
        return switch (resolvedType) {
            case AUTO -> resolveAutoResultSerializer(classLoader);
            case JACKSON -> {
                assertClassPresent(JACKSON_CLASS, "JACKSON", classLoader);
                yield new JacksonResultSerializer();
            }
            case FASTJSON2 -> {
                assertClassPresent(FASTJSON2_CLASS, "FASTJSON2", classLoader);
                yield new FastJson2ResultSerializer();
            }
            case GSON -> {
                assertClassPresent(GSON_CLASS, "GSON", classLoader);
                yield new GsonResultSerializer();
            }
        };
    }

    private static ResultSerializer resolveAutoResultSerializer(ClassLoader classLoader) {
        if (ClassUtils.isPresent(JACKSON_CLASS, classLoader)) {
            return new JacksonResultSerializer();
        }
        if (ClassUtils.isPresent(FASTJSON2_CLASS, classLoader)) {
            return new FastJson2ResultSerializer();
        }
        if (ClassUtils.isPresent(GSON_CLASS, classLoader)) {
            return new GsonResultSerializer();
        }
        return new UnsupportedResultSerializer();
    }

    private static void assertClassPresent(String className, String configuredType, ClassLoader classLoader) {
        if (!ClassUtils.isPresent(className, classLoader)) {
            throw new IllegalStateException(
                    "cascade.idempotent.result-serializer=" + configuredType
                            + " but required class '" + className + "' is missing from classpath.");
        }
    }

    static IdempotentKeyHasher resolveIdempotentKeyHasher(CascadeIdempotentProperties.IdempotentKeyHasherType type,
                                                          ClassLoader classLoader) {
        CascadeIdempotentProperties.IdempotentKeyHasherType resolvedType = Objects.requireNonNullElse(
                type, CascadeIdempotentProperties.IdempotentKeyHasherType.AUTO);
        return switch (resolvedType) {
            case AUTO -> resolveAutoKeyHasher(classLoader);
            case JACKSON -> {
                assertKeyHasherClassPresent(JACKSON_CLASS, "JACKSON", classLoader);
                yield new JacksonIdempotentKeyHasher();
            }
            case FASTJSON2 -> {
                assertKeyHasherClassPresent(FASTJSON2_CLASS, "FASTJSON2", classLoader);
                yield new FastJson2IdempotentKeyHasher();
            }
            case GSON -> {
                assertKeyHasherClassPresent(GSON_CLASS, "GSON", classLoader);
                yield new GsonIdempotentKeyHasher();
            }
        };
    }

    private static IdempotentKeyHasher resolveAutoKeyHasher(ClassLoader classLoader) {
        if (ClassUtils.isPresent(JACKSON_CLASS, classLoader)) {
            return new JacksonIdempotentKeyHasher();
        }
        if (ClassUtils.isPresent(FASTJSON2_CLASS, classLoader)) {
            return new FastJson2IdempotentKeyHasher();
        }
        if (ClassUtils.isPresent(GSON_CLASS, classLoader)) {
            return new GsonIdempotentKeyHasher();
        }
        return new UnsupportedDefaultIdempotentKeyHasher();
    }

    private static void assertKeyHasherClassPresent(String className, String configuredType, ClassLoader classLoader) {
        if (!ClassUtils.isPresent(className, classLoader)) {
            throw new IllegalStateException(
                    "cascade.idempotent.key-hasher=" + configuredType
                            + " but required class '" + className + "' is missing from classpath.");
        }
    }

    static IdempotentStore resolveIdempotentStore(CascadeIdempotentProperties.StoreType type,
                                                  RedissonClient redissonClient) {
        CascadeIdempotentProperties.StoreType resolvedType = Objects.requireNonNullElse(
                type, CascadeIdempotentProperties.StoreType.REDIS_SCRIPT);
        return switch (resolvedType) {
            case REDIS_SCRIPT -> new RedisIdempotentStore(redissonClient);
            case REDISSON -> new RedissonIdempotentStore(redissonClient);
        };
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.web.context.request.RequestContextHolder")
    static class ServletHeaderResolverConfiguration {
        @Bean
        @ConditionalOnMissingBean
        IdempotentHeaderResolver idempotentHeaderResolver() {
            return new ServletRequestIdempotentHeaderResolver();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingClass("org.springframework.web.context.request.RequestContextHolder")
    static class NoopHeaderResolverConfiguration {
        @Bean
        @ConditionalOnMissingBean
        IdempotentHeaderResolver idempotentHeaderResolver() {
            return IdempotentHeaderResolver.noop();
        }
    }
}
