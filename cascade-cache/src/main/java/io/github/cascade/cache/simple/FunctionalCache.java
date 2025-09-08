package io.github.cascade.cache.simple;

import io.github.cascade.cache.config.CascadeCacheProperties;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * 基于函数式编程的缓存实现 - 文档设计思想的完整体现
 * <p>
 * 核心思想：
 * 1. Function驱动的缓存链：用Function组合替代if-else嵌套
 * 2. 装配式管道：通过orElse组合器构建缓存查找流水线  
 * 3. 装饰器模式：通过函数包装添加横切能力（写回、防击穿等）
 * 4. 可测试性：函数化设计便于mock和单元测试
 * 
 * 缓存管道示例：
 * L1 -> L2 -> DataSource
 * 等价于：orElse(loadFromL1, withWriteBack(L1, orElse(loadFromL2, withWriteBack(L2, loadFromSource))))
 * 
 * @param <K> 键类型
 * @param <V> 值类型
 * @author cascade
 */
public class FunctionalCache<K, V> implements Cache<K, V> {
    
    private static final Logger log = LoggerFactory.getLogger(FunctionalCache.class);
    
    private final String cacheName;
    
    @Getter
    private final Class<K> keyType;
    
    @Getter  
    private final Class<V> valueType;
    
    // 核心：缓存查找管道Function
    private final Function<K, V> cachePipeline;
    
    // 可选的组件引用（用于直接操作和生命周期管理）
    private final Cache<K, V> l1Cache;
    private final Cache<K, V> l2Cache; 
    private final CacheLoader<K, V> loader;
    
    private volatile boolean closed = false;
    
    // ==================== 构造器 ====================
    
    /**
     * 标准双缓存构造器
     */
    public FunctionalCache(String cacheName, Class<K> keyType, Class<V> valueType,
                          Cache<K, V> l1Cache, Cache<K, V> l2Cache, CacheLoader<K, V> loader) {
        this(cacheName, keyType, valueType, l1Cache, l2Cache, loader, CacheStrategy.STANDARD);
    }
    
    /**
     * 带策略的构造器
     */
    public FunctionalCache(String cacheName, Class<K> keyType, Class<V> valueType,
                          Cache<K, V> l1Cache, Cache<K, V> l2Cache, CacheLoader<K, V> loader,
                          CacheStrategy strategy) {
        this.cacheName = cacheName;
        this.keyType = keyType;
        this.valueType = valueType;
        this.l1Cache = l1Cache;
        this.l2Cache = l2Cache;
        this.loader = loader;
        
        // 根据策略构建缓存管道
        this.cachePipeline = buildCachePipeline(l1Cache, l2Cache, loader, strategy);
        
        validateConfiguration();
        
        log.info("函数式缓存创建: {} - L1={}, L2={}, Loader={}, Strategy={}", 
                cacheName,
                l1Cache != null ? l1Cache.getClass().getSimpleName() : "无",
                l2Cache != null ? l2Cache.getClass().getSimpleName() : "无", 
                loader != null ? "已配置" : "无",
                strategy);
    }
    
    /**
     * 自定义管道构造器 - 完全的灵活性
     */
    public FunctionalCache(String cacheName, Class<K> keyType, Class<V> valueType,
                          Function<K, V> customPipeline) {
        this.cacheName = cacheName;
        this.keyType = keyType;
        this.valueType = valueType;
        this.cachePipeline = customPipeline;
        this.l1Cache = null;
        this.l2Cache = null;
        this.loader = null;
        
        log.info("自定义管道缓存创建: {}", cacheName);
    }
    
    // ==================== Cache接口实现 ====================
    
    @Override
    public Optional<V> get(K key) {
        checkNotClosed();
        
        try {
            V value = cachePipeline.apply(key);
            return Optional.ofNullable(value);
        } catch (Exception e) {
            log.error("缓存获取失败: cache={}, key={}, error={}", cacheName, key, e.getMessage());
            return Optional.empty();
        }
    }
    
