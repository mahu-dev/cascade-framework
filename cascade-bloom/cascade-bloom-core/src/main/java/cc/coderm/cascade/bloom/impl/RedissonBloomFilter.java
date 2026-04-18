package cc.coderm.cascade.bloom.impl;

import cc.coderm.cascade.bloom.core.CascadeBloomFilter;
import cc.coderm.cascade.bloom.exception.BloomFilterException;
import cc.coderm.cascade.bloom.util.BloomFilterKeyUtil;
import io.netty.buffer.ByteBuf;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.BatchOptions;
import org.redisson.api.RBatch;
import org.redisson.api.RBitSetAsync;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.redisson.misc.Hash;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 基于 Redisson {@link RBloomFilter} 的布隆过滤器实现
 * <p>
 * <strong>类型安全说明</strong>：
 * 为确保类型安全和避免序列化问题，内部统一使用 {@code RBloomFilter<String>}
 * 作为底层存储。虽然接口支持泛型 {@code <T>}，但所有元素都会被标准化为字符串存储。
 * <ul>
 *   <li>使用 {@link cc.coderm.cascade.bloom.util.BloomFilterKeyUtil#toKey(Object)} 统一处理 key 转换</li>
 *   <li>避免直接使用 {@code Long}、{@code Integer} 等类型，可能导致序列化不一致</li>
 *   <li>自定义类型需确保 {@code toString()} 方法返回唯一且稳定的字符串表示</li>
 * </ul>
 *
 * @param <T> 元素类型（推荐使用 {@code String}，实际存储时统一转换为 String）
 * @author lionel lionelk@163.com
 * =============================
 * Date: 2025-01-10
 * Time: 16:00:00
 * =============================
 */
@Slf4j
public class RedissonBloomFilter<T> implements CascadeBloomFilter<T> {

    private static final double LOG_OF_TWO = Math.log(2D);
    private static final int MAX_PIPELINE_BIT_OPERATIONS = 50_000;
    private final RBloomFilter<String> stringBloomFilter;
    private final RedissonClient redissonClient;
    private final Method nativeBulkAddMethod;
    private volatile boolean nativeBulkAddUnavailable;
    private final String name;
    private final long expectedInsertions;
    private final double falseProbability;

    public RedissonBloomFilter(RBloomFilter<String> stringBloomFilter,
                               RedissonClient redissonClient,
                               String name,
                               long expectedInsertions,
                               double falseProbability) {
        this.stringBloomFilter = Objects.requireNonNull(stringBloomFilter, "stringBloomFilter must not be null");
        this.redissonClient = Objects.requireNonNull(redissonClient, "redissonClient must not be null");
        this.nativeBulkAddMethod = resolveNativeBulkAddMethod(stringBloomFilter.getClass());
        this.name = name;
        this.expectedInsertions = expectedInsertions;
        this.falseProbability = falseProbability;
    }

    /**
     * 添加元素到布隆过滤器
     * <p>
     * 将泛型值标准化为字符串后添加到底层 Redisson 布隆过滤器中。
     * 使用 BloomFilterKeyUtil.toKey() 确保所有类型的值都能正确转换为字符串存储。
     *
     * @param value 待添加的元素，会被标准化为字符串后存储
     * @return {@code true} 表示该元素是首次添加，{@code false} 表示元素已存在
     */
    @Override
    public boolean add(T value) {
        // 将泛型值标准化为字符串
        String normalized = BloomFilterKeyUtil.toKey(value);
        boolean added = stringBloomFilter.add(normalized);
        if (log.isDebugEnabled()) {
            log.debug("[cascade-bloom] add to [{}]: value={}, normalizedKey={}, firstAdd={}", name, value, normalized, added);
        }
        return added;
    }

    /**
     * 批量添加元素到布隆过滤器
     * <p>
     * 基于 Redisson pipeline 批量写入，避免每个元素一次 Redis 往返。
     *
     * @param values 待添加的元素集合，不能为 null
     * @throws NullPointerException 当 values 为 null 时抛出
     */
    @Override
    public void addAll(Collection<T> values) {
        Objects.requireNonNull(values, "BloomFilter elements collection must not be null");
        if (values.isEmpty()) {
            return;
        }

        Collection<String> normalizedValues = new ArrayList<>(values.size());
        for (T value : values) {
            normalizedValues.add(BloomFilterKeyUtil.toKey(value));
        }

        if (normalizedValues.size() == 1) {
            stringBloomFilter.add(normalizedValues.iterator().next());
            return;
        }

        Long firstAddCount = tryAddAllByNativeApi(normalizedValues);
        if (firstAddCount != null) {
            if (log.isDebugEnabled()) {
                log.debug("[cascade-bloom] addAll to [{}] by native RBloomFilter.add(Collection): valueCount={}, firstAddCount={}",
                        name, normalizedValues.size(), firstAddCount);
            }
            return;
        }

        long bitSize = calculateBitSize(expectedInsertions, falseProbability);
        int hashIterations = calculateHashIterations(expectedInsertions, bitSize);
        if (bitSize <= 0 || hashIterations <= 0) {
            throw new BloomFilterException("Bloom filter config is invalid for addAll. name=" + name
                    + ", expectedInsertions=" + expectedInsertions
                    + ", falseProbability=" + falseProbability);
        }

        long bitOperations = addAllByPipeline(normalizedValues, bitSize, hashIterations);
        if (log.isDebugEnabled()) {
            log.debug("[cascade-bloom] addAll to [{}] by pipeline: valueCount={}, hashIterations={}, bitOperations={}",
                    name, normalizedValues.size(), hashIterations, bitOperations);
        }
    }

    @Override
    public boolean mightContain(T value) {
        String normalized = BloomFilterKeyUtil.toKey(value);
        boolean result = stringBloomFilter.contains(normalized);
        if (log.isDebugEnabled()) {
            log.debug("[cascade-bloom] mightContain [{}]: value={}, normalizedKey={}, result={}", name, value, normalized, result);
        }
        return result;
    }

    @Override
    public Map<T, Boolean> mightContainAll(Collection<T> values) {
        Objects.requireNonNull(values, "BloomFilter elements collection must not be null");
        Map<T, Boolean> result = new LinkedHashMap<>(values.size());
        for (T value : values) {
            result.put(value, mightContain(value));
        }
        return result;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public long getExpectedInsertions() {
        return expectedInsertions;
    }

    @Override
    public double getFalseProbability() {
        return falseProbability;
    }

    @Override
    public long count() {
        return stringBloomFilter.count();
    }

    @Override
    public boolean isExists() {
        return stringBloomFilter.isExists();
    }

    @Override
    public void delete() {
        log.warn("[cascade-bloom] deleting bloom filter [{}], all data will be lost!", name);
        stringBloomFilter.delete();
    }

    private long addAllByPipeline(Collection<String> normalizedValues, long bitSize, int hashIterations) {
        RBatch batch = redissonClient.createBatch(BatchOptions.defaults().skipResult());
        RBitSetAsync bitSet = batch.getBitSet(stringBloomFilter.getName());

        long bitOperations = 0L;
        int pendingOperations = 0;
        for (String normalizedValue : normalizedValues) {
            long[] hash = hash128(normalizedValue);
            long hash1 = hash[0];
            long hash2 = hash[1];
            long nextHash = hash1;
            for (int i = 0; i < hashIterations; i++) {
                long index = (nextHash & Long.MAX_VALUE) % bitSize;
                bitSet.setAsync(index);
                nextHash = (i & 1) == 0 ? nextHash + hash2 : nextHash + hash1;
                bitOperations++;
                pendingOperations++;
                if (pendingOperations >= MAX_PIPELINE_BIT_OPERATIONS) {
                    batch.execute();
                    batch = redissonClient.createBatch(BatchOptions.defaults().skipResult());
                    bitSet = batch.getBitSet(stringBloomFilter.getName());
                    pendingOperations = 0;
                }
            }
        }
        if (pendingOperations > 0) {
            batch.execute();
        }
        return bitOperations;
    }

    private long[] hash128(String value) {
        ByteBuf encoded = null;
        try {
            encoded = stringBloomFilter.getCodec().getValueEncoder().encode(value);
            return Hash.hash128(encoded);
        } catch (IOException e) {
            throw new BloomFilterException("Failed to encode bloom filter element. name=" + name + ", value=" + value, e);
        } finally {
            if (encoded != null) {
                encoded.release();
            }
        }
    }

    private static long calculateBitSize(long expectedInsertions, double falseProbability) {
        double probability = falseProbability == 0D ? Double.MIN_VALUE : falseProbability;
        return (long) (-expectedInsertions * Math.log(probability) / (LOG_OF_TWO * LOG_OF_TWO));
    }

    private static int calculateHashIterations(long expectedInsertions, long bitSize) {
        return Math.max(1, (int) Math.round((double) bitSize / expectedInsertions * LOG_OF_TWO));
    }

    private static Method resolveNativeBulkAddMethod(Class<?> bloomFilterType) {
        try {
            Method method = bloomFilterType.getMethod("add", Collection.class);
            Class<?> returnType = method.getReturnType();
            if (returnType == long.class || returnType == Long.class) {
                return method;
            }
            return null;
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    private Long tryAddAllByNativeApi(Collection<String> normalizedValues) {
        if (nativeBulkAddMethod == null || nativeBulkAddUnavailable) {
            return null;
        }
        try {
            Object result = nativeBulkAddMethod.invoke(stringBloomFilter, normalizedValues);
            if (result instanceof Number number) {
                return number.longValue();
            }
            return disableNativeBulkAddAndFallback("Unsupported return type for RBloomFilter.add(Collection): "
                    + nativeBulkAddMethod.getReturnType().getName(), null);
        } catch (IllegalAccessException e) {
            return disableNativeBulkAddAndFallback(
                    "Failed to access RBloomFilter.add(Collection), fallback to pipeline for [" + name + "]", e);
        } catch (IllegalArgumentException e) {
            return disableNativeBulkAddAndFallback(
                    "Failed to invoke RBloomFilter.add(Collection) with current signature, fallback to pipeline for ["
                            + name + "]", e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (isNativeBulkAddCompatibilityFailure(cause)) {
                return disableNativeBulkAddAndFallback(
                        "RBloomFilter.add(Collection) is incompatible in current Redisson runtime, fallback to pipeline for ["
                                + name + "]", cause);
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new BloomFilterException("Failed to invoke RBloomFilter.add(Collection) for [" + name + "]", cause);
        }
    }

    private Long disableNativeBulkAddAndFallback(String message, Throwable cause) {
        nativeBulkAddUnavailable = true;
        if (cause == null) {
            log.warn("[cascade-bloom] {}", message);
        } else {
            log.warn("[cascade-bloom] {}", message, cause);
        }
        return null;
    }

    private static boolean isNativeBulkAddCompatibilityFailure(Throwable throwable) {
        return throwable instanceof UnsupportedOperationException
                || throwable instanceof IncompatibleClassChangeError
                || throwable instanceof ClassCastException;
    }
}
