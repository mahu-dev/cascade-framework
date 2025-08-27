package io.github.cascade.cache.annotation.processor;

import io.github.cascade.cache.annotation.CascadeCacheRefresh;
import io.github.cascade.cache.api.Cache;
import io.github.cascade.cache.api.CacheLoader;
import io.github.cascade.cache.config.CascadeCacheConfiguration;
import io.github.cascade.cache.refresh.CacheRefreshScheduler;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * 基于注解的刷新调度器管理器
 * 管理所有基于注解配置的缓存刷新调度器
 * 
 * @author cascade
 */
@Slf4j
public class AnnotationRefreshSchedulerManager {
    
    private final ApplicationContext applicationContext;
    private final CacheLoaderRegistry loaderRegistry;
    private final ExpressionParser parser = new SpelExpressionParser();
    private final ConcurrentHashMap<String, CacheRefreshScheduler<Object, Object>> schedulers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RefreshConfiguration> refreshConfigs = new ConcurrentHashMap<>();
    
    public AnnotationRefreshSchedulerManager(ApplicationContext applicationContext, 
                                           CacheLoaderRegistry loaderRegistry) {
        this.applicationContext = applicationContext;
        this.loaderRegistry = loaderRegistry;
    }
    
    
    /**
     * 为@CascadeCacheRefresh注解创建刷新调度器
     */
    public void createRefreshScheduler(String cacheName, Cache<Object, Object> cache,
                                     CascadeCacheRefresh annotation, Method method, Object[] args) {
        try {
            RefreshConfiguration config = buildRefreshConfiguration(annotation);
            refreshConfigs.put(cacheName, config);
            
            CacheLoader<Object, Object> loader = resolveLoader(annotation, cacheName);
            if (loader == null) {
                log.warn("缓存 '{}' 没有找到缓存加载器，跳过创建刷新调度器", cacheName);
                return;
            }
            
            Duration refreshInterval = config.getRefreshInterval();
            if (refreshInterval == null) {
                log.warn("缓存 '{}' 没有配置刷新间隔，使用注解中的默认值", cacheName);
                refreshInterval = Duration.ofSeconds(annotation.refreshInterval());
            }
            
            CacheRefreshScheduler.RefreshConfig schedulerConfig = buildSchedulerConfig();
            
            CacheRefreshScheduler<Object, Object> scheduler = new CacheRefreshScheduler<>(
                    (key, value) -> {
                        try {
                            cache.put(key, value);
                            log.debug("已刷新缓存 '{}' 中键 '{}' 的值", cacheName, key);
                        } catch (Exception e) {
                            log.warn("使用刷新值更新缓存 '{}' 中的键 '{}' 失败: {}", 
                                    cacheName, key, e.getMessage());
                        }
                    },
                    loader,
                    refreshInterval,
                    schedulerConfig
            );
            
            schedulers.put(cacheName, scheduler);
            log.info("为缓存 '{}' 创建了刷新调度器，刷新间隔为 {}", cacheName, refreshInterval);
            
        } catch (Exception e) {
            log.error("为缓存 '{}' 创建刷新调度器失败: {}", cacheName, e.getMessage(), e);
        }
    }
    
    /**
     * 调度键的刷新任务
     */
    public void scheduleRefresh(String cacheName, Object key) {
        CacheRefreshScheduler<Object, Object> scheduler = schedulers.get(cacheName);
        if (scheduler != null) {
            scheduler.scheduleRefresh(key);
            log.debug("已为缓存 '{}' 中的键 '{}' 调度刷新任务", cacheName, key);
        } else {
            log.debug("缓存 '{}' 没有找到刷新调度器", cacheName);
        }
    }
    
    /**
     * 取消键的刷新任务
     */
    public void cancelRefresh(String cacheName, Object key) {
        CacheRefreshScheduler<Object, Object> scheduler = schedulers.get(cacheName);
        if (scheduler != null) {
            scheduler.cancelRefresh(key);
            log.debug("已取消缓存 '{}' 中键 '{}' 的刷新任务", cacheName, key);
        }
    }
    
    /**
     * 关闭所有调度器
     */
    public void shutdown() {
        log.info("正在关闭 {} 个刷新调度器", schedulers.size());
        
        schedulers.forEach((cacheName, scheduler) -> {
            try {
                scheduler.shutdown();
                log.debug("已关闭缓存 '{}' 的刷新调度器", cacheName);
            } catch (Exception e) {
                log.warn("关闭缓存 '{}' 的刷新调度器失败: {}", cacheName, e.getMessage());
            }
        });
        
        schedulers.clear();
        refreshConfigs.clear();
    }
    
