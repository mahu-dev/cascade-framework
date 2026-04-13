package cc.coderm.cascade.idempotent.executor;

import cc.coderm.cascade.idempotent.exception.IdempotentException;
import cc.coderm.cascade.idempotent.model.ConflictStrategy;
import cc.coderm.cascade.idempotent.model.IdempotentContext;
import cc.coderm.cascade.idempotent.serializer.ResultSerializer;
import cc.coderm.cascade.idempotent.store.IdempotentStore;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IdempotentExecutorTest {

    @Test
    void shouldRetryContendedAcquireBeforeFirstExecutionForFailFast() throws Throwable {
        IdempotentStore store = mock(IdempotentStore.class);
        ResultSerializer serializer = mock(ResultSerializer.class);
        when(serializer.serialize(any())).thenReturn("\"ok\"");
        when(serializer.nullPlaceholder()).thenReturn("__NULL__");
        when(store.tryOccupy(anyString(), anyString(), anyLong(), anyString()))
                .thenReturn(IdempotentStore.OccupyResult.contention())
                .thenReturn(IdempotentStore.OccupyResult.success());
        when(store.markSucceeded(anyString(), anyString(), anyString(), anyString(), anyLong())).thenReturn(true);

        IdempotentExecutor executor = new IdempotentExecutor(store, serializer, null);
        AtomicInteger calls = new AtomicInteger();
        IdempotentContext context = context("idem:executor:contended:retry", ConflictStrategy.FAIL_FAST, 0);

        try {
            Object value = executor.execute(context, () -> {
                calls.incrementAndGet();
                return "ok";
            });

            assertThat(value).isEqualTo("ok");
            assertThat(calls.get()).isEqualTo(1);
            verify(store, times(2)).tryOccupy(anyString(), anyString(), anyLong(), anyString());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void shouldFailClosedWhenContendedAcquireExceedsRetryWindow() {
        IdempotentStore store = mock(IdempotentStore.class);
        ResultSerializer serializer = mock(ResultSerializer.class);
        when(store.tryOccupy(anyString(), anyString(), anyLong(), anyString()))
                .thenReturn(IdempotentStore.OccupyResult.contention());

        IdempotentExecutor executor = new IdempotentExecutor(store, serializer, null);
        IdempotentContext context = context("idem:executor:contended:timeout", ConflictStrategy.FAIL_FAST, 0);

        try {
            assertThatThrownBy(() -> executor.execute(context, () -> "ok"))
                    .isInstanceOf(IdempotentException.class)
                    .hasMessageContaining("Unable to determine occupancy");
        } finally {
            executor.shutdown();
        }
    }

    private static IdempotentContext context(String key, ConflictStrategy strategy, long waitTimeoutMs) {
        return IdempotentContext.builder()
                .idempotentKey(key)
                .ttlMs(5_000)
                .scene("test")
                .deleteOnSuccess(false)
                .deleteOnFailure(true)
                .conflictStrategy(strategy)
                .waitTimeoutMs(waitTimeoutMs)
                .targetMethod(null)
                .returnType(String.class)
                .build();
    }
}
