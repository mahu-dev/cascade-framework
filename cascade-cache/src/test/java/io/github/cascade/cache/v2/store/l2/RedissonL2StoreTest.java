package io.github.cascade.cache.v2.store.l2;

import org.junit.jupiter.api.Test;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedissonL2StoreTest {

    @Test
    void clearShouldBumpNamespaceVersionWithoutKeyScan() {
        AtomicLong namespaceVersion = new AtomicLong(0L);
        AtomicInteger getKeysCalls = new AtomicInteger(0);
        List<String> requestedAtomicKeys = new ArrayList<>();

        RAtomicLong atomicLong = (RAtomicLong) Proxy.newProxyInstance(
                RAtomicLong.class.getClassLoader(),
                new Class<?>[]{RAtomicLong.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "incrementAndGet" -> namespaceVersion.incrementAndGet();
                    case "get" -> namespaceVersion.get();
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
                    case "getKeys" -> {
                        getKeysCalls.incrementAndGet();
                        yield null;
                    }
                    case "toString" -> "RedissonClientProxy";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == (args != null && args.length > 0 ? args[0] : null);
                    default -> throw new UnsupportedOperationException("Unsupported RedissonClient method: " + method.getName());
                }
        );

        RedissonL2Store<String, String> store = new RedissonL2Store<>("user", client, "it");
        store.clear();

        assertEquals(1L, namespaceVersion.get(), "clear应仅通过命名空间版本递增实现逻辑清理");
        assertEquals(0, getKeysCalls.get(), "clear不应触发Redis全量key扫描");
        assertTrue(requestedAtomicKeys.contains("it:user:ns:version"));
    }
}
