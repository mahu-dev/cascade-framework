package io.github.cascade.cache.simple;

import io.github.cascade.cache.config.CascadeCacheProperties;
import lombok.Getter;

import java.util.Collection;
import java.util.Collections;
import java.util.Set;

/**
 * 缓存管理器统计信息
 * <p>
 * 设计原则：
 * 1. 只读快照：提供某个时刻的状态快照
 * 2. 线程安全：不可变对象设计
 * 3. 信息全面：包含关键运行指标
 * 4. 序列化友好：支持JSON序列化
 *
 * @author cascade
 */
@Getter
public class CacheManagerStats {

    private final String nodeId;
    private final int cacheCount;
    private final int refresherCount;
    private final int syncerCount;
    private final boolean closed;
    private final CascadeCacheProperties defaultConfig;
    private final Set<String> cacheNames;
    private final long timestamp;

    private CacheManagerStats(Builder builder) {
        this.nodeId = builder.nodeId;
        this.cacheCount = builder.cacheCount;
        this.refresherCount = builder.refresherCount;
        this.syncerCount = builder.syncerCount;
        this.closed = builder.closed;
        this.defaultConfig = builder.defaultConfig;
        this.cacheNames = Set.copyOf(builder.cacheNames);
        this.timestamp = System.currentTimeMillis();
    }

    // ==================== Getter方法 ====================

    // ==================== 便利方法 ====================

    /**
     * 是否启用分布式功能
     */
    public boolean isDistributedMode() {
        return syncerCount > 0;
    }

    /**
     * 是否启用自动刷新
     */
    public boolean isAutoRefreshEnabled() {
        return refresherCount > 0;
    }

    /**
     * 获取健康状态
     */
    public String getHealthStatus() {
        if (closed) {
            return "CLOSED";
        }
        if (cacheCount == 0) {
            return "EMPTY";
        }
        return "HEALTHY";
    }

    // ==================== Builder模式 ====================

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String nodeId;
        private int cacheCount;
        private int refresherCount;
        private int syncerCount;
        private boolean closed;
        private CascadeCacheProperties defaultConfig;
        private Collection<String> cacheNames = Collections.emptyList();

        public Builder nodeId(String nodeId) {
            this.nodeId = nodeId;
            return this;
        }

        public Builder cacheCount(int cacheCount) {
            this.cacheCount = cacheCount;
            return this;
        }

        public Builder refresherCount(int refresherCount) {
            this.refresherCount = refresherCount;
            return this;
        }

        public Builder syncerCount(int syncerCount) {
            this.syncerCount = syncerCount;
            return this;
        }

        public Builder closed(boolean closed) {
            this.closed = closed;
            return this;
        }

        public Builder defaultConfig(CascadeCacheProperties defaultConfig) {
            this.defaultConfig = defaultConfig;
            return this;
        }

        public Builder cacheNames(Collection<String> cacheNames) {
            this.cacheNames = cacheNames != null ? cacheNames : Collections.emptyList();
            return this;
        }

        public CacheManagerStats build() {
            return new CacheManagerStats(this);
        }
    }

    // ==================== Object方法覆盖 ====================

    @Override
    public String toString() {
        return String.format("CacheManagerStats{" +
                        "nodeId='%s', " +
                        "cacheCount=%d, " +
                        "refresherCount=%d, " +
                        "syncerCount=%d, " +
                        "closed=%s, " +
                        "healthStatus='%s', " +
                        "timestamp=%d" +
                        "}",
                nodeId, cacheCount, refresherCount, syncerCount,
                closed, getHealthStatus(), timestamp);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;

        CacheManagerStats that = (CacheManagerStats) obj;
        return cacheCount == that.cacheCount &&
                refresherCount == that.refresherCount &&
                syncerCount == that.syncerCount &&
                closed == that.closed &&
                timestamp == that.timestamp &&
                java.util.Objects.equals(nodeId, that.nodeId) &&
                java.util.Objects.equals(defaultConfig, that.defaultConfig) &&
                java.util.Objects.equals(cacheNames, that.cacheNames);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(nodeId, cacheCount, refresherCount,
                syncerCount, closed, defaultConfig,
                cacheNames, timestamp);
    }
}