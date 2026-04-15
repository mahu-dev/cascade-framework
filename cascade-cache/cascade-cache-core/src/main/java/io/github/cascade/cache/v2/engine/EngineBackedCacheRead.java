package io.github.cascade.cache.v2.engine;

import io.github.cascade.cache.v2.store.l2.L2CacheStore;
import io.github.cascade.cache.v2.store.model.CacheRecord;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * EngineBackedCache 批量读取管道逻辑。
 */
final class EngineBackedCacheRead {

    private EngineBackedCacheRead() {
    }

    /**
     * 批量读取所需的协作者集合。
     * <p>
     * 用参数对象替代长参数列表，避免调用点依赖回调位置顺序。
     */
    static final class BatchReadContext<K, V> {

        private final Consumer<K> trackKey;
        private final Function<K, Optional<CacheRecord<V>>> readFromL1;
        private final Consumer<K> evictL1;
        private final L2CacheStore<K, V> l2Store;
        private final Predicate<CacheRecord<V>> isHardExpired;
        private final Runnable markL1Hit;
        private final Runnable markL2Hit;
        private final Runnable markMiss;
        private final BiConsumer<K, CacheRecord<V>> writeBackL1;
        private final BiConsumer<K, CacheRecord<V>> triggerRefreshIfSoftExpired;
        private final Function<K, V> loadOnMiss;

        private BatchReadContext(Builder<K, V> builder) {
            this.trackKey = requireNonNull(builder.trackKey, "trackKey");
            this.readFromL1 = requireNonNull(builder.readFromL1, "readFromL1");
            this.evictL1 = requireNonNull(builder.evictL1, "evictL1");
            this.l2Store = builder.l2Store;
            this.isHardExpired = requireNonNull(builder.isHardExpired, "isHardExpired");
            this.markL1Hit = requireNonNull(builder.markL1Hit, "markL1Hit");
            this.markL2Hit = requireNonNull(builder.markL2Hit, "markL2Hit");
            this.markMiss = requireNonNull(builder.markMiss, "markMiss");
            this.writeBackL1 = requireNonNull(builder.writeBackL1, "writeBackL1");
            this.triggerRefreshIfSoftExpired = requireNonNull(
                    builder.triggerRefreshIfSoftExpired, "triggerRefreshIfSoftExpired"
            );
            this.loadOnMiss = requireNonNull(builder.loadOnMiss, "loadOnMiss");
        }

        static <K, V> Builder<K, V> builder() {
            return new Builder<>();
        }

        static final class Builder<K, V> {
            private Consumer<K> trackKey;
            private Function<K, Optional<CacheRecord<V>>> readFromL1;
            private Consumer<K> evictL1;
            private L2CacheStore<K, V> l2Store;
            private Predicate<CacheRecord<V>> isHardExpired;
            private Runnable markL1Hit;
            private Runnable markL2Hit;
            private Runnable markMiss;
            private BiConsumer<K, CacheRecord<V>> writeBackL1;
            private BiConsumer<K, CacheRecord<V>> triggerRefreshIfSoftExpired;
            private Function<K, V> loadOnMiss;

            Builder<K, V> trackKey(Consumer<K> trackKey) {
                this.trackKey = trackKey;
                return this;
            }

            Builder<K, V> readFromL1(Function<K, Optional<CacheRecord<V>>> readFromL1) {
                this.readFromL1 = readFromL1;
                return this;
            }

            Builder<K, V> evictL1(Consumer<K> evictL1) {
                this.evictL1 = evictL1;
                return this;
            }

            Builder<K, V> l2Store(L2CacheStore<K, V> l2Store) {
                this.l2Store = l2Store;
                return this;
            }

            Builder<K, V> isHardExpired(Predicate<CacheRecord<V>> isHardExpired) {
                this.isHardExpired = isHardExpired;
                return this;
            }

            Builder<K, V> markL1Hit(Runnable markL1Hit) {
                this.markL1Hit = markL1Hit;
                return this;
            }

            Builder<K, V> markL2Hit(Runnable markL2Hit) {
                this.markL2Hit = markL2Hit;
                return this;
            }

