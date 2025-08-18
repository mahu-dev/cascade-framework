package io.github.cascade.cache.core.impl;

import io.github.cascade.cache.api.*;
import io.github.cascade.cache.stats.LoadingStatsImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 默认LoadingCache实现
 * 基于装饰器模式，为基础缓存添加自动加载功能
 */
public class DefaultLoadingCache<K, V> implements LoadingCache<K, V> {
    
    private static final Logger log = LoggerFactory.getLogger(DefaultLoadingCache.class);
    
    private final Cache<K, V> delegate;
    private final CacheLoader<K, V> cacheLoader;
    private final Executor executor;
    private final LoadingStatsImpl loadingStats;
    private final ConcurrentMap<K, CompletableFuture<V>> loadingFutures;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    
    // 配置参数
    private final long loadTimeoutMillis;
    private final boolean enableBatchLoading;
    
    public DefaultLoadingCache(Cache<K, V> delegate, CacheLoader<K, V> cacheLoader) {
        this(delegate, cacheLoader, ForkJoinPool.commonPool(), 30000L, true);
    }
    
    public DefaultLoadingCache(Cache<K, V> delegate, CacheLoader<K, V> cacheLoader, 
                              Executor executor, long loadTimeoutMillis, boolean enableBatchLoading) {
        this.delegate = Objects.requireNonNull(delegate, "Delegate cache cannot be null");
        this.cacheLoader = Objects.requireNonNull(cacheLoader, "CacheLoader cannot be null");
        this.executor = Objects.requireNonNull(executor, "Executor cannot be null");
        this.loadTimeoutMillis = loadTimeoutMillis;
        this.enableBatchLoading = enableBatchLoading;
        this.loadingStats = new LoadingStatsImpl();
        this.loadingFutures = new ConcurrentHashMap<>();
    }
    
    @Override
    public V get(K key) {
        V value = delegate.get(key);
        if (value != null) {
            return value;
        }
        
        // 缓存未命中，尝试加载
        try {
            return loadAndCache(key);
        } catch (Exception e) {
            log.warn("Failed to load value for key: {}", key, e);
            return null;
        }
    }
    
    @Override
    public V getUnchecked(K key) {
        V value = delegate.get(key);
        if (value != null) {
            return value;
        }
        
        // 缓存未命中，加载并缓存（抛出运行时异常）
        try {
            return loadAndCache(key);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load value for key: " + key, e);
        }
    }
    
    @Override
    public Map<K, V> getAll(Set<K> keys) {
        Map<K, V> result = delegate.getAll(keys);
        Set<K> missingKeys = new HashSet<>(keys);
        missingKeys.removeAll(result.keySet());
        
        if (!missingKeys.isEmpty()) {
            Map<K, V> loaded = loadAllAndCache(missingKeys);
            result.putAll(loaded);
        }
        
        return result;
    }
    
    @Override
    public Map<K, V> getAllUnchecked(Set<K> keys) {
        try {
            return getAll(keys);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load values for keys: " + keys, e);
        }
    }
    
    @Override
    public CompletableFuture<V> getAsync(K key) {
        V cached = delegate.get(key);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }
        
