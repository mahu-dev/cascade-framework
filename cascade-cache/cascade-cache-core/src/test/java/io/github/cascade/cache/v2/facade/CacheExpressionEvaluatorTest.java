package io.github.cascade.cache.v2.facade;

import io.github.cascade.cache.v2.common.exception.CacheConfigurationException;
import org.junit.jupiter.api.Test;
import org.springframework.expression.EvaluationContext;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CacheExpressionEvaluatorTest {

    private final CacheExpressionEvaluator evaluator = new CacheExpressionEvaluator();

    @Test
    void shouldResolveSingleDtoShorthandWithoutEagerGetterInvocation() throws Exception {
        Method method = DtoTarget.class.getMethod("handle", SideEffectDto.class);
        SideEffectDto dto = new SideEffectDto("u1", new NestedUser("n1"));

        boolean matched = evaluator.evaluateBoolean(
                "#id == 'u1'",
                method,
                new DtoTarget(),
                new Object[]{dto},
                null,
                false
        );

        assertTrue(matched);
        assertEquals(1, dto.idCalls.get(), "命中表达式变量 #id 时只应调用 getId()");
        assertEquals(0, dto.userCalls.get(), "未引用 #user 时不应调用 getUser()");
        assertEquals(0, dto.expensiveCalls.get(), "未引用字段不应触发副作用 getter");
    }

    @Test
    void shouldCallReferencedGetterOnlyOnceWithinSingleEvaluation() throws Exception {
        Method method = DtoTarget.class.getMethod("handle", SideEffectDto.class);
        SideEffectDto dto = new SideEffectDto("u1", new NestedUser("n1"));

        boolean matched = evaluator.evaluateBoolean(
                "#id == 'u1' && #id == 'u1'",
                method,
                new DtoTarget(),
                new Object[]{dto},
                null,
                false
        );

        assertTrue(matched);
        assertEquals(1, dto.idCalls.get(), "同一次表达式求值中，#id 应命中懒绑定缓存避免重复调用 getter");
    }

    @Test
    void shouldMemoizeNestedPropertyReadsForDtoShortcutVariable() throws Exception {
        Method method = DtoTarget.class.getMethod("handle", SideEffectDto.class);
        SideEffectDto dto = new SideEffectDto("u1", new NestedUser("n1"));

        boolean matched = evaluator.evaluateBoolean(
                "#user.id == 'n1' && #user.id == 'n1'",
                method,
                new DtoTarget(),
                new Object[]{dto},
                null,
                false
        );

        assertTrue(matched);
        assertEquals(1, dto.userCalls.get(), "同一次表达式求值中，#user 应只读取一次");
        assertEquals(1, dto.user.idCalls.get(), "同一次表达式求值中，#user.id 应只读取一次");
    }

    @Test
    void shouldMemoizeNestedPropertyReadsForIndexedArgumentVariable() throws Exception {
        Method method = DtoTarget.class.getMethod("handle", SideEffectDto.class);
        SideEffectDto dto = new SideEffectDto("u1", new NestedUser("n1"));

        boolean matched = evaluator.evaluateBoolean(
                "#p0.user.id == 'n1' && #p0.user.id == 'n1'",
                method,
                new DtoTarget(),
                new Object[]{dto},
                null,
                false
        );

        assertTrue(matched);
        assertEquals(1, dto.userCalls.get(), "同一次表达式求值中，#p0.user 应只读取一次");
        assertEquals(1, dto.user.idCalls.get(), "同一次表达式求值中，#p0.user.id 应只读取一次");
    }

    @Test
    void shouldExposeMethodAsMethodObjectAndMethodNameAlias() throws Exception {
        Method method = DtoTarget.class.getMethod("handle", SideEffectDto.class);
        SideEffectDto dto = new SideEffectDto("u1", new NestedUser("n1"));

        boolean matched = evaluator.evaluateBoolean(
                "#method.name == 'handle' && #method.parameterCount == 1 && #methodName == 'handle'",
                method,
                new DtoTarget(),
                new Object[]{dto},
                null,
                false
        );

        assertTrue(matched, "#method 应保持 Method 对象语义，且 #methodName 应可直接访问方法名");
    }

    @Test
    void shouldBoundPropertyMemoSizeWithinSingleEvaluationAccessor() throws Exception {
        Class<?> accessorClass = Class.forName(
                "io.github.cascade.cache.v2.facade.CacheExpressionEvaluator$MemoizingBeanPropertyAccessor");
        Constructor<?> constructor = accessorClass.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object accessor = constructor.newInstance();

        Method readMethod = accessorClass.getDeclaredMethod("read", EvaluationContext.class, Object.class, String.class);
        readMethod.setAccessible(true);
        int totalReads = CacheExpressionEvaluator.PROPERTY_READ_MEMO_MAX_ENTRIES + 128;
        for (int i = 0; i < totalReads; i++) {
            readMethod.invoke(accessor, null, new MemoProbeTarget("id-" + i), "id");
        }

        Field memoField = accessorClass.getDeclaredField("localPropertyReadMemo");
        memoField.setAccessible(true);
        Map<?, ?> memo = (Map<?, ?>) memoField.get(accessor);
        assertTrue(memo.size() <= CacheExpressionEvaluator.PROPERTY_READ_MEMO_MAX_ENTRIES,
                "单次求值内 property memo 应有上限，避免 target 数量线性膨胀");
    }

    @Test
    void shouldFailFastWhenMethodVariableIsComparedAsStringLiteral() throws Exception {
        Method method = DtoTarget.class.getMethod("handle", SideEffectDto.class);
        SideEffectDto dto = new SideEffectDto("u1", new NestedUser("n1"));

        CacheConfigurationException exception = assertThrows(CacheConfigurationException.class,
                () -> evaluator.evaluateBoolean(
                        "#method == 'handle'",
                        method,
                        new DtoTarget(),
                        new Object[]{dto},
                        null,
                        false
                ));
        assertTrue(exception.getMessage().contains("#methodName"),
                "应提示将字符串语义的#method表达式迁移到#methodName");
    }

    static class DtoTarget {
        @SuppressWarnings("unused")
        public void handle(SideEffectDto dto) {
            // no-op
        }
    }

    static class SideEffectDto {
        private final String id;
        private final NestedUser user;
        private final AtomicInteger idCalls = new AtomicInteger(0);
        private final AtomicInteger userCalls = new AtomicInteger(0);
        private final AtomicInteger expensiveCalls = new AtomicInteger(0);

        SideEffectDto(String id, NestedUser user) {
            this.id = id;
            this.user = user;
        }

        public String getId() {
            idCalls.incrementAndGet();
            return id;
        }

        public NestedUser getUser() {
            userCalls.incrementAndGet();
            return user;
        }

        public String getExpensive() {
            expensiveCalls.incrementAndGet();
            return "expensive";
        }
    }

    static class NestedUser {
        private final String id;
        private final AtomicInteger idCalls = new AtomicInteger(0);

        NestedUser(String id) {
            this.id = id;
        }

        public String getId() {
            idCalls.incrementAndGet();
            return id;
        }
    }

    static class MemoProbeTarget {
        private final String id;

        MemoProbeTarget(String id) {
            this.id = id;
        }

        public String getId() {
            return id;
        }
    }
}
