package cc.coderm.cascade.bloom;

import cc.coderm.cascade.bloom.config.BloomFilterProperties;
import cc.coderm.cascade.bloom.core.CascadeBloomFilter;
import cc.coderm.cascade.bloom.impl.RedissonBloomFilterManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class RedissonBloomFilterManagerRecursiveUpdateTest {

    @Test
    @DisplayName("getFilter 不应触发 ConcurrentHashMap 递归更新异常")
    void getFilterShouldNotTriggerRecursiveUpdate() {
        BloomFilterProperties properties = new BloomFilterProperties();
        properties.setKeyPrefix("test:bloom:");
        properties.setDefaultExpectedInsertions(10_000L);
        properties.setDefaultFalseProbability(0.03D);

        AtomicInteger getBloomFilterCalls = new AtomicInteger();
        RBloomFilter<Object> bloomFilter = createBloomFilterProxy();
        RedissonClient redissonClient = createRedissonClientProxy(bloomFilter, getBloomFilterCalls);

        RedissonBloomFilterManager manager = new RedissonBloomFilterManager(redissonClient, properties);

        assertThatCode(() -> manager.getFilter("user-bloom"))
                .doesNotThrowAnyException();

        CascadeBloomFilter<Object> filter1 = manager.getFilter("user-bloom");
        CascadeBloomFilter<Object> filter2 = manager.getFilter("user-bloom");

        assertThat(filter1).isSameAs(filter2);
        assertThat(getBloomFilterCalls.get()).isEqualTo(1);
    }

    @SuppressWarnings("unchecked")
    private static RBloomFilter<Object> createBloomFilterProxy() {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "tryInit" -> true;
            case "contains" -> false;
            case "add" -> true;
            case "count" -> 0L;
            case "isExists" -> true;
            case "delete" -> true;
            case "toString" -> "RBloomFilterProxy";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> defaultValue(method);
        };
        return (RBloomFilter<Object>) Proxy.newProxyInstance(
                RBloomFilter.class.getClassLoader(),
                new Class<?>[]{RBloomFilter.class},
                handler
        );
    }

    @SuppressWarnings("unchecked")
    private static RedissonClient createRedissonClientProxy(RBloomFilter<Object> bloomFilter,
                                                            AtomicInteger getBloomFilterCalls) {
        InvocationHandler handler = (proxy, method, args) -> {
            if ("getBloomFilter".equals(method.getName())) {
                getBloomFilterCalls.incrementAndGet();
                return bloomFilter;
            }
            if ("toString".equals(method.getName())) {
                return "RedissonClientProxy";
            }
            if ("hashCode".equals(method.getName())) {
                return System.identityHashCode(proxy);
            }
            if ("equals".equals(method.getName())) {
                return proxy == args[0];
            }
            return defaultValue(method);
        };
        return (RedissonClient) Proxy.newProxyInstance(
                RedissonClient.class.getClassLoader(),
                new Class<?>[]{RedissonClient.class},
                handler
        );
    }

    private static Object defaultValue(Method method) {
        Class<?> returnType = method.getReturnType();
        if (!returnType.isPrimitive()) {
            return null;
        }
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == byte.class) {
            return (byte) 0;
        }
        if (returnType == short.class) {
            return (short) 0;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == float.class) {
            return 0F;
        }
        if (returnType == double.class) {
            return 0D;
        }
        if (returnType == char.class) {
            return '\0';
        }
        throw new IllegalStateException("Unsupported primitive return type: " + returnType.getName());
    }
}