    @Override
    public V getOrLoad(K key, Function<K, V> fallbackLoader) {
        checkNotClosed();
        
        // 首先尝试缓存管道
        Optional<V> cached = get(key);
        if (cached.isPresent()) {
            return cached.get();
        }
        
        // 使用fallback loader
        if (fallbackLoader != null) {
            try {
                V loaded = fallbackLoader.apply(key);
                if (loaded != null) {
                    put(key, loaded);
                    log.debug("Fallback加载成功: cache={}, key={}", cacheName, key);
                }
                return loaded;
            } catch (Exception e) {
                log.warn("Fallback加载失败: cache={}, key={}, error={}", cacheName, key, e.getMessage());
            }
        }
        
        return null;
    }
    
    @Override
    public CompletableFuture<Optional<V>> getAsync(K key) {
        checkNotClosed();
        
        return CompletableFuture.supplyAsync(() -> get(key))
                .exceptionally(throwable -> {
                    log.error("异步缓存获取失败: cache={}, key={}, error={}", 
                             cacheName, key, throwable.getMessage());
                    return Optional.empty();
                });
    }
    
    @Override
    public void put(K key, V value) {
        put(key, value, 0);
    }
    
    @Override
    public void put(K key, V value, long ttlSeconds) {
        checkNotClosed();
        
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        
        // 写入所有可用的缓存层
        if (l1Cache != null) {
            futures.add(CompletableFuture.runAsync(() -> l1Cache.put(key, value, ttlSeconds)));
        }
        if (l2Cache != null) {
            futures.add(CompletableFuture.runAsync(() -> l2Cache.put(key, value, ttlSeconds)));
        }
        
        // 等待所有写入完成
        if (!futures.isEmpty()) {
            try {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                log.debug("多级缓存写入完成: cache={}, key={}", cacheName, key);
            } catch (Exception e) {
                log.error("多级缓存写入失败: cache={}, key={}, error={}", cacheName, key, e.getMessage());
            }
        }
    }
    
