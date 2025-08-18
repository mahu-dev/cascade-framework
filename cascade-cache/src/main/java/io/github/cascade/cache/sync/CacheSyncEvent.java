package io.github.cascade.cache.sync;

import lombok.Getter;

import java.time.Instant;
import java.util.Set;

/**
 * 缓存同步事件
 */
@Getter
public class CacheSyncEvent {

    /**
     * 操作类型
     */
    public enum Operation {
        PUT, EVICT, CLEAR, REFRESH
    }

    private final String cacheId;
    private final Operation operation;
    private final Object key;
    private final Object value;
    private final Set<Object> keys;
    private final String sourceNodeId;
    private final Instant timestamp;

    // PUT操作
    public CacheSyncEvent(String cacheId, Object key, Object value, String sourceNodeId) {
        this.cacheId = cacheId;
        this.operation = Operation.PUT;
        this.key = key;
        this.value = value;
        this.keys = null;
        this.sourceNodeId = sourceNodeId;
        this.timestamp = Instant.now();
    }

    // EVICT操作
    public CacheSyncEvent(String cacheId, Operation operation, Object key, String sourceNodeId) {
        this.cacheId = cacheId;
        this.operation = operation;
        this.key = key;
        this.value = null;
        this.keys = null;
        this.sourceNodeId = sourceNodeId;
        this.timestamp = Instant.now();
    }

    // 批量操作
    public CacheSyncEvent(String cacheId, Operation operation, Set<Object> keys, String sourceNodeId) {
        this.cacheId = cacheId;
        this.operation = operation;
        this.key = null;
        this.value = null;
        this.keys = keys;
        this.sourceNodeId = sourceNodeId;
        this.timestamp = Instant.now();
    }

    // CLEAR操作
    public CacheSyncEvent(String cacheId, String sourceNodeId) {
        this.cacheId = cacheId;
        this.operation = Operation.CLEAR;
        this.key = null;
        this.value = null;
        this.keys = null;
        this.sourceNodeId = sourceNodeId;
        this.timestamp = Instant.now();
    }

    @Override
    public String toString() {
        return "CacheSyncEvent{" +
                "cacheId='" + cacheId + '\'' +
                ", operation=" + operation +
                ", key=" + key +
                ", value=" + value +
                ", keys=" + keys +
                ", sourceNodeId='" + sourceNodeId + '\'' +
                ", timestamp=" + timestamp +
                '}';
    }
}