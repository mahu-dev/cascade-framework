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

    static <K, V> Map<K, V> getAll(Iterable<K> keys,
                                   Consumer<K> trackKey,
                                   Function<K, Optional<CacheRecord<V>>> readFromL1,
                                   Consumer<K> evictL1,
                                   L2CacheStore<K, V> l2Store,
                                   Predicate<CacheRecord<V>> isHardExpired,
                                   Runnable markL1Hit,
                                   Runnable markL2Hit,
                                   Runnable markMiss,
                                   BiConsumer<K, CacheRecord<V>> writeBackL1,
                                   BiConsumer<K, CacheRecord<V>> triggerRefreshIfSoftExpired,
                                   Function<K, V> loadOnMiss) {
        Map<K, V> result = new LinkedHashMap<>();
        if (keys == null) {
            return result;
        }

        ArrayList<K> l2Candidates = new ArrayList<>();
        for (K key : keys) {
            if (key == null) {
                continue;
            }
            trackKey.accept(key);
            Optional<CacheRecord<V>> l1 = readFromL1.apply(key);
            if (l1.isPresent()) {
                CacheRecord<V> record = l1.get();
                if (isHardExpired.test(record)) {
                    evictL1.accept(key);
                } else {
                    markL1Hit.run();
                    triggerRefreshIfSoftExpired.accept(key, record);
                    result.put(key, record.getValue());
                    continue;
                }
            }
            l2Candidates.add(key);
        }

        Map<K, CacheRecord<V>> l2Batch = l2Store != null
                ? l2Store.getAll(l2Candidates)
                : Map.of();
        for (K key : l2Candidates) {
            CacheRecord<V> l2Record = l2Batch.get(key);
            if (l2Record != null) {
                if (isHardExpired.test(l2Record)) {
                    if (l2Store != null) {
                        l2Store.evict(key);
                    }
                } else {
                    markL2Hit.run();
                    writeBackL1.accept(key, l2Record);
                    triggerRefreshIfSoftExpired.accept(key, l2Record);
                    result.put(key, l2Record.getValue());
                    continue;
                }
            }
            markMiss.run();
            V loaded = loadOnMiss.apply(key);
            if (loaded != null) {
                result.put(key, loaded);
            }
        }
        return result;
    }
}
