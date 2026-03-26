package io.github.cascade.cache.v2.consistency;

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
    private String keyTypeName;
    private long version;
    private UpdatePayload updatePayload;
    private String valueTypeName;
    private String nodeId;
    private long timestamp;

    public InvalidationEvent() {
    }

    public InvalidationEvent(String cacheName,
                             Operation operation,
                             K key,
                             String keyTypeName,
                             long version,
                             UpdatePayload updatePayload,
                             String valueTypeName,
                             String nodeId,
                             long timestamp) {
        this.cacheName = cacheName;
        this.operation = operation;
        this.key = key;
        this.keyTypeName = keyTypeName;
        this.version = version;
        this.updatePayload = updatePayload;
        this.valueTypeName = valueTypeName;
        this.nodeId = nodeId;
        this.timestamp = timestamp;
    }

    public static <K> InvalidationEvent<K> invalidate(String cacheName, K key, long version, String nodeId) {
        return new InvalidationEvent<>(
                cacheName, Operation.INVALIDATE, key, resolveKeyTypeName(key), version, null, null, nodeId, System.currentTimeMillis()
        );
    }

    public static <K> InvalidationEvent<K> update(String cacheName,
                                                   K key,
                                                   long version,
                                                   UpdatePayload updatePayload,
                                                   String valueTypeName,
                                                   String nodeId) {
        return new InvalidationEvent<>(
                cacheName, Operation.UPDATE, key, resolveKeyTypeName(key), version, updatePayload, valueTypeName, nodeId,
                System.currentTimeMillis()
        );
    }

    public static <K> InvalidationEvent<K> clear(String cacheName, long version, String nodeId) {
        return new InvalidationEvent<>(cacheName, Operation.CLEAR, null, null, version, null, null, nodeId,
                System.currentTimeMillis());
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

    public String getKeyTypeName() {
        return keyTypeName;
    }

    public void setKeyTypeName(String keyTypeName) {
        this.keyTypeName = keyTypeName;
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }

    public UpdatePayload getUpdatePayload() {
        return updatePayload;
    }

    public void setUpdatePayload(UpdatePayload updatePayload) {
        this.updatePayload = updatePayload;
    }

    public String getValueTypeName() {
        return valueTypeName;
    }

    public void setValueTypeName(String valueTypeName) {
        this.valueTypeName = valueTypeName;
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

    private static String resolveKeyTypeName(Object key) {
        return key != null ? key.getClass().getName() : null;
    }

    /**
     * UPDATE 事件载荷（值本体序列化后跨节点传输）。
     */
    public static class UpdatePayload implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /**
         * payload 编解码方案，当前固定为 jackson-json-v1
         */
        private String codec;
        private String valuePayload;
        private long writeTimeMs;
        private long softExpireAtMs;
        private long hardExpireAtMs;
        private String sourceNodeId;

        public UpdatePayload() {
        }

        public UpdatePayload(String codec,
                             String valuePayload,
                             long writeTimeMs,
                             long softExpireAtMs,
                             long hardExpireAtMs,
                             String sourceNodeId) {
            this.codec = codec;
            this.valuePayload = valuePayload;
            this.writeTimeMs = writeTimeMs;
            this.softExpireAtMs = softExpireAtMs;
            this.hardExpireAtMs = hardExpireAtMs;
            this.sourceNodeId = sourceNodeId;
        }

        public String getCodec() {
            return codec;
        }

        public void setCodec(String codec) {
            this.codec = codec;
        }

        public String getValuePayload() {
            return valuePayload;
        }

        public void setValuePayload(String valuePayload) {
            this.valuePayload = valuePayload;
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
}