            Builder<K, V> markMiss(Runnable markMiss) {
                this.markMiss = markMiss;
                return this;
            }

            Builder<K, V> writeBackL1(BiConsumer<K, CacheRecord<V>> writeBackL1) {
                this.writeBackL1 = writeBackL1;
                return this;
            }

            Builder<K, V> triggerRefreshIfSoftExpired(BiConsumer<K, CacheRecord<V>> triggerRefreshIfSoftExpired) {
                this.triggerRefreshIfSoftExpired = triggerRefreshIfSoftExpired;
                return this;
            }

            Builder<K, V> loadOnMiss(Function<K, V> loadOnMiss) {
                this.loadOnMiss = loadOnMiss;
                return this;
            }

            BatchReadContext<K, V> build() {
                return new BatchReadContext<>(this);
            }
        }
    }

    static <K, V> Map<K, V> getAll(Iterable<K> keys, BatchReadContext<K, V> context) {
        Map<K, V> result = new LinkedHashMap<>();
        if (keys == null) {
            return result;
        }

        ArrayList<K> l2Candidates = new ArrayList<>();
        collectL2Candidates(keys, result, l2Candidates, context);

        Map<K, CacheRecord<V>> l2Batch = context.l2Store != null
                ? context.l2Store.getAll(l2Candidates)
                : Map.of();
        fillFromL2OrLoad(l2Candidates, l2Batch, result, context);
        return result;
    }

    private static <K, V> void collectL2Candidates(Iterable<K> keys,
                                                   Map<K, V> result,
                                                   ArrayList<K> l2Candidates,
                                                   BatchReadContext<K, V> context) {
        for (K key : keys) {
            if (shouldSkipKey(key)) {
                continue;
            }
            context.trackKey.accept(key);
            if (tryFillFromL1(key, result, context)) {
                continue;
            }
            l2Candidates.add(key);
        }
    }

    private static <K, V> boolean tryFillFromL1(K key,
                                                Map<K, V> result,
                                                BatchReadContext<K, V> context) {
        Optional<CacheRecord<V>> l1 = context.readFromL1.apply(key);
        if (l1.isEmpty()) {
            return false;
        }

        CacheRecord<V> record = l1.get();
        if (context.isHardExpired.test(record)) {
            context.evictL1.accept(key);
            return false;
        }

        context.markL1Hit.run();
        context.triggerRefreshIfSoftExpired.accept(key, record);
        result.put(key, record.getValue());
        return true;
    }

    private static <K, V> void fillFromL2OrLoad(Iterable<K> l2Candidates,
                                                Map<K, CacheRecord<V>> l2Batch,
                                                Map<K, V> result,
                                                BatchReadContext<K, V> context) {
        for (K key : l2Candidates) {
            if (tryFillFromL2(key, l2Batch, result, context)) {
                continue;
            }
            loadMissValue(key, result, context);
        }
    }

    private static <K, V> boolean tryFillFromL2(K key,
                                                Map<K, CacheRecord<V>> l2Batch,
                                                Map<K, V> result,
                                                BatchReadContext<K, V> context) {
        CacheRecord<V> l2Record = l2Batch.get(key);
        if (l2Record == null) {
            return false;
        }
        if (context.isHardExpired.test(l2Record)) {
            evictFromL2IfPresent(context.l2Store, key);
            return false;
        }

        context.markL2Hit.run();
        context.writeBackL1.accept(key, l2Record);
        context.triggerRefreshIfSoftExpired.accept(key, l2Record);
        result.put(key, l2Record.getValue());
        return true;
    }

    private static <K> boolean shouldSkipKey(K key) {
        return key == null;
    }

    private static <K, V> void loadMissValue(K key,
                                             Map<K, V> result,
                                             BatchReadContext<K, V> context) {
        context.markMiss.run();
        V loaded = context.loadOnMiss.apply(key);
        if (loaded != null) {
            result.put(key, loaded);
        }
    }

    private static <K, V> void evictFromL2IfPresent(L2CacheStore<K, V> l2Store, K key) {
        if (l2Store != null) {
            l2Store.evict(key);
        }
    }

    private static <T> T requireNonNull(T value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + "不能为空");
        }
        return value;
    }
}
