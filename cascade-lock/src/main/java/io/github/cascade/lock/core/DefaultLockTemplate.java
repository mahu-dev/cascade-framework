package io.github.cascade.lock.core;

import io.github.cascade.lock.enums.LockStrategy;
import io.github.cascade.lock.enums.LockType;
import io.github.cascade.lock.model.LockInfo;
import io.github.cascade.lock.model.LockResult;
import lombok.RequiredArgsConstructor;

import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

@RequiredArgsConstructor
public class DefaultLockTemplate implements LockTemplate {

    private final LockExecutor lockExecutor;

    @Override
    public <T> T lock(String key, Callable<T> action) {
        return lock(key, LockType.REENTRANT, action);
    }

    @Override
    public <T> T lock(String key, LockType lockType, Callable<T> action) {
        LockResult<T> result = lock(key, lockType, -1, -1,
                TimeUnit.SECONDS, LockStrategy.FAIL_FAST, action);
        return result.getResult();
    }

    @Override
    public <T> LockResult<T> lock(String key, LockType lockType, long waitTime, long leaseTime,
                                  TimeUnit timeUnit, LockStrategy strategy, Callable<T> action) {
        LockInfo lockInfo = LockInfo.builder()
                .lockKey(key)
                .lockType(lockType)
                .waitTime(waitTime)
                .leaseTime(leaseTime)
                .timeUnit(timeUnit)
                .lockStrategy(strategy)
                .failMessage("获取分布式锁失败: " + key)
                .build();
        return lockExecutor.execute(lockInfo, action);
    }

    @Override
    public <T> T readLock(String key, Callable<T> action) {
        return lock(key, LockType.READ, action);
    }

    @Override
    public <T> T writeLock(String key, Callable<T> action) {
        return lock(key, LockType.WRITE, action);
    }

    @Override
    public <T> T tryLock(String key, Callable<T> action) {
        LockResult<T> result = lock(key, LockType.REENTRANT, -1, -1,
                TimeUnit.SECONDS, LockStrategy.SKIP, action);
        return result.getResult();
    }
}