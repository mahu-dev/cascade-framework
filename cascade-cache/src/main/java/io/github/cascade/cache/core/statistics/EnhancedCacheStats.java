package io.github.cascade.cache.core.statistics;

import io.github.cascade.cache.api.CacheStats;
import io.github.cascade.cache.api.LoadingStats;

/**
 * 增强的缓存统计信息
 * 
 * @author cascade
 */
public class EnhancedCacheStats {
    
    private final String cacheName;
    private final CacheStats l1Stats;
    private final CacheStats l2Stats;
    private final LoadingStats loadingStats;
    private final boolean syncEnabled;
    private final boolean protectionEnabled;
    
    private EnhancedCacheStats(Builder builder) {
        this.cacheName = builder.cacheName;
        this.l1Stats = builder.l1Stats;
        this.l2Stats = builder.l2Stats;
        this.loadingStats = builder.loadingStats;
        this.syncEnabled = builder.syncEnabled;
        this.protectionEnabled = builder.protectionEnabled;
    }
    
    public static Builder builder() {
        return new Builder();
    }
    
    public String getCacheName() {
        return cacheName;
    }
    
    public CacheStats getL1Stats() {
        return l1Stats;
    }
    
    public CacheStats getL2Stats() {
        return l2Stats;
    }
    
    public LoadingStats getLoadingStats() {
        return loadingStats;
    }
    
    public boolean isSyncEnabled() {
        return syncEnabled;
    }
    
    public boolean isProtectionEnabled() {
        return protectionEnabled;
    }
    
    /**
     * 计算总的命中率
     */
    public double getOverallHitRate() {
        long totalHits = 0;
        long totalRequests = 0;
        
        if (l1Stats != null) {
            totalHits += l1Stats.hitCount();
            totalRequests += l1Stats.requestCount();
        }
        
        if (l2Stats != null) {
            totalHits += l2Stats.hitCount();
            totalRequests += l2Stats.requestCount();
        }
        
        return totalRequests == 0 ? 0.0 : (double) totalHits / totalRequests;
    }
    
    /**
     * 获取L1和L2的命中数总和
     */
    public long getTotalHitCount() {
        long totalHits = 0;
        
        if (l1Stats != null) {
            totalHits += l1Stats.hitCount();
        }
        
        if (l2Stats != null) {
            totalHits += l2Stats.hitCount();
        }
        
        return totalHits;
    }
    
    /**
     * 获取L1和L2的请求数总和
     */
    public long getTotalRequestCount() {
        long totalRequests = 0;
        
        if (l1Stats != null) {
            totalRequests += l1Stats.requestCount();
        }
        
        if (l2Stats != null) {
            totalRequests += l2Stats.requestCount();
        }
        
        return totalRequests;
    }
    
    @Override
    public String toString() {
        return "EnhancedCacheStats{" +
                "cacheName='" + cacheName + '\'' +
                ", overallHitRate=" + String.format("%.2f%%", getOverallHitRate() * 100) +
                ", totalHitCount=" + getTotalHitCount() +
                ", totalRequestCount=" + getTotalRequestCount() +
                ", syncEnabled=" + syncEnabled +
                ", protectionEnabled=" + protectionEnabled +
                ", l1Stats=" + l1Stats +
                ", l2Stats=" + l2Stats +
                ", loadingStats=" + loadingStats +
                '}';
    }
    
    public static class Builder {
        private String cacheName;
        private CacheStats l1Stats;
        private CacheStats l2Stats;
        private LoadingStats loadingStats;
        private boolean syncEnabled;
        private boolean protectionEnabled;
        
        public Builder cacheName(String cacheName) {
            this.cacheName = cacheName;
            return this;
        }
        
        public Builder l1Stats(CacheStats l1Stats) {
            this.l1Stats = l1Stats;
            return this;
        }
        
        public Builder l2Stats(CacheStats l2Stats) {
            this.l2Stats = l2Stats;
            return this;
        }
        
        public Builder loadingStats(LoadingStats loadingStats) {
            this.loadingStats = loadingStats;
            return this;
        }
        
        public Builder syncEnabled(boolean syncEnabled) {
            this.syncEnabled = syncEnabled;
            return this;
        }
        
        public Builder protectionEnabled(boolean protectionEnabled) {
            this.protectionEnabled = protectionEnabled;
            return this;
        }
        
        public EnhancedCacheStats build() {
            return new EnhancedCacheStats(this);
        }
    }
}