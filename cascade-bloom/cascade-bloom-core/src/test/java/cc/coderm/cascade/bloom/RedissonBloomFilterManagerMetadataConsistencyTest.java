package cc.coderm.cascade.bloom;

import cc.coderm.cascade.bloom.config.BloomFilterProperties;
import cc.coderm.cascade.bloom.core.CascadeBloomFilter;
import cc.coderm.cascade.bloom.exception.BloomFilterException;
import cc.coderm.cascade.bloom.impl.RedissonBloomFilterManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RedissonBloomFilterManagerMetadataConsistencyTest {

    @Test
    @DisplayName("请求配置与 Redis 已有配置不一致时应快速失败")
    void shouldFailFastWhenRequestedConfigDoesNotMatchExistingRedisConfig() {
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

        assertThatThrownBy(() -> manager.getOrCreate("user-bloom", 1_000_000L, 0.03D))
                .isInstanceOf(BloomFilterException.class)
                .hasMessageContaining("configuration mismatch")
                .hasMessageContaining("source=redis");
        assertThat(tryInitCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("请求配置与 Redis 已有配置一致时应复用已存在过滤器")
    void shouldReuseExistingFilterWhenRequestedConfigMatchesRedisConfig() {
        long actualExpectedInsertions = 1_000_000L;
        double actualFalseProbability = 0.03D;

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

        CascadeBloomFilter<Object> filter = manager.getOrCreate("user-bloom", actualExpectedInsertions, actualFalseProbability);

        assertThat(filter.getExpectedInsertions()).isEqualTo(actualExpectedInsertions);
        assertThat(filter.getFalseProbability()).isEqualTo(actualFalseProbability);
        assertThat(tryInitCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("getFilter 按名称获取时应复用 Redis 已有配置而非默认参数")
    void shouldReuseExistingRedisConfigWhenGetByName() {
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
        properties.setDefaultExpectedInsertions(100_000L);
        properties.setDefaultFalseProbability(0.03D);
        RedissonBloomFilterManager manager = new RedissonBloomFilterManager(redissonClient, properties);

        CascadeBloomFilter<Object> filter = manager.getFilter("user-bloom");

        assertThat(filter.getExpectedInsertions()).isEqualTo(actualExpectedInsertions);
        assertThat(filter.getFalseProbability()).isEqualTo(actualFalseProbability);
        assertThat(tryInitCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("getFilter 在预定义过滤器重建时应优先使用 filters[] 配置")
    void shouldUsePredefinedFilterConfigWhenRecreatingByName() {
        AtomicLong requestedExpectedInsertions = new AtomicLong(-1L);
        AtomicReference<Double> requestedFalseProbability = new AtomicReference<>(-1D);
        RBloomFilter<Object> bloomFilter = createTryInitCaptureBloomFilterProxy(
                requestedExpectedInsertions,
                requestedFalseProbability,
                true
        );
        RedissonClient redissonClient = createRedissonClientProxy(bloomFilter);

        BloomFilterProperties.BloomFilterDefinition definition = new BloomFilterProperties.BloomFilterDefinition();
        definition.setName("user-bloom");
        definition.setExpectedInsertions(5_000_000L);
        definition.setFalseProbability(0.01D);

        BloomFilterProperties properties = new BloomFilterProperties();
        properties.setKeyPrefix("test:bloom:");
        properties.setDefaultExpectedInsertions(100_000L);
        properties.setDefaultFalseProbability(0.03D);
        properties.setFilters(List.of(definition));

        RedissonBloomFilterManager manager = new RedissonBloomFilterManager(redissonClient, properties);

        CascadeBloomFilter<Object> filter = manager.getFilter("user-bloom");

        assertThat(filter.getExpectedInsertions()).isEqualTo(5_000_000L);
        assertThat(filter.getFalseProbability()).isEqualTo(0.01D);
        assertThat(requestedExpectedInsertions.get()).isEqualTo(5_000_000L);
        assertThat(requestedFalseProbability.get()).isEqualTo(0.01D);
    }

    @Test
    @DisplayName("getFilter 在预定义过滤器未指定参数时应回落全局默认配置")
    void shouldFallbackToGlobalDefaultWhenPredefinedConfigMissingParams() {
        AtomicLong requestedExpectedInsertions = new AtomicLong(-1L);
        AtomicReference<Double> requestedFalseProbability = new AtomicReference<>(-1D);
        RBloomFilter<Object> bloomFilter = createTryInitCaptureBloomFilterProxy(
                requestedExpectedInsertions,
                requestedFalseProbability,
                true
        );
        RedissonClient redissonClient = createRedissonClientProxy(bloomFilter);

        BloomFilterProperties.BloomFilterDefinition definition = new BloomFilterProperties.BloomFilterDefinition();
        definition.setName("user-bloom");
        definition.setExpectedInsertions(null);
        definition.setFalseProbability(null);

        BloomFilterProperties properties = new BloomFilterProperties();
        properties.setKeyPrefix("test:bloom:");
        properties.setDefaultExpectedInsertions(200_000L);
        properties.setDefaultFalseProbability(0.02D);
        properties.setFilters(List.of(definition));

        RedissonBloomFilterManager manager = new RedissonBloomFilterManager(redissonClient, properties);
        CascadeBloomFilter<Object> filter = manager.getFilter("user-bloom");

        assertThat(filter.getExpectedInsertions()).isEqualTo(200_000L);
        assertThat(filter.getFalseProbability()).isEqualTo(0.02D);
        assertThat(requestedExpectedInsertions.get()).isEqualTo(200_000L);
        assertThat(requestedFalseProbability.get()).isEqualTo(0.02D);
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
        Set<String> registry = ConcurrentHashMap.newKeySet();
        RSet<String> registrySet = createRegistrySetProxy(registry);
        InvocationHandler handler = (proxy, method, args) -> {
            String name = method.getName();
            if ("getBloomFilter".equals(name)) {
                return bloomFilter;
            }
            if ("getSet".equals(name)) {
                return registrySet;
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

    @SuppressWarnings("unchecked")
    private static RBloomFilter<Object> createTryInitCaptureBloomFilterProxy(
            AtomicLong requestedExpectedInsertions,
            AtomicReference<Double> requestedFalseProbability,
            boolean tryInitResult) {
        InvocationHandler handler = (proxy, method, args) -> {
            String name = method.getName();
            if ("tryInit".equals(name)) {
                requestedExpectedInsertions.set((Long) args[0]);
                requestedFalseProbability.set((Double) args[1]);
                return tryInitResult;
            }
            if ("getExpectedInsertions".equals(name)) {
                return requestedExpectedInsertions.get();
            }
            if ("getFalseProbability".equals(name)) {
                return requestedFalseProbability.get();
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
    private static RSet<String> createRegistrySetProxy(Set<String> backingSet) {
        InvocationHandler handler = (proxy, method, args) -> {
            String name = method.getName();
            if ("add".equals(name)) {
                return backingSet.add((String) args[0]);
            }
            if ("remove".equals(name)) {
                return backingSet.remove((String) args[0]);
            }
            if ("contains".equals(name)) {
                return backingSet.contains((String) args[0]);
            }
            if ("readAll".equals(name)) {
                return new HashSet<>(backingSet);
            }
            if ("toString".equals(name)) {
                return "RSetProxy";
            }
            if ("hashCode".equals(name)) {
                return System.identityHashCode(proxy);
            }
            if ("equals".equals(name)) {
                return proxy == args[0];
            }
            return defaultValue(method);
        };
        return (RSet<String>) Proxy.newProxyInstance(
                RSet.class.getClassLoader(),
                new Class<?>[]{RSet.class},
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
