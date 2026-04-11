package io.github.cascade.lock.core;

import io.github.cascade.lock.enums.LockStrategy;
import io.github.cascade.lock.enums.LockType;
import io.github.cascade.lock.exception.LockException;
import io.github.cascade.lock.factory.LockFactory;
import io.github.cascade.lock.model.LockInfo;
import io.github.cascade.lock.model.LockResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedissonLockExecutorTest {

    @Mock
    private LockFactory lockFactory;

    @Mock
    private RLock lock;

    private RedissonLockExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new RedissonLockExecutor(lockFactory, List.of());
        when(lockFactory.getLock(any(), anyString())).thenReturn(lock);
    }

    @Test
    void shouldBlockUntilAcquiredWhenKeepTryingAndWaitTimeIsNegative() throws Exception {
        LockInfo lockInfo = buildLockInfo(LockStrategy.KEEP_TRYING, -1, -1);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        LockResult<String> result = executor.execute(lockInfo, () -> "ok");

        assertTrue(result.isAcquired());
        assertEquals("ok", result.getResult());
        verify(lock).lockInterruptibly();
        verify(lock, never()).tryLock();
        verify(lock, never()).tryLock(anyLong(), anyLong(), any());
    }

    @Test
    void shouldUseLeaseBlockingLockWhenKeepTryingAndWaitTimeIsNegative() throws Exception {
        LockInfo lockInfo = buildLockInfo(LockStrategy.KEEP_TRYING, -1, 5);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        executor.execute(lockInfo, () -> "ok");

        verify(lock).lockInterruptibly(5, TimeUnit.SECONDS);
        verify(lock, never()).tryLock();
        verify(lock, never()).lockInterruptibly();
        verify(lock, never()).tryLock(anyLong(), anyLong(), any());
    }

    @Test
    void shouldFailAfterTimedWaitWhenKeepTryingWithPositiveWaitTime() throws Exception {
        LockInfo lockInfo = buildLockInfo(LockStrategy.KEEP_TRYING, 2, -1);
        when(lock.tryLock(2, -1, TimeUnit.SECONDS)).thenReturn(false);

        LockException ex = assertThrows(LockException.class, () -> executor.execute(lockInfo, () -> "ok"));

        assertEquals("KEEP_TRYING 模式下仍未获取到锁", ex.getMessage());
        verify(lock).tryLock(2, -1, TimeUnit.SECONDS);
        verify(lock, never()).lockInterruptibly();
        verify(lock, never()).lockInterruptibly(anyLong(), any());
    }

    @Test
    void shouldStillFailFastImmediatelyWhenStrategyIsFailFast() throws Exception {
        LockInfo lockInfo = buildLockInfo(LockStrategy.FAIL_FAST, -1, -1);
        when(lock.tryLock()).thenReturn(false);

        LockException ex = assertThrows(LockException.class, () -> executor.execute(lockInfo, () -> "ok"));

        assertEquals("获取分布式锁失败: test-lock", ex.getMessage());
        verify(lock).tryLock();
        verify(lock, never()).lockInterruptibly();
        verify(lock, never()).lockInterruptibly(anyLong(), any());
    }

    @Test
    void shouldPropagateBusinessRuntimeExceptionWithoutWrapping() throws Exception {
        LockInfo lockInfo = buildLockInfo(LockStrategy.FAIL_FAST, -1, -1);
        when(lock.tryLock()).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        IllegalStateException expected = new IllegalStateException("biz-runtime-error");
        IllegalStateException actual = assertThrows(IllegalStateException.class,
                () -> executor.execute(lockInfo, () -> {
                    throw expected;
                }));

        assertSame(expected, actual);
        verify(lock).unlock();
    }

    @Test
    void shouldPropagateBusinessCheckedExceptionWithoutWrapping() throws Exception {
        LockInfo lockInfo = buildLockInfo(LockStrategy.FAIL_FAST, -1, -1);
        when(lock.tryLock()).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        BizCheckedException expected = new BizCheckedException("biz-checked-error");
        BizCheckedException actual = assertThrows(BizCheckedException.class,
                () -> executor.execute(lockInfo, () -> {
                    throw expected;
                }));

        assertSame(expected, actual);
        verify(lock).unlock();
    }

    @Test
    void shouldRestoreInterruptedFlagWhenAcquireInterrupted() throws Exception {
        Thread.interrupted();
        LockInfo lockInfo = buildLockInfo(LockStrategy.FAIL_FAST, 1, -1);
        when(lock.tryLock(1, -1, TimeUnit.SECONDS)).thenThrow(new InterruptedException("interrupted"));

        try {
            assertFalse(Thread.currentThread().isInterrupted());
            LockException ex = assertThrows(LockException.class, () -> executor.execute(lockInfo, () -> "ok"));
            assertEquals("获取锁时线程被中断", ex.getMessage());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void shouldNotFailWhenUnlockThrowsException() throws Exception {
        LockInfo lockInfo = buildLockInfo(LockStrategy.FAIL_FAST, -1, -1);
        when(lock.tryLock()).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        doThrow(new IllegalMonitorStateException("unlock-failed")).when(lock).unlock();

        LockResult<String> result = executor.execute(lockInfo, () -> "ok");

        assertTrue(result.isAcquired());
        assertEquals("ok", result.getResult());
        verify(lock).unlock();
    }

    @Test
    void shouldNotFailWhenHoldCheckThrowsException() throws Exception {
        LockInfo lockInfo = buildLockInfo(LockStrategy.FAIL_FAST, -1, -1);
        when(lock.tryLock()).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenThrow(new RuntimeException("check-failed"));

        LockResult<String> result = executor.execute(lockInfo, () -> "ok");

        assertTrue(result.isAcquired());
        assertEquals("ok", result.getResult());
        verify(lock, never()).unlock();
    }

    @Test
    void shouldKeepBusinessExceptionWhenUnlockThrowsException() throws Exception {
        LockInfo lockInfo = buildLockInfo(LockStrategy.FAIL_FAST, -1, -1);
        when(lock.tryLock()).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        doThrow(new IllegalMonitorStateException("unlock-failed")).when(lock).unlock();

        IllegalStateException expected = new IllegalStateException("biz-error");
        IllegalStateException actual = assertThrows(IllegalStateException.class,
                () -> executor.execute(lockInfo, () -> {
                    throw expected;
                }));

        assertSame(expected, actual);
    }

    private LockInfo buildLockInfo(LockStrategy strategy, long waitTime, long leaseTime) {
        return LockInfo.builder()
                .keys(List.of("test-lock"))
                .lockType(LockType.REENTRANT)
                .lockStrategy(strategy)
                .waitTime(waitTime)
                .leaseTime(leaseTime)
                .timeUnit(TimeUnit.SECONDS)
                .failMessage("获取分布式锁失败: test-lock")
                .build();
    }

    private static class BizCheckedException extends Exception {
        private BizCheckedException(String message) {
            super(message);
        }
    }
}