    /**
     * 获取刷新调度器
     */
    public CacheRefreshScheduler<Object, Object> getScheduler(String cacheName) {
        return schedulers.get(cacheName);
    }
    
    
    /**
     * 构建刷新配置
     */
    private RefreshConfiguration buildRefreshConfiguration(CascadeCacheRefresh annotation) {
        RefreshConfiguration config = new RefreshConfiguration();
        
        config.setRefreshInterval(Duration.ofSeconds(annotation.refreshInterval()));
        
        if (annotation.minRefreshInterval() > 0) {
            config.setMinRefreshInterval(Duration.ofSeconds(annotation.minRefreshInterval()));
        }
        
        if (annotation.maxRefreshInterval() > 0) {
            config.setMaxRefreshInterval(Duration.ofSeconds(annotation.maxRefreshInterval()));
        }
        
        config.setAllowConcurrentRefresh(annotation.allowConcurrentRefresh());
        
        if (annotation.refreshTimeout() > 0) {
            config.setRefreshTimeout(Duration.ofSeconds(annotation.refreshTimeout()));
        }
        
        if (annotation.maxRetries() > 0) {
            config.setMaxRetries(annotation.maxRetries());
        }
        
        if (annotation.retryInterval() > 0) {
            config.setRetryInterval(Duration.ofSeconds(annotation.retryInterval()));
        }
        
        config.setEnablePreload(annotation.enablePreload());
        config.setPreloadBatchSize(annotation.preloadBatchSize());
        config.setPreloadConcurrency(annotation.preloadConcurrency());
        config.setLoaderName(annotation.loader());
        
        return config;
    }
    
    /**
     * 构建调度器配置
     */
    private CacheRefreshScheduler.RefreshConfig buildSchedulerConfig() {
        // 可以在这里根据需要自定义调度器配置
        // 例如线程池大小、队列容量等
        return CacheRefreshScheduler.RefreshConfig.defaultConfig();
    }
    
    
    /**
     * 解析加载器
     */
    private CacheLoader<Object, Object> resolveLoader(CascadeCacheRefresh annotation, String cacheName) {
        if (StringUtils.hasText(annotation.loader())) {
            // 优先使用指定的加载器
            CacheLoader<Object, Object> loader = loaderRegistry.getLoader(annotation.loader());
            if (loader != null) {
                return loader;
            }
            
            // 尝试从Spring容器获取
            try {
                return applicationContext.getBean(annotation.loader(), CacheLoader.class);
            } catch (Exception e) {
                log.warn("获取加载器Bean '{}' 失败: {}", annotation.loader(), e.getMessage());
            }
        }
        
        // 尝试根据缓存名称查找
        return loaderRegistry.getLoaderForCache(cacheName);
    }
    
    /**
     * 解析SpEL表达式获取Duration值
     */
    private Duration parseSpelDuration(String expression, Method method, Object[] args) {
        if (!StringUtils.hasText(expression)) {
            return null;
        }
        
        try {
            // 尝试直接解析为Duration
            return Duration.parse(expression);
        } catch (Exception e) {
            // 尝试SpEL表达式解析
            try {
                EvaluationContext context = createEvaluationContext(method, args);
                Expression spelExpression = parser.parseExpression(expression);
                Object value = spelExpression.getValue(context);
                
                if (value instanceof Duration duration) {
                    return duration;
                } else if (value instanceof String string) {
                    return Duration.parse(string);
                } else if (value instanceof Number number) {
                    return Duration.ofSeconds(number.longValue());
                }
            } catch (Exception spelException) {
                log.warn("解析持续时间表达式 '{}' 失败: {}", expression, spelException.getMessage());
            }
        }
        
        return null;
    }
    
    /**
     * 创建SpEL评估上下文
     */
    private EvaluationContext createEvaluationContext(Method method, Object[] args) {
        StandardEvaluationContext context = new StandardEvaluationContext();
        
        // 设置方法参数
        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                context.setVariable("p" + i, args[i]);
                context.setVariable("a" + i, args[i]);
            }
        }
        
        // 设置方法信息
        context.setVariable("method", method);
        context.setVariable("target", method.getDeclaringClass());
        
        return context;
    }
    
    /**
     * 刷新配置内部类
     */
    @Setter
    @Getter
    private static class RefreshConfiguration {
        // Getters and Setters
        private Duration refreshInterval;
        private Duration minRefreshInterval;
        private Duration maxRefreshInterval;
        private boolean allowConcurrentRefresh;
        private Duration refreshTimeout;
        private int maxRetries;
        private Duration retryInterval;
        private boolean enablePreload;
        private int preloadBatchSize;
        private int preloadConcurrency;
        private String loaderName;

    }
}