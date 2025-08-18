package io.github.cascade.cache.core.management;

import io.github.cascade.cache.api.CacheLoader;
import io.github.cascade.cache.api.LoadingStats;
import io.github.cascade.cache.core.properties.DistributedTieredCacheProperties;
import io.github.cascade.cache.protection.SimplifiedCacheProtectionManager;
import io.github.cascade.cache.stats.LoadingStatsImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Supplier;

/**
 * 缓存加载管理器
 * 专注于数据加载、重试、并发控制等逻辑
 *
 * @param <K> 键类型
 * @param <V> 值类型
 * @author cascade
 */
public class CacheLoadingManager<K, V> {

    private static final Logger log = LoggerFactory.getLogger(CacheLoadingManager.class);

    private final DistributedTieredCacheProperties properties;
    private final LoadingStatsImpl loadingStats;
    private final Set<K> currentlyLoading;
    private final Executor executor;

    private SimplifiedCacheProtectionManager protectionManager;

    public CacheLoadingManager(DistributedTieredCacheProperties properties) {
        this.properties = properties;
        this.loadingStats = new LoadingStatsImpl();
        this.currentlyLoading = ConcurrentHashMap.newKeySet();
        this.executor = ForkJoinPool.commonPool();
    }

    /**
     * 使用CacheLoader加载单个值
     */
    public V loadValue(K key, CacheLoader<K, V> loader) throws Exception {
        if (key == null || loader == null) {
            return null;
        }

        return loadValueDirectly(key, loader);
    }

    public V loadValue(K key, CacheLoader<K, V> loader, Supplier<V> cacheSupplier) {
        if (key == null || loader == null) {
            return null;
        }

        // 优先使用防护机制
        if (protectionManager != null) {
            return loadValueWithProtection(key, loader, cacheSupplier);
        }

        return loadValueDirectly(key, loader);
    }

    /**
     * 使用防护机制加载值
     */
    private V loadValueWithProtection(K key, CacheLoader<K, V> loader, Supplier<V> cacheSupplier) {
        String keyStr = String.valueOf(key);

        try {
            return protectionManager.executeWithProtection(
                    keyStr,
                    cacheSupplier,
                    () -> loadValueDirectly(key, loader)
            );
        } catch (SimplifiedCacheProtectionManager.CacheProtectionException e) {
            log.warn("Cache protection failed for key {}: {}", key, e.getMessage());
            return loadValueDirectly(key, loader);
        }
    }

    /**
     * 直接加载值（带并发控制）
     */
    private V loadValueDirectly(K key, CacheLoader<K, V> loader) {
        // 检查是否已在加载中
        if (currentlyLoading.contains(key)) {
            return handleConcurrentLoading(key);
        }

        // 标记为正在加载
        if (!currentlyLoading.add(key)) {
            return handleConcurrentLoading(key);
        }

        loadingStats.recordActiveLoadStart();
        long startTime = System.nanoTime();

        try {
            V value = executeLoadWithRetry(key, loader);

            if (value != null) {
                loadingStats.recordLoadSuccess();

                // 添加到布隆过滤器
                if (protectionManager != null) {
                    protectionManager.addToBloomFilter(String.valueOf(key));
                }
            }

            return value;
        } catch (Exception e) {
            loadingStats.recordLoadFailure();
            log.error("Failed to load value for key: {}", key, e);
            throw new RuntimeException("Failed to load value for key: " + key, e);
        } finally {
            currentlyLoading.remove(key);
            loadingStats.recordActiveLoadEnd();
            loadingStats.recordLoadTime(System.nanoTime() - startTime);
        }
    }

