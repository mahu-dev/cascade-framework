package cc.coderm.cascade.bloom.impl;

import cc.coderm.cascade.bloom.core.BloomFilterManager;
import cc.coderm.cascade.bloom.core.BloomFilterTemplate;
import cc.coderm.cascade.bloom.core.CascadeBloomFilter;
import cc.coderm.cascade.bloom.util.BloomFilterArgumentValidator;
import cc.coderm.cascade.bloom.util.BloomFilterResultPresenceUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
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
public class DefaultBloomFilterTemplate implements BloomFilterTemplate {

    private final BloomFilterManager bloomFilterManager;
    private final ConcurrentHashMap<String, BoundBloomFilterOperations> boundOperationsCache = new ConcurrentHashMap<>();

    public DefaultBloomFilterTemplate(BloomFilterManager bloomFilterManager) {
        this.bloomFilterManager = bloomFilterManager;
    }

    @Override
    public BoundBloomFilterOperations bind(String filterName) {
        String normalizedFilterName = BloomFilterArgumentValidator.normalizeFilterName(filterName);
        return boundOperationsCache.computeIfAbsent(normalizedFilterName, BoundBloomFilterOperationsImpl::new);
    }

    private final class BoundBloomFilterOperationsImpl implements BoundBloomFilterOperations {
        private final String filterName;
        private volatile CascadeBloomFilter<Object> cachedFilter;

        private BoundBloomFilterOperationsImpl(String filterName) {
            this.filterName = filterName;
        }

        @Override
        public String filterName() {
            return filterName;
        }

        @Override
        public boolean add(Object value) {
            String normalizedKey = BloomFilterArgumentValidator.normalizeKey(value, "value");
            return executeWithAutoRefresh(filter -> filter.add(normalizedKey));
        }

        @Override
        public boolean mightContain(Object value) {
            String normalizedKey = BloomFilterArgumentValidator.normalizeKey(value, "value");
            return executeWithAutoRefresh(filter -> filter.mightContain(normalizedKey));
        }

        @Override
        public <T> T getWithBloomGuard(String key, Supplier<T> loader, T fallback) {
            String normalizedKey = BloomFilterArgumentValidator.normalizeKey(key, "key");
            Supplier<T> checkedLoader = BloomFilterArgumentValidator.requireLoader(loader);

            if (!executeWithAutoRefresh(filter -> filter.mightContain(normalizedKey))) {
                log.debug("[cascade-bloom] BloomFilter [{}] confirmed absent for key [{}], returning fallback",
                        filterName, normalizedKey);
                return fallback;
            }
            // 可能存在，执行 loader 并返回
            T result = checkedLoader.get();
            if (BloomFilterResultPresenceUtil.isAsyncResult(result)) {
                return decorateAsyncWriteBackResult(result, normalizedKey);
            }

            // 如果查询到数据，自动写回布隆过滤器
            if (BloomFilterResultPresenceUtil.shouldWriteBack(result)) {
                executeWithAutoRefresh(filter -> filter.add(normalizedKey));
                log.debug("[cascade-bloom] BloomFilter [{}] write-back key [{}]", filterName, normalizedKey);
            }

            return result;
        }

        @Override
        public <T> T getWithSelfHeal(String key, Supplier<T> loader, T fallback) {
            String normalizedKey = BloomFilterArgumentValidator.normalizeKey(key, "key");
            Supplier<T> checkedLoader = BloomFilterArgumentValidator.requireLoader(loader);

            boolean mightContain = executeWithAutoRefresh(filter -> filter.mightContain(normalizedKey));
            if (!mightContain) {
                log.debug("[cascade-bloom] BloomFilter [{}] confirmed absent for key [{}], trying loader for self-heal",
                        filterName, normalizedKey);
            }

            // 总是执行 loader（自愈模式）
            T result = checkedLoader.get();
            if (BloomFilterResultPresenceUtil.isAsyncResult(result)) {
                return decorateAsyncSelfHealResult(result, normalizedKey, mightContain, fallback);
            }

            // 若源数据存在则写回布隆过滤器
            if (BloomFilterResultPresenceUtil.shouldWriteBack(result)) {
                executeWithAutoRefresh(filter -> filter.add(normalizedKey));
                log.debug("[cascade-bloom] BloomFilter [{}] write-back key [{}]", filterName, normalizedKey);
                return result;
            }

            // 源数据不存在时统一返回 fallback，保持与接口契约一致
            if (mightContain) {
                log.debug("[cascade-bloom] BloomFilter [{}] false-positive suspected for key [{}], returning fallback",
                        filterName, normalizedKey);
            }
            return fallback;
        }

