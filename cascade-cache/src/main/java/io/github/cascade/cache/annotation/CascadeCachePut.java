package io.github.cascade.cache.annotation;

import java.lang.annotation.*;

/**
 * Cascade缓存更新注解
 * 用于标记方法需要更新缓存
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CascadeCachePut {
    
    /**
     * 缓存名称
     */
    String[] value() default {};
    
    /**
     * 缓存名称（别名）
     */
    String[] cacheNames() default {};
    
    /**
     * 缓存键表达式
     */
    String key() default "";
    
    /**
     * 缓存键生成器
     */
    String keyGenerator() default "";
    
    /**
     * 缓存条件表达式
     */
    String condition() default "";
    
    /**
     * 排除条件表达式
     */
    String unless() default "";
    
    /**
     * TTL（生存时间）
     */
    String ttl() default "";
    
    /**
     * 是否启用L1缓存
     */
    boolean enableL1() default true;
    
    /**
     * 是否启用L2缓存
     */
    boolean enableL2() default true;
    
    /**
     * 是否同步更新
     */
    boolean syncPut() default true;
    
    /**
     * 是否广播更新事件
     */
    boolean broadcast() default true;
    
    /**
     * 更新模式
     */
    PutMode mode() default PutMode.ALL_LEVELS;
    
    /**
     * 更新模式枚举
     */
    enum PutMode {
        /** 仅更新L1缓存 */
        L1_ONLY,
        /** 仅更新L2缓存 */
        L2_ONLY,
        /** 更新所有级别缓存 */
        ALL_LEVELS
    }
}