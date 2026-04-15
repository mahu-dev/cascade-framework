package cc.coderm.cascade.bloom;

import cc.coderm.cascade.bloom.impl.RedissonBloomFilter;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.BatchResult;
import org.redisson.api.RBatch;
import org.redisson.api.RBitSetAsync;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.Codec;
import org.redisson.client.protocol.Encoder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RedissonBloomFilterKeyCanonicalizationTest {

    @Test
    @DisplayName("RedissonBloomFilter 应按字符串标准化 add/mightContain 的 key")
    void shouldCanonicalizeKeysForAddAndContain() {
        AtomicReference<String> addedArg = new AtomicReference<>();
        AtomicReference<String> containsArg = new AtomicReference<>();

        RBloomFilter<String> delegate = createBloomFilterProxy(addedArg, containsArg);
        RedissonBloomFilter<Object> filter = new RedissonBloomFilter<>(
                delegate, mock(RedissonClient.class), "user-bloom", 1000L, 0.03D);

        filter.add(123L);
        filter.mightContain(456L);

        assertThat(addedArg.get()).isEqualTo("123");
        assertThat(containsArg.get()).isEqualTo("456");
    }

    @Test
    @DisplayName("RedissonBloomFilter addAll 应标准化 key 并使用 pipeline")
    void shouldCanonicalizeKeysAndUsePipelineForAddAll() throws Exception {
        List<String> encodedValues = new ArrayList<>();
        AtomicInteger singleAddCalls = new AtomicInteger();
        Codec codec = mock(Codec.class);
        Encoder encoder = mock(Encoder.class);
        when(codec.getValueEncoder()).thenReturn(encoder);
        doAnswer(invocation -> {
            String normalized = (String) invocation.getArgument(0);
            encodedValues.add(normalized);
            return Unpooled.copiedBuffer(normalized, StandardCharsets.UTF_8);
        }).when(encoder).encode(any());

        RedissonClient redissonClient = mock(RedissonClient.class);
        RBatch batch = mock(RBatch.class);
        RBitSetAsync bitSet = mock(RBitSetAsync.class);
        BatchResult<?> batchResult = mock(BatchResult.class);
        when(redissonClient.createBatch(any())).thenReturn(batch);
        when(batch.getBitSet("test:bloom:user-bloom")).thenReturn(bitSet);
        when(bitSet.setAsync(anyLong())).thenReturn(null);
        when(batch.execute()).thenReturn((BatchResult) batchResult);

        RBloomFilter<String> delegate = createBatchAwareBloomFilterProxy(
                codec, singleAddCalls);
        RedissonBloomFilter<Object> filter = new RedissonBloomFilter<>(
                delegate, redissonClient, "user-bloom", 1000L, 0.03D);

        filter.addAll(List.of(123L, "u-2", 789));

        assertThat(encodedValues).containsExactly("123", "u-2", "789");
        assertThat(singleAddCalls.get()).isZero();
        verify(bitSet, atLeastOnce()).setAsync(anyLong());
        verify(redissonClient).createBatch(any());
        verify(batch).execute();
    }

    @Test
    @DisplayName("RedissonBloomFilter addAll 在新版 Redisson 上应优先使用原生 add(Collection)")
    void shouldPreferNativeCollectionAddWhenAvailable() {
        AtomicInteger singleAddCalls = new AtomicInteger();
        AtomicInteger nativeCollectionAddCalls = new AtomicInteger();
        AtomicReference<Collection<String>> nativeCollectionArgs = new AtomicReference<>();

        RBloomFilter<String> delegate = createNativeCollectionAddCapableBloomFilterProxy(
                singleAddCalls, nativeCollectionAddCalls, nativeCollectionArgs);
        RedissonClient redissonClient = mock(RedissonClient.class);

        RedissonBloomFilter<Object> filter = new RedissonBloomFilter<>(
                delegate, redissonClient, "user-bloom", 1000L, 0.03D);
        filter.addAll(List.of(123L, "u-2", 789));

        assertThat(nativeCollectionAddCalls.get()).isEqualTo(1);
        assertThat(singleAddCalls.get()).isZero();
        assertThat(nativeCollectionArgs.get()).containsExactly("123", "u-2", "789");
        verifyNoInteractions(redissonClient);
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
            Codec codec,
            AtomicInteger singleAddCalls) {
        InvocationHandler handler = (proxy, method, args) -> {
            String name = method.getName();
            if ("add".equals(name)) {
                singleAddCalls.incrementAndGet();
                return true;
            }
            if ("getCodec".equals(name)) {
                return codec;
            }
            if ("getName".equals(name)) {
                return "test:bloom:user-bloom";
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

    @SuppressWarnings("unchecked")
    private static RBloomFilter<String> createNativeCollectionAddCapableBloomFilterProxy(
            AtomicInteger singleAddCalls,
            AtomicInteger nativeCollectionAddCalls,
            AtomicReference<Collection<String>> nativeCollectionArgs) {
        InvocationHandler handler = (proxy, method, args) -> {
            String name = method.getName();
            if ("add".equals(name) && args != null && args.length == 1 && args[0] instanceof Collection<?>) {
                nativeCollectionAddCalls.incrementAndGet();
                Collection<?> raw = (Collection<?>) args[0];
                List<String> snapshot = new ArrayList<>(raw.size());
                for (Object item : raw) {
                    snapshot.add((String) item);
                }
                nativeCollectionArgs.set(snapshot);
                return (long) raw.size();
            }
            if ("add".equals(name)) {
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
                new Class<?>[]{RBloomFilter.class, NativeCollectionAddCapable.class},
                handler
        );
    }

    public interface NativeCollectionAddCapable {
        long add(Collection<String> values);
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