    /**
     * 带重试的加载执行
     */
    private V executeLoadWithRetry(K key, CacheLoader<K, V> loader) throws Exception {
        int maxRetries = properties.getLoading().getMaxRetries();
        Exception lastException = null;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                return loader.load(key);
            } catch (Exception e) {
                lastException = e;
                if (attempt < maxRetries) {
                    try {
                        Thread.sleep(properties.getLoading().getRetryDelay().toMillis());
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Loading interrupted", ie);
                    }
                    log.warn("Load attempt {} failed for key {}, retrying...", attempt + 1, key);
                }
            }
        }

        throw lastException;
    }

    /**
     * 处理并发加载情况
     */
    private V handleConcurrentLoading(K key) {
        // 简化处理：返回null，让调用方重试或降级
        log.debug("Key {} is currently being loaded by another thread", key);
        return null;
    }

    /**
     * 批量加载值
     */
    public Map<K, V> loadValues(Set<K> keys, CacheLoader<K, V> loader) {
        if (keys == null || keys.isEmpty() || loader == null) {
            return Map.of();
        }

        if (!properties.getLoading().isEnableBatchLoading()) {
            return loadValuesIndividually(keys, loader);
        }

        return loadValuesBatch(keys, loader);
    }

    /**
     * 逐个加载值
     */
    private Map<K, V> loadValuesIndividually(Set<K> keys, CacheLoader<K, V> loader) {
        Map<K, V> result = new ConcurrentHashMap<>();

        keys.parallelStream().forEach(key -> {
            try {
                V value = loadValueDirectly(key, loader);
                if (value != null) {
                    result.put(key, value);
                }
            } catch (Exception e) {
                log.warn("Failed to load key: {}", key, e);
            }
        });

        return result;
    }

    /**
     * 批量加载值
     */
    private Map<K, V> loadValuesBatch(Set<K> keys, CacheLoader<K, V> loader) {
        // 过滤正在加载的键
        Set<K> keysToLoad = keys.stream()
                .filter(key -> !currentlyLoading.contains(key))
                .collect(java.util.stream.Collectors.toSet());

        if (keysToLoad.isEmpty()) {
            return Map.of();
        }

        // 标记为正在加载
        keysToLoad.forEach(key -> {
            currentlyLoading.add(key);
            loadingStats.recordActiveLoadStart();
        });

        long startTime = System.nanoTime();

        try {
            Map<K, V> result = executeLoadAllWithRetry(keysToLoad, loader);
            loadingStats.recordLoadSuccess();
            return result != null ? result : Map.of();
        } catch (Exception e) {
            loadingStats.recordLoadFailure();
            log.error("Failed to batch load keys: {}", keysToLoad, e);
            return Map.of();
        } finally {
            keysToLoad.forEach(key -> {
                currentlyLoading.remove(key);
                loadingStats.recordActiveLoadEnd();
            });
            loadingStats.recordLoadTime(System.nanoTime() - startTime);
        }
    }

    /**
     * 带重试的批量加载
     */
    private Map<K, V> executeLoadAllWithRetry(Set<K> keys, CacheLoader<K, V> loader) throws Exception {
        int maxRetries = properties.getLoading().getMaxRetries();
        Exception lastException = null;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                return loader.loadAll(keys);
            } catch (Exception e) {
                lastException = e;
                if (attempt < maxRetries) {
                    try {
                        Thread.sleep(properties.getLoading().getRetryDelay().toMillis());
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Batch loading interrupted", ie);
                    }
                    log.warn("Batch load attempt {} failed, retrying...", attempt + 1);
                }
            }
        }

        throw lastException;
    }

    /**
     * 取消加载
     */
    public boolean cancelLoading(K key) {
        if (currentlyLoading.remove(key)) {
            loadingStats.recordLoadCancel();
            loadingStats.recordActiveLoadEnd();
            return true;
        }
        return false;
    }

    /**
     * 取消所有加载
     */
    public int cancelAllLoading() {
        int count = currentlyLoading.size();
        currentlyLoading.clear();
        return count;
    }

    /**
     * 检查是否正在加载
     */
    public boolean isLoading(K key) {
        return currentlyLoading.contains(key);
    }

    /**
     * 获取正在加载的键
     */
    public Set<K> getLoadingKeys() {
        return Set.copyOf(currentlyLoading);
    }

    /**
     * 获取加载统计
     */
    public LoadingStats getLoadingStats() {
        return loadingStats;
    }

    /**
     * 设置防护管理器
     */
    public void setProtectionManager(SimplifiedCacheProtectionManager protectionManager) {
        this.protectionManager = protectionManager;
    }
}