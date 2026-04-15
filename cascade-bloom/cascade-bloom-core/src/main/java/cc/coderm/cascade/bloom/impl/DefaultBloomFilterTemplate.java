package cc.coderm.cascade.bloom.impl;

import cc.coderm.cascade.bloom.core.BloomFilterManager;
import cc.coderm.cascade.bloom.core.BloomFilterTemplate;
import cc.coderm.cascade.bloom.core.CascadeBloomFilter;
import cc.coderm.cascade.bloom.util.BloomFilterArgumentValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.function.Supplier;

/**
 * {@link BloomFilterTemplate} 的默认实现
 *
 * @author lionel lionelk@163.com
 * =============================
 * Date: 2025-01-10
 * Time: 16:00:00
 * =============================
 */
@Slf4j
@RequiredArgsConstructor
public class DefaultBloomFilterTemplate implements BloomFilterTemplate {

    private final BloomFilterManager bloomFilterManager;

    @Override
    public boolean add(String filterName, Object value) {
        String normalizedFilterName = BloomFilterArgumentValidator.normalizeFilterName(filterName);
        String normalizedKey = BloomFilterArgumentValidator.normalizeKey(value, "value");
        CascadeBloomFilter<Object> filter = bloomFilterManager.getFilter(normalizedFilterName);
        return filter.add(normalizedKey);
    }

    @Override
    public boolean mightContain(String filterName, Object value) {
        String normalizedFilterName = BloomFilterArgumentValidator.normalizeFilterName(filterName);
        String normalizedKey = BloomFilterArgumentValidator.normalizeKey(value, "value");
        CascadeBloomFilter<Object> filter = bloomFilterManager.getFilter(normalizedFilterName);
        return filter.mightContain(normalizedKey);
    }

    @Override
    public <T> T getWithBloomGuard(String filterName, String key, Supplier<T> loader, T fallback) {
        String normalizedFilterName = BloomFilterArgumentValidator.normalizeFilterName(filterName);
        String normalizedKey = BloomFilterArgumentValidator.normalizeKey(key, "key");
        Supplier<T> checkedLoader = BloomFilterArgumentValidator.requireLoader(loader);
        CascadeBloomFilter<Object> filter = bloomFilterManager.getFilter(normalizedFilterName);

        if (!filter.mightContain(normalizedKey)) {
            log.debug("[cascade-bloom] BloomFilter [{}] confirmed absent for key [{}], returning fallback",
                    normalizedFilterName, normalizedKey);
            return fallback;
        }

        // 可能存在，执行 loader 并返回
        T result = checkedLoader.get();

        // 如果查询到数据，自动写回布隆过滤器
        if (result != null) {
            filter.add(normalizedKey);
            log.debug("[cascade-bloom] BloomFilter [{}] write-back key [{}]", normalizedFilterName, normalizedKey);
        }

        return result;
    }

    @Override
    public <T> T getWithSelfHeal(String filterName, String key, Supplier<T> loader, T fallback) {
        String normalizedFilterName = BloomFilterArgumentValidator.normalizeFilterName(filterName);
        String normalizedKey = BloomFilterArgumentValidator.normalizeKey(key, "key");
        Supplier<T> checkedLoader = BloomFilterArgumentValidator.requireLoader(loader);
        CascadeBloomFilter<Object> filter = bloomFilterManager.getFilter(normalizedFilterName);

        boolean mightContain = filter.mightContain(normalizedKey);
        if (!mightContain) {
            log.debug("[cascade-bloom] BloomFilter [{}] confirmed absent for key [{}], trying loader for self-heal",
                    normalizedFilterName, normalizedKey);
        }

        // 总是执行 loader（自愈模式）
        T result = checkedLoader.get();

        // 若源数据存在则写回布隆过滤器
        if (result != null) {
            filter.add(normalizedKey);
            log.debug("[cascade-bloom] BloomFilter [{}] write-back key [{}]", normalizedFilterName, normalizedKey);
            return result;
        }

        // 源数据不存在时统一返回 fallback，保持与接口契约一致
        if (mightContain) {
            log.debug("[cascade-bloom] BloomFilter [{}] false-positive suspected for key [{}], returning fallback",
                    normalizedFilterName, normalizedKey);
        }
        return fallback;
    }
}
