package io.github.cascade.lock.core;

import io.github.cascade.lock.exception.LockException;
import io.github.cascade.lock.model.LockInfo;
import io.github.cascade.lock.model.LockResult;

import java.util.concurrent.Callable;

/**
 * @author lionel lionelk@163.com
 * =============================
 * Date: 2026/4/12
 * Time: 01:01
 * =============================
 */
public interface LockExecutor {
    /**
     * 执行加锁逻辑，成功后调用 action
     *
     * @param lockInfo 锁配置信息
     * @param action   业务逻辑
     * @return 执行结果
     */
    <T> LockResult<T> execute(LockInfo lockInfo, Callable<T> action) throws LockException;
}
