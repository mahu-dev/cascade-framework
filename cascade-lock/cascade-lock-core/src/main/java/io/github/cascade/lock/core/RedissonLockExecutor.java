package io.github.cascade.lock.core;

import io.github.cascade.lock.enums.LockStrategy;
import io.github.cascade.lock.exception.LockException;
import io.github.cascade.lock.factory.LockFactory;
import io.github.cascade.lock.listener.LockEvent;
import io.github.cascade.lock.listener.LockEventListener;
import io.github.cascade.lock.model.LockInfo;
import io.github.cascade.lock.model.LockResult;
import io.github.cascade.lock.util.SneakyThrow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

@Slf4j
@RequiredArgsConstructor
public class RedissonLockExecutor implements LockExecutor {

    private enum AcquireMode {
        WAIT_WITH_LEASE,
        NO_WAIT_WITH_LEASE,
        WAIT_WITH_WATCHDOG,
        NO_WAIT_WITH_WATCHDOG
    }

    private final LockFactory lockFactory;
    private final List<LockEventListener> eventListeners;

    @Override
    public <T> LockResult<T> execute(LockInfo lockInfo, Callable<T> action) {
        String displayKey = lockInfo.getDisplayKey();
        RLock lock = resolveLock(lockInfo);
        long start = System.currentTimeMillis();
        boolean acquired = false;

        try {
            acquired = tryAcquire(lock, lockInfo);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LockException("获取锁时线程被中断", displayKey, e);
        } catch (LockException e) {
            throw e;
        } catch (Throwable e) {
            throw new LockException("加锁阶段发生异常", displayKey, e);
        }

        try {
            if (!acquired) {
                return handleNotAcquired(lockInfo, displayKey);
            }

            publishEvent(LockEvent.acquired(displayKey));
            T result = invokeAction(action);
            return LockResult.success(result, displayKey,
                    System.currentTimeMillis() - start);
        } finally {
            releaseIfNeeded(acquired, lock, displayKey);
        }
    }

    private RLock resolveLock(LockInfo lockInfo) {
        List<String> keys = lockInfo.getKeys();
        if (keys.size() == 1) {
            return lockFactory.getLock(lockInfo.getLockType(), keys.get(0));
        }
        return lockFactory.getMultiLock(lockInfo.getLockType(), keys);
    }

    private boolean tryAcquire(RLock lock, LockInfo lockInfo) throws InterruptedException {
        if (lockInfo.getLockStrategy() == LockStrategy.KEEP_TRYING) {
            return tryAcquireForKeepTrying(lock, lockInfo);
        }
        return tryAcquireOnce(lock, lockInfo);
    }

    private static boolean tryAcquireForKeepTrying(RLock lock, LockInfo lockInfo) throws InterruptedException {
        long waitTime = lockInfo.getWaitTime();
        long leaseTime = lockInfo.getLeaseTime();
        TimeUnit unit = lockInfo.getTimeUnit();

        if (waitTime > 0) {
            // 有限等待窗口：在 waitTime 内持续等待
            return lock.tryLock(waitTime, leaseTime > 0 ? leaseTime : -1, unit);
        }

        // waitTime <= 0：无限阻塞直到获取锁
        if (leaseTime > 0) {
            lock.lockInterruptibly(leaseTime, unit);
        } else {
            lock.lockInterruptibly();
        }
        return true;
    }

    private static boolean tryAcquireOnce(RLock lock, LockInfo lockInfo) throws InterruptedException {
        long waitTime = lockInfo.getWaitTime();
        long leaseTime = lockInfo.getLeaseTime();
        TimeUnit unit = lockInfo.getTimeUnit();
        return switch (resolveAcquireMode(waitTime, leaseTime)) {
            case WAIT_WITH_LEASE -> lock.tryLock(waitTime, leaseTime, unit);
            case NO_WAIT_WITH_LEASE -> lock.tryLock(0, leaseTime, unit);
            case WAIT_WITH_WATCHDOG -> lock.tryLock(waitTime, -1, unit);
            case NO_WAIT_WITH_WATCHDOG -> lock.tryLock();
        };
    }

    private static AcquireMode resolveAcquireMode(long waitTime, long leaseTime) {
        if (leaseTime > 0 && waitTime > 0) {
            return AcquireMode.WAIT_WITH_LEASE;
        }
        if (leaseTime > 0) {
            return AcquireMode.NO_WAIT_WITH_LEASE;
        }
        if (waitTime > 0) {
            return AcquireMode.WAIT_WITH_WATCHDOG;
        }
        return AcquireMode.NO_WAIT_WITH_WATCHDOG;
    }

    private <T> LockResult<T> handleNotAcquired(LockInfo lockInfo, String displayKey) {
        publishEvent(LockEvent.failed(displayKey));
        log.warn("[cascade-lock] 获取锁失败: {}", displayKey);

        return switch (lockInfo.getLockStrategy()) {
            case FAIL_FAST -> throw new LockException(lockInfo.getFailMessage(), displayKey);
            case SKIP -> LockResult.failed(displayKey);
            case KEEP_TRYING -> throw new LockException("KEEP_TRYING 模式下仍未获取到锁", displayKey);
        };
    }

    private static <T> T invokeAction(Callable<T> action) {
        try {
            return action.call();
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            return SneakyThrow.rethrow(e);
        }
    }

    private void releaseIfNeeded(boolean acquired, RLock lock, String displayKey) {
        if (!acquired) {
            return;
        }

        boolean heldByCurrentThread;
        try {
            heldByCurrentThread = lock.isHeldByCurrentThread();
        } catch (Exception e) {
            log.error("[cascade-lock] 检查锁持有状态异常，可能存在锁泄露风险: {}", displayKey, e);
            return;
        }

        if (!heldByCurrentThread) {
            log.warn("[cascade-lock] 当前线程未持有锁，跳过释放: {}", displayKey);
            return;
        }

        try {
            lock.unlock();
            publishEvent(LockEvent.released(displayKey));
            log.debug("[cascade-lock] 锁已释放: {}", displayKey);
        } catch (Exception e) {
            log.error("[cascade-lock] 释放锁异常，可能存在锁泄露风险: {}", displayKey, e);
        }
    }

    private void publishEvent(LockEvent event) {
        if (eventListeners != null) {
            eventListeners.forEach(l -> {
                try {
                    l.onEvent(event);
                } catch (Exception e) {
                    log.warn(
                            "[cascade-lock] 事件监听器异常, lockKey={}, eventType={}, listener={}",
                            event.getLockKey(),
                            event.getType(),
                            l.getClass().getName(),
                            e
                    );
                }
            });
        }
    }
}
