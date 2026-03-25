package io.github.cascade.cache.v2.store;

import io.github.cascade.cache.v2.model.CacheRecord;

import java.util.Optional;

/**
 * L2 存储抽象。
 */
public interface L2CacheStore<K, V> {

    Optional<CacheRecord<V>> get(K key);

    void put(K key, CacheRecord<V> record, long ttlSeconds);

    void evict(K key);

    void clear();

    long size();

    long nextVersion(K key);

    void close();
}

