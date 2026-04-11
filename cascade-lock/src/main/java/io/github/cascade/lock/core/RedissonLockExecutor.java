package io.github.cascade.lock.core;

import io.github.cascade.lock.exception.LockException;
import io.github.cascade.lock.factory.LockFactory;
import io.github.cascade.lock.listener.LockEvent;
import io.github.cascade.lock.listener.LockEventListener;
import io.github.cascade.lock.model.LockInfo;
import io.github.cascade.lock.model.LockResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.springframework.util.CollectionUtils;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

@Slf4j
@RequiredArgsConstructor
public class RedissonLockExecutor implements LockExecutor {

    private final LockFactory lockFactory;
    private final List<LockEventListener> eventListeners;

    @Override
    public <T> LockResult<T> execute(LockInfo lockInfo, Callable<T> action) {
        RLock lock = resolveLock(lockInfo);
        long start = System.currentTimeMillis();
        boolean acquired = false;

        try {
            acquired = tryAcquire(lock, lockInfo);

            if (!acquired) {
                return handleNotAcquired(lockInfo);
            }

            publishEvent(LockEvent.acquired(lockInfo.getLockKey()));
            T result = action.call();
            return LockResult.success(result, lockInfo.getLockKey(),
                    System.currentTimeMillis() - start);

        } catch (LockException e) {
            throw e;
        } catch (Throwable e) {
            throw new LockException("业务执行异常", lockInfo.getLockKey(), e);
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
                publishEvent(LockEvent.released(lockInfo.getLockKey()));
                log.debug("[cascade-lock] 锁已释放: {}", lockInfo.getLockKey());
            }
        }
    }

    private RLock resolveLock(LockInfo lockInfo) {
        if (!CollectionUtils.isEmpty(lockInfo.getLockKeys())) {
            return lockFactory.getMultiLock(lockInfo.getLockType(), lockInfo.getLockKeys());
        }
        return lockFactory.getLock(lockInfo.getLockType(), lockInfo.getLockKey());
    }

    private boolean tryAcquire(RLock lock, LockInfo lockInfo) throws InterruptedException {
        long waitTime = lockInfo.getWaitTime();
        long leaseTime = lockInfo.getLeaseTime();
        TimeUnit unit = lockInfo.getTimeUnit();

        if (leaseTime > 0 && waitTime > 0) {
            return lock.tryLock(waitTime, leaseTime, unit);
        } else if (leaseTime > 0) {
            // 不等待，立即尝试，指定租约
            return lock.tryLock(0, leaseTime, unit);
        } else if (waitTime > 0) {
            // 等待，看门狗续期
            return lock.tryLock(waitTime, -1, unit);
        } else {
            // 不等待，看门狗续期
            return lock.tryLock();
        }
    }

    private <T> LockResult<T> handleNotAcquired(LockInfo lockInfo) {
        publishEvent(LockEvent.failed(lockInfo.getLockKey()));
        log.warn("[cascade-lock] 获取锁失败: {}", lockInfo.getLockKey());

        return switch (lockInfo.getLockStrategy()) {
            case FAIL_FAST -> throw new LockException(lockInfo.getFailMessage(), lockInfo.getLockKey());
            case SKIP -> LockResult.failed(lockInfo.getLockKey());
            case KEEP_TRYING -> throw new LockException("KEEP_TRYING 模式下仍未获取到锁", lockInfo.getLockKey());
        };
    }

    private void publishEvent(LockEvent event) {
        if (eventListeners != null) {
            eventListeners.forEach(l -> {
                try {
                    l.onEvent(event);
                } catch (Exception e) {
                    log.warn("[cascade-lock] 事件监听器异常", e);
                }
            });
        }
    }
}