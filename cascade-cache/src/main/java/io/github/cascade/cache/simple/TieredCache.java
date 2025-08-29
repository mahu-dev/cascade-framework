package io.github.cascade.cache.simple;

import io.github.cascade.cache.config.CascadeCacheProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;

/**
 * 多级缓存实现
 * <p>
 * 设计原则：
 * 1. 分层策略：L1(本地) -> L2(分布式) -> Loader(数据源)
 * 2. 读写穿透：写操作同时更新L1和L2，读操作逐层查找
 * 3. 性能优化：优先使用L1，L2作为备份和分布式共享
 * 4. 一致性：支持同步机制保证多级缓存一致性
 * <p>
 * 缓存策略：
 * - GET: L1 -> L2 -> Loader，命中后回填上层缓存
 * - PUT: 同时写入L1和L2
 * - EVICT: 同时清除L1和L2
 *
 * @param <K> 键类型
 * @param <V> 值类型
 * @author cascade
 */
public class TieredCache<K, V> implements Cache<K, V> {

    private static final Logger log = LoggerFactory.getLogger(TieredCache.class);

    private final String cacheName;
    private final CascadeCacheProperties config;
    private final Cache<K, V> l1Cache;    // L1缓存（可选）
    private final Cache<K, V> l2Cache;    // L2缓存（可选）
    private final CacheLoader<K, V> loader; // 数据加载器（可选）
    private final CacheLoaderResolver cacheLoaderResolver; // CacheLoader解析器（可选）
    private final Class<K> keyType;  // 键类型
    private final Class<V> valueType; // 值类型
    private volatile boolean closed = false;

    /**
     * 构造器
     */
    public TieredCache(String cacheName, CascadeCacheProperties config,
                       Cache<K, V> l1Cache, Cache<K, V> l2Cache, CacheLoader<K, V> loader,
                       CacheLoaderResolver cacheLoaderResolver, Class<K> keyType, Class<V> valueType) {
        this.cacheName = cacheName;
        this.config = config;
        this.l1Cache = l1Cache;
        this.l2Cache = l2Cache;
        this.loader = loader;
        this.cacheLoaderResolver = cacheLoaderResolver;
        this.keyType = keyType;
        this.valueType = valueType;

        // 参数校验
        if (l1Cache == null && l2Cache == null) {
            throw new IllegalArgumentException("至少需要提供L1或L2缓存之一");
        }

        log.info("创建多级缓存: {} - L1={}, L2={}, Loader={}, AutoDiscover={}",
                cacheName,
                l1Cache != null ? "Caffeine" : "无",
                l2Cache != null ? "Redis" : "无",
                loader != null ? "已配置" : "无",
                cacheLoaderResolver != null && config.getLoader().isAutoDiscover());
    }

    // ==================== Cache接口实现 ====================

    @Override
    public Optional<V> get(K key) {
        checkNotClosed();

        // 1. 尝试从L1缓存获取
        if (l1Cache != null) {
            Optional<V> l1Value = l1Cache.get(key);
            if (l1Value.isPresent()) {
                log.debug("L1缓存命中: cache={}, key={}", cacheName, key);
                return l1Value;
            }
        }

        // 2. 尝试从L2缓存获取
        if (l2Cache != null) {
            Optional<V> l2Value = l2Cache.get(key);
            if (l2Value.isPresent()) {
                log.debug("L2缓存命中: cache={}, key={}", cacheName, key);
                // 回填L1缓存
                if (l1Cache != null) {
                    l1Cache.put(key, l2Value.get());
                }
                return l2Value;
            }
        }

        // 3. 缓存未命中，尝试使用autoDiscover功能
        if (config.getLoader().isAutoDiscover()) {
            Optional<V> autoLoadedValue = tryAutoLoad(key);
            if (autoLoadedValue.isPresent()) {
                log.debug("AutoDiscover加载成功: cache={}, key={}", cacheName, key);
                return autoLoadedValue;
            }
        }

        log.debug("缓存未命中: cache={}, key={}", cacheName, key);
        return Optional.empty();
    }

    @Override
    public V getOrLoad(K key, Function<K, V> loaderFunc) {
        checkNotClosed();

        // 首先尝试从缓存获取
        Optional<V> cached = get(key);
        if (cached.isPresent()) {
            return cached.get();
        }
        // 使用提供的loader或默认loader加载数据
        Function<K, V> actualLoader = Objects.requireNonNullElseGet(loaderFunc,
                () -> Objects.requireNonNullElseGet(loader, () -> k -> null));
        V loaded = actualLoader.apply(key);
        if (loaded != null) {
            put(key, loaded);
            log.debug("数据已加载并缓存: cache={}, key={}", cacheName, key);
        }

        return loaded;
    }

