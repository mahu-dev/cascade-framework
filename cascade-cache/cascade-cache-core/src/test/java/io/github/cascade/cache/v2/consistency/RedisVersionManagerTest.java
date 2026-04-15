package io.github.cascade.cache.v2.consistency;

import org.junit.jupiter.api.Test;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisVersionManagerTest {

    @Test
    void shouldUseBoundedSequenceKeyForKeyVersions() {
        AtomicLong sequenceCounter = new AtomicLong(40L);
        AtomicLong namespaceCounter = new AtomicLong(1L);
        List<String> requestedAtomicKeys = new ArrayList<>();

        RAtomicLong sequence = atomicLongProxy(sequenceCounter);
        RAtomicLong namespace = atomicLongProxy(namespaceCounter);

        RedissonClient client = (RedissonClient) Proxy.newProxyInstance(
                RedissonClient.class.getClassLoader(),
                new Class<?>[]{RedissonClient.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getAtomicLong" -> {
                        String key = (String) args[0];
                        requestedAtomicKeys.add(key);
                        if ("it:user:ver:sequence".equals(key)) {
                            yield sequence;
                        }
                        if ("it:user:ns:version".equals(key)) {
                            yield namespace;
                        }
                        throw new AssertionError("unexpected atomic key: " + key);
                    }
                    case "toString" -> "RedissonClientProxy";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == (args != null && args.length > 0 ? args[0] : null);
                    default -> throw new UnsupportedOperationException("Unsupported RedissonClient method: " + method.getName());
                }
        );

        RedisVersionManager<String> manager = new RedisVersionManager<>("user", client, "it");

        assertEquals(41L, manager.nextVersion("k1"));
        assertEquals(42L, manager.nextVersion("k2"));
        assertEquals(42L, manager.currentVersion("k1"));
        assertTrue(requestedAtomicKeys.contains("it:user:ver:sequence"));
        assertFalse(requestedAtomicKeys.stream()
                .anyMatch(key -> key.startsWith("it:user:ver:") && !"it:user:ver:sequence".equals(key)));
    }

    @Test
    void shouldUseNamespaceVersionKeyForClearVersion() {
        AtomicLong namespaceCounter = new AtomicLong(6L);
        AtomicLong sequenceCounter = new AtomicLong(0L);
        List<String> requestedAtomicKeys = new ArrayList<>();

        RAtomicLong namespace = atomicLongProxy(namespaceCounter);
        RAtomicLong sequence = atomicLongProxy(sequenceCounter);

        RedissonClient client = (RedissonClient) Proxy.newProxyInstance(
                RedissonClient.class.getClassLoader(),
                new Class<?>[]{RedissonClient.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getAtomicLong" -> {
                        String key = (String) args[0];
                        requestedAtomicKeys.add(key);
                        if ("it:user:ns:version".equals(key)) {
                            yield namespace;
                        }
                        if ("it:user:ver:sequence".equals(key)) {
                            yield sequence;
                        }
                        throw new AssertionError("unexpected atomic key: " + key);
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
        assertFalse(requestedAtomicKeys.contains("it:user:ver:sequence"));
    }

    private static RAtomicLong atomicLongProxy(AtomicLong counter) {
        return (RAtomicLong) Proxy.newProxyInstance(
                RAtomicLong.class.getClassLoader(),
                new Class<?>[]{RAtomicLong.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "incrementAndGet" -> counter.incrementAndGet();
                    case "get" -> counter.get();
                    case "toString" -> "RAtomicLongProxy";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == (args != null && args.length > 0 ? args[0] : null);
                    default -> throw new UnsupportedOperationException("Unsupported RAtomicLong method: " + method.getName());
                }
        );
    }
}
