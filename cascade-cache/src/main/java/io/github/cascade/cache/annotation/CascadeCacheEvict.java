package io.github.cascade.cache.annotation;

import java.lang.annotation.*;

/**
 * Cascade缓存清除注解
 * 用于标记方法需要清除缓存
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CascadeCacheEvict {
    
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
     * 是否清除所有缓存
     */
    boolean allEntries() default false;
    
    /**
     * 是否在方法执行前清除缓存
     */
    boolean beforeInvocation() default false;
    
    /**
     * 是否同步清除L1和L2缓存
     */
    boolean syncEvict() default true;
    
    /**
     * 是否广播清除事件
     */
    boolean broadcast() default true;
    
    /**
     * 清除模式
     */
    EvictMode mode() default EvictMode.ALL_LEVELS;
    
    /**
     * 清除模式枚举
     */
    enum EvictMode {
        /** 仅清除L1缓存 */
        L1_ONLY,
        /** 仅清除L2缓存 */
        L2_ONLY,
        /** 清除所有级别缓存 */
        ALL_LEVELS
    }
}