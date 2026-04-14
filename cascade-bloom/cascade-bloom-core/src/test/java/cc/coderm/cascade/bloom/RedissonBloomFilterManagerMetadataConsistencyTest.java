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

class RedissonBloomFilterManagerMetadataConsistencyTest {

    @Test
    @DisplayName("复用已有 BloomFilter 时应以 Redis 真实配置填充元数据")
    void shouldUseActualRedisConfigWhenFilterAlreadyExists() {
        long actualExpectedInsertions = 7_000_000L;
        double actualFalseProbability = 0.007D;

        AtomicInteger tryInitCalls = new AtomicInteger();
        RBloomFilter<Object> bloomFilter = createBloomFilterProxy(
                false,
                actualExpectedInsertions,
                actualFalseProbability,
                tryInitCalls
        );
        RedissonClient redissonClient = createRedissonClientProxy(bloomFilter);

        BloomFilterProperties properties = new BloomFilterProperties();
        properties.setKeyPrefix("test:bloom:");
        RedissonBloomFilterManager manager = new RedissonBloomFilterManager(redissonClient, properties);

        CascadeBloomFilter<Object> filter = manager.getOrCreate("user-bloom", 1_000_000L, 0.03D);

        assertThat(filter.getExpectedInsertions()).isEqualTo(actualExpectedInsertions);
        assertThat(filter.getFalseProbability()).isEqualTo(actualFalseProbability);
        assertThat(tryInitCalls.get()).isEqualTo(1);
    }

    @SuppressWarnings("unchecked")
    private static RBloomFilter<Object> createBloomFilterProxy(boolean tryInitResult,
                                                               long expectedInsertions,
                                                               double falseProbability,
                                                               AtomicInteger tryInitCalls) {
        InvocationHandler handler = (proxy, method, args) -> {
            String name = method.getName();
            if ("tryInit".equals(name)) {
                tryInitCalls.incrementAndGet();
                return tryInitResult;
            }
            if ("getExpectedInsertions".equals(name)) {
                return expectedInsertions;
            }
            if ("getFalseProbability".equals(name)) {
                return falseProbability;
            }
            if ("add".equals(name) || "contains".equals(name)) {
                return false;
            }
            if ("count".equals(name)) {
                return 0L;
            }
            if ("isExists".equals(name)) {
                return true;
            }
            if ("delete".equals(name)) {
                return true;
            }
            if ("toString".equals(name)) {
                return "RBloomFilterProxy";
            }
            if ("hashCode".equals(name)) {
                return System.identityHashCode(proxy);
            }
            if ("equals".equals(name)) {
                return proxy == args[0];
            }
            return defaultValue(method);
        };
        return (RBloomFilter<Object>) Proxy.newProxyInstance(
                RBloomFilter.class.getClassLoader(),
                new Class<?>[]{RBloomFilter.class},
                handler
        );
    }

    @SuppressWarnings("unchecked")
    private static RedissonClient createRedissonClientProxy(RBloomFilter<Object> bloomFilter) {
        InvocationHandler handler = (proxy, method, args) -> {
            String name = method.getName();
            if ("getBloomFilter".equals(name)) {
                return bloomFilter;
            }
            if ("toString".equals(name)) {
                return "RedissonClientProxy";
            }
            if ("hashCode".equals(name)) {
                return System.identityHashCode(proxy);
            }
            if ("equals".equals(name)) {
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