        @SuppressWarnings("unchecked")
        private <T> T decorateAsyncWriteBackResult(T result, String normalizedKey) {
            CompletionStage<Object> resultStage = (CompletionStage<Object>) result;
            CompletableFuture<Object> decorated = resultStage.toCompletableFuture()
                    .thenApply(value -> {
                        if (BloomFilterResultPresenceUtil.shouldWriteBack(value)) {
                            executeWithAutoRefresh(filter -> filter.add(normalizedKey));
                            log.debug("[cascade-bloom] BloomFilter [{}] write-back key [{}]", filterName, normalizedKey);
                        }
                        return value;
                    });
            return (T) decorated;
        }

        @SuppressWarnings("unchecked")
        private <T> T decorateAsyncSelfHealResult(T result,
                                                  String normalizedKey,
                                                  boolean mightContain,
                                                  T fallback) {
            CompletionStage<Object> resultStage = (CompletionStage<Object>) result;
            CompletableFuture<Object> decorated = resultStage.toCompletableFuture()
                    .thenCompose(value -> {
                        if (BloomFilterResultPresenceUtil.shouldWriteBack(value)) {
                            executeWithAutoRefresh(filter -> filter.add(normalizedKey));
                            log.debug("[cascade-bloom] BloomFilter [{}] write-back key [{}]", filterName, normalizedKey);
                            return CompletableFuture.completedFuture(value);
                        }
                        if (mightContain) {
                            log.debug("[cascade-bloom] BloomFilter [{}] false-positive suspected for key [{}], returning fallback",
                                    filterName, normalizedKey);
                        }
                        return BloomFilterResultPresenceUtil.toCompletionFuture(fallback);
                    });
            return (T) decorated;
        }

        private CascadeBloomFilter<Object> currentFilter() {
            CascadeBloomFilter<Object> snapshot = cachedFilter;
            if (snapshot != null) {
                return snapshot;
            }
            synchronized (this) {
                snapshot = cachedFilter;
                if (snapshot == null) {
                    snapshot = bloomFilterManager.getFilter(filterName);
                    cachedFilter = snapshot;
                }
                return snapshot;
            }
        }

        private CascadeBloomFilter<Object> reloadFilter(CascadeBloomFilter<Object> failedFilter) {
            CascadeBloomFilter<Object> snapshot = cachedFilter;
            if (snapshot != null && snapshot != failedFilter) {
                return snapshot;
            }
            synchronized (this) {
                snapshot = cachedFilter;
                if (snapshot != null && snapshot != failedFilter) {
                    return snapshot;
                }
                // 强一致探测先行，确保外部删除场景下 manager 内部状态先被收敛。
                bloomFilterManager.existsInRedis(filterName);
                snapshot = bloomFilterManager.getFilter(filterName);
                cachedFilter = snapshot;
                return snapshot;
            }
        }

        private <R> R executeWithAutoRefresh(Function<CascadeBloomFilter<Object>, R> operation) {
            CascadeBloomFilter<Object> current = currentFilter();
            try {
                return operation.apply(current);
            } catch (RuntimeException firstFailure) {
                CascadeBloomFilter<Object> refreshed;
                try {
                    refreshed = reloadFilter(current);
                } catch (RuntimeException reloadFailure) {
                    reloadFailure.addSuppressed(firstFailure);
                    throw reloadFailure;
                }
                if (refreshed == current) {
                    throw firstFailure;
                }
                try {
                    return operation.apply(refreshed);
                } catch (RuntimeException secondFailure) {
                    secondFailure.addSuppressed(firstFailure);
                    throw secondFailure;
                }
            }
        }
    }
}
