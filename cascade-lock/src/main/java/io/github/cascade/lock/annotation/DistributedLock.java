package io.github.cascade.lock.annotation;

import io.github.cascade.lock.enums.LockStrategy;
import io.github.cascade.lock.enums.LockType;
import org.springframework.core.annotation.AliasFor;

import java.lang.annotation.*;
import java.util.concurrent.TimeUnit;

/**
 * 分布式锁注解
 * <p>
 * 单锁示例：
 * <pre>
 * {@literal @}DistributedLock("'order:' + #orderId")
 * public void processOrder(Long orderId) { ... }
 * </pre>
 * <p>
 * 联锁示例：
 * <pre>
 * {@literal @}DistributedLock(keys = {"'order:' + #orderId", "'stock:' + #skuId"}, lockType = LockType.MULTI)
 * public void placeOrder(Long orderId, Long skuId) { ... }
 * </pre>
 *
 * @author lionel lionelk@163.com
 * =============================
 * Date: 2026/4/12
 * Time: 01:00
 * =============================
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface DistributedLock {

    /**
     * 表示属性未在注解中显式声明，需回落到全局配置。
     */
    long UNSET = Long.MIN_VALUE;

    /**
     * {@link #keys()} 的快捷方式，支持 SpEL 表达式。
     * <p>
     * 单锁时推荐直接写：{@code @DistributedLock("'order:' + #orderId")}
     */
    @AliasFor("keys")
    String[] value() default {};

    /**
     * 锁的 key 列表，支持 SpEL 表达式。
     * <ul>
     *   <li>单个元素：普通锁（REENTRANT / FAIR / READ / WRITE）</li>
     *   <li>多个元素：联锁/红锁（仅支持 MULTI / RED）</li>
     * </ul>
     */
    @AliasFor("value")
    String[] keys() default {};

    /**
     * key 前缀，最终 key = prefix + ":" + key
     * 默认使用配置文件中的全局前缀
     */
    String keyPrefix() default "";

    /**
     * 锁类型，默认可重入锁
     */
    LockType lockType() default LockType.REENTRANT;

    /**
     * 等待获取锁的最长时间，<= 0 表示不等待。
     * 默认为 UNSET，表示回落到 cascade.lock.waitTime。
     */
    long waitTime() default UNSET;

    /**
     * 锁持有的最长时间（租约时间，单位由 timeUnit 指定）。
     * <= 0：使用看门狗自动续期（推荐用于业务执行时间不确定的场景）
     * > 0：指定持有时间，到期自动释放
     * <p>
     * 默认为 UNSET，表示回落到 cascade.lock.leaseTime（默认 -1）
     */
    long leaseTime() default UNSET;

    /**
     * 时间单位
     */
    TimeUnit timeUnit() default TimeUnit.SECONDS;

    /**
     * 获取锁失败策略
     */
    LockStrategy strategy() default LockStrategy.FAIL_FAST;

    /**
     * 获取锁失败时的提示信息
     */
    String message() default "获取分布式锁失败，请稍后再试";
}
