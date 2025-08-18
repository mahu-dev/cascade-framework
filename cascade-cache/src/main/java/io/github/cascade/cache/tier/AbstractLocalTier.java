package io.github.cascade.cache.tier;

import io.github.cascade.cache.api.CacheTier;

import java.time.Duration;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 抽象本地缓存实现基类
 * 提供本地缓存的通用功能实现
 *
 * @param <K> 键类型
 * @param <V> 值类型
 * @author cascade
 */
public abstract class AbstractLocalTier<K, V> extends AbstractCache<K, V> implements LocalTier<K, V> {
    
    // 配置参数
    protected volatile long maximumSize = -1;
    protected volatile long maximumWeight = -1;
    protected volatile Duration expireAfterWrite;
    protected volatile Duration expireAfterAccess;
    protected volatile Duration refreshAfterWrite;
    protected volatile boolean softValues = false;
    protected volatile boolean weakKeys = false;
    protected volatile int concurrencyLevel = 4;
    protected volatile int initialCapacity = 16;
    
    // 移除监听器
    protected final AtomicReference<RemovalListener<K, V>> removalListenerRef = new AtomicReference<>();
    
    protected AbstractLocalTier(String name) {
        super(name, CacheTier.L1);
    }
    
    // ==================== 容量管理 ====================
    
    @Override
    public long maximumSize() {
        return maximumSize;
    }
    
    @Override
    public void setMaximumSize(long size) {
        this.maximumSize = size;
        onConfigurationChange();
    }
    
    @Override
    public long maximumWeight() {
        return maximumWeight;
    }
    
    @Override
    public void setMaximumWeight(long weight) {
        this.maximumWeight = weight;
        onConfigurationChange();
    }
    
    // ==================== 过期策略 ====================
    
    @Override
    public Duration expireAfterWrite() {
        return expireAfterWrite;
    }
    
    @Override
    public void setExpireAfterWrite(Duration duration) {
        this.expireAfterWrite = duration;
        onConfigurationChange();
    }
    
    @Override
    public Duration expireAfterAccess() {
        return expireAfterAccess;
    }
    
    @Override
    public void setExpireAfterAccess(Duration duration) {
        this.expireAfterAccess = duration;
        onConfigurationChange();
    }
    
    @Override
    public Duration refreshAfterWrite() {
        return refreshAfterWrite;
    }
    
    @Override
    public void setRefreshAfterWrite(Duration duration) {
        this.refreshAfterWrite = duration;
        onConfigurationChange();
    }
    
    // ==================== 内存管理 ====================
    
    @Override
    public boolean isSoftValues() {
        return softValues;
    }
    
    @Override
    public void setSoftValues(boolean softValues) {
        this.softValues = softValues;
        onConfigurationChange();
    }
    
    @Override
    public boolean isWeakKeys() {
        return weakKeys;
    }
    
    @Override
    public void setWeakKeys(boolean weakKeys) {
        this.weakKeys = weakKeys;
        onConfigurationChange();
    }
    
    // ==================== 并发控制 ====================
    
    @Override
    public int concurrencyLevel() {
        return concurrencyLevel;
    }
    
    @Override
    public void setConcurrencyLevel(int level) {
        this.concurrencyLevel = level;
        onConfigurationChange();
    }
    
    @Override
    public int initialCapacity() {
        return initialCapacity;
    }
    
    @Override
    public void setInitialCapacity(int capacity) {
        this.initialCapacity = capacity;
        onConfigurationChange();
    }
    
    // ==================== 高级功能 ====================
    
    @Override
    public void invalidate(K key) {
        RemovalListener<K, V> listener = removalListenerRef.get();
        V oldValue = null;
        
        if (listener != null) {
            oldValue = get(key);
        }
        
        evict(key);
        
        if (listener != null && oldValue != null) {
            listener.onRemoval(key, oldValue, RemovalCause.EXPLICIT);
        }
    }
    
    @Override
    public void invalidateAll(Iterable<K> keys) {
        for (K key : keys) {
            invalidate(key);
        }
    }
    
    @Override
    public void invalidateAll() {
        RemovalListener<K, V> listener = removalListenerRef.get();
        
        if (listener != null) {
            // 通知所有条目被移除
            ConcurrentMap<K, V> map = asMap();
            for (java.util.Map.Entry<K, V> entry : map.entrySet()) {
                listener.onRemoval(entry.getKey(), entry.getValue(), RemovalCause.EXPLICIT);
            }
        }
        
        clear();
    }
    
    @Override
    public void setRemovalListener(RemovalListener<K, V> listener) {
        this.removalListenerRef.set(listener);
    }
    
    @Override
    public RemovalListener<K, V> getRemovalListener() {
        return removalListenerRef.get();
    }
    
    // ==================== 抽象方法 ====================
    
    /**
     * 当配置发生变化时调用
     * 子类可以重写此方法来响应配置变化
     */
    protected void onConfigurationChange() {
        // 默认空实现，子类可以重写
    }
    
    // ==================== 工具方法 ====================
    
    /**
     * 触发移除监听器
     */
    protected void notifyRemoval(K key, V value, RemovalCause cause) {
        RemovalListener<K, V> listener = removalListenerRef.get();
        if (listener != null) {
            try {
                listener.onRemoval(key, value, cause);
            } catch (Exception e) {
                // 移除监听器异常不应该影响缓存操作
                System.err.println("RemovalListener threw exception: " + e.getMessage());
            }
        }
    }
    
    /**
     * 检查条目是否应该过期
     */
    protected boolean shouldExpire(long writeTime, long accessTime, long currentTime) {
        if (expireAfterWrite != null) {
            if (currentTime - writeTime > expireAfterWrite.toNanos()) {
                return true;
            }
        }
        
        if (expireAfterAccess != null) {
            if (currentTime - accessTime > expireAfterAccess.toNanos()) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * 检查条目是否应该刷新
     */
    protected boolean shouldRefresh(long writeTime, long currentTime) {
        if (refreshAfterWrite != null) {
            return currentTime - writeTime > refreshAfterWrite.toNanos();
        }
        return false;
    }
}