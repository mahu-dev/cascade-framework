package io.github.cascade.cache.v2.support;

import org.aspectj.lang.JoinPoint;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultCacheKeyGeneratorTest {

    @Test
    void shouldGenerateStableSha256KeyForSameArgs() {
        JoinPoint joinPoint = joinPoint(new Object[]{"u1", 42, new int[]{1, 2, 3}});

        String key1 = DefaultCacheKeyGenerator.generate(joinPoint);
        String key2 = DefaultCacheKeyGenerator.generate(joinPoint);

        assertEquals(key1, key2);
        assertTrue(key1.startsWith("args#sha256:"));
        assertEquals("args#sha256:".length() + 64, key1.length(), "默认key应为固定长度的SHA-256摘要");
    }

    @Test
    void shouldAvoidDeepHashCodeCollisionAlias() {
        Object[] left = new Object[]{0, 31};
        Object[] right = new Object[]{1, 0};
        assertEquals(Arrays.deepHashCode(left), Arrays.deepHashCode(right), "测试前提：两组参数32位哈希碰撞");

        String leftKey = DefaultCacheKeyGenerator.generate(joinPoint(left));
        String rightKey = DefaultCacheKeyGenerator.generate(joinPoint(right));

        assertNotEquals(leftKey, rightKey, "碰撞参数不应映射到同一默认缓存键");
    }

    @Test
    void shouldHandleNullArgsSafely() {
        String key = DefaultCacheKeyGenerator.generate(joinPoint(null));
        assertTrue(key.startsWith("args#sha256:"));
    }

    private static JoinPoint joinPoint(Object[] args) {
        return (JoinPoint) Proxy.newProxyInstance(
                JoinPoint.class.getClassLoader(),
                new Class<?>[]{JoinPoint.class},
                (proxy, method, methodArgs) -> switch (method.getName()) {
                    case "getArgs" -> args;
                    case "toString", "toShortString", "toLongString" -> "JoinPointStub";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == (methodArgs != null && methodArgs.length > 0 ? methodArgs[0] : null);
                    default -> null;
                }
        );
    }
}
