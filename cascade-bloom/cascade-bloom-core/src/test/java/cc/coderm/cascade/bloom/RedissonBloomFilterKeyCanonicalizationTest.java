package cc.coderm.cascade.bloom;

import cc.coderm.cascade.bloom.impl.RedissonBloomFilter;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.BatchResult;
import org.redisson.api.RBatch;
import org.redisson.api.RBitSetAsync;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RFuture;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.Codec;
import org.redisson.client.protocol.Encoder;
import org.redisson.misc.Hash;

import java.io.IOException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    @DisplayName("RedissonBloomFilter 应按数组内容标准化 add/mightContain 的 key")
    void shouldCanonicalizeArrayKeysForAddAndContain() {
        AtomicReference<String> addedArg = new AtomicReference<>();
        AtomicReference<String> containsArg = new AtomicReference<>();

        RBloomFilter<String> delegate = createBloomFilterProxy(addedArg, containsArg);
        RedissonBloomFilter<Object> filter = new RedissonBloomFilter<>(
                delegate, mock(RedissonClient.class), "user-bloom", 1000L, 0.03D);

        filter.add(new int[]{1, 2, 3});
        filter.mightContain(new Object[]{"u-1", new long[]{7L, 8L}});

        assertThat(addedArg.get()).isEqualTo("[1,2,3]");
        assertThat(containsArg.get()).isEqualTo("[u-1,[7,8]]");
    }

    @Test
    @DisplayName("RedissonBloomFilter addAll 应标准化 key 并使用 pipeline")
    void shouldCanonicalizeKeysAndUsePipelineForAddAll() throws Exception {
        List<String> encodedValues = new ArrayList<>();
        List<Long> actualIndexes = new ArrayList<>();
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
        when(bitSet.setAsync(anyLong())).thenAnswer(invocation -> {
            actualIndexes.add(invocation.getArgument(0));
            return null;
        });
        when(batch.execute()).thenReturn((BatchResult) batchResult);

        RBloomFilter<String> delegate = createBatchAwareBloomFilterProxy(
                codec, singleAddCalls);
        RedissonBloomFilter<Object> filter = new RedissonBloomFilter<>(
                delegate, redissonClient, "user-bloom", 1000L, 0.03D);

        filter.addAll(List.of(123L, "u-2", 789));

        assertThat(encodedValues).containsExactly("123", "u-2", "789");
        assertThat(actualIndexes).containsExactlyElementsOf(computeExpectedIndexes(
                encodedValues,
                1000L,
                0.03D
        ));
        assertThat(singleAddCalls.get()).isZero();
        verify(bitSet, atLeastOnce()).setAsync(anyLong());
        verify(redissonClient).createBatch(any());
        verify(batch).execute();
    }

    @Test
    @DisplayName("RedissonBloomFilter mightContainAll 应标准化 key 并使用 pipeline 批量读取")
    void shouldCanonicalizeKeysAndUsePipelineForMightContainAll() throws Exception {
        List<String> encodedValues = new ArrayList<>();
        List<Long> actualIndexes = new ArrayList<>();
        AtomicInteger singleContainCalls = new AtomicInteger();
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
        RFuture<Boolean> completedTrueFuture = completedBooleanFuture(true);
        when(bitSet.getAsync(anyLong())).thenAnswer(invocation -> {
            actualIndexes.add(invocation.getArgument(0));
            return completedTrueFuture;
        });
        when(batch.execute()).thenReturn((BatchResult) batchResult);

        RBloomFilter<String> delegate = mock(RBloomFilter.class);
        when(delegate.getCodec()).thenReturn(codec);
        when(delegate.getName()).thenReturn("test:bloom:user-bloom");
        when(delegate.contains(any())).thenAnswer(invocation -> {
            singleContainCalls.incrementAndGet();
            return true;
        });
        RedissonBloomFilter<Object> filter = new RedissonBloomFilter<>(
                delegate, redissonClient, "user-bloom", 1000L, 0.03D);

        filter.mightContainAll(List.of(123L, "u-2", 789));

        assertThat(encodedValues).containsExactly("123", "u-2", "789");
        assertThat(actualIndexes).containsExactlyElementsOf(computeExpectedIndexes(
                encodedValues,
                1000L,
                0.03D
        ));
        assertThat(singleContainCalls.get()).isZero();
        verify(redissonClient).createBatch(any());
        verify(bitSet, atLeastOnce()).getAsync(anyLong());
        verify(batch).execute();
    }

    @Test
    @DisplayName("RedissonBloomFilter pipeline 哈希步进应与 Redisson 原生兼容（nextHash += hash2）")
    void shouldUseRedissonCompatibleHashStrideForPipeline() throws Exception {
        List<String> encodedValues = new ArrayList<>();
        List<Long> addIndexes = new ArrayList<>();
        List<Long> containIndexes = new ArrayList<>();
        Codec codec = mock(Codec.class);
        Encoder encoder = mock(Encoder.class);
        when(codec.getValueEncoder()).thenReturn(encoder);
        doAnswer(invocation -> {
            String normalized = (String) invocation.getArgument(0);
            encodedValues.add(normalized);
            return Unpooled.copiedBuffer(normalized, StandardCharsets.UTF_8);
        }).when(encoder).encode(any());

        RedissonClient redissonClient = mock(RedissonClient.class);
        RBatch addBatch = mock(RBatch.class);
        RBatch containBatch = mock(RBatch.class);
        RBitSetAsync addBitSet = mock(RBitSetAsync.class);
        RBitSetAsync containBitSet = mock(RBitSetAsync.class);
        BatchResult<?> batchResult = mock(BatchResult.class);
        when(redissonClient.createBatch(any())).thenReturn(addBatch, containBatch);
        when(addBatch.getBitSet("test:bloom:user-bloom")).thenReturn(addBitSet);
        when(containBatch.getBitSet("test:bloom:user-bloom")).thenReturn(containBitSet);
        when(addBitSet.setAsync(anyLong())).thenAnswer(invocation -> {
            addIndexes.add(invocation.getArgument(0));
            return null;
        });
        RFuture<Boolean> completedTrueFuture = completedBooleanFuture(true);
        when(containBitSet.getAsync(anyLong())).thenAnswer(invocation -> {
            containIndexes.add(invocation.getArgument(0));
            return completedTrueFuture;
        });
        when(addBatch.execute()).thenReturn((BatchResult) batchResult);
        when(containBatch.execute()).thenReturn((BatchResult) batchResult);

        RBloomFilter<String> delegate = createBatchAwareBloomFilterProxy(codec, new AtomicInteger());
        RedissonBloomFilter<Object> filter = new RedissonBloomFilter<>(
                delegate, redissonClient, "user-bloom", 1000L, 0.03D);

        filter.addAll(List.of("k1", "k2"));
        filter.mightContainAll(List.of("k1", "k2"));

        List<String> normalizedValues = List.of("k1", "k2");
        List<Long> expectedIndexes = computeExpectedIndexes(normalizedValues, 1000L, 0.03D);
        assertThat(addIndexes).containsExactlyElementsOf(expectedIndexes);
        assertThat(containIndexes).containsExactlyElementsOf(expectedIndexes);
        assertThat(encodedValues).containsAll(normalizedValues);
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

    @Test
    @DisplayName("RedissonBloomFilter addAll 原生批量 API 不兼容时应降级到 pipeline")
    void shouldFallbackToPipelineWhenNativeCollectionAddIsIncompatible() throws Exception {
        AtomicInteger singleAddCalls = new AtomicInteger();
        AtomicInteger nativeCollectionAddCalls = new AtomicInteger();
        Codec codec = mock(Codec.class);
        Encoder encoder = mock(Encoder.class);
        when(codec.getValueEncoder()).thenReturn(encoder);
        doAnswer(invocation -> Unpooled.copiedBuffer(
                (String) invocation.getArgument(0), StandardCharsets.UTF_8)).when(encoder).encode(any());

        RedissonClient redissonClient = mock(RedissonClient.class);
        RBatch batch = mock(RBatch.class);
        RBitSetAsync bitSet = mock(RBitSetAsync.class);
        BatchResult<?> batchResult = mock(BatchResult.class);
        when(redissonClient.createBatch(any())).thenReturn(batch);
        when(batch.getBitSet("test:bloom:user-bloom")).thenReturn(bitSet);
        when(bitSet.setAsync(anyLong())).thenReturn(null);
        when(batch.execute()).thenReturn((BatchResult) batchResult);

        RBloomFilter<String> delegate = createNativeCollectionAddFailureBloomFilterProxy(
                codec,
                singleAddCalls,
                nativeCollectionAddCalls,
                new UnsupportedOperationException("native addAll is not supported"));

        RedissonBloomFilter<Object> filter = new RedissonBloomFilter<>(
                delegate, redissonClient, "user-bloom", 1000L, 0.03D);

        filter.addAll(List.of(123L, "u-2", 789));

        assertThat(nativeCollectionAddCalls.get()).isEqualTo(1);
        assertThat(singleAddCalls.get()).isZero();
        verify(redissonClient).createBatch(any());
        verify(bitSet, atLeastOnce()).setAsync(anyLong());
        verify(batch).execute();
    }

    @Test
    @DisplayName("RedissonBloomFilter addAll 原生批量 API 真实运行时失败应继续抛出异常")
    void shouldRethrowWhenNativeCollectionAddFailsByBusinessRuntimeException() {
        AtomicInteger singleAddCalls = new AtomicInteger();
        AtomicInteger nativeCollectionAddCalls = new AtomicInteger();
        RedissonClient redissonClient = mock(RedissonClient.class);

        RBloomFilter<String> delegate = createNativeCollectionAddFailureBloomFilterProxy(
                mock(Codec.class),
                singleAddCalls,
                nativeCollectionAddCalls,
                new IllegalStateException("redis command failed"));

        RedissonBloomFilter<Object> filter = new RedissonBloomFilter<>(
                delegate, redissonClient, "user-bloom", 1000L, 0.03D);

        assertThatThrownBy(() -> filter.addAll(List.of(123L, "u-2", 789)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("redis command failed");
        assertThat(nativeCollectionAddCalls.get()).isEqualTo(1);
        assertThat(singleAddCalls.get()).isZero();
        verifyNoInteractions(redissonClient);
    }

    @Test
    @DisplayName("RedissonBloomFilter addAll pipeline 编码失败时应丢弃当前 batch")
    void shouldDiscardCurrentBatchWhenEncodingFailsInPipelinePath() throws Exception {
        AtomicInteger singleAddCalls = new AtomicInteger();
        AtomicInteger executeCalls = new AtomicInteger();
        AtomicInteger discardCalls = new AtomicInteger();

        Codec codec = mock(Codec.class);
        Encoder encoder = mock(Encoder.class);
        when(codec.getValueEncoder()).thenReturn(encoder);
        doAnswer(invocation -> {
            String normalized = (String) invocation.getArgument(0);
            if ("boom".equals(normalized)) {
                throw new IOException("encode failed");
            }
            return Unpooled.copiedBuffer(normalized, StandardCharsets.UTF_8);
        }).when(encoder).encode(any());

        RedissonClient redissonClient = mock(RedissonClient.class);
        RBitSetAsync bitSet = mock(RBitSetAsync.class);
        when(bitSet.setAsync(anyLong())).thenReturn(null);

        RBatch batch = createDiscardAwareBatchProxy(bitSet, executeCalls, discardCalls);
        when(redissonClient.createBatch(any())).thenReturn(batch);

        RBloomFilter<String> delegate = createBatchAwareBloomFilterProxy(codec, singleAddCalls);
        RedissonBloomFilter<Object> filter = new RedissonBloomFilter<>(
                delegate, redissonClient, "user-bloom", 1000L, 0.03D);

        assertThatThrownBy(() -> filter.addAll(List.of("ok-1", "boom", "ok-2")))
                .isInstanceOf(cc.coderm.cascade.bloom.exception.BloomFilterException.class)
                .hasMessageContaining("Failed to encode bloom filter element");

        assertThat(singleAddCalls.get()).isZero();
        assertThat(executeCalls.get()).isZero();
        assertThat(discardCalls.get()).isEqualTo(1);
        verify(redissonClient).createBatch(any());
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

    private static List<Long> computeExpectedIndexes(List<String> normalizedValues,
                                                     long expectedInsertions,
                                                     double falseProbability) {
        long bitSize = calculateBitSize(expectedInsertions, falseProbability);
        int hashIterations = calculateHashIterations(expectedInsertions, bitSize);
        List<Long> indexes = new ArrayList<>(normalizedValues.size() * hashIterations);
        for (String value : normalizedValues) {
            long[] hash = hash128(value);
            long nextHash = hash[0];
            long hash2 = hash[1];
            for (int i = 0; i < hashIterations; i++) {
                indexes.add((nextHash & Long.MAX_VALUE) % bitSize);
                nextHash += hash2;
            }
        }
        return indexes;
    }

    private static long[] hash128(String value) {
        ByteBuf encoded = Unpooled.copiedBuffer(value, StandardCharsets.UTF_8);
        try {
            return Hash.hash128(encoded);
        } finally {
            encoded.release();
        }
    }

    private static long calculateBitSize(long expectedInsertions, double falseProbability) {
        double logOfTwo = Math.log(2D);
        return (long) (-expectedInsertions * Math.log(falseProbability) / (logOfTwo * logOfTwo));
    }

    private static int calculateHashIterations(long expectedInsertions, long bitSize) {
        return Math.max(1, (int) Math.round((double) bitSize / expectedInsertions * Math.log(2D)));
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

    @SuppressWarnings("unchecked")
    private static RBloomFilter<String> createNativeCollectionAddFailureBloomFilterProxy(
            Codec codec,
            AtomicInteger singleAddCalls,
            AtomicInteger nativeCollectionAddCalls,
            RuntimeException failure) {
        InvocationHandler handler = (proxy, method, args) -> {
            String name = method.getName();
            if ("add".equals(name) && args != null && args.length == 1 && args[0] instanceof Collection<?>) {
                nativeCollectionAddCalls.incrementAndGet();
                throw failure;
            }
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
                new Class<?>[]{RBloomFilter.class, NativeCollectionAddCapable.class},
                handler
        );
    }

    public interface NativeCollectionAddCapable {
        long add(Collection<String> values);
    }

    @SuppressWarnings("unchecked")
    private static RBatch createDiscardAwareBatchProxy(RBitSetAsync bitSet,
                                                       AtomicInteger executeCalls,
                                                       AtomicInteger discardCalls) {
        InvocationHandler handler = (proxy, method, args) -> {
            String name = method.getName();
            if ("getBitSet".equals(name)) {
                return bitSet;
            }
            if ("execute".equals(name)) {
                executeCalls.incrementAndGet();
                return mock(BatchResult.class);
            }
            if ("discard".equals(name)) {
                discardCalls.incrementAndGet();
                return null;
            }
            if ("toString".equals(name)) {
                return "RBatchProxy";
            }
            if ("hashCode".equals(name)) {
                return System.identityHashCode(proxy);
            }
            if ("equals".equals(name)) {
                return proxy == args[0];
            }
            return defaultValue(method);
        };
        return (RBatch) Proxy.newProxyInstance(
                RBatch.class.getClassLoader(),
                new Class<?>[]{RBatch.class},
                handler
        );
    }

    @SuppressWarnings("unchecked")
    private static RFuture<Boolean> completedBooleanFuture(boolean value) {
        RFuture<Boolean> future = mock(RFuture.class);
        when(future.join()).thenReturn(value);
        when(future.getNow()).thenReturn(value);
        return future;
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
