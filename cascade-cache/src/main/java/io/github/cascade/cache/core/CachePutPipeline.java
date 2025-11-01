package io.github.cascade.cache.core;

import io.github.cascade.cache.api.Cache;
import io.github.cascade.cache.common.FunctionalCacheOperations;

import static io.github.cascade.cache.common.FunctionalCacheOperations.safely;

/**
 * 缓存存储管道
 * <p>
 * 提供链式调用的缓存存储操作，支持条件执行和TTL设置。
 * 该类从FunctionalCacheOperations的内部类提取而来，
 * 解决SonarQube关于内部类过长的警告。
 *
 * @param <K> 键类型
 * @param <V> 值类型
 * @author cascade
 */
public class CachePutPipeline<K, V> {

    private final Cache<K, V> cache;
    private final K key;
    private final V value;

    public CachePutPipeline(Cache<K, V> cache, K key, V value) {
        this.cache = cache;
        this.key = key;
        this.value = value;
    }

    public FunctionalCacheOperations.Result<Void> execute() {
        return safely(() -> {
            cache.put(key, value);
            return null;
        });
    }

    public FunctionalCacheOperations.Result<Void> executeWithTtl(long ttlSeconds) {
        return safely(() -> {
            cache.put(key, value, ttlSeconds);
            return null;
        });
    }

    public CachePutPipeline<K, V> whenNotNull() {
        return value != null ? this : new CachePutPipeline<>(cache, key, null) {
            @Override
            public FunctionalCacheOperations.Result<Void> execute() {
                // Skip operation
                return FunctionalCacheOperations.Result.success(null);
            }

            @Override
            public FunctionalCacheOperations.Result<Void> executeWithTtl(long ttlSeconds) {
                // Skip operation  
                return FunctionalCacheOperations.Result.success(null);
            }
        };
    }
}