        return loadAsync(key);
    }
    
    @Override
    public CompletableFuture<Map<K, V>> getAllAsync(Set<K> keys) {
        Map<K, V> cached = delegate.getAll(keys);
        Set<K> missingKeys = new HashSet<>(keys);
        missingKeys.removeAll(cached.keySet());
        
        if (missingKeys.isEmpty()) {
            return CompletableFuture.completedFuture(cached);
        }
        
        return loadAllAsync(missingKeys).thenApply(loaded -> {
            Map<K, V> result = new HashMap<>(cached);
            result.putAll(loaded);
            return result;
        });
    }
    
    @Override
    public void refresh(K key) {
        try {
            V value = cacheLoader.reload(key, delegate.get(key));
            if (value != null) {
                delegate.put(key, value);
            }
        } catch (Exception e) {
            log.warn("Failed to refresh key: {}", key, e);
        }
    }
    
    @Override
    public CompletableFuture<Void> refreshAsync(K key) {
        return CompletableFuture.runAsync(() -> refresh(key), executor);
    }
    
    @Override
    public void refreshAll(Set<K> keys) {
        keys.forEach(this::refresh);
    }
    
    @Override
    public CompletableFuture<Void> refreshAllAsync(Set<K> keys) {
        return CompletableFuture.runAsync(() -> refreshAll(keys), executor);
    }
    
    @Override
    public void preload(K key) throws Exception {
        if (!delegate.containsKey(key)) {
            V value = cacheLoader.load(key);
            if (value != null) {
                delegate.put(key, value);
            }
        }
    }
    
    @Override
    public void preloadAll(Set<K> keys) throws Exception {
        Set<K> keysToLoad = keys.stream()
            .filter(k -> !delegate.containsKey(k))
            .collect(java.util.stream.Collectors.toSet());
        
        if (!keysToLoad.isEmpty()) {
            Map<K, V> loaded = cacheLoader.loadAll(keysToLoad);
            delegate.putAll(loaded);
        }
    }
    
    @Override
    public CompletableFuture<Void> preloadAsync(K key) {
        return CompletableFuture.runAsync(() -> {
            try {
                preload(key);
            } catch (Exception e) {
                throw new RuntimeException("Failed to preload key: " + key, e);
            }
        }, executor);
    }
    
    @Override
    public CompletableFuture<Void> preloadAllAsync(Set<K> keys) {
        return CompletableFuture.runAsync(() -> {
            try {
                preloadAll(keys);
            } catch (Exception e) {
                throw new RuntimeException("Failed to preload keys: " + keys, e);
            }
        }, executor);
    }
    
    @Override
    public LoadingStats getLoadingStats() {
        return loadingStats;
    }
    
    @Override
    public boolean isLoading(K key) {
        CompletableFuture<V> future = loadingFutures.get(key);
        return future != null && !future.isDone();
    }
    
    @Override
    public Set<K> getLoadingKeys() {
        return loadingFutures.entrySet().stream()
            .filter(entry -> !entry.getValue().isDone())
            .map(Map.Entry::getKey)
            .collect(java.util.stream.Collectors.toSet());
    }
    
    @Override
    public boolean cancelLoading(K key) {
        CompletableFuture<V> future = loadingFutures.get(key);
        if (future != null && !future.isDone()) {
            boolean cancelled = future.cancel(true);
            if (cancelled) {
                loadingStats.recordLoadCancel();
                loadingFutures.remove(key);
            }
            return cancelled;
        }
        return false;
    }
    
    @Override
    public int cancelAllLoading() {
        int cancelled = 0;
        for (Map.Entry<K, CompletableFuture<V>> entry : loadingFutures.entrySet()) {
            if (entry.getValue().cancel(true)) {
                cancelled++;
                loadingStats.recordLoadCancel();
            }
        }
        loadingFutures.clear();
        return cancelled;
    }
    
    @Override
    public Cache<K, V> asCache() {
        return delegate;
    }
    
    @Override
    public AsyncCache<K, V> asAsyncCache() {
        if (delegate instanceof AsyncCache) {
            return (AsyncCache<K, V>) delegate;
        }
        throw new UnsupportedOperationException("Delegate cache does not implement AsyncCache");
    }
    
    // 私有辅助方法
    
    private V loadAndCache(K key) throws Exception {
        // 检查是否已在加载中
        CompletableFuture<V> existingFuture = loadingFutures.get(key);
        if (existingFuture != null && !existingFuture.isDone()) {
            try {
                return existingFuture.get(loadTimeoutMillis, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                loadingStats.recordLoadTimeout();
                throw new Exception("Load timeout for key: " + key, e);
            }
        }
        
        // 创建新的加载任务
        CompletableFuture<V> loadFuture = CompletableFuture.supplyAsync(() -> {
            loadingStats.recordActiveLoadStart();
            long startTime = System.nanoTime();
            try {
                V value = cacheLoader.load(key);
                long duration = System.nanoTime() - startTime;
                loadingStats.recordLoadTime(duration);
                loadingStats.recordLoadSuccess();
                
                if (value != null) {
                    delegate.put(key, value);
                }
                return value;
            } catch (Exception e) {
                long duration = System.nanoTime() - startTime;
                loadingStats.recordLoadTime(duration);
                loadingStats.recordLoadFailure();
                throw new RuntimeException("Failed to load key: " + key, e);
            } finally {
                loadingStats.recordActiveLoadEnd();
                loadingFutures.remove(key);
            }
        }, executor);
        
        loadingFutures.put(key, loadFuture);
        
        try {
            return loadFuture.get(loadTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            loadingStats.recordLoadTimeout();
            loadFuture.cancel(true);
            loadingFutures.remove(key);
            throw new Exception("Load timeout for key: " + key, e);
        }
    }
    
    private Map<K, V> loadAllAndCache(Set<K> keys) {
        if (!enableBatchLoading || keys.size() == 1) {
            // 单个加载
            Map<K, V> result = new HashMap<>();
            for (K key : keys) {
                try {
                    V value = loadAndCache(key);
                    if (value != null) {
                        result.put(key, value);
                    }
                } catch (Exception e) {
                    log.warn("Failed to load key: {}", key, e);
                }
            }
            return result;
        } else {
            // 批量加载
            loadingStats.recordBatchLoadStart();
            loadingStats.recordBatchSize(keys.size());
            long startTime = System.nanoTime();
            try {
                Map<K, V> loaded = cacheLoader.loadAll(keys);
                long duration = System.nanoTime() - startTime;
                loadingStats.recordLoadTime(duration);
                loadingStats.recordLoadSuccess();
                
                if (loaded != null && !loaded.isEmpty()) {
                    delegate.putAll(loaded);
                }
                return loaded != null ? loaded : new HashMap<>();
            } catch (Exception e) {
                long duration = System.nanoTime() - startTime;
                loadingStats.recordLoadTime(duration);
                loadingStats.recordLoadFailure();
                log.warn("Batch load failed for keys: {}", keys, e);
                return new HashMap<>();
            }
        }
    }
    
    private CompletableFuture<V> loadAsync(K key) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return loadAndCache(key);
            } catch (Exception e) {
                throw new RuntimeException("Failed to load key: " + key, e);
            }
        }, executor);
    }
    
    private CompletableFuture<Map<K, V>> loadAllAsync(Set<K> keys) {
        return CompletableFuture.supplyAsync(() -> loadAllAndCache(keys), executor);
    }
    
    // 委托方法
    @Override
    public V get(K key, java.util.function.Function<K, V> loader) {
        return delegate.get(key, loader);
    }
    
    @Override
    public Map<K, V> getAll(Set<K> keys, java.util.function.Function<Set<K>, Map<K, V>> loader) {
        return delegate.getAll(keys, loader);
    }
    
    @Override
    public void put(K key, V value) {
        delegate.put(key, value);
    }
    
    public void put(K key, V value, java.time.Duration ttl) {
        // 委托给底层缓存，如果支持TTL则使用，否则忽略TTL参数
        try {
            if (delegate instanceof io.github.cascade.cache.core.impl.MultiLevelCascadeCache) {
                ((io.github.cascade.cache.core.impl.MultiLevelCascadeCache<K, V>) delegate).put(key, value, ttl);
            } else if (delegate instanceof io.github.cascade.cache.core.impl.EnhancedDistributedTieredCache) {
                ((io.github.cascade.cache.core.impl.EnhancedDistributedTieredCache<K, V>) delegate).put(key, value, ttl);
            } else if (delegate instanceof io.github.cascade.cache.core.impl.SimpleCascadeCache) {
                ((io.github.cascade.cache.core.impl.SimpleCascadeCache<K, V>) delegate).put(key, value, ttl);
            } else {
                // 如果底层缓存不支持TTL，则使用普通put方法
                delegate.put(key, value);
            }
        } catch (Exception e) {
            // 降级到普通put方法
            delegate.put(key, value);
        }
    }
    
    @Override
    public void putAll(Map<K, V> map) {
        delegate.putAll(map);
    }
    
    @Override
    public boolean putIfAbsent(K key, V value) {
        return delegate.putIfAbsent(key, value);
    }
    
    @Override
    public void evict(K key) {
        delegate.evict(key);
    }
    
    @Override
    public void evictAll(Set<K> keys) {
        delegate.evictAll(keys);
    }
    
    @Override
    public void clear() {
        delegate.clear();
    }
    
    @Override
    public boolean containsKey(K key) {
        return delegate.containsKey(key);
    }
    
    @Override
    public long size() {
        return delegate.size();
    }
    
    @Override
    public long estimatedSize() {
        return delegate.estimatedSize();
    }
    
    @Override
    public boolean isEmpty() {
        return delegate.isEmpty();
    }
    
    @Override
    public CacheStats getStats() {
        return delegate.getStats();
    }
    
    @Override
    public void cleanUp() {
        delegate.cleanUp();
    }
    
    @Override
    public String getName() {
        return delegate.getName();
    }

    @Override
    public CacheTier getTier() {
        return delegate.getTier();
    }

    @Override
    public void setLoader(CacheLoader<K, V> loader) {
        delegate.setLoader(loader);
    }

    @Override
    public CacheLoader<K, V> getLoader() {
        return cacheLoader;
    }
}