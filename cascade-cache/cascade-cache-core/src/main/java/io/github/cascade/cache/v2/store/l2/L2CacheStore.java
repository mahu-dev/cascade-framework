package io.github.cascade.cache.v2.store.l2;

import io.github.cascade.cache.v2.store.model.CacheRecord;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * L2 存储抽象。
 */
public interface L2CacheStore<K, V> {

    Optional<CacheRecord<V>> get(K key);

    /**
     * 批量读取，默认退化为逐个读取，便于非 Redis 实现快速接入。
     */
    default Map<K, CacheRecord<V>> getAll(Iterable<K> keys) {
        Map<K, CacheRecord<V>> result = new LinkedHashMap<>();
        if (keys == null) {
            return result;
        }
        for (K key : keys) {
            get(key).ifPresent(record -> result.put(key, record));
        }
        return result;
    }

    void put(K key, CacheRecord<V> record, long ttlSeconds);

    void evict(K key);

    void clear();

    long size();

    /**
     * 生成下一写入版本；实现可按需采用“按 key”或“缓存级全局序列”。
     */
    long nextVersion(K key);

    void close();
}
