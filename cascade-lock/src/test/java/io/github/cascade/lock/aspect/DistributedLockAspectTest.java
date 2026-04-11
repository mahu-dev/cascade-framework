package io.github.cascade.lock.aspect;

import io.github.cascade.lock.annotation.DistributedLock;
import io.github.cascade.lock.config.CascadeLockProperties;
import io.github.cascade.lock.core.LockExecutor;
import io.github.cascade.lock.enums.LockStrategy;
import io.github.cascade.lock.enums.LockType;
import io.github.cascade.lock.exception.LockException;
import io.github.cascade.lock.key.KeyGenerator;
import io.github.cascade.lock.model.LockInfo;
import io.github.cascade.lock.model.LockResult;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DistributedLockAspectTest {

    @Mock
    private ProceedingJoinPoint joinPoint;

    @Mock
    private MethodSignature methodSignature;

    @Mock
    private KeyGenerator keyGenerator;

    private CascadeLockProperties properties;

    @BeforeEach
    void setUp() throws Exception {
        properties = new CascadeLockProperties();
        properties.setKeyPrefix("cascade:lock");

        when(joinPoint.getSignature()).thenReturn(methodSignature);
        mockMethod("process");

        when(keyGenerator.generate(anyString(), eq(joinPoint), any(Method.class)))
                .thenReturn("order:1");
    }

    @Test
    void shouldPropagateCheckedExceptionFromJoinPoint() throws Throwable {
        DistributedLockAspect aspect = createAspect(new PassthroughLockExecutor());
        BizCheckedException expected = new BizCheckedException("biz-checked-error");
        when(joinPoint.proceed()).thenThrow(expected);

        BizCheckedException actual = assertThrows(BizCheckedException.class,
                () -> aspect.around(joinPoint));

        assertSame(expected, actual);
    }

    @Test
    void shouldPropagateRuntimeExceptionFromJoinPoint() throws Throwable {
        DistributedLockAspect aspect = createAspect(new PassthroughLockExecutor());
        IllegalArgumentException expected = new IllegalArgumentException("biz-runtime-error");
        when(joinPoint.proceed()).thenThrow(expected);

        IllegalArgumentException actual = assertThrows(IllegalArgumentException.class,
                () -> aspect.around(joinPoint));

        assertSame(expected, actual);
    }

    @Test
    void shouldThrowReadableLockExceptionWhenSkipReturnsNullForPrimitiveReturnType() throws Throwable {
        DistributedLockAspect aspect = createAspect(new AlwaysSkipLockExecutor());
        mockMethod("primitiveInt");

        LockException ex = assertThrows(LockException.class,
                () -> aspect.around(joinPoint));

        assertTrue(ex.getMessage().contains("SKIP 策略在未获取锁时会返回 null"));
        assertTrue(ex.getMessage().contains("primitiveInt"));
    }

    @Test
    void shouldAllowNullWhenSkipReturnsNullForReferenceReturnType() throws Throwable {
        DistributedLockAspect aspect = createAspect(new AlwaysSkipLockExecutor());
        mockMethod("objectValue");

        Object result = aspect.around(joinPoint);
        assertNull(result);
    }

    @Test
    void shouldUseGlobalWaitAndLeaseWhenAnnotationValuesUnset() throws Throwable {
        properties.setWaitTime(6);
        properties.setLeaseTime(12);
        CapturingLockExecutor lockExecutor = new CapturingLockExecutor();
        DistributedLockAspect aspect = createAspect(lockExecutor);

        aspect.around(joinPoint);

        assertEquals(6, lockExecutor.capturedLockInfo.getWaitTime());
        assertEquals(12, lockExecutor.capturedLockInfo.getLeaseTime());
    }

    @Test
    void shouldPreferAnnotationValuesWhenWaitAndLeaseExplicitlySet() throws Throwable {
        properties.setWaitTime(6);
        properties.setLeaseTime(12);
        mockMethod("withExplicitTiming");

        CapturingLockExecutor lockExecutor = new CapturingLockExecutor();
        DistributedLockAspect aspect = createAspect(lockExecutor);

        aspect.around(joinPoint);

        assertEquals(-1L, lockExecutor.capturedLockInfo.getWaitTime());
        assertEquals(9L, lockExecutor.capturedLockInfo.getLeaseTime());
    }

    private DistributedLockAspect createAspect(LockExecutor lockExecutor) {
        return new DistributedLockAspect(lockExecutor, keyGenerator, properties);
    }

    private void mockMethod(String methodName) throws NoSuchMethodException {
        Method method = DummyService.class.getDeclaredMethod(methodName);
        when(methodSignature.getMethod()).thenReturn(method);
    }

    private static class PassthroughLockExecutor implements LockExecutor {
        @Override
        public <T> LockResult<T> execute(LockInfo lockInfo, Callable<T> action) {
            try {
                return LockResult.success(action.call(), lockInfo.getDisplayKey(), 0);
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Throwable e) {
                return sneakyThrow(e);
            }
        }

        @SuppressWarnings("unchecked")
        private static <T, E extends Throwable> T sneakyThrow(Throwable throwable) throws E {
            throw (E) throwable;
        }
    }

    private static class AlwaysSkipLockExecutor implements LockExecutor {
        @Override
        public <T> LockResult<T> execute(LockInfo lockInfo, Callable<T> action) {
            return LockResult.failed(lockInfo.getDisplayKey());
        }
    }

    private static class CapturingLockExecutor implements LockExecutor {
        private LockInfo capturedLockInfo;

        @Override
        public <T> LockResult<T> execute(LockInfo lockInfo, Callable<T> action) {
            capturedLockInfo = lockInfo;
            return LockResult.success(null, lockInfo.getDisplayKey(), 0);
        }
    }

    @SuppressWarnings("unused")
    private static class DummyService {
        @DistributedLock("'order:1'")
        public void process() {
            // no-op
        }

        @DistributedLock(keys = "'order:1'", strategy = LockStrategy.SKIP)
        public int primitiveInt() {
            return 1;
        }

        @DistributedLock(keys = "'order:1'", strategy = LockStrategy.SKIP)
        public String objectValue() {
            return "v";
        }

        @DistributedLock(keys = "'order:1'", waitTime = -1, leaseTime = 9)
        public void withExplicitTiming() {
            // no-op
        }
    }

    private static class BizCheckedException extends Exception {
        private BizCheckedException(String message) {
            super(message);
        }
    }
}
