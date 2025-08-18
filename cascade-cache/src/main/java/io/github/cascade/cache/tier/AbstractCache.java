package io.github.cascade.cache.tier;

import io.github.cascade.cache.api.Cache;
import io.github.cascade.cache.api.CacheLoader;
import io.github.cascade.cache.api.CacheStats;
import io.github.cascade.cache.api.CacheTier;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * 抽象缓存实现基类
 * 提供所有缓存类型的通用功能实现
 *
 * @param <K> 键类型
 * @param <V> 值类型
 * @author cascade
 */
public abstract class AbstractCache<K, V> implements Cache<K, V> {
    
    protected final String name;
    protected final CacheTier tier;
    
    // 统计信息
    protected final AtomicLong hitCount = new AtomicLong(0);
    protected final AtomicLong missCount = new AtomicLong(0);
    protected final AtomicLong loadCount = new AtomicLong(0);
    protected final AtomicLong loadExceptionCount = new AtomicLong(0);
    protected final AtomicLong loadTimeTotal = new AtomicLong(0);
    protected final AtomicLong evictionCount = new AtomicLong(0);
    
    // CacheLoader支持 - 使用AtomicReference保证线程安全
    protected final AtomicReference<CacheLoader<K, V>> loaderRef = new AtomicReference<>();
    
    protected AbstractCache(String name, CacheTier tier) {
        this.name = name;
        this.tier = tier;
    }
    
    // ==================== 元数据和管理 ====================
    
    @Override
    public String getName() {
        return name;
    }
    
    @Override
    public CacheTier getTier() {
        return tier;
    }
    
    @Override
    public CacheStats getStats() {
        return new CacheStatsImpl();
    }
    
    // ==================== 加载器支持 ====================
    
    @Override
    public void setLoader(CacheLoader<K, V> loader) {
        this.loaderRef.set(loader);
    }
    
    @Override
    public CacheLoader<K, V> getLoader() {
        return loaderRef.get();
    }
    
    // ==================== 带加载器的获取方法 ====================
    
    @Override
    public V get(K key, Function<K, V> loader) {
        V value = get(key);
        if (value == null && loader != null) {
            long startTime = System.nanoTime();
            recordLoad();
            
            try {
                value = loader.apply(key);
                if (value != null) {
                    put(key, value);
                }
                
                long loadTime = System.nanoTime() - startTime;
                recordLoadTime(loadTime);
                
                return value;
            } catch (Exception e) {
                recordLoadException();
                throw new RuntimeException("Error loading value for key: " + key, e);
            }
        }
        return value;
    }
    
    @Override
    public Map<K, V> getAll(Set<K> keys, Function<Set<K>, Map<K, V>> loader) {
        Map<K, V> result = getAll(keys);
        
        if (loader != null) {
            Set<K> missingKeys = keys.stream()
                .filter(key -> !result.containsKey(key))
                .collect(java.util.stream.Collectors.toSet());
            
            if (!missingKeys.isEmpty()) {
                long startTime = System.nanoTime();
                recordLoad();
                
                try {
                    Map<K, V> loaded = loader.apply(missingKeys);
                    if (loaded != null && !loaded.isEmpty()) {
                        putAll(loaded);
                        result.putAll(loaded);
                    }
                    
                    long loadTime = System.nanoTime() - startTime;
                    recordLoadTime(loadTime);
                    
                } catch (Exception e) {
                    recordLoadException();
                    throw new RuntimeException("Error loading values for keys: " + missingKeys, e);
                }
            }
        }
        
        return result;
    }
    
    // ==================== 维护操作默认实现 ====================
    
    @Override
    public void refresh(K key) {
        CacheLoader<K, V> currentLoader = loaderRef.get();
        if (currentLoader != null) {
            try {
                V newValue = currentLoader.load(key);
                if (newValue != null) {
                    put(key, newValue);
                } else {
                    evict(key);
                }
            } catch (Exception e) {
                throw new RuntimeException("Error refreshing key: " + key, e);
            }
        } else {
            // 没有loader时，只能删除旧值
            evict(key);
        }
    }
    
