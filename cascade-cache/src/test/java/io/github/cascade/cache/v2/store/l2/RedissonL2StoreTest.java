package io.github.cascade.cache.v2.store.l2;

import org.junit.jupiter.api.Test;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RKeys;
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
    void clearShouldBumpNamespaceVersionAndReclaimPreviousNamespaceData() {
        AtomicLong namespaceVersion = new AtomicLong(0L);
        AtomicInteger getKeysCalls = new AtomicInteger(0);
        List<String> unlinkedPatterns = new ArrayList<>();
        List<String> deletedPatterns = new ArrayList<>();
        List<String> requestedAtomicKeys = new ArrayList<>();

        RKeys keys = (RKeys) Proxy.newProxyInstance(
                RKeys.class.getClassLoader(),
                new Class<?>[]{RKeys.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "unlinkByPattern" -> {
                        unlinkedPatterns.add((String) args[0]);
                        yield 0L;
                    }
                    case "deleteByPattern" -> {
                        deletedPatterns.add((String) args[0]);
                        yield 0L;
                    }
                    case "toString" -> "RKeysProxy";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == (args != null && args.length > 0 ? args[0] : null);
                    default -> throw new UnsupportedOperationException("Unsupported RKeys method: " + method.getName());
                }
        );

        RAtomicLong atomicLong = (RAtomicLong) Proxy.newProxyInstance(
                RAtomicLong.class.getClassLoader(),
                new Class<?>[]{RAtomicLong.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "incrementAndGet" -> namespaceVersion.incrementAndGet();
                    case "get" -> namespaceVersion.get();
                    case "compareAndSet" -> namespaceVersion.compareAndSet((Long) args[0], (Long) args[1]);
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
                        yield keys;
                    }
                    case "toString" -> "RedissonClientProxy";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == (args != null && args.length > 0 ? args[0] : null);
                    default -> throw new UnsupportedOperationException("Unsupported RedissonClient method: " + method.getName());
                }
        );

        RedissonL2Store<String, String> store = new RedissonL2Store<>("user", client, "it");
        store.clear();

        assertEquals(2L, namespaceVersion.get(), "clear应先初始化命名空间并递增版本");
        assertEquals(1, getKeysCalls.get(), "clear应触发旧命名空间数据回收");
        assertTrue(unlinkedPatterns.isEmpty(), "当前Redisson API不支持unlink时不应调用");
        assertEquals(List.of("it:user:ns:1:data:*"), deletedPatterns, "应回收上一个命名空间的数据");
        assertTrue(requestedAtomicKeys.contains("it:user:ns:version"));
    }
}
