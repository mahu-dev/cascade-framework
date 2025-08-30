package io.github.cascade.cache.simple;

/**
 * 缓存类型枚举
 * 
 * @author cascade
 */
public enum CacheType {
    /**
     * 仅L1缓存（本地Caffeine缓存）
     */
    L1_ONLY,
    
    /**
     * 仅L2缓存（Redis分布式缓存）
     */
    L2_ONLY,
    
    /**
     * 多级缓存（L1 + L2）
     */
    TIERED
}