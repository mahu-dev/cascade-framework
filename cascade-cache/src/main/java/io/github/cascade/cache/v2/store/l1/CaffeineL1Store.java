package io.github.cascade.cache.v2.store.l1;

import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.cascade.cache.v2.store.model.CacheRecord;

import java.util.Optional;

/**
 * 基于 Caffeine 的 L1 实现。
 */
public class CaffeineL1Store<K, V> implements L1CacheStore<K, V> {

    private final com.github.benmanes.caffeine.cache.Cache<K, CacheRecord<V>> cache;

    public CaffeineL1Store(long maxSize, boolean recordStats) {
        Caffeine<Object, Object> builder = Caffeine.newBuilder().maximumSize(maxSize);
        if (recordStats) {
            builder.recordStats();
        }
        this.cache = builder.build();
    }

    @Override
    public Optional<CacheRecord<V>> get(K key) {
        return Optional.ofNullable(cache.getIfPresent(key));
    }

    @Override
    public void put(K key, CacheRecord<V> record) {
        cache.put(key, record);
    }

    @Override
    public void evict(K key) {
        cache.invalidate(key);
    }

    @Override
    public void clear() {
        cache.invalidateAll();
    }

    @Override
    public boolean containsKey(K key) {
        return cache.getIfPresent(key) != null;
    }

    @Override
    public long size() {
        return cache.estimatedSize();
    }

    @Override
    public void close() {
        clear();
        cache.cleanUp();
    }
}

