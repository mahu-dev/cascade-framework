package cc.coderm.cascade.bloom.impl;

import cc.coderm.cascade.bloom.core.CascadeBloomFilter;
import cc.coderm.cascade.bloom.util.BloomFilterKeyUtil;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;

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

    private final RBloomFilter<String> stringBloomFilter;
    private final String name;
    private final long expectedInsertions;
    private final double falseProbability;

    public RedissonBloomFilter(RBloomFilter<String> stringBloomFilter,
                               String name,
                               long expectedInsertions,
                               double falseProbability) {
        this.stringBloomFilter = stringBloomFilter;
        this.name = name;
        this.expectedInsertions = expectedInsertions;
        this.falseProbability = falseProbability;
    }

    @Override
    public boolean add(T value) {
        String normalized = BloomFilterKeyUtil.toKey(value);
        boolean added = stringBloomFilter.add(normalized);
        if (log.isDebugEnabled()) {
            log.debug("[cascade-bloom] add to [{}]: value={}, normalizedKey={}, firstAdd={}", name, value, normalized, added);
        }
        return added;
    }

    @Override
    public void addAll(Collection<T> values) {
        Objects.requireNonNull(values, "BloomFilter elements collection must not be null");
        for (T value : values) {
            add(value);
        }
        log.debug("[cascade-bloom] addAll to [{}]: count={}", name, values.size());
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
}
