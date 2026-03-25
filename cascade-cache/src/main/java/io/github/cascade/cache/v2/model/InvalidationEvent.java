package io.github.cascade.cache.v2.model;

import java.io.Serial;
import java.io.Serializable;

/**
 * 失效同步事件。
 *
 * @param <K> 键类型
 */
public class InvalidationEvent<K> implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public enum Operation {
        INVALIDATE,
        UPDATE,
        CLEAR
    }

    private String cacheName;
    private Operation operation;
    private K key;
    private long version;
    private CacheRecord<Object> record;
    private String nodeId;
    private long timestamp;

    public InvalidationEvent() {
    }

    public InvalidationEvent(String cacheName,
                             Operation operation,
                             K key,
                             long version,
                             CacheRecord<Object> record,
                             String nodeId,
                             long timestamp) {
        this.cacheName = cacheName;
        this.operation = operation;
        this.key = key;
        this.version = version;
        this.record = record;
        this.nodeId = nodeId;
        this.timestamp = timestamp;
    }

    public static <K> InvalidationEvent<K> invalidate(String cacheName, K key, long version, String nodeId) {
        return new InvalidationEvent<>(
                cacheName, Operation.INVALIDATE, key, version, null, nodeId, System.currentTimeMillis()
        );
    }

    public static <K, V> InvalidationEvent<K> update(String cacheName, K key, CacheRecord<V> record, String nodeId) {
        CacheRecord<Object> payload = null;
        if (record != null) {
            payload = new CacheRecord<>(
                    record.getValue(),
                    record.getVersion(),
                    record.getWriteTimeMs(),
                    record.getSoftExpireAtMs(),
                    record.getHardExpireAtMs(),
                    record.getSourceNodeId()
            );
        }
        long version = record != null ? record.getVersion() : 0L;
        return new InvalidationEvent<>(
                cacheName, Operation.UPDATE, key, version, payload, nodeId, System.currentTimeMillis()
        );
    }

    public static <K> InvalidationEvent<K> clear(String cacheName, long version, String nodeId) {
        return new InvalidationEvent<>(cacheName, Operation.CLEAR, null, version, null, nodeId, System.currentTimeMillis());
    }

    public String getCacheName() {
        return cacheName;
    }

    public void setCacheName(String cacheName) {
        this.cacheName = cacheName;
    }

    public Operation getOperation() {
        return operation;
    }

    public void setOperation(Operation operation) {
        this.operation = operation;
    }

    public K getKey() {
        return key;
    }

    public void setKey(K key) {
        this.key = key;
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }

    public CacheRecord<Object> getRecord() {
        return record;
    }

    public void setRecord(CacheRecord<Object> record) {
        this.record = record;
    }

    public String getNodeId() {
        return nodeId;
    }

    public void setNodeId(String nodeId) {
        this.nodeId = nodeId;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }
}