    @Override
    public CompletableFuture<Void> refreshAsync(K key) {
        return CompletableFuture.runAsync(() -> refresh(key));
    }
    
    @Override
    public long estimatedSize() {
        return size();
    }
    
    @Override
    public boolean isEmpty() {
        return size() == 0;
    }
    
    // ==================== 统计记录方法 ====================
    
    protected void recordHit() {
        hitCount.incrementAndGet();
    }
    
    protected void recordMiss() {
        missCount.incrementAndGet();
    }
    
    protected void recordLoad() {
        loadCount.incrementAndGet();
    }
    
    protected void recordLoadException() {
        loadExceptionCount.incrementAndGet();
    }
    
    protected void recordLoadTime(long nanos) {
        loadTimeTotal.addAndGet(nanos);
    }
    
    protected void recordEviction() {
        evictionCount.incrementAndGet();
    }
    
    // ==================== 抽象方法 ====================
    
    /**
     * 子类必须实现的核心获取方法
     */
    @Override
    public abstract V get(K key);
    
    /**
     * 子类必须实现的核心存储方法
     */
    @Override
    public abstract void put(K key, V value);
    
    /**
     * 子类必须实现的核心删除方法
     */
    @Override
    public abstract void evict(K key);
    
    /**
     * 子类必须实现的清空方法
     */
    @Override
    public abstract void clear();
    
    /**
     * 子类必须实现的大小获取方法
     */
    @Override
    public abstract long size();
    
    /**
     * 子类必须实现的键存在检查方法
     */
    @Override
    public abstract boolean containsKey(K key);
    
    /**
     * 子类必须实现的批量获取方法
     */
    @Override
    public abstract Map<K, V> getAll(Set<K> keys);
    
    /**
     * 子类必须实现的批量存储方法
     */
    @Override
    public abstract void putAll(Map<K, V> map);
    
    /**
     * 子类必须实现的批量删除方法
     */
    @Override
    public abstract void evictAll(Set<K> keys);
    
    /**
     * 子类必须实现的条件存储方法
     */
    @Override
    public abstract boolean putIfAbsent(K key, V value);
    
    /**
     * 子类必须实现的清理方法
     */
    @Override
    public abstract void cleanUp();
    
    // ==================== 统计信息实现 ====================
    
    /**
     * 统计信息实现类
     */
    private class CacheStatsImpl implements CacheStats {
        
        @Override
        public long hitCount() {
            return hitCount.get();
        }
        
        @Override
        public long missCount() {
            return missCount.get();
        }
        
        @Override
        public long requestCount() {
            return hitCount() + missCount();
        }
        
        @Override
        public double hitRate() {
            long hits = hitCount();
            long total = requestCount();
            return total == 0 ? 1.0 : (double) hits / total;
        }
        
        @Override
        public double missRate() {
            return 1.0 - hitRate();
        }
        
        @Override
        public long loadCount() {
            return AbstractCache.this.loadCount.get();
        }
        
        @Override
        public long loadExceptionCount() {
            return AbstractCache.this.loadExceptionCount.get();
        }
        
        @Override
        public long totalLoadTime() {
            return loadTimeTotal.get();
        }
        
        @Override
        public double averageLoadPenalty() {
            long loads = loadCount();
            if (loads == 0) {
                return 0.0;
            }
            return (double) totalLoadTime() / loads;
        }
        
        @Override
        public long evictionCount() {
            return AbstractCache.this.evictionCount.get();
        }
        
        @Override
        public long evictionWeight() {
            return evictionCount(); // 简单实现，权重等于次数
        }
        
        @Override
        public void reset() {
            hitCount.set(0);
            missCount.set(0);
            AbstractCache.this.loadCount.set(0);
            AbstractCache.this.loadExceptionCount.set(0);
            AbstractCache.this.loadTimeTotal.set(0);
            AbstractCache.this.evictionCount.set(0);
        }
        
        @Override
        public String toString() {
            return String.format("%s[hits=%d, misses=%d, hitRate=%.2f%%, loads=%d, exceptions=%d, evictions=%d]", 
                tier.getName(), hitCount(), missCount(), hitRate() * 100, loadCount(), 
                loadExceptionCount(), evictionCount());
        }
    }
}