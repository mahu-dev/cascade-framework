package io.github.cascade.cache.event;

import lombok.Getter;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 统一的缓存事件模型
 * 整合了操作事件和同步事件的功能
 *
 * @author Cascade Framework
 */
public class UnifiedCacheEvent {

    /**
     * 事件类型
     */
    public enum Type {
        // 基础操作
        GET, PUT, EVICT, CLEAR, REFRESH,
        // 加载操作
        LOAD_START, LOAD_SUCCESS, LOAD_FAILURE,
        // 同步操作
        SYNC_PUT, SYNC_EVICT, SYNC_CLEAR, SYNC_REFRESH,
        // 生命周期
        CACHE_CREATED, CACHE_DESTROYED,
        // 监控事件
        HIT, MISS, TIMEOUT, ERROR
    }

    /**
     * 事件级别
     */
    public enum Level {
        TRACE, DEBUG, INFO, WARN, ERROR
    }

    // Getters
    @Getter
    private final String cacheId;
    @Getter
    private final Type type;
    @Getter
    private final Level level;
    @Getter
    private final Instant timestamp;
    @Getter
    private final String sourceNodeId;
    @Getter
    private final Duration duration;

    // 数据字段
    @Getter
    private final Object key;
    @Getter
    private final Object value;
    @Getter
    private final Set<Object> keys;
    private final Map<String, Object> metadata;

    // 状态字段
    @Getter
    private final boolean success;
    @Getter
    private final Throwable exception;

    private UnifiedCacheEvent(Builder builder) {
        this.cacheId = builder.cacheId;
        this.type = builder.type;
        this.level = builder.level;
        this.timestamp = builder.timestamp != null ? builder.timestamp : Instant.now();
        this.sourceNodeId = builder.sourceNodeId;
        this.duration = builder.duration;
        this.key = builder.key;
        this.value = builder.value;
        this.keys = builder.keys;
        this.metadata = new HashMap<>(builder.metadata);
        this.success = builder.success;
        this.exception = builder.exception;
    }

    public Map<String, Object> getMetadata() {
        return new HashMap<>(metadata);
    }

    /**
     * 获取元数据值
     */
    @SuppressWarnings("unchecked")
    public <T> T getMetadata(String key) {
        return (T) metadata.get(key);
    }

    /**
     * 获取操作耗时（毫秒）
     */
    public long getDurationMillis() {
        return duration != null ? duration.toMillis() : 0;
    }

    /**
     * 是否为同步事件
     */
    public boolean isSyncEvent() {
        return type == Type.SYNC_PUT || type == Type.SYNC_EVICT ||
                type == Type.SYNC_CLEAR || type == Type.SYNC_REFRESH;
    }

    /**
     * 是否为操作事件
     */
    public boolean isOperationEvent() {
        return type == Type.GET || type == Type.PUT || type == Type.EVICT ||
                type == Type.CLEAR || type == Type.REFRESH;
    }

    /**
     * 是否为监控事件
     */
    public boolean isMonitoringEvent() {
        return type == Type.HIT || type == Type.MISS || type == Type.TIMEOUT || type == Type.ERROR;
    }

    @Override
    public String toString() {
        return String.format(
                "UnifiedCacheEvent{cacheId='%s', type=%s, level=%s, timestamp=%s, duration=%s, success=%s}",
                cacheId, type, level, timestamp, getDurationMillis() + "ms", success
        );
    }

    /**
     * 创建构建器
     */
    public static Builder builder(String cacheId, Type type) {
        return new Builder(cacheId, type);
    }

    /**
     * 构建器
     */
    public static class Builder {
        private final String cacheId;
        private final Type type;
        private Level level = Level.INFO;
        private Instant timestamp;
        private String sourceNodeId;
        private Duration duration;
        private Object key;
        private Object value;
        private Set<Object> keys;
        private Map<String, Object> metadata = new HashMap<>();
        private boolean success = true;
        private Throwable exception;

        public Builder(String cacheId, Type type) {
            this.cacheId = cacheId;
            this.type = type;
        }

        public Builder level(Level level) {
            this.level = level;
            return this;
        }

        public Builder timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public Builder sourceNodeId(String sourceNodeId) {
            this.sourceNodeId = sourceNodeId;
            return this;
        }

        public Builder duration(Duration duration) {
            this.duration = duration;
            return this;
        }

        public Builder key(Object key) {
            this.key = key;
            return this;
        }

        public Builder value(Object value) {
            this.value = value;
            return this;
        }

        public Builder keys(Set<Object> keys) {
            this.keys = keys;
            return this;
        }

        public Builder metadata(String key, Object value) {
            this.metadata.put(key, value);
            return this;
        }

        public Builder metadata(Map<String, Object> metadata) {
            this.metadata.putAll(metadata);
            return this;
        }

        public Builder success(boolean success) {
            this.success = success;
            return this;
        }

        public Builder exception(Throwable exception) {
            this.exception = exception;
            this.success = false;
            this.level = Level.ERROR;
            return this;
        }

        public UnifiedCacheEvent build() {
            return new UnifiedCacheEvent(this);
        }
    }

    // 静态工厂方法
    public static UnifiedCacheEvent hit(String cacheId, Object key, Object value, Duration duration) {
        return builder(cacheId, Type.HIT)
                .key(key)
                .value(value)
                .duration(duration)
                .level(Level.DEBUG)
                .build();
    }

    public static UnifiedCacheEvent miss(String cacheId, Object key, Duration duration) {
        return builder(cacheId, Type.MISS)
                .key(key)
                .duration(duration)
                .level(Level.DEBUG)
                .build();
    }

    public static UnifiedCacheEvent syncPut(String cacheId, Object key, Object value, String sourceNodeId) {
        return builder(cacheId, Type.SYNC_PUT)
                .key(key)
                .value(value)
                .sourceNodeId(sourceNodeId)
                .level(Level.DEBUG)
                .build();
    }

    public static UnifiedCacheEvent syncEvict(String cacheId, Object key, String sourceNodeId) {
        return builder(cacheId, Type.SYNC_EVICT)
                .key(key)
                .sourceNodeId(sourceNodeId)
                .level(Level.DEBUG)
                .build();
    }

    public static UnifiedCacheEvent syncClear(String cacheId, String sourceNodeId) {
        return builder(cacheId, Type.SYNC_CLEAR)
                .sourceNodeId(sourceNodeId)
                .level(Level.INFO)
                .build();
    }

    public static UnifiedCacheEvent error(String cacheId, Throwable exception) {
        return builder(cacheId, Type.ERROR)
                .exception(exception)
                .build();
    }
}