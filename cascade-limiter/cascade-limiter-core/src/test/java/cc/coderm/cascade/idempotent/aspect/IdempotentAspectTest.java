package cc.coderm.cascade.idempotent.aspect;

import cc.coderm.cascade.idempotent.annotation.Idempotent;
import cc.coderm.cascade.idempotent.config.CascadeIdempotentProperties;
import cc.coderm.cascade.idempotent.executor.IdempotentBusiness;
import cc.coderm.cascade.idempotent.executor.IdempotentExecutor;
import cc.coderm.cascade.idempotent.key.IdempotentKeyHasher;
import cc.coderm.cascade.idempotent.model.IdempotentContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanFactory;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IdempotentAspectTest {

    private final IdempotentExecutor executor = mock(IdempotentExecutor.class);
    private final CascadeIdempotentProperties properties = new CascadeIdempotentProperties();
    private final BeanFactory beanFactory = mock(BeanFactory.class);
    private final IdempotentKeyHasher keyHasher = mock(IdempotentKeyHasher.class);

    @Test
    void shouldRestoreInterruptFlagAndWrapInterruptedException() throws Throwable {
        IdempotentAspect aspect = new IdempotentAspect(executor, properties, beanFactory, keyHasher);
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        DemoService target = new DemoService();
        Method method = DemoService.class.getDeclaredMethod("doWork");
        Idempotent idempotent = method.getAnnotation(Idempotent.class);

        when(signature.getMethod()).thenReturn(method);
        when(pjp.getSignature()).thenReturn(signature);
        when(pjp.getTarget()).thenReturn(target);
        when(pjp.getArgs()).thenReturn(new Object[0]);
        when(keyHasher.hash(any(Method.class), any(Object[].class))).thenReturn("hash");
        when(executor.execute(any(IdempotentContext.class), any(IdempotentBusiness.class)))
                .thenThrow(new InterruptedException("stop"));

        try {
            assertThatThrownBy(() -> aspect.around(pjp, idempotent))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Interrupted while waiting idempotent completion.")
                    .hasCauseInstanceOf(InterruptedException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    private static final class DemoService {
        @Idempotent
        public String doWork() {
            return "ok";
        }
    }
}
