package io.github.cascade.autoconfigure.annotation;

import io.github.cascade.cache.annotation.CascadeCacheAspect;
import io.github.cascade.cache.api.CacheManager;
import io.github.cascade.cache.event.UnifiedEventProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.cache.interceptor.SimpleCacheErrorHandler;
import org.springframework.cache.interceptor.SimpleKeyGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;


/**
 * Cascade缓存注解配置
 *
 * @author cascade
 */
@Configuration
@EnableCaching
@EnableAspectJAutoProxy
@ConditionalOnClass(EnableCaching.class)
@ConditionalOnProperty(prefix = "cascade.cache", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CascadeCacheAnnotationConfiguration implements CachingConfigurer {

    /**
     * 缓存键生成器
     */
    @Bean
    @ConditionalOnMissingBean
    @Override
    public KeyGenerator keyGenerator() {
        return new CascadeKeyGenerator();
    }

    /**
     * 缓存错误处理器
     */
    @Bean
    @ConditionalOnMissingBean
    @Override
    public CacheErrorHandler errorHandler() {
        return new CascadeCacheErrorHandler();
    }

    /**
     * 统一事件处理器
     */
    @Bean
    @ConditionalOnMissingBean
    public UnifiedEventProcessor unifiedEventProcessor() {
        return new UnifiedEventProcessor(true);
    }

    /**
     * Cascade缓存切面
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(CascadeCacheAspect.class)
    public CascadeCacheAspect cascadeCacheAspect(CacheManager cacheManager, UnifiedEventProcessor eventProcessor) {
        return new CascadeCacheAspect(cacheManager, eventProcessor);
    }

    /**
     * Cascade键生成器
     */
    public static class CascadeKeyGenerator extends SimpleKeyGenerator {

        @Override
        public Object generate(Object target, java.lang.reflect.Method method, Object... params) {
            // 使用类名 + 方法名 + 参数生成键
            StringBuilder keyBuilder = new StringBuilder();
            keyBuilder.append(target.getClass().getSimpleName())
                    .append("#")
                    .append(method.getName());

            if (params.length > 0) {
                keyBuilder.append("#");
                for (int i = 0; i < params.length; i++) {
                    if (i > 0) {
                        keyBuilder.append("_");
                    }
                    if (params[i] != null) {
                        keyBuilder.append(params[i].toString());
                    } else {
                        keyBuilder.append("null");
                    }
                }
            }

            return keyBuilder.toString();
        }
    }

    /**
     * Cascade缓存错误处理器
     */
    public static class CascadeCacheErrorHandler extends SimpleCacheErrorHandler {

        @Override
        public void handleCacheGetError(RuntimeException exception,
                                        org.springframework.cache.Cache cache,
                                        Object key) {
            // 记录错误日志但不抛出异常，保证业务正常执行
            System.err.println("Cache get error for cache: " + cache.getName() +
                    ", key: " + key + ", error: " + exception.getMessage());
        }

        @Override
        public void handleCachePutError(RuntimeException exception,
                                        org.springframework.cache.Cache cache,
                                        Object key, Object value) {
            // 记录错误日志但不抛出异常
            System.err.println("Cache put error for cache: " + cache.getName() +
                    ", key: " + key + ", error: " + exception.getMessage());
        }

        @Override
        public void handleCacheEvictError(RuntimeException exception,
                                          org.springframework.cache.Cache cache,
                                          Object key) {
            // 记录错误日志但不抛出异常
            System.err.println("Cache evict error for cache: " + cache.getName() +
                    ", key: " + key + ", error: " + exception.getMessage());
        }

        @Override
        public void handleCacheClearError(RuntimeException exception,
                                          org.springframework.cache.Cache cache) {
            // 记录错误日志但不抛出异常
            System.err.println("Cache clear error for cache: " + cache.getName() +
                    ", error: " + exception.getMessage());
        }
    }
}