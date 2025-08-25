package io.github.cascade.cache.annotation.processor;

import io.github.cascade.cache.annotation.CacheLoaderMethod;
import io.github.cascade.cache.api.CacheLoader;
import io.github.cascade.cache.core.CacheLoaderResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 基于注解的缓存加载器注册表
 * 扫描@CacheLoaderMethod注解并注册到Spring容器中
 * 与CacheLoaderResolver集成，避免重复的CacheLoader管理
 * 
 * @author cascade
 */
@Slf4j
public class CacheLoaderRegistry {
    
    private final ApplicationContext applicationContext;
    private final CacheLoaderResolver cacheLoaderResolver;
    
    public CacheLoaderRegistry(ApplicationContext applicationContext, CacheLoaderResolver cacheLoaderResolver) {
        this.applicationContext = applicationContext;
        this.cacheLoaderResolver = cacheLoaderResolver;
        scanAndRegisterCacheLoaderMethods();
    }
    
    /**
     * 扫描所有带有@CacheLoaderMethod注解的方法并注册到Spring容器
     */
    private void scanAndRegisterCacheLoaderMethods() {
        String[] beanNames = applicationContext.getBeanDefinitionNames();
        
        for (String beanName : beanNames) {
            try {
                Object bean = applicationContext.getBean(beanName);
                Class<?> targetClass = AopUtils.getTargetClass(bean);
                
                ReflectionUtils.doWithMethods(targetClass, method -> {
                    CacheLoaderMethod annotation = AnnotatedElementUtils.findMergedAnnotation(method, CacheLoaderMethod.class);
                    if (annotation != null) {
                        registerCacheLoaderMethodAsBean(beanName, bean, method, annotation);
                    }
                });
                
            } catch (Exception e) {
                log.warn("Failed to scan bean '{}' for cache loader methods: {}", beanName, e.getMessage());
            }
        }
        
        log.info("Scanned and registered cache loader methods from {} beans", beanNames.length);
    }
    
    /**
     * 将缓存加载器方法注册为Spring Bean，供CacheLoaderResolver统一管理
     */
    private void registerCacheLoaderMethodAsBean(String beanName, Object bean, Method method, CacheLoaderMethod annotation) {
        String loaderName = StringUtils.hasText(annotation.name()) ? annotation.name() : 
                            beanName + "#" + method.getName();
        
        MethodBasedCacheLoader<Object, Object> loader = new MethodBasedCacheLoader<>(
                bean, method, annotation, applicationContext);
        
        // 注册到Spring容器中，让CacheLoaderResolver能够发现
        registerCacheLoaderBean(loaderName, loader);
        
        // 根据缓存名称也注册一份，支持按缓存名称查找
        String[] cacheNames = getCacheNames(annotation);
        for (String cacheName : cacheNames) {
            String cacheLoaderBeanName = cacheName + "CacheLoader";
            if (!isBeanAlreadyRegistered(cacheLoaderBeanName)) {
                registerCacheLoaderBean(cacheLoaderBeanName, loader);
                log.debug("Registered cache loader bean '{}' for cache '{}'", cacheLoaderBeanName, cacheName);
            }
        }
        
        log.info("Registered cache loader method as bean: {} -> {}", loaderName, method.toGenericString());
    }
    
    /**
     * 获取指定名称的缓存加载器 - 委托给CacheLoaderResolver
     */
    @SuppressWarnings("unchecked")
    public <K, V> CacheLoader<K, V> getLoader(String name) {
        try {
            if (applicationContext.containsBean(name)) {
                Object bean = applicationContext.getBean(name);
                if (bean instanceof CacheLoader) {
                    return (CacheLoader<K, V>) bean;
                }
            }
        } catch (Exception e) {
            log.debug("Failed to get loader bean '{}': {}", name, e.getMessage());
        }
        return null;
    }
    
    /**
     * 根据缓存名称获取加载器 - 委托给CacheLoaderResolver
     */
    @SuppressWarnings("unchecked")
    public <K, V> CacheLoader<K, V> getLoaderForCache(String cacheName) {
        return cacheLoaderResolver.resolveCacheLoader(cacheName, (Class<K>) Object.class, (Class<V>) Object.class);
    }
    
    /**
     * 注册CacheLoader Bean到Spring容器
     */
    private void registerCacheLoaderBean(String beanName, CacheLoader<?, ?> loader) {
        if (applicationContext instanceof ConfigurableApplicationContext) {
            ConfigurableListableBeanFactory beanFactory = 
                ((ConfigurableApplicationContext) applicationContext).getBeanFactory();
            beanFactory.registerSingleton(beanName, loader);
            log.debug("Registered CacheLoader bean: {}", beanName);
        } else {
            log.warn("Cannot register CacheLoader bean '{}' - ApplicationContext is not configurable", beanName);
        }
    }
    
    /**
     * 检查Bean是否已经注册
     */
    private boolean isBeanAlreadyRegistered(String beanName) {
        return applicationContext.containsBean(beanName);
    }
    
    /**
     * 获取缓存名称数组
     */
    private String[] getCacheNames(CacheLoaderMethod annotation) {
        String[] value = annotation.value();
        if (value.length > 0) {
            return value;
        }
        return annotation.cacheNames();
    }
    
    /**
     * 基于方法的缓存加载器实现
     * 将@CacheLoaderMethod注解的方法包装为标准的CacheLoader
     */
    private static class MethodBasedCacheLoader<K, V> implements CacheLoader<K, V> {
        
