package io.github.cascade.cache.v2.api.annotations;

import io.github.cascade.cache.v2.policy.SyncMode;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 缓存注解 - 用于方法级别的缓存
 * <p>
 * 设计原则：
 * 1. 简洁实用：只包含核心配置项
 * 2. 见名知义：参数名称清晰易懂
 * 3. 默认合理：提供合理的默认值
 * 4. 向后兼容：支持未来扩展
 *
 * @author cascade
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Cacheable {

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
     * 前置条件，只有条件为true时才进入缓存逻辑（读取/写入）
     * 支持SpEL表达式，不支持 #result（方法尚未执行）
     * 示例：
     * - "#id > 0"           : 当id大于0时才缓存
     */
    String condition() default "";

    /**
     * 后置否决表达式
     * 方法执行后求值，返回true时不写入缓存，支持 #result
     * 示例：
     * - "#result == null"                 : 结果为空不缓存
     * - "#result.score < 60"              : 低分结果不缓存
     * - "#result.startsWith('skip-')"     : 命中特定前缀不缓存
     */
    String unless() default "";

    /**
     * 缓存TTL（秒）
     * 0表示使用配置的默认TTL，-1表示永不过期
     */
    long ttl() default 0;

    /**
     * 是否启用L1缓存（本地缓存）
     */
    boolean enableL1() default true;

    /**
     * 是否启用L2缓存（分布式缓存）
     */
    boolean enableL2() default true;

    /**
     * 是否启用同步机制
     */
    boolean enableSync() default true;

    /**
     * 是否启用定时刷新
     */
    boolean enableRefresh() default true;

    /**
     * 是否启用自动刷新（别名，为了向后兼容）
     */
    boolean autoRefresh() default true;

    /**
     * 同步模式（默认失效同步）
     */
    SyncMode syncMode() default SyncMode.INVALIDATE;

    /**
     * 刷新间隔（秒）
     * 仅在enableRefresh为true时有效
     */
    long refreshInterval() default 300; // 5分钟

    /**
     * 是否异步加载
     * true: 缓存未命中时异步加载，立即返回null
     * false: 缓存未命中时同步加载
     */
    boolean asyncLoad() default false;
}
