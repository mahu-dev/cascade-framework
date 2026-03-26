package io.github.cascade.cache.v2.engine;

import io.github.cascade.cache.v2.store.model.CacheRecord;

/**
 * 写入管线：构建统一缓存记录。
 */
public class WritePipeline<V> {

    public CacheRecord<V> createRecord(V value,
                                       long version,
                                       long nowMs,
                                       long hardTtlSeconds,
                                       long softTtlSeconds,
                                       String sourceNodeId) {
        long normalizedHardTtl = hardTtlSeconds > 0 ? hardTtlSeconds : -1L;
        long normalizedSoftTtl = softTtlSeconds > 0 ? softTtlSeconds : normalizedHardTtl;
        if (normalizedHardTtl > 0 && normalizedSoftTtl > normalizedHardTtl) {
            normalizedSoftTtl = normalizedHardTtl;
        }

        long hardExpireAt = normalizedHardTtl > 0 ? nowMs + normalizedHardTtl * 1000 : Long.MAX_VALUE;
        long softExpireAt = normalizedSoftTtl > 0 ? nowMs + normalizedSoftTtl * 1000 : hardExpireAt;
        return new CacheRecord<>(value, version, nowMs, softExpireAt, hardExpireAt, sourceNodeId);
    }
}