        private final Object targetBean;
        private final Method method;
        private final CacheLoaderMethod annotation;
        private final ApplicationContext applicationContext;
        
        public MethodBasedCacheLoader(Object targetBean, Method method, CacheLoaderMethod annotation, 
                                    ApplicationContext applicationContext) {
            this.targetBean = targetBean;
            this.method = method;
            this.annotation = annotation;
            this.applicationContext = applicationContext;
            
            // 确保方法可访问
            ReflectionUtils.makeAccessible(method);
        }
        
        @Override
        @SuppressWarnings("unchecked")
        public V load(K key) throws Exception {
            try {
                log.debug("Loading cache value for key '{}' using method '{}'", key, method.getName());
                
                Object result;
                if (annotation.async()) {
                    // 异步加载
                    CompletableFuture<Object> future = CompletableFuture.supplyAsync(() -> {
                        try {
                            return ReflectionUtils.invokeMethod(method, targetBean, key);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    });
                    
                    Duration timeout = parseTimeout(annotation.asyncTimeout());
                    result = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                } else {
                    // 同步加载
                    result = ReflectionUtils.invokeMethod(method, targetBean, key);
                }
                
                return (V) result;
                
            } catch (Exception e) {
                return handleFailure(key, e);
            }
        }
        
        @Override
        @SuppressWarnings("unchecked")
        public Map<K, V> loadAll(Set<K> keys) throws Exception {
            if (!annotation.supportsBatch()) {
                // 如果不支持批量，则逐个加载
                Map<K, V> result = new HashMap<>();
                for (K key : keys) {
                    try {
                        V value = load(key);
                        if (value != null) {
                            result.put(key, value);
                        }
                    } catch (Exception e) {
                        log.warn("Failed to load key '{}': {}", key, e.getMessage());
                    }
                }
                return result;
            }
            
            try {
                log.debug("Batch loading cache values using method '{}'", method.getName());
                
                Object result;
                if (annotation.async()) {
                    // 异步批量加载
                    CompletableFuture<Object> future = CompletableFuture.supplyAsync(() -> {
                        try {
                            return ReflectionUtils.invokeMethod(method, targetBean, keys);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    });
                    
                    Duration timeout = parseTimeout(annotation.batchTimeout());
                    result = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                } else {
                    // 同步批量加载
                    result = ReflectionUtils.invokeMethod(method, targetBean, keys);
                }
                
                return (Map<K, V>) result;
                
            } catch (Exception e) {
                log.warn("Batch loading failed, falling back to individual loading: {}", e.getMessage());
                return loadAll(keys);
            }
        }
        
        /**
         * 处理加载失败
         */
        @SuppressWarnings("unchecked")
        private V handleFailure(K key, Exception e) throws Exception {
            switch (annotation.onFailure()) {
                case THROW:
                    throw e;
                    
                case RETURN_NULL:
                    log.debug("Returning null for failed key '{}': {}", key, e.getMessage());
                    return null;
                    
                case RETURN_DEFAULT:
                    if (StringUtils.hasText(annotation.defaultValue())) {
                        return (V) annotation.defaultValue();
                    }
                    return null;
                    
                case FALLBACK:
                    return executeFallback(key, e);
                    
                default:
                    throw e;
            }
        }
        
        /**
         * 执行降级逻辑
         */
        @SuppressWarnings("unchecked")
        private V executeFallback(K key, Exception originalException) throws Exception {
            if (!StringUtils.hasText(annotation.fallbackMethod())) {
                log.warn("Fallback method not specified for key '{}'", key);
                throw originalException;
            }
            
            try {
                Method fallbackMethod = targetBean.getClass().getMethod(annotation.fallbackMethod(), 
                                                                       method.getParameterTypes());
                ReflectionUtils.makeAccessible(fallbackMethod);
                
                Object result = ReflectionUtils.invokeMethod(fallbackMethod, targetBean, key);
                log.debug("Fallback executed successfully for key '{}'", key);
                return (V) result;
                
            } catch (Exception e) {
                log.warn("Fallback method execution failed for key '{}': {}", key, e.getMessage());
                throw originalException;
            }
        }
        
        /**
         * 解析超时时间
         */
        private Duration parseTimeout(String timeoutStr) {
            try {
                return Duration.parse(timeoutStr);
            } catch (Exception e) {
                try {
                    return Duration.ofSeconds(Long.parseLong(timeoutStr));
                } catch (Exception ex) {
                    log.warn("Failed to parse timeout '{}', using default 30 seconds", timeoutStr);
                    return Duration.ofSeconds(30);
                }
            }
        }
        
        @Override
        public String getName() {
            return String.format("%s#%s", 
                    targetBean.getClass().getSimpleName(), method.getName());
        }
        
        @Override
        public boolean supportsBatchLoading() {
            return annotation.supportsBatch();
        }
        
        @Override
        public boolean supportsAsyncLoading() {
            return annotation.async();
        }
        
        @Override
        public long getLoadTimeoutMillis() {
            if (annotation.async()) {
                Duration timeout = parseTimeout(annotation.asyncTimeout());
                return timeout.toMillis();
            }
            return 0;
        }
        
        @Override
        public String toString() {
            return String.format("MethodBasedCacheLoader[%s#%s]", 
                    targetBean.getClass().getSimpleName(), method.getName());
        }
    }
}