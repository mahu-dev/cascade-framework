package io.github.cascade.cache.v2.consistency;

import io.github.cascade.cache.v2.support.ObjectMapperHolder;
import org.junit.jupiter.api.Test;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisVersionManagerTest {

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

        RedisVersionManager<String> first = new RedisVersionManager<>("user-a", client, "it");
        RedisVersionManager<String> second = new RedisVersionManager<>("user-b", client, "it");

        Object firstMapper = readPrivateField(first, "objectMapper");
        Object secondMapper = readPrivateField(second, "objectMapper");

        assertSame(ObjectMapperHolder.getInstance(), firstMapper);
        assertSame(firstMapper, secondMapper);
    }

    @Test
    void shouldUseNamespaceVersionKeyForClearVersion() {
        AtomicLong namespaceCounter = new AtomicLong(6L);
        List<String> requestedAtomicKeys = new ArrayList<>();

        RAtomicLong atomicLong = (RAtomicLong) Proxy.newProxyInstance(
                RAtomicLong.class.getClassLoader(),
                new Class<?>[]{RAtomicLong.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "incrementAndGet" -> namespaceCounter.incrementAndGet();
                    case "get" -> namespaceCounter.get();
                    case "toString" -> "RAtomicLongProxy";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == (args != null && args.length > 0 ? args[0] : null);
                    default -> throw new UnsupportedOperationException("Unsupported RAtomicLong method: " + method.getName());
                }
        );

        RedissonClient client = (RedissonClient) Proxy.newProxyInstance(
                RedissonClient.class.getClassLoader(),
                new Class<?>[]{RedissonClient.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getAtomicLong" -> {
                        String key = (String) args[0];
                        requestedAtomicKeys.add(key);
                        yield atomicLong;
                    }
                    case "toString" -> "RedissonClientProxy";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == (args != null && args.length > 0 ? args[0] : null);
                    default -> throw new UnsupportedOperationException("Unsupported RedissonClient method: " + method.getName());
                }
        );

        RedisVersionManager<String> manager = new RedisVersionManager<>("user", client, "it");

        assertEquals(7L, manager.nextClearVersion());
        assertEquals(7L, manager.currentClearVersion());
        assertTrue(requestedAtomicKeys.contains("it:user:ns:version"));
        assertFalse(requestedAtomicKeys.contains("it:user:ver:clear"));
    }

    private static Object readPrivateField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }
}
