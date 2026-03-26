package io.github.cascade.cache.v2.engine;

import io.github.cascade.cache.v2.store.model.CacheRecord;

import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 读取管线：L1 -> L2。
 */
public class ReadPipeline<K, V> {

    public enum HitLevel {
        L1,
        L2,
        MISS
    }

    public record ReadResult<V>(HitLevel level, CacheRecord<V> record, boolean l1HardExpired, boolean l2HardExpired) {
        public boolean hit() {
            return level != HitLevel.MISS && record != null;
        }
    }

    public ReadResult<V> read(K key,
                              Function<K, Optional<CacheRecord<V>>> l1Reader,
                              Function<K, Optional<CacheRecord<V>>> l2Reader,
                              Predicate<CacheRecord<V>> hardExpired) {
        boolean l1HardExpired = false;
        Optional<CacheRecord<V>> l1 = l1Reader.apply(key);
        if (l1.isPresent()) {
            if (!hardExpired.test(l1.get())) {
                return new ReadResult<>(HitLevel.L1, l1.get(), false, false);
            }
            l1HardExpired = true;
        }

        Optional<CacheRecord<V>> l2 = l2Reader.apply(key);
        if (l2.isPresent()) {
            if (!hardExpired.test(l2.get())) {
                return new ReadResult<>(HitLevel.L2, l2.get(), l1HardExpired, false);
            }
            return new ReadResult<>(HitLevel.MISS, null, l1HardExpired, true);
        }
        return new ReadResult<>(HitLevel.MISS, null, l1HardExpired, false);
    }
}
