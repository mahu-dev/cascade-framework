package io.github.cascade.cache.v2.api.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 缓存清除注解
 * <p>
 * 设计原则：
 * 1. 灵活清除：支持单个键、多个键或全部清除
 * 2. 时机可控：支持方法执行前/后清除
 * 3. 条件清除：支持条件表达式控制清除时机
 * 4. 同步清除：支持分布式同步清除
 *
 * @author cascade
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface CacheEvict {

    /**
     * 缓存名称
     * 如果不指定，将使用 类名.方法名 作为缓存名称
     */
    String value() default "";

    /**
     * 缓存键表达式
     * 支持SpEL表达式，如果不指定则仅使用方法参数生成键（不包含方法签名）
     * 当表达式结果为集合或数组时，会逐个元素执行驱逐
     * 示例：
     * - ""           : 使用所有参数生成键
     * - "#id"        : 使用参数id作为键
     * - "#user.id"   : 使用参数user的id属性作为键
     * - "{'key1', 'key2'}" : 清除多个指定键
     */
    String key() default "";

    /**
     * 是否清除所有缓存条目
     * true: 清空整个缓存
     * false: 只清除指定键的缓存
     */
    boolean allEntries() default false;

    /**
     * 是否在方法执行前清除缓存
     * true: 方法执行前清除
     * false: 方法执行后清除（默认）
     */
    boolean beforeInvocation() default false;

    /**
     * 条件表达式
     * 只有条件为true时才清除缓存，支持SpEL表达式
     * 示例：
     * - "#result == true"   : 当方法返回true时才清除
     * - "#p0 > 0"          : 当第一个参数大于0时才清除
     */
    String condition() default "";

    /**
     * 是否同步清除（分布式环境）
     * true: 发布跨节点同步事件
     * false: 仅当前节点执行本地驱逐（不发布同步事件，不操作共享L2）
     */
    boolean sync() default true;
}
