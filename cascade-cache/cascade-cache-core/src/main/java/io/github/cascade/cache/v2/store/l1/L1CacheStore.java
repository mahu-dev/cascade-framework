package io.github.cascade.cache.v2.store.l1;

import io.github.cascade.cache.v2.store.model.CacheRecord;

import java.util.Optional;

/**
 * L1 存储抽象。
 */
public interface L1CacheStore<K, V> {

    Optional<CacheRecord<V>> get(K key);

    void put(K key, CacheRecord<V> record);

    void evict(K key);

    void clear();

    boolean containsKey(K key);

    long size();

    void close();
}

