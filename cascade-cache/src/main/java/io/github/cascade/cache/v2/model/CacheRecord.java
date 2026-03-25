package io.github.cascade.cache.v2.model;

import java.io.Serial;
import java.io.Serializable;

/**
 * 统一缓存记录模型。
 *
 * @param <V> 值类型
 */
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

    public V getValue() {
        return value;
    }

    public void setValue(V value) {
        this.value = value;
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }

    public long getWriteTimeMs() {
        return writeTimeMs;
    }

    public void setWriteTimeMs(long writeTimeMs) {
        this.writeTimeMs = writeTimeMs;
    }

    public long getSoftExpireAtMs() {
        return softExpireAtMs;
    }

    public void setSoftExpireAtMs(long softExpireAtMs) {
        this.softExpireAtMs = softExpireAtMs;
    }

    public long getHardExpireAtMs() {
        return hardExpireAtMs;
    }

    public void setHardExpireAtMs(long hardExpireAtMs) {
        this.hardExpireAtMs = hardExpireAtMs;
    }

    public String getSourceNodeId() {
        return sourceNodeId;
    }

    public void setSourceNodeId(String sourceNodeId) {
        this.sourceNodeId = sourceNodeId;
    }
}

