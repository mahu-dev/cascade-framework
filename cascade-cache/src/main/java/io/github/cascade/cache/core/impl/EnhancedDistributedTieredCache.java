package io.github.cascade.cache.core.impl;

import io.github.cascade.cache.annotation.AutoConfigureLoader;
import io.github.cascade.cache.api.*;
import io.github.cascade.cache.core.management.CacheLoadingManager;
import io.github.cascade.cache.core.management.CacheLoadingManagerFactory;
import io.github.cascade.cache.core.management.CacheSyncCoordinator;
import io.github.cascade.cache.core.operations.TieredCacheOperations;
import io.github.cascade.cache.core.properties.DistributedTieredCacheProperties;
import io.github.cascade.cache.core.statistics.EnhancedCacheStats;
import io.github.cascade.cache.sync.CacheSyncEvent;
import io.github.cascade.cache.sync.CacheSyncListener;
import io.github.cascade.cache.sync.CacheSyncManager;
import io.github.cascade.cache.tier.LocalTier;
import io.github.cascade.cache.tier.RemoteTier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationContext;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Function;


/**
 * 优雅重构版的分布式分层缓存
 * 采用组合模式，职责分离，更易维护和测试
 *
 * @param <K> 键类型
 * @param <V> 值类型
 * @author cascade
 */
