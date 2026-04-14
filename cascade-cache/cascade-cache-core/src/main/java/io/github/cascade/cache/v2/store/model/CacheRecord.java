package io.github.cascade.cache.v2.store.model;

import lombok.Getter;
import lombok.Setter;

import java.io.Serial;
import java.io.Serializable;

/**
 * 统一缓存记录模型。
 *
 * @param <V> 值类型
 */
@Setter
@Getter
public class CacheRecord<V> implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private V value;
    private long version;
    private long writeTimeMs;
    private long softExpireAtMs;
    private long hardExpireAtMs;
    private String sourceNodeId;

    public CacheRecord() {
    }

    public CacheRecord(V value,
                       long version,
                       long writeTimeMs,
                       long softExpireAtMs,
                       long hardExpireAtMs,
                       String sourceNodeId) {
        this.value = value;
        this.version = version;
        this.writeTimeMs = writeTimeMs;
        this.softExpireAtMs = softExpireAtMs;
        this.hardExpireAtMs = hardExpireAtMs;
        this.sourceNodeId = sourceNodeId;
    }

}

