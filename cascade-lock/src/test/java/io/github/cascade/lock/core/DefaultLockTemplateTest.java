package io.github.cascade.lock.core;

import io.github.cascade.lock.config.CascadeLockProperties;
import io.github.cascade.lock.enums.LockStrategy;
import io.github.cascade.lock.enums.LockType;
import io.github.cascade.lock.exception.LockException;
import io.github.cascade.lock.model.LockInfo;
import io.github.cascade.lock.model.LockResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultLockTemplateTest {

    @Mock
    private LockExecutor lockExecutor;

    private CascadeLockProperties properties;
    private DefaultLockTemplate lockTemplate;

    @BeforeEach
    void setUp() {
        properties = new CascadeLockProperties();
        lockTemplate = new DefaultLockTemplate(lockExecutor, properties);
    }

    @Test
    void shouldFailFastWhenUsingRedTypeWithSingleKeyApi() {
        assertThrows(LockException.class,
                () -> lockTemplate.lock("order:1", LockType.RED, () -> "ok"));
        verifyNoInteractions(lockExecutor);
    }

    @Test
    void shouldFailFastWhenSingleKeyLockTypeIsNull() {
        LockException ex = assertThrows(
                LockException.class,
                () -> lockTemplate.lock("order:1", (LockType) null, () -> "ok")
        );
        assertEquals("单 key API 的 lockType 不能为空", ex.getMessage());
        verifyNoInteractions(lockExecutor);
    }

    @Test
    void shouldFailFastWhenSingleKeyIsNull() {
        LockException ex = assertThrows(
                LockException.class,
                () -> lockTemplate.lock((String) null, LockType.REENTRANT, () -> "ok")
        );
        assertEquals("单 key API 的 key 不能为空", ex.getMessage());
        verifyNoInteractions(lockExecutor);
    }

    @Test
    void shouldFailFastWhenSingleKeyIsBlank() {
        LockException ex = assertThrows(
                LockException.class,
                () -> lockTemplate.lock("   ", LockType.REENTRANT, () -> "ok")
        );
        assertEquals("单 key API 的 key 不能为空白", ex.getMessage());
        verifyNoInteractions(lockExecutor);
    }

    @Test
    void shouldFailFastWhenUsingNonMultiTypeWithMultiKeyApi() {
        assertThrows(LockException.class,
                () -> lockTemplate.lock(List.of("order:1", "order:2"), LockType.REENTRANT, () -> "ok"));
        verifyNoInteractions(lockExecutor);
    }

    @Test
    void shouldFailFastWhenMultiKeyLockTypeIsNull() {
        LockException ex = assertThrows(
                LockException.class,
                () -> lockTemplate.lock(List.of("order:1", "order:2"), (LockType) null, () -> "ok")
        );
        assertEquals("多 key API 的 lockType 不能为空", ex.getMessage());
        verifyNoInteractions(lockExecutor);
    }

    @Test
    void shouldFailFastWhenMultiKeyContainsBlankValue() {
        assertThrows(LockException.class,
                () -> lockTemplate.lock(List.of("order:1", "   "), LockType.MULTI, () -> "ok"));
        verifyNoInteractions(lockExecutor);
    }

    @Test
    void shouldBuildMultiKeyLockInfoForRedLock() {
        when(lockExecutor.execute(any(), any()))
                .thenReturn(LockResult.success("ok", "multi[order:1,order:2]", 1));

        LockResult<String> result = lockTemplate.lock(
                List.of(" order:1 ", "order:2"),
                LockType.RED,
                3,
                9,
                TimeUnit.SECONDS,
                LockStrategy.FAIL_FAST,
                () -> "business");

        assertEquals("ok", result.getResult());

        ArgumentCaptor<LockInfo> captor = ArgumentCaptor.forClass(LockInfo.class);
        verify(lockExecutor).execute(captor.capture(), any());
        LockInfo lockInfo = captor.getValue();

        assertEquals(LockType.RED, lockInfo.getLockType());
        assertEquals(List.of("cascade:lock:order:1", "cascade:lock:order:2"), lockInfo.getKeys());
        assertEquals("multi[cascade:lock:order:1,cascade:lock:order:2]", lockInfo.getDisplayKey());
        assertEquals("获取分布式锁失败: cascade:lock:order:1,cascade:lock:order:2", lockInfo.getFailMessage());
        assertEquals(3, lockInfo.getWaitTime());
        assertEquals(9, lockInfo.getLeaseTime());
        assertEquals(TimeUnit.SECONDS, lockInfo.getTimeUnit());
        assertEquals(LockStrategy.FAIL_FAST, lockInfo.getLockStrategy());
    }

    @Test
    void shouldUseGlobalWaitAndLeaseDefaultsForSingleKeyApi() {
        properties.setWaitTime(7);
        properties.setLeaseTime(13);
        when(lockExecutor.execute(any(), any()))
                .thenReturn(LockResult.success("ok", "order:1", 1));

        String result = lockTemplate.lock("order:1", LockType.REENTRANT, () -> "business");
        assertEquals("ok", result);

        ArgumentCaptor<LockInfo> captor = ArgumentCaptor.forClass(LockInfo.class);
        verify(lockExecutor).execute(captor.capture(), any());
        LockInfo lockInfo = captor.getValue();
        assertEquals(7, lockInfo.getWaitTime());
        assertEquals(13, lockInfo.getLeaseTime());
    }

    @Test
    void shouldTrimSingleKeyBeforePrefixAndLockInfoBuild() {
        when(lockExecutor.execute(any(), any()))
                .thenReturn(LockResult.success("ok", "order:1", 1));

        String result = lockTemplate.lock(" order:1 ", LockType.REENTRANT, () -> "business");
        assertEquals("ok", result);

        ArgumentCaptor<LockInfo> captor = ArgumentCaptor.forClass(LockInfo.class);
        verify(lockExecutor).execute(captor.capture(), any());
        LockInfo lockInfo = captor.getValue();
        assertEquals(List.of("cascade:lock:order:1"), lockInfo.getKeys());
        assertEquals("获取分布式锁失败: cascade:lock:order:1", lockInfo.getFailMessage());
    }

    @Test
    void shouldUseGlobalWaitAndLeaseDefaultsForMultiKeyApi() {
        properties.setWaitTime(5);
        properties.setLeaseTime(11);
        when(lockExecutor.execute(any(), any()))
                .thenReturn(LockResult.success("ok", "multi[order:1,order:2]", 1));

        String result = lockTemplate.lock(List.of("order:1", "order:2"), LockType.MULTI, () -> "business");
        assertEquals("ok", result);

        ArgumentCaptor<LockInfo> captor = ArgumentCaptor.forClass(LockInfo.class);
        verify(lockExecutor).execute(captor.capture(), any());
        LockInfo lockInfo = captor.getValue();
        assertEquals(5, lockInfo.getWaitTime());
        assertEquals(11, lockInfo.getLeaseTime());
    }
}