public class EnhancedDistributedTieredCache<K, V>
        implements TieredCache<K, V>, LoadingCache<K, V>, AsyncCache<K, V>,
        CacheSyncListener, InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(EnhancedDistributedTieredCache.class);

    // 核心组件
    private final String name;
    private final TieredCacheOperations<K, V> cacheOperations;
    private final CacheLoadingManager<K, V> loadingManager;
    private final CacheSyncCoordinator syncCoordinator;
    private final Executor executor;

    // 配置
    private final DistributedTieredCacheProperties properties;
    private CacheLoader<K, V> cacheLoader;
    private ApplicationContext applicationContext;

    // 类型信息
    private final Class<K> keyType;
    private final Class<V> valueType;

    /**
     * 构造函数
     */
    public EnhancedDistributedTieredCache(String name,
                                          LocalTier<K, V> l1Cache,
                                          RemoteTier<K, V> l2Cache,
                                          DistributedTieredCacheProperties properties,
                                          CacheLoadingManagerFactory loadingManagerFactory,
                                          CacheSyncManager syncManager) {
        this.name = name;
        this.properties = properties;
        this.executor = ForkJoinPool.commonPool();
        this.keyType = null;    // 明确初始化为null
        this.valueType = null;  // 明确初始化为null

        // 初始化核心组件
        this.cacheOperations = new TieredCacheOperations<>(l1Cache, l2Cache);
        this.loadingManager = loadingManagerFactory.createLoadingManager();
        this.syncCoordinator = new CacheSyncCoordinator(name, syncManager, properties.isEnableSync());
    }

    public EnhancedDistributedTieredCache(String name,
                                          LocalTier<K, V> l1Cache,
                                          RemoteTier<K, V> l2Cache,
                                          DistributedTieredCacheProperties properties,
                                          CacheLoadingManagerFactory loadingManagerFactory,
                                          CacheSyncManager syncManager,
                                          ApplicationContext applicationContext) {
        this(name, l1Cache, l2Cache, properties, loadingManagerFactory, syncManager, applicationContext, null, null);
    }

    /**
     * 带类型信息的构造函数
     */
    public EnhancedDistributedTieredCache(String name,
                                          LocalTier<K, V> l1Cache,
                                          RemoteTier<K, V> l2Cache,
                                          DistributedTieredCacheProperties properties,
                                          CacheLoadingManagerFactory loadingManagerFactory,
                                          CacheSyncManager syncManager,
                                          ApplicationContext applicationContext,
                                          Class<K> keyType,
                                          Class<V> valueType) {
        this.name = name;
        this.properties = properties;
        this.executor = ForkJoinPool.commonPool();
        this.keyType = keyType;
        this.valueType = valueType;

        // 初始化核心组件
        this.cacheOperations = new TieredCacheOperations<>(l1Cache, l2Cache);
        this.loadingManager = loadingManagerFactory.createLoadingManager();
        this.syncCoordinator = new CacheSyncCoordinator(name, syncManager, properties.isEnableSync());
        this.applicationContext = applicationContext;
    }

    // ==================== Spring生命周期 ====================


    @Override
    public void afterPropertiesSet() throws Exception {
        // Bean初始化完成后自动调用
        if (properties.isEnableSync()) {
            syncCoordinator.initialize(this);
        }
        log.info("Enhanced distributed tiered cache '{}' initialized", name);
    }

    @Override
    public void destroy() throws Exception {
        // Bean销毁时自动调用
        if (properties.isEnableSync()) {
            syncCoordinator.shutdown();
        }
        cacheOperations.clearAllTiers();
        log.info("Enhanced distributed tiered cache '{}' destroyed", name);
    }

    // ==================== Cache接口实现 ====================

    @Override
    public V get(K key) {
        if (key == null) {
            return null;
        }

        // 1. 从多级缓存获取
        V value = cacheOperations.getFromTiers(key);
        if (value != null) {
            return value;
        }
        if (cacheLoader == null) {
            // 尝试从Spring容器中自动发现并注入CacheLoader
            tryAutoDiscoverCacheLoader();
        }

        // 2. 如果有CacheLoader，尝试加载
        if (cacheLoader != null) {
            log.info("从Loader 加载....");
            return loadingManager.loadValue(key, cacheLoader, () -> cacheOperations.getFromTiers(key));
        }

        return null;
    }

    @Override
    public V get(K key, Function<K, V> loader) {
        V value = get(key);
        if (value == null && loader != null) {
            value = loader.apply(key);
            if (value != null) {
                put(key, value);
            }
        }
        return value;
    }

    @Override
    public Map<K, V> getAll(Set<K> keys) {
        if (keys == null || keys.isEmpty()) {
            return Map.of();
        }

        Map<K, V> result = cacheOperations.getAllFromTiers(keys);

        // 如果有缺失的键且有CacheLoader，批量加载
        if (result.size() < keys.size() && cacheLoader != null) {
            Set<K> missingKeys = keys.stream()
                    .filter(key -> !result.containsKey(key))
                    .collect(java.util.stream.Collectors.toSet());

            Map<K, V> loadedValues = loadingManager.loadValues(missingKeys, cacheLoader);
            if (!loadedValues.isEmpty()) {
                cacheOperations.putAllToTiers(loadedValues);
                result.putAll(loadedValues);
            }
        }

        return result;
    }

    @Override
    public Map<K, V> getAll(Set<K> keys, Function<Set<K>, Map<K, V>> loader) {
        Map<K, V> result = getAll(keys);
        Set<K> missingKeys = keys.stream()
                .filter(key -> !result.containsKey(key))
                .collect(java.util.stream.Collectors.toSet());

        if (!missingKeys.isEmpty() && loader != null) {
            Map<K, V> loaded = loader.apply(missingKeys);
            if (loaded != null) {
                putAll(loaded);
                result.putAll(loaded);
            }
        }
        return result;
    }

    @Override
    public void put(K key, V value) {
        if (key == null || value == null) {
            return;
        }

        cacheOperations.putToAllTiers(key, value, null);

        // 发布同步事件
        if (properties.isEnableSync()) {
            syncCoordinator.publishPutEvent(key, value);
        }
    }

    public void put(K key, V value, Duration ttl) {
        if (key == null || value == null) {
            return;
        }

        cacheOperations.putToAllTiers(key, value, ttl);

        // 发布同步事件
        if (properties.isEnableSync()) {
            syncCoordinator.publishPutEvent(key, value);
        }
    }

    @Override
    public void putAll(Map<K, V> map) {
        if (map == null || map.isEmpty()) {
            return;
        }

        cacheOperations.putAllToTiers(map);

        // 发布同步事件
        if (properties.isEnableSync()) {
            map.forEach(syncCoordinator::publishPutEvent);
        }
    }

    @Override
    public boolean putIfAbsent(K key, V value) {
        if (key == null || value == null) {
            return false;
        }

        if (cacheOperations.containsKeyInAnyTier(key)) {
            return false;
        }

        put(key, value);
        return true;
    }

    @Override
    public void evict(K key) {
        if (key == null) {
            return;
        }

        cacheOperations.evictFromAllTiers(key);

        if (properties.isEnableSync()) {
            syncCoordinator.publishEvictEvent(key);
        }
    }

    @Override
    public void evictAll(Set<K> keys) {
        if (keys == null || keys.isEmpty()) {
            return;
        }

        cacheOperations.evictAllFromTiers(keys);

        if (properties.isEnableSync()) {
            syncCoordinator.publishEvictAllEvent(keys);
        }
    }

    @Override
    public void clear() {
        cacheOperations.clearAllTiers();

        if (properties.isEnableSync()) {
            syncCoordinator.publishClearEvent();
        }
    }

    @Override
    public boolean containsKey(K key) {
        return cacheOperations.containsKeyInAnyTier(key);
    }

    @Override
    public long size() {
        return cacheOperations.totalSize();
    }

    @Override
    public long estimatedSize() {
        return size();
    }

    @Override
    public boolean isEmpty() {
        return cacheOperations.isEmpty();
    }

    @Override
    public void refresh(K key) {
        if (key == null || cacheLoader == null) {
            return;
        }

        evict(key); // 先删除
        get(key);   // 重新加载

        if (properties.isEnableSync()) {
            syncCoordinator.publishRefreshEvent(key);
        }
    }

    @Override
    public CompletableFuture<Void> refreshAsync(K key) {
        return CompletableFuture.runAsync(() -> refresh(key), executor);
    }

    @Override
    public CacheStats getStats() {
        return cacheOperations.getStats(CacheTier.L1);
    }

    /**
     * 获取增强的缓存统计信息
     */
    public EnhancedCacheStats getEnhancedStats() {
        Map<CacheTier, CacheStats> allStats = cacheOperations.getAllStats();
        LoadingStats loadingStats = loadingManager.getLoadingStats();

        return EnhancedCacheStats.builder()
                .cacheName(name)
                .l1Stats(allStats.get(CacheTier.L1))
                .l2Stats(allStats.get(CacheTier.L2))
                .loadingStats(loadingStats)
                .syncEnabled(properties.isEnableSync())
                .protectionEnabled(properties.getProtection().isEnableBloomFilter())
                .build();
    }

    @Override
    public void cleanUp() {
        cacheOperations.cleanUp();
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public CacheTier getTier() {
        return CacheTier.MULTI_LEVEL;
    }

    @Override
    public void setLoader(CacheLoader<K, V> loader) {
        this.cacheLoader = loader;
    }

    @Override
    public CacheLoader<K, V> getLoader() {
        return cacheLoader;
    }

    // ==================== TieredCache接口实现 ====================

    @Override
    public V get(K key, CacheTier tier) {
        return cacheOperations.get(key, tier);
    }

    @Override
    public void put(K key, V value, CacheTier tier) {
        cacheOperations.put(key, value, tier);
    }

    @Override
    public void evict(K key, CacheTier tier) {
        cacheOperations.evict(key, tier);
    }

    @Override
    public void clear(CacheTier tier) {
        cacheOperations.clear(tier);
    }

    @Override
    public boolean containsKey(K key, CacheTier tier) {
        return cacheOperations.containsKey(key, tier);
    }

    @Override
    public long size(CacheTier tier) {
        return cacheOperations.size(tier);
    }

    @Override
    public CacheStats getStats(CacheTier tier) {
        return cacheOperations.getStats(tier);
    }

    @Override
    public void promote(K key) {
        cacheOperations.promote(key);
    }

    @Override
    public void promoteAll(Set<K> keys) {
        cacheOperations.promoteAll(keys);
    }

    @Override
    public void demote(K key) {
        cacheOperations.demote(key);
    }

    @Override
    public void demoteAll(Set<K> keys) {
        cacheOperations.demoteAll(keys);
    }

    @Override
    public void sync(K key) {
        cacheOperations.sync(key);
    }

    @Override
    public void syncAll(Set<K> keys) {
        keys.forEach(cacheOperations::sync);
    }

    @Override
    public Map<CacheTier, CacheStats> getAllStats() {
        return cacheOperations.getAllStats();
    }

    @Override
    public Set<CacheTier> getSupportedTiers() {
        return cacheOperations.getSupportedTiers();
    }

    @Override
    public boolean isTierAvailable(CacheTier tier) {
        return cacheOperations.isTierAvailable(tier);
    }

    // ==================== LoadingCache接口实现 ====================

    @Override
    public V getUnchecked(K key) {
        V value = get(key);
        if (value == null && cacheLoader != null) {
            try {
                value = cacheLoader.load(key);
                if (value != null) {
                    put(key, value);
                }
            } catch (Exception e) {
                throw new RuntimeException("Failed to load value for key: " + key, e);
            }
        }
        return value;
    }

    @Override
    public Map<K, V> getAllUnchecked(Set<K> keys) {
        return getAll(keys);
    }

    @Override
    public void refreshAll(Set<K> keys) {
        if (keys != null) {
            keys.forEach(this::refresh);
        }
    }

    @Override
    public CompletableFuture<Void> refreshAllAsync(Set<K> keys) {
        return CompletableFuture.runAsync(() -> refreshAll(keys), executor);
    }

    @Override
    public void preload(K key) {
        getUnchecked(key);
    }

    @Override
    public void preloadAll(Set<K> keys) {
        getAllUnchecked(keys);
    }

    @Override
    public CompletableFuture<Void> preloadAsync(K key) {
        return CompletableFuture.runAsync(() -> preload(key), executor);
    }

    @Override
    public CompletableFuture<Void> preloadAllAsync(Set<K> keys) {
        return CompletableFuture.runAsync(() -> preloadAll(keys), executor);
    }

    @Override
    public LoadingStats getLoadingStats() {
        return loadingManager.getLoadingStats();
    }

    @Override
    public boolean isLoading(K key) {
        return loadingManager.isLoading(key);
    }

    @Override
    public boolean cancelLoading(K key) {
        return loadingManager.cancelLoading(key);
    }

    @Override
    public Set<K> getLoadingKeys() {
        return loadingManager.getLoadingKeys();
    }

    @Override
    public int cancelAllLoading() {
        return loadingManager.cancelAllLoading();
    }

    @Override
    public Cache<K, V> asCache() {
        return this;
    }

    @Override
    public AsyncCache<K, V> asAsyncCache() {
        return this;
    }

    // ==================== AsyncCache接口实现 ====================

    @Override
    public CompletableFuture<V> getAsync(K key) {
        return CompletableFuture.supplyAsync(() -> get(key), executor);
    }

    @Override
    public CompletableFuture<V> getAsync(K key, Function<K, CompletableFuture<V>> loader) {
        return getAsync(key).thenCompose(value -> {
            if (value == null && loader != null) {
                return loader.apply(key).thenApply(loadedValue -> {
                    if (loadedValue != null) {
                        put(key, loadedValue);
                    }
                    return loadedValue;
                });
            }
            return CompletableFuture.completedFuture(value);
        });
    }

    @Override
    public CompletableFuture<Map<K, V>> getAllAsync(Set<K> keys) {
        return CompletableFuture.supplyAsync(() -> getAll(keys), executor);
    }

    @Override
    public CompletableFuture<Map<K, V>> getAllAsync(Set<K> keys, Function<Set<K>, CompletableFuture<Map<K, V>>> loader) {
        return getAllAsync(keys).thenCompose(result -> {
            Set<K> missingKeys = keys.stream()
                    .filter(key -> !result.containsKey(key))
                    .collect(java.util.stream.Collectors.toSet());

            if (!missingKeys.isEmpty() && loader != null) {
                return loader.apply(missingKeys).thenApply(loaded -> {
                    if (loaded != null) {
                        putAll(loaded);
                        result.putAll(loaded);
                    }
                    return result;
                });
            }
            return CompletableFuture.completedFuture(result);
        });
    }

    @Override
    public CompletableFuture<Void> putAsync(K key, V value) {
        return CompletableFuture.runAsync(() -> put(key, value), executor);
    }

    @Override
    public CompletableFuture<Void> putAsync(K key, V value, Duration ttl) {
        return CompletableFuture.runAsync(() -> put(key, value, ttl), executor);
    }

    @Override
    public CompletableFuture<Void> putAllAsync(Map<K, V> map) {
        return CompletableFuture.runAsync(() -> putAll(map), executor);
    }

    @Override
    public CompletableFuture<Boolean> putIfAbsentAsync(K key, V value) {
        return CompletableFuture.supplyAsync(() -> putIfAbsent(key, value), executor);
    }

    @Override
    public CompletableFuture<Void> evictAsync(K key) {
        return CompletableFuture.runAsync(() -> evict(key), executor);
    }

    @Override
    public CompletableFuture<Void> evictAllAsync(Set<K> keys) {
        return CompletableFuture.runAsync(() -> evictAll(keys), executor);
    }

    @Override
    public CompletableFuture<Void> clearAsync() {
        return CompletableFuture.runAsync(this::clear, executor);
    }

    @Override
    public CompletableFuture<Boolean> containsKeyAsync(K key) {
        return CompletableFuture.supplyAsync(() -> containsKey(key), executor);
    }

    @Override
    public CompletableFuture<Long> sizeAsync() {
        return CompletableFuture.supplyAsync(this::size, executor);
    }

    @Override
    public CompletableFuture<CacheStats> getStatsAsync() {
        return CompletableFuture.supplyAsync(this::getStats, executor);
    }

    @Override
    public CompletableFuture<Void> cleanUpAsync() {
        return CompletableFuture.runAsync(this::cleanUp, executor);
    }

    @Override
    public CompletableFuture<V> refreshAsync(K key, Function<K, CompletableFuture<V>> loader) {
        evict(key);
        if (loader != null) {
            return loader.apply(key).thenApply(value -> {
                if (value != null) {
                    put(key, value);
                }
                return value;
            });
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public Cache<K, V> synchronous() {
        return this;
    }

    // ==================== CacheSyncListener接口实现 ====================

    @Override
    public String getListenerId() {
        return "enhanced-cache-" + name;
    }

    @Override
    public boolean shouldHandle(CacheSyncEvent event) {
        return name.equals(event.getCacheId());
    }

    @Override
    public void onSyncEvent(CacheSyncEvent event) {
        syncCoordinator.handleSyncEvent(event, cacheOperations);
    }

    // ==================== 私有方法 ====================

    /**
     * 尝试从Spring容器中自动发现CacheLoader
     */
    private void tryAutoDiscoverCacheLoader() {
        if (applicationContext == null) {
            log.debug("ApplicationContext not available, skipping auto-discovery of CacheLoader");
            return;
        }

        try {
            // 1. 尝试按照泛型类型匹配
            Map<String, CacheLoader> loaderBeans = applicationContext.getBeansOfType(CacheLoader.class);

            for (Map.Entry<String, CacheLoader> entry : loaderBeans.entrySet()) {
                String beanName = entry.getKey();
                CacheLoader<K, V> loader = entry.getValue();

                if (isLoaderTypeMatched(loader)) {
                    @SuppressWarnings("unchecked")
                    CacheLoader<K, V> typedLoader = loader;
                    this.cacheLoader = typedLoader;
                    log.info("Auto-discovered CacheLoader '{}' for cache '{}'", beanName, name);
                    return;
                }
            }

            // 2. 如果没有找到精确匹配的，尝试查找有@AutoConfigureLoader注解的
            for (Map.Entry<String, CacheLoader> entry : loaderBeans.entrySet()) {
                String beanName = entry.getKey();
                CacheLoader<?, ?> loader = entry.getValue();

                if (loader.getClass().isAnnotationPresent(AutoConfigureLoader.class)) {
                    @SuppressWarnings("unchecked")
                    CacheLoader<K, V> typedLoader = (CacheLoader<K, V>) loader;
                    this.cacheLoader = typedLoader;
                    log.info("Auto-configured CacheLoader '{}' with @AutoConfigureLoader for cache '{}'", beanName, name);
                    return;
                }
            }

            log.debug("No matching CacheLoader found for cache '{}' with types K={}, V={}",
                    name, getKeyType(), getValueType());

        } catch (Exception e) {
            log.warn("Failed to auto-discover CacheLoader for cache '{}': {}", name, e.getMessage());
        }
    }

    /**
     * 检查CacheLoader的泛型类型是否匹配
     */
    private boolean isLoaderTypeMatched(CacheLoader<K, V> loader) {
        try {
            // 获取CacheLoader接口的泛型参数
            Type[] interfaces = loader.getClass().getGenericInterfaces();

            for (Type interfaceType : interfaces) {
                if (interfaceType instanceof ParameterizedType) {
                    ParameterizedType paramType = (ParameterizedType) interfaceType;
                    if (CacheLoader.class.equals(paramType.getRawType())) {
                        Type[] typeArgs = paramType.getActualTypeArguments();
                        if (typeArgs.length == 2) {
                            // 简单的类型匹配 - 实际项目中可能需要更复杂的匹配逻辑
                            String loaderKeyType = typeArgs[0].getTypeName();
                            String loaderValueType = typeArgs[1].getTypeName();
                            String cacheKeyType = getKeyType();
                            String cacheValueType = getValueType();

                            return loaderKeyType.equals(cacheKeyType) && loaderValueType.equals(cacheValueType);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Failed to check type matching for CacheLoader: {}", e.getMessage());
        }
        return false;
    }

    /**
     * 获取缓存的Key类型名称
     */
    private String getKeyType() {
        if (keyType != null) {
            return keyType.getName();
        }

        // 如果没有明确的类型信息，尝试从运行时信息推断
        try {
            // 从泛型超类获取类型信息（适用于匿名子类）
            Type superClass = this.getClass().getGenericSuperclass();
            if (superClass instanceof ParameterizedType paramType) {
                Type[] typeArgs = paramType.getActualTypeArguments();
                if (typeArgs.length >= 1) {
                    return typeArgs[0].getTypeName();
                }
            }
        } catch (Exception e) {
            log.debug("Failed to infer key type from generic superclass: {}", e.getMessage());
        }

        // 默认返回Object类型
        return Object.class.getName();
    }

    /**
     * 获取缓存的Value类型名称
     */
    private String getValueType() {
        if (valueType != null) {
            return valueType.getName();
        }

        // 如果没有明确的类型信息，尝试从运行时信息推断
        try {
            // 从泛型超类获取类型信息（适用于匿名子类）
            Type superClass = this.getClass().getGenericSuperclass();
            if (superClass instanceof ParameterizedType paramType) {
                Type[] typeArgs = paramType.getActualTypeArguments();
                if (typeArgs.length >= 2) {
                    return typeArgs[1].getTypeName();
                }
            }
        } catch (Exception e) {
            log.debug("Failed to infer value type from generic superclass: {}", e.getMessage());
        }

        // 默认返回Object类型
        return Object.class.getName();
    }

    /**
     * 获取Key的Class对象
     */
    public Class<K> getKeyClass() {
        return keyType;
    }

    /**
     * 获取Value的Class对象
     */
    public Class<V> getValueClass() {
        return valueType;
    }
}