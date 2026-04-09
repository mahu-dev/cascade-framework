package io.github.cascade.cache.v2.loader;

import io.github.cascade.cache.v2.support.ObjectMapperHolder;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertSame;

class RedisDistLockCoordinatorTest {

    @Test
    void shouldReuseSharedObjectMapperAcrossInstances() throws Exception {
        RedissonClient client = (RedissonClient) Proxy.newProxyInstance(
                RedissonClient.class.getClassLoader(),
                new Class<?>[]{RedissonClient.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "toString" -> "RedissonClientProxy";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == (args != null && args.length > 0 ? args[0] : null);
                    default -> throw new UnsupportedOperationException("Unsupported RedissonClient method: " + method.getName());
                }
        );

        RedisDistLockCoordinator<String> first = new RedisDistLockCoordinator<>("user-a", client, "it");
        RedisDistLockCoordinator<String> second = new RedisDistLockCoordinator<>("user-b", client, "it");

        Object firstMapper = readPrivateField(first, "objectMapper");
        Object secondMapper = readPrivateField(second, "objectMapper");

        assertSame(ObjectMapperHolder.getInstance(), firstMapper);
        assertSame(firstMapper, secondMapper);
    }

    private static Object readPrivateField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }
}
