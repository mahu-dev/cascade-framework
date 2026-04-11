package io.github.cascade.lock.annotation;

import io.github.cascade.lock.enums.LockStrategy;
import io.github.cascade.lock.enums.LockType;

import java.lang.annotation.*;
import java.util.concurrent.TimeUnit;

/**
 * 分布式锁注解
 * 示例：
 * <pre>
 * {@literal @}DistributedLock(key = "'order:' + #orderId", leaseTime = 10)
 * public void processOrder(Long orderId) { ... }
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
     * 锁的 key，支持 SpEL 表达式
     * 例如：key = "'order:lock:' + #orderId"
     */
    String key();

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
     * 等待获取锁的最长时间，-1 表示不等待
     * 仅在 strategy = KEEP_TRYING 时有效
     */
    long waitTime() default -1;

    /**
     * 锁持有的最长时间（租约时间），-1 使用看门狗自动续期
     */
    long leaseTime() default -1;


    /**
     * 时间单位
     */
    TimeUnit timeUnit() default TimeUnit.SECONDS;

    /**
     * 获取锁失败策略
     */
    LockStrategy strategy() default LockStrategy.FAIL_FAST;

    /**
     * 联锁/红锁时使用的多个 key（支持 SpEL）
     */
    String[] keys() default {};

    /**
     * 获取锁失败时的提示信息
     */
    String message() default "获取分布式锁失败，请稍后再试";
}