    @Override
    public CompletableFuture<Void> putAsync(K key, V value) {
        checkNotClosed();
        
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        
        if (l1Cache != null) {
            futures.add(l1Cache.putAsync(key, value));
        }
        if (l2Cache != null) {
            futures.add(l2Cache.putAsync(key, value));
        }
        
        if (futures.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .exceptionally(throwable -> {
                    log.error("异步多级缓存写入失败: cache={}, key={}, error={}", 
                             cacheName, key, throwable.getMessage());
                    return null;
                });
    }
    
    @Override
    public void evict(K key) {
        checkNotClosed();
        
        if (l1Cache != null) {
            l1Cache.evict(key);
        }
        if (l2Cache != null) {
            l2Cache.evict(key);
        }
        
        log.debug("缓存删除: cache={}, key={}", cacheName, key);
    }
    
    @Override
    public void clear() {
        checkNotClosed();
        
        if (l1Cache != null) {
            l1Cache.clear();
        }
        if (l2Cache != null) {
            l2Cache.clear();
        }
        
        log.info("缓存清空: cache={}", cacheName);
    }
    
    @Override
    public Map<K, V> getAll(Iterable<K> keys) {
        checkNotClosed();
        
        Map<K, V> result = new LinkedHashMap<>();
        for (K key : keys) {
            Optional<V> value = get(key);
            if (value.isPresent()) {
                result.put(key, value.get());
            }
        }
        return result;
    }
    
    @Override
    public void putAll(Map<K, V> entries) {
        checkNotClosed();
        
        if (entries == null || entries.isEmpty()) {
            return;
        }
        
        // 批量写入所有缓存层
        if (l1Cache != null) {
            l1Cache.putAll(entries);
        }
        if (l2Cache != null) {
            l2Cache.putAll(entries);
        }
        
        log.debug("批量写入完成: cache={}, size={}", cacheName, entries.size());
    }
    
    @Override
    public boolean containsKey(K key) {
        checkNotClosed();
        
        // 快速检查：如果能从管道获取到值就说明包含该key
        return get(key).isPresent();
    }
    
    @Override
    public long size() {
        checkNotClosed();
        
        // 返回最有代表性的缓存层大小
        if (l2Cache != null) {
            return l2Cache.size();
        } else if (l1Cache != null) {
            return l1Cache.size();
        }
        return 0;
    }
    
    @Override
    public String getName() {
        return cacheName;
    }
    
    @Override
    public void close() {
        if (!closed) {
            closed = true;
            
            if (l1Cache != null) {
                l1Cache.close();
            }
            if (l2Cache != null) {
                l2Cache.close();
            }
            
            log.info("函数式缓存已关闭: {}", cacheName);
        }
    }
    
    @Override
    public boolean isClosed() {
        return closed;
    }
    
    // ==================== 扩展方法 ====================
    
    /**
     * 获取L1缓存实例
     */
    public Optional<Cache<K, V>> getL1Cache() {
        return Optional.ofNullable(l1Cache);
    }
    
    /**
     * 获取L2缓存实例  
     */
    public Optional<Cache<K, V>> getL2Cache() {
        return Optional.ofNullable(l2Cache);
    }
    
    /**
     * 获取数据加载器
     */
    public Optional<CacheLoader<K, V>> getLoader() {
        return Optional.ofNullable(loader);
    }
    
    /**
     * 获取缓存管道Function（用于测试）
     */
    public Function<K, V> getCachePipeline() {
        return cachePipeline;
    }
    
    /**
     * 刷新指定key的缓存
     */
    public CompletableFuture<V> refresh(K key) {
        if (loader == null) {
            throw new UnsupportedOperationException("未配置缓存加载器，无法刷新");
        }
        
        return loader.loadAsync(key).thenApply(newValue -> {
            if (newValue != null) {
                put(key, newValue);
                log.debug("缓存刷新完成: cache={}, key={}", cacheName, key);
            }
            return newValue;
        });
    }
    
    /**
     * 预热缓存
     */
    public CompletableFuture<Map<K, V>> warmUp(Iterable<K> keys) {
        if (loader == null) {
            throw new UnsupportedOperationException("未配置缓存加载器，无法预热");
        }
        
        return loader.loadAllAsync(keys).thenApply(loadedData -> {
            if (loadedData != null && !loadedData.isEmpty()) {
                putAll(loadedData);
                log.info("缓存预热完成: cache={}, 条目数={}", cacheName, loadedData.size());
            }
            return loadedData;
        });
    }
    
    // ==================== 私有方法 ====================
    
    private void checkNotClosed() {
        if (closed) {
            throw new IllegalStateException("缓存已关闭: " + cacheName);
        }
    }
    
    private void validateConfiguration() {
        if (l1Cache == null && l2Cache == null && loader == null) {
            throw new IllegalArgumentException("至少需要提供一个缓存层或数据加载器");
        }
    }
    
    /**
     * 根据策略构建缓存管道 - 文档思想的核心实现
     */
    private Function<K, V> buildCachePipeline(Cache<K, V> l1, Cache<K, V> l2, 
                                             CacheLoader<K, V> loader, CacheStrategy strategy) {
        return switch (strategy) {
            case STANDARD -> CacheFunctions.buildTieredPipeline(l1, l2, loader);
            case PROTECTED -> CacheFunctions.buildProtectedTieredPipeline(l1, l2, loader);
            case ASYNC_OPTIMIZED -> CacheFunctions.buildAsyncTieredPipeline(l1, l2, loader);
            case L1_ONLY -> l1 != null ? 
                CacheFunctions.loadFromCache(l1) : 
                CacheFunctions.loadFromSource(loader);
            case L2_ONLY -> l2 != null ?
                CacheFunctions.loadFromCache(l2) :
                CacheFunctions.loadFromSource(loader);
            case LOADER_ONLY -> CacheFunctions.loadFromSource(loader);
        };
    }
    
    /**
     * 缓存策略枚举
     */
    public enum CacheStrategy {
        /** 标准双缓存：L1 -> L2 -> Loader，带标准写回 */
        STANDARD,
        
        /** 防击穿双缓存：标准策略 + 并发控制 */
        PROTECTED,
        
        /** 异步优化双缓存：异步写回 + 高级并发控制 */
        ASYNC_OPTIMIZED,
        
        /** 仅L1缓存 */
        L1_ONLY,
        
        /** 仅L2缓存 */
        L2_ONLY,
        
        /** 仅数据加载器，无缓存 */
        LOADER_ONLY
    }
    
    @Override
    public String toString() {
        return String.format("FunctionalCache{name=%s, l1=%s, l2=%s, loader=%s, closed=%s}",
                cacheName,
                l1Cache != null ? l1Cache.getClass().getSimpleName() : "null",
                l2Cache != null ? l2Cache.getClass().getSimpleName() : "null", 
                loader != null,
                closed);
    }
}