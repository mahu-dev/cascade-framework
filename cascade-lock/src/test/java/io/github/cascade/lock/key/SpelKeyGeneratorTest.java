package io.github.cascade.lock.key;

import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SpelKeyGeneratorTest {

    @Mock
    private ProceedingJoinPoint joinPoint;

    private Method method;
    private DummyService target;

    @BeforeEach
    void setUp() throws Exception {
        target = new DummyService();
        method = DummyService.class.getDeclaredMethod("process", Long.class);
        when(joinPoint.getTarget()).thenReturn(target);
    }

    @Test
    void shouldResolveBeanReferenceExpression() {
        when(joinPoint.getArgs()).thenReturn(new Object[]{123L});
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("keyBuilder", new KeyBuilder());

        SpelKeyGenerator keyGenerator = new SpelKeyGenerator(beanFactory);
        String result = keyGenerator.generate("@keyBuilder.build(#p0)", joinPoint, method);

        System.out.println(result);
        assertEquals("order:123", result);
    }

    @Test
    void shouldResolveArgumentExpressionWithoutBeanFactory() {
        when(joinPoint.getArgs()).thenReturn(new Object[]{123L});
        SpelKeyGenerator keyGenerator = new SpelKeyGenerator();
        String result = keyGenerator.generate("'order:' + #p0", joinPoint, method);

        assertEquals("order:123", result);
    }

    @Test
    void shouldFallbackToClassMethodWhenExpressionEmpty() {
        SpelKeyGenerator keyGenerator = new SpelKeyGenerator();
        String result = keyGenerator.generate("", joinPoint, method);

        assertEquals("DummyService.process", result);
    }

    private static class DummyService {
        @SuppressWarnings("unused")
        public void process(Long id) {
            // no-op
        }
    }

    private static class KeyBuilder {
        @SuppressWarnings("unused")
        public String build(Long id) {
            return "order:" + id;
        }
    }
}
