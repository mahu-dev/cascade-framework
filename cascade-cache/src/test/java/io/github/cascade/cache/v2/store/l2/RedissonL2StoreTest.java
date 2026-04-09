package io.github.cascade.cache.v2.store.l2;

import io.github.cascade.cache.v2.support.ObjectMapperHolder;
import io.github.cascade.cache.v2.store.model.CacheRecord;
import org.junit.jupiter.api.Test;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RBuckets;
import org.redisson.api.RKeys;
import org.redisson.api.RedissonClient;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedissonL2StoreTest {

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

        RedissonL2Store<String, String> first = new RedissonL2Store<>("user-a", client, "it");
        RedissonL2Store<String, String> second = new RedissonL2Store<>("user-b", client, "it");

        Object firstMapper = readPrivateField(first, "objectMapper");
        Object secondMapper = readPrivateField(second, "objectMapper");

        assertSame(ObjectMapperHolder.getInstance(), firstMapper);
        assertSame(firstMapper, secondMapper);
    }

    @Test
    void shouldUseRedisMGetWhenBatchReading() {
        AtomicLong namespaceVersion = new AtomicLong(1L);
        AtomicInteger getBucketCalls = new AtomicInteger(0);
        AtomicInteger getBucketsCalls = new AtomicInteger(0);
        List<String> requestedKeys = new ArrayList<>();
        long now = System.currentTimeMillis();

        RBuckets buckets = (RBuckets) Proxy.newProxyInstance(
                RBuckets.class.getClassLoader(),
                new Class<?>[]{RBuckets.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "get" -> {
                        String[] redisKeys = (String[]) args[0];
                        requestedKeys.addAll(List.of(redisKeys));
                        Map<String, CacheRecord<String>> values = new LinkedHashMap<>();
                        if (redisKeys.length > 0) {
                            values.put(redisKeys[0], new CacheRecord<>("v1", 1L, now, now + 60_000, now + 60_000, "node"));
                        }
                        if (redisKeys.length > 1) {
                            values.put(redisKeys[1], new CacheRecord<>("v2", 2L, now, now + 60_000, now + 60_000, "node"));
                        }
                        yield values;
                    }
                    case "toString" -> "RBucketsProxy";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == (args != null && args.length > 0 ? args[0] : null);
                    default -> throw new UnsupportedOperationException("Unsupported RBuckets method: " + method.getName());
                }
        );

        RAtomicLong atomicLong = (RAtomicLong) Proxy.newProxyInstance(
                RAtomicLong.class.getClassLoader(),
                new Class<?>[]{RAtomicLong.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "get" -> namespaceVersion.get();
                    case "compareAndSet" -> namespaceVersion.compareAndSet((Long) args[0], (Long) args[1]);
                    case "incrementAndGet" -> namespaceVersion.incrementAndGet();
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
                    case "getAtomicLong" -> atomicLong;
                    case "getBucket" -> {
                        getBucketCalls.incrementAndGet();
                        throw new AssertionError("batch读取不应走单 key getBucket");
                    }
                    case "getBuckets" -> {
                        getBucketsCalls.incrementAndGet();
                        yield buckets;
                    }
                    case "toString" -> "RedissonClientProxy";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == (args != null && args.length > 0 ? args[0] : null);
                    default -> throw new UnsupportedOperationException("Unsupported RedissonClient method: " + method.getName());
                }
        );

        RedissonL2Store<String, String> store = new RedissonL2Store<>("user", client, "it");
        Map<String, CacheRecord<String>> records = store.getAll(List.of("k1", "k2", "k3"));

        assertEquals(1, getBucketsCalls.get(), "批量读取应只调用一次RBuckets#get");
        assertEquals(0, getBucketCalls.get(), "批量读取不应退化为逐 key getBucket");
        assertEquals(3, requestedKeys.size(), "MGET应包含全部请求key");
        assertEquals("v1", records.get("k1").getValue());
        assertEquals("v2", records.get("k2").getValue());
        assertTrue(!records.containsKey("k3"), "空值key不应进入结果");
    }

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

    private static Object readPrivateField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }
}
