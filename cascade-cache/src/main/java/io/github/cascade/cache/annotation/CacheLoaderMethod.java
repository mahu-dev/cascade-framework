package io.github.cascade.cache.annotation;

import java.lang.annotation.*;

/**
 * 标记方法作为CacheLoader
 * 被标记的方法将被自动识别为缓存加载器方法
 * 
 * @author cascade
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CacheLoaderMethod {
    
    /**
     * 关联的缓存名称
     */
    String[] value() default {};
    
    /**
     * 缓存名称（别名）
     */
    String[] cacheNames() default {};
    
    /**
     * 加载器名称（用于标识）
     */
    String name() default "";
    
    /**
     * 是否异步加载
     */
    boolean async() default false;
    
    /**
     * 异步加载超时时间
     */
    String asyncTimeout() default "PT30S";
    
    /**
     * 是否支持批量加载
     */
    boolean supportsBatch() default false;
    
    /**
     * 批量加载的最大批次大小
     */
    int maxBatchSize() default 100;
    
    /**
     * 批量加载超时时间
     */
    String batchTimeout() default "PT60S";
    
    /**
     * 加载失败时的处理策略
     */
    FailureStrategy onFailure() default FailureStrategy.THROW;
    
    /**
     * 失败处理策略
     */
    enum FailureStrategy {
        /** 抛出异常 */
        THROW,
        /** 返回null */
        RETURN_NULL,
        /** 返回默认值（需配合defaultValue使用） */
        RETURN_DEFAULT,
        /** 使用降级逻辑 */
        FALLBACK
    }
    
    /**
     * 默认返回值（仅在failureStrategy=RETURN_DEFAULT时生效）
     */
    String defaultValue() default "";
    
    /**
     * 降级方法名称（仅在failureStrategy=FALLBACK时生效）
     */
    String fallbackMethod() default "";
    
    /**
     * 加载条件表达式
     */
    String condition() default "";
    
    /**
     * 是否启用指标监控
     */
    boolean enableMetrics() default true;
    
    /**
     * 优先级（数值越小优先级越高）
     */
    int priority() default 0;
}