    @Override
    public CompletableFuture<Optional<V>> getAsync(K key) {
        checkNotClosed();

        // 异步获取L1缓存
        CompletableFuture<Optional<V>> l1Future = l1Cache != null ?
                l1Cache.getAsync(key) :
                CompletableFuture.completedFuture(Optional.empty());

        return l1Future.thenCompose(l1Value -> {
            if (l1Value.isPresent()) {
                log.debug("异步L1缓存命中: cache={}, key={}", cacheName, key);
                return CompletableFuture.completedFuture(l1Value);
            }

            // 异步获取L2缓存
            CompletableFuture<Optional<V>> l2Future = l2Cache != null ?
                    l2Cache.getAsync(key) :
                    CompletableFuture.completedFuture(Optional.empty());

            return l2Future.thenApply(l2Value -> {
                if (l2Value.isPresent()) {
                    log.debug("异步L2缓存命中: cache={}, key={}", cacheName, key);
                    // 异步回填L1缓存
                    if (l1Cache != null) {
                        l1Cache.putAsync(key, l2Value.get());
                    }
                }
                return l2Value;
            });
        }).exceptionally(throwable -> {
            log.error("异步缓存获取失败: cache={}, key={}, error={}",
                    cacheName, key, throwable.getMessage());
            return Optional.empty();
        });
    }

    @Override
    public void put(K key, V value) {
        put(key, value, 0); // 使用默认TTL
    }

    @Override
    public void put(K key, V value, long ttlSeconds) {
        checkNotClosed();

        List<CompletableFuture<Void>> futures = new ArrayList<>();

        // 同时写入L1和L2缓存
        if (l1Cache != null) {
            futures.add(CompletableFuture.runAsync(() ->
                    l1Cache.put(key, value, ttlSeconds)));
        }

        if (l2Cache != null) {
            futures.add(CompletableFuture.runAsync(() ->
                    l2Cache.put(key, value, ttlSeconds)));
        }

        // 等待所有写入完成（可选：可配置为异步）
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            log.debug("多级缓存写入完成: cache={}, key={}, ttl={}s", cacheName, key, ttlSeconds);
        } catch (CompletionException e) {
            log.error("多级缓存写入部分失败: cache={}, key={}, error={}",
                    cacheName, key, e.getMessage());
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

        // 同时从L1和L2删除
        if (l1Cache != null) {
            l1Cache.evict(key);
        }

        if (l2Cache != null) {
            l2Cache.evict(key);
        }

        log.debug("多级缓存删除: cache={}, key={}", cacheName, key);
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

        log.info("多级缓存清空: cache={}", cacheName);
    }

    @Override
    public Map<K, V> getAll(Iterable<K> keys) {
        checkNotClosed();

        List<K> keyList = new ArrayList<>();
        keys.forEach(keyList::add);

        if (keyList.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<K, V> result = new LinkedHashMap<>();
        List<K> missedKeys = new ArrayList<>(keyList);

        // 1. 从L1缓存批量获取
        if (l1Cache != null) {
            Map<K, V> l1Results = l1Cache.getAll(keyList);
            result.putAll(l1Results);
            missedKeys.removeAll(l1Results.keySet());
        }

        // 2. 从L2缓存获取L1未命中的键
        if (l2Cache != null && !missedKeys.isEmpty()) {
            Map<K, V> l2Results = l2Cache.getAll(missedKeys);
            result.putAll(l2Results);
            missedKeys.removeAll(l2Results.keySet());

            // 回填L1缓存
            if (l1Cache != null && !l2Results.isEmpty()) {
                l1Cache.putAll(l2Results);
            }
        }

        // 3. 如果还有未命中的键，尝试使用autoDiscover功能
        if (!missedKeys.isEmpty() && config.getLoader().isAutoDiscover()) {
            Map<K, V> autoLoadedResults = tryAutoLoadAll(missedKeys);
            if (!autoLoadedResults.isEmpty()) {
                result.putAll(autoLoadedResults);
                log.debug("AutoDiscover批量加载成功: cache={}, 加载数={}", cacheName, autoLoadedResults.size());
            }
        }

        log.debug("多级缓存批量获取: cache={}, 请求数={}, 返回数={}",
                cacheName, keyList.size(), result.size());
        return result;
    }

    @Override
    public void putAll(Map<K, V> entries) {
        checkNotClosed();

        if (entries == null || entries.isEmpty()) {
            return;
        }

        // 同时写入L1和L2
        if (l1Cache != null) {
            l1Cache.putAll(entries);
        }

        if (l2Cache != null) {
            l2Cache.putAll(entries);
        }

        log.debug("多级缓存批量写入: cache={}, 条目数={}", cacheName, entries.size());
    }

    @Override
    public boolean containsKey(K key) {
        checkNotClosed();

        // 检查L1
        if (l1Cache != null && l1Cache.containsKey(key)) {
            return true;
        }

        // 检查L2
        if (l2Cache != null && l2Cache.containsKey(key)) {
            return true;
        }

        return false;
    }

    @Override
    public long size() {
        checkNotClosed();

        // 返回L2的大小（更准确），如果没有L2则返回L1大小
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

            log.info("多级缓存已关闭: {}", cacheName);
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
     * 获取缓存加载器
     */
    public Optional<CacheLoader<K, V>> getLoader() {
        return Optional.ofNullable(loader);
    }

    /**
     * 刷新指定键的缓存（重新加载数据）
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
     * 预热缓存（批量加载数据）
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

    /**
     * 获取缓存统计信息
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("cacheName", cacheName);
        stats.put("closed", closed);
        stats.put("hasL1", l1Cache != null);
        stats.put("hasL2", l2Cache != null);
        stats.put("hasLoader", loader != null);

        if (l1Cache != null) {
            stats.put("l1Size", l1Cache.size());
        }

        if (l2Cache != null) {
            stats.put("l2Size", l2Cache.size());
        }

        return stats;
    }

    // ==================== 私有方法 ====================

    private void checkNotClosed() {
        if (closed) {
            throw new IllegalStateException("缓存已关闭: " + cacheName);
        }
    }

    /**
     * 尝试自动加载数据
     */
    private Optional<V> tryAutoLoad(K key) {
        // 1. 优先使用已配置的loader
        if (loader != null) {
            try {
                V loaded = loader.apply(key);
                if (loaded != null) {
                    put(key, loaded); // 加载后存入缓存
                    log.debug("使用配置的CacheLoader加载成功: cache={}, key={}", cacheName, key);
                    return Optional.of(loaded);
                }
            } catch (Exception e) {
                log.warn("使用配置的CacheLoader加载失败: cache={}, key={}, error={}",
                        cacheName, key, e.getMessage());
            }
        }

        // 2. 尝试自动发现CacheLoader
        if (cacheLoaderResolver != null) {
            try {
                CacheLoader<K, V> autoLoader = cacheLoaderResolver.resolveCacheLoader(keyType, valueType);
                if (autoLoader != null) {
                    V loaded = autoLoader.apply(key);
                    if (loaded != null) {
                        put(key, loaded); // 加载后存入缓存
                        log.info("AutoDiscover CacheLoader加载成功: cache={}, key={}, loader={}",
                                cacheName, key, autoLoader.getClass().getSimpleName());
                        return Optional.of(loaded);
                    }
                }
            } catch (Exception e) {
                log.warn("AutoDiscover CacheLoader加载失败: cache={}, key={}, error={}",
                        cacheName, key, e.getMessage());
            }
        }

        return Optional.empty();
    }

    /**
     * 尝试批量自动加载数据
     */
    private Map<K, V> tryAutoLoadAll(List<K> keys) {
        Map<K, V> result = new LinkedHashMap<>();

        // 1. 优先使用已配置的loader
        if (loader != null) {
            try {
                Map<K, V> loaded = loader.loadAll(keys);
                if (!loaded.isEmpty()) {
                    putAll(loaded); // 批量存入缓存
                    result.putAll(loaded);
                    log.debug("使用配置的CacheLoader批量加载成功: cache={}, 加载数={}", cacheName, loaded.size());
                }
            } catch (Exception e) {
                log.warn("使用配置的CacheLoader批量加载失败: cache={}, error={}", cacheName, e.getMessage());
            }
        }

        // 2. 尝试自动发现CacheLoader（对于未成功加载的键）
        if (cacheLoaderResolver != null && result.size() < keys.size()) {
            try {
                CacheLoader<K, V> autoLoader = cacheLoaderResolver.resolveCacheLoader(keyType, valueType);
                if (autoLoader != null) {
                    List<K> remainingKeys = keys.stream()
                            .filter(key -> !result.containsKey(key))
                            .toList();

                    if (!remainingKeys.isEmpty()) {
                        Map<K, V> autoLoaded = autoLoader.loadAll(remainingKeys);
                        if (!autoLoaded.isEmpty()) {
                            putAll(autoLoaded); // 批量存入缓存
                            result.putAll(autoLoaded);
                            log.info("AutoDiscover CacheLoader批量加载成功: cache={}, 加载数={}, loader={}",
                                    cacheName, autoLoaded.size(), autoLoader.getClass().getSimpleName());
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("AutoDiscover CacheLoader批量加载失败: cache={}, error={}", cacheName, e.getMessage());
            }
        }

        return result;
    }

    /**
     * 获取键类型
     */
    public Class<K> getKeyType() {
        return keyType;
    }

    /**
     * 获取值类型
     */
    public Class<V> getValueType() {
        return valueType;
    }

    @Override
    public String toString() {
        return String.format("TieredCache{name=%s, l1=%s, l2=%s, loader=%s, closed=%s}",
                cacheName,
                l1Cache != null ? l1Cache.getClass().getSimpleName() : "null",
                l2Cache != null ? l2Cache.getClass().getSimpleName() : "null",
                loader != null,
                closed);
    }
}