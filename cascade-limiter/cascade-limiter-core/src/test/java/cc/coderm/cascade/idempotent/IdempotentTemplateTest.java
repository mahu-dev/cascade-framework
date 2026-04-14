package cc.coderm.cascade.idempotent;

import cc.coderm.cascade.idempotent.config.CascadeIdempotentProperties;
import cc.coderm.cascade.idempotent.exception.IdempotentConflictException;
import cc.coderm.cascade.idempotent.executor.IdempotentBusiness;
import cc.coderm.cascade.idempotent.executor.IdempotentExecutor;
import cc.coderm.cascade.idempotent.model.ConflictStrategy;
import cc.coderm.cascade.idempotent.model.IdempotentContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.ParameterizedTypeReference;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IdempotentTemplateTest {

    private final IdempotentExecutor executor = mock(IdempotentExecutor.class);
    private final CascadeIdempotentProperties properties = new CascadeIdempotentProperties();

    private IdempotentTemplate template;

    @BeforeEach
    void setUp() {
        template = new IdempotentTemplate(executor, properties);
    }

    @Test
    void shouldBuildDefaultContextAndExecuteBusiness() throws Throwable {
        when(executor.execute(any(IdempotentContext.class), any(IdempotentBusiness.class)))
                .thenAnswer(invocation -> invocation.<IdempotentBusiness>getArgument(1).call());

        String value = template.key("order:1001")
                .execute(String.class, () -> "ok");

        assertThat(value).isEqualTo("ok");
        ArgumentCaptor<IdempotentContext> contextCaptor = ArgumentCaptor.forClass(IdempotentContext.class);
        verify(executor).execute(contextCaptor.capture(), any(IdempotentBusiness.class));
        IdempotentContext context = contextCaptor.getValue();
        assertThat(context.getIdempotentKey()).isEqualTo("cascade:idempotent:default:order:1001");
        assertThat(context.getTtlMs()).isEqualTo(24 * 60 * 60 * 1000L);
        assertThat(context.getScene()).isEqualTo("default");
        assertThat(context.getConflictStrategy()).isEqualTo(ConflictStrategy.WAIT);
        assertThat(context.getWaitTimeoutMs()).isEqualTo(5000L);
        assertThat(context.getReturnType()).isEqualTo(String.class);
    }

    @Test
    void shouldUseParameterizedTypeAsReturnType() throws Throwable {
        when(executor.execute(any(IdempotentContext.class), any(IdempotentBusiness.class)))
                .thenReturn(List.of("A", "B"));
        ParameterizedTypeReference<List<String>> type = new ParameterizedTypeReference<>() {
        };

        List<String> value = template.key("list:1001")
                .scene("orders")
                .execute(type, () -> List.of("A", "B"));

        assertThat(value).containsExactly("A", "B");
        ArgumentCaptor<IdempotentContext> contextCaptor = ArgumentCaptor.forClass(IdempotentContext.class);
        verify(executor).execute(contextCaptor.capture(), any(IdempotentBusiness.class));
        Type expectedType = type.getType();
        assertThat(contextCaptor.getValue().getReturnType()).isEqualTo(expectedType);
    }

    @Test
    void shouldRunConflictFallbackWhenConfigured() throws Throwable {
        when(executor.execute(any(IdempotentContext.class), any(IdempotentBusiness.class)))
                .thenThrow(new IdempotentConflictException("order:1002", null));

        String value = template.key("order:1002")
                .conflictStrategy(ConflictStrategy.FAIL_FAST)
                .execute(String.class, () -> "primary", () -> "fallback");

        assertThat(value).isEqualTo("fallback");
    }

    @Test
    void shouldRunVoidAction() throws Throwable {
        when(executor.execute(any(IdempotentContext.class), any(IdempotentBusiness.class)))
                .thenAnswer(invocation -> {
                    invocation.<IdempotentBusiness>getArgument(1).call();
                    return null;
                });
        AtomicBoolean invoked = new AtomicBoolean(false);

        template.key("void:1003").run(() -> invoked.set(true));

        assertThat(invoked).isTrue();
    }

    @Test
    void shouldRejectCrossThreadBuilderReuse() throws Exception {
        IdempotentTemplate.Builder builder = template.key("thread:1004");
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                builder.scene("worker");
            } catch (Throwable ex) {
                error.set(ex);
            }
        });

        worker.start();
        worker.join();

        assertThat(error.get()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("thread-confined");
    }

    @Test
    void shouldRejectBlankKey() {
        assertThatThrownBy(() -> template.key("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank");
    }

    @Test
    void shouldRethrowBusinessCheckedException() throws Throwable {
        when(executor.execute(any(IdempotentContext.class), any(IdempotentBusiness.class)))
                .thenAnswer(invocation -> invocation.<IdempotentBusiness>getArgument(1).call());

        assertThatThrownBy(() -> template.key("checked:1005")
                .execute(String.class, () -> {
                    throw new DemoBusinessException("biz");
                }))
                .isInstanceOf(DemoBusinessException.class)
                .hasMessage("biz");
    }

    @Test
    void shouldWrapInternalCheckedExceptionFromExecutor() throws Throwable {
        when(executor.execute(any(IdempotentContext.class), any(IdempotentBusiness.class)))
                .thenThrow(new IOException("io"));

        assertThatThrownBy(() -> template.key("checked:1006")
                .execute(String.class, () -> "ok"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unexpected checked throwable")
                .hasCauseInstanceOf(IOException.class);
    }

    @Test
    void shouldRestoreInterruptFlagWhenInterrupted() throws Throwable {
        when(executor.execute(any(IdempotentContext.class), any(IdempotentBusiness.class)))
                .thenThrow(new InterruptedException("stop"));

        try {
            assertThatThrownBy(() -> template.key("interrupt:1007")
                    .execute(String.class, () -> "ok"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Interrupted while waiting")
                    .hasCauseInstanceOf(InterruptedException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    private static final class DemoBusinessException extends Exception {
        private DemoBusinessException(String message) {
            super(message);
        }
    }
}
