package cc.coderm.cascade.bloom;

import cc.coderm.cascade.bloom.impl.RedissonBloomFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBloomFilter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RedissonBloomFilterKeyCanonicalizationTest {

    @Test
    @DisplayName("RedissonBloomFilter 应按字符串标准化 add/mightContain 的 key")
    void shouldCanonicalizeKeysForAddAndContain() {
        AtomicReference<String> addedArg = new AtomicReference<>();
        AtomicReference<String> containsArg = new AtomicReference<>();

        RBloomFilter<String> delegate = createBloomFilterProxy(addedArg, containsArg);
        RedissonBloomFilter<Object> filter = new RedissonBloomFilter<>(delegate, "user-bloom", 1000L, 0.03D);

        filter.add(123L);
        filter.mightContain(456L);

        assertThat(addedArg.get()).isEqualTo("123");
        assertThat(containsArg.get()).isEqualTo("456");
    }

    @Test
    @DisplayName("RedissonBloomFilter addAll 应标准化 key 并走批量 add(Collection)")
    void shouldCanonicalizeKeysAndUseBatchAddForAddAll() {
        AtomicReference<Collection<String>> batchedAddedArgs = new AtomicReference<>();
        AtomicInteger singleAddCalls = new AtomicInteger();
        AtomicInteger batchAddCalls = new AtomicInteger();

        RBloomFilter<String> delegate = createBatchAwareBloomFilterProxy(
                batchedAddedArgs, singleAddCalls, batchAddCalls);
        RedissonBloomFilter<Object> filter = new RedissonBloomFilter<>(delegate, "user-bloom", 1000L, 0.03D);

        filter.addAll(List.of(123L, "u-2", 789));

        assertThat(batchAddCalls.get()).isEqualTo(1);
        assertThat(singleAddCalls.get()).isZero();
        assertThat(batchedAddedArgs.get()).containsExactly("123", "u-2", "789");
    }

    @SuppressWarnings("unchecked")
    private static RBloomFilter<String> createBloomFilterProxy(AtomicReference<String> addedArg,
                                                                AtomicReference<String> containsArg) {
        InvocationHandler handler = (proxy, method, args) -> {
            String name = method.getName();
            if ("add".equals(name)) {
                addedArg.set((String) args[0]);
                return true;
            }
            if ("contains".equals(name)) {
                containsArg.set((String) args[0]);
                return true;
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
        return (RBloomFilter<String>) Proxy.newProxyInstance(
                RBloomFilter.class.getClassLoader(),
                new Class<?>[]{RBloomFilter.class},
                handler
        );
    }

    @SuppressWarnings("unchecked")
    private static RBloomFilter<String> createBatchAwareBloomFilterProxy(
            AtomicReference<Collection<String>> batchedAddedArgs,
            AtomicInteger singleAddCalls,
            AtomicInteger batchAddCalls) {
        InvocationHandler handler = (proxy, method, args) -> {
            String name = method.getName();
            if ("add".equals(name)) {
                Object arg = args[0];
                if (arg instanceof Collection<?>) {
                    batchAddCalls.incrementAndGet();
                    Collection<?> rawCollection = (Collection<?>) arg;
                    Collection<String> snapshot = new ArrayList<>(rawCollection.size());
                    for (Object item : rawCollection) {
                        snapshot.add((String) item);
                    }
                    batchedAddedArgs.set(snapshot);
                    return (long) rawCollection.size();
                }
                singleAddCalls.incrementAndGet();
                return true;
            }
            if ("contains".equals(name)) {
                return true;
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
        return (RBloomFilter<String>) Proxy.newProxyInstance(
                RBloomFilter.class.getClassLoader(),
                new Class<?>[]{RBloomFilter.class},
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
