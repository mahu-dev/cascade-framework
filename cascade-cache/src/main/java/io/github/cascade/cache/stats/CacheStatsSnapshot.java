package io.github.cascade.cache.stats;

import java.time.Instant;

/**
 * 缓存统计快照类
 * 不可变的统计数据快照，用于监控和报告
 */
public final class CacheStatsSnapshot {
    
    private final long hitCount;
    private final long missCount;
    private final long loadCount;
    private final long loadSuccessCount;
    private final long loadFailureCount;
    private final double averageLoadTime;
    private final long evictionCount;
    private final long evictionWeight;
    private final long estimatedSize;
    private final Instant creationTime;
    private final Instant lastAccessTime;
    private final Instant snapshotTime;
    
    private CacheStatsSnapshot(Builder builder) {
        this.hitCount = builder.hitCount;
        this.missCount = builder.missCount;
        this.loadCount = builder.loadCount;
        this.loadSuccessCount = builder.loadSuccessCount;
        this.loadFailureCount = builder.loadFailureCount;
        this.averageLoadTime = builder.averageLoadTime;
        this.evictionCount = builder.evictionCount;
        this.evictionWeight = builder.evictionWeight;
        this.estimatedSize = builder.estimatedSize;
        this.creationTime = builder.creationTime;
        this.lastAccessTime = builder.lastAccessTime;
        this.snapshotTime = Instant.now();
    }
    
    public static Builder builder() {
        return new Builder();
    }
    
    public long hitCount() {
        return hitCount;
    }
    
    public long missCount() {
        return missCount;
    }
    
    public long requestCount() {
        return hitCount + missCount;
    }
    
    public double hitRate() {
        long requests = requestCount();
        return requests == 0 ? 1.0 : (double) hitCount / requests;
    }
    
    public double missRate() {
        return 1.0 - hitRate();
    }
    
    public long loadCount() {
        return loadCount;
    }
    
    public long loadSuccessCount() {
        return loadSuccessCount;
    }
    
    public long loadFailureCount() {
        return loadFailureCount;
    }
    
    public double averageLoadTime() {
        return averageLoadTime;
    }
    
    public double averageLoadTimeMillis() {
        return averageLoadTime / 1_000_000.0;
    }
    
    public long evictionCount() {
        return evictionCount;
    }
    
    public long evictionWeight() {
        return evictionWeight;
    }
    
    public long estimatedSize() {
        return estimatedSize;
    }
    
    public Instant creationTime() {
        return creationTime;
    }
    
    public Instant lastAccessTime() {
        return lastAccessTime;
    }
    
    public Instant snapshotTime() {
        return snapshotTime;
    }
    
    @Override
    public String toString() {
        return String.format(
            "CacheStatsSnapshot{" +
                "hitRate=%.2f%%, " +
                "requests=%d, " +
                "hits=%d, " +
                "misses=%d, " +
                "loads=%d, " +
                "loadSuccesses=%d, " +
                "loadFailures=%d, " +
                "avgLoadTime=%.2fms, " +
                "evictions=%d, " +
                "size=%d, " +
                "snapshot=%s" +
                "}",
            hitRate() * 100,
            requestCount(),
            hitCount,
            missCount,
            loadCount,
            loadSuccessCount,
            loadFailureCount,
            averageLoadTimeMillis(),
            evictionCount,
            estimatedSize,
            snapshotTime
        );
    }
    
    public static class Builder {
        private long hitCount;
        private long missCount;
        private long loadCount;
        private long loadSuccessCount;
        private long loadFailureCount;
        private double averageLoadTime;
        private long evictionCount;
        private long evictionWeight;
        private long estimatedSize;
        private Instant creationTime;
        private Instant lastAccessTime;
        
        public Builder hitCount(long hitCount) {
            this.hitCount = hitCount;
            return this;
        }
        
        public Builder missCount(long missCount) {
            this.missCount = missCount;
            return this;
        }
        
        public Builder loadCount(long loadCount) {
            this.loadCount = loadCount;
            return this;
        }
        
        public Builder loadSuccessCount(long loadSuccessCount) {
            this.loadSuccessCount = loadSuccessCount;
            return this;
        }
        
        public Builder loadFailureCount(long loadFailureCount) {
            this.loadFailureCount = loadFailureCount;
            return this;
        }
        
        public Builder averageLoadTime(double averageLoadTime) {
            this.averageLoadTime = averageLoadTime;
            return this;
        }
        
        public Builder evictionCount(long evictionCount) {
            this.evictionCount = evictionCount;
            return this;
        }
        
        public Builder evictionWeight(long evictionWeight) {
            this.evictionWeight = evictionWeight;
            return this;
        }
        
        public Builder estimatedSize(long estimatedSize) {
            this.estimatedSize = estimatedSize;
            return this;
        }
        
        public Builder creationTime(Instant creationTime) {
            this.creationTime = creationTime;
            return this;
        }
        
        public Builder lastAccessTime(Instant lastAccessTime) {
            this.lastAccessTime = lastAccessTime;
            return this;
        }
        
        public CacheStatsSnapshot build() {
            return new CacheStatsSnapshot(this);
        }
    }
}