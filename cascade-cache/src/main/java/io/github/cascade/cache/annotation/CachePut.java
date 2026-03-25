package io.github.cascade.cache.annotation;

import io.github.cascade.cache.v2.policy.SyncMode;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 缓存更新注解
 * 
 * 设计原则：
 * 1. 强制更新：不管缓存是否存在都更新
 * 2. 返回结果：更新缓存后返回方法执行结果
 * 3. 条件更新：支持条件表达式控制更新时机
 * 4. 同步更新：支持分布式同步更新
 * 
 * @author cascade
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface CachePut {
    
    /**
     * 缓存名称
     * 如果不指定，将使用 类名.方法名 作为缓存名称
     */
    String value() default "";
    
    /**
     * 缓存键表达式
     * 支持SpEL表达式，如果不指定则使用方法参数生成键
     * 示例：
     * - ""           : 使用所有参数生成键  
     * - "#id"        : 使用参数id作为键
     * - "#user.id"   : 使用参数user的id属性作为键
     * - "'user_' + #id" : 字符串拼接
     */
    String key() default "";
    
    /**
     * 条件表达式
     * 只有条件为true时才更新缓存，支持SpEL表达式
     * 示例：
     * - "#result != null"   : 当结果不为null时才更新
     * - "#p0 > 0"          : 当第一个参数大于0时才更新
     */
    String condition() default "";
    
    /**
     * 缓存TTL（秒）
     * 0表示使用配置的默认TTL，-1表示永不过期
     */
    long ttl() default 0;
    
    /**
     * 是否同步更新（分布式环境）
     * true: 同步到其他节点
     * false: 只更新本地缓存
     */
    boolean sync() default true;
    
    /**
     * 是否启用L1缓存（本地缓存）
     */
    boolean enableL1() default true;
    
    /**
     * 是否启用L2缓存（分布式缓存）
     */
    boolean enableL2() default true;

    /**
     * 是否启用自动刷新
     */
    boolean autoRefresh() default true;

    /**
     * 同步模式（默认失效同步）
     */
    SyncMode syncMode() default SyncMode.INVALIDATE;

    /**
     * 刷新间隔（秒）
     * 仅在autoRefresh为true时有效
     */
    long refreshInterval() default 300; // 5分钟
}
