package io.github.cascade.cache.simple;

import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * 缓存刷新器接口
 * 
 * 设计原则：
 * 1. 异步刷新：所有刷新操作都是异步的
 * 2. 可配置：支持多种刷新策略
 * 3. 批量优化：支持批量刷新提高性能
 * 4. 生命周期：提供启动和停止管理
 * 
 * @param <K> 键类型
 * @param <V> 值类型
 * @author cascade
 */
public interface CacheRefresher<K, V> {
    
    // ==================== 核心刷新方法 ====================
    
    /**
     * 刷新单个缓存项
     */
    CompletableFuture<V> refresh(K key);
    
    /**
     * 批量刷新缓存项
     */
    CompletableFuture<Void> refreshAll(Set<K> keys);
    
    /**
     * 刷新所有缓存项
     */
    CompletableFuture<Void> refreshAll();
    
    // ==================== 管理方法 ====================
    
    /**
     * 添加需要刷新的键
     */
    void addKey(K key);
    
    /**
     * 添加需要刷新的键（带自定义间隔）
     */
    void addKey(K key, long refreshIntervalSeconds);
    
    /**
     * 移除不需要刷新的键
     */
    void removeKey(K key);
    
    /**
     * 获取所有被监控的键
     */
    Set<K> getMonitoredKeys();
    
    /**
     * 清空所有监控的键
     */
    void clearMonitoredKeys();
    
    // ==================== 生命周期管理 ====================
    
    /**
     * 启动刷新器
     */
    void start();
    
    /**
     * 停止刷新器
     */
    void stop();
    
    /**
     * 检查是否运行中
     */
    boolean isRunning();
    
    // ==================== 配置信息 ====================
    
    /**
     * 获取默认刷新间隔（秒）
     */
    long getDefaultRefreshIntervalSeconds();
    
    /**
     * 设置默认刷新间隔（秒）
     */
    void setDefaultRefreshIntervalSeconds(long seconds);
    
    /**
     * 是否并行刷新
     */
    boolean isParallelRefresh();
    
    /**
     * 设置是否并行刷新
     */
    void setParallelRefresh(boolean parallel);
}