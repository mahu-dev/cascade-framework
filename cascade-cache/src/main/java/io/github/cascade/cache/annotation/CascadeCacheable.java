package io.github.cascade.cache.annotation;

import java.lang.annotation.*;

/**
 * Cascade缓存注解
 * 用于标记方法需要缓存结果
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CascadeCacheable {

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
     * 是否同步执行
     */
    boolean sync() default false;

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
     * 是否启用布隆过滤器
     */
    boolean enableBloomFilter() default false;

    /**
     * 缓存加载器
     */
    String loader() default "";

    /**
     * 异步加载
     */
    boolean asyncLoad() default false;

    /**
     * 批量加载
     */
    boolean batchLoad() default false;

    /**
     * 预刷新时间比例（0.0-1.0）
     */
    double refreshAheadFactor() default 0.0;
}