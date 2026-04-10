package cc.coderm.cascade.bloom.impl;

import cc.coderm.cascade.bloom.core.BloomFilterManager;
import cc.coderm.cascade.bloom.core.BloomFilterTemplate;
import cc.coderm.cascade.bloom.core.CascadeBloomFilter;
import cc.coderm.cascade.bloom.util.BloomFilterKeyUtil;
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
        CascadeBloomFilter<Object> filter = bloomFilterManager.getFilter(filterName);
        return filter.add(BloomFilterKeyUtil.toKey(value));
    }

    @Override
    public boolean mightContain(String filterName, Object value) {
        CascadeBloomFilter<Object> filter = bloomFilterManager.getFilter(filterName);
        return filter.mightContain(BloomFilterKeyUtil.toKey(value));
    }

    @Override
    public <T> T getWithBloomGuard(String filterName, String key, Supplier<T> loader, T fallback) {
        CascadeBloomFilter<Object> filter = bloomFilterManager.getFilter(filterName);

        if (!filter.mightContain(key)) {
            log.debug("[cascade-bloom] BloomFilter [{}] confirmed absent for key [{}], returning fallback", filterName, key);
            return fallback;
        }

        // 可能存在，执行 loader 并返回
        T result = loader.get();

        // 如果查询到数据，自动写回布隆过滤器
        if (result != null) {
            filter.add(key);
            log.debug("[cascade-bloom] BloomFilter [{}] write-back key [{}]", filterName, key);
        }

        return result;
    }

    @Override
    public <T> T getWithBloomGuardAndWriteBack(String filterName, String key, Supplier<T> loader, T fallback) {
        // 此方法语义不够清晰，建议使用 getWithSelfHeal 代替
        // 为了保持向后兼容，内部委托给 getWithSelfHeal
        log.debug("[cascade-bloom] getWithBloomGuardAndWriteBack is deprecated, using getWithSelfHeal instead");
        return getWithSelfHeal(filterName, key, loader, fallback);
    }

    @Override
    public <T> T getWithSelfHeal(String filterName, String key, Supplier<T> loader, T fallback) {
        CascadeBloomFilter<Object> filter = bloomFilterManager.getFilter(filterName);

        boolean mightContain = filter.mightContain(key);
        if (!mightContain) {
            log.debug("[cascade-bloom] BloomFilter [{}] confirmed absent for key [{}], trying loader for self-heal", filterName, key);
        }

        // 总是执行 loader（自愈模式）
        T result = loader.get();

        // 若源数据存在则写回布隆过滤器
        if (result != null) {
            filter.add(key);
            log.debug("[cascade-bloom] BloomFilter [{}] write-back key [{}]", filterName, key);
            return result;
        }

        // 源数据不存在
        if (!mightContain) {
            // 布隆过滤器 miss 且 loader 未命中，返回 fallback
            return fallback;
        }

        // 布隆过滤器 hit 但 loader 未命中（误判场景），返回 null
        return null;
    }
}
