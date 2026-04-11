package io.github.cascade.lock.core;

import io.github.cascade.lock.enums.LockStrategy;
import io.github.cascade.lock.enums.LockType;
import io.github.cascade.lock.model.LockResult;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/**
 * 编程式分布式锁 API
 * <p>
 * 示例：
 * <pre>
 * lockTemplate.lock("order:123", () -> {
 *     // 业务逻辑
 *     return result;
 * });
 * </pre>
 *
 * @author lionel lionelk@163.com
 * =============================
 * Date: 2026/4/12
 * Time: 01:01
 * =============================
 */
public interface LockTemplate {

    /**
     * 使用默认配置加锁（可重入锁 + 看门狗续期）
     */
    <T> T lock(String key, Callable<T> action);

    /**
     * 指定锁类型
     */
    <T> T lock(String key, LockType lockType, Callable<T> action);

    /**
     * 完整参数
     */
    <T> LockResult<T> lock(String key, LockType lockType, long waitTime, long leaseTime,
                           TimeUnit timeUnit, LockStrategy strategy, Callable<T> action);

    /**
     * 多 key 加锁（仅支持 RED / MULTI）
     */
    <T> T lock(List<String> keys, LockType lockType, Callable<T> action);

    /**
     * 多 key 完整参数（仅支持 RED / MULTI）
     */
    <T> LockResult<T> lock(List<String> keys, LockType lockType, long waitTime, long leaseTime,
                           TimeUnit timeUnit, LockStrategy strategy, Callable<T> action);

    /**
     * 读写锁：读锁
     */
    <T> T readLock(String key, Callable<T> action);

    /**
     * 读写锁：写锁
     */
    <T> T writeLock(String key, Callable<T> action);

    /**
     * 尝试获取锁，失败则跳过（返回 null）
     */
    <T> T tryLock(String key, Callable<T> action);
}
