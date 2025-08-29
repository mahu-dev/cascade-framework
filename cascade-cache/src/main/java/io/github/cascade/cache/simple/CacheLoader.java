package io.github.cascade.cache.simple;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * 缓存加载器接口
 * <p>
 * 设计原则：
 * 1. 函数式：继承Function接口，支持Lambda
 * 2. 异步支持：提供异步加载方法
 * 3. 批量加载：支持批量操作优化性能
 * 4. 异常处理：优雅的错误处理机制
 *
 * @param <K> 键类型
 * @param <V> 值类型
 * @author cascade
 */
@FunctionalInterface
public interface CacheLoader<K, V> extends Function<K, V> {


    // ==================== 核心加载方法 ====================

    /**
     * 加载单个值（继承自Function）
     */
    @Override
    V apply(K key);

    /**
     * 异步加载单个值
     */
    default CompletableFuture<V> loadAsync(K key) {
        return CompletableFuture.supplyAsync(() -> apply(key));
    }

    /**
     * 批量加载（可选实现，默认逐个调用）
     */
    default Map<K, V> loadAll(Iterable<K> keys) {
        return keys instanceof java.util.Collection<K> coll ?
                coll.stream().collect(java.util.stream.Collectors.toMap(
                        k -> k,
                        this::apply,
                        (v1, v2) -> v2,
                        java.util.LinkedHashMap::new)) :
                java.util.stream.StreamSupport.stream(keys.spliterator(), false)
                        .collect(java.util.stream.Collectors.toMap(
                                k -> k,
                                this::apply,
                                (v1, v2) -> v2,
                                java.util.LinkedHashMap::new));
    }

    /**
     * 异步批量加载
     */
    default CompletableFuture<Map<K, V>> loadAllAsync(Iterable<K> keys) {
        return CompletableFuture.supplyAsync(() -> loadAll(keys));
    }

    // ==================== 便捷静态方法 ====================

    /**
     * 创建简单的同步加载器
     */
    static <K, V> CacheLoader<K, V> of(Function<K, V> loader) {
        return loader::apply;
    }

    /**
     * 创建异步加载器
     */
    static <K, V> CacheLoader<K, V> async(Function<K, CompletableFuture<V>> asyncLoader) {
        return key -> {
            try {
                return asyncLoader.apply(key).join();
            } catch (Exception e) {
                throw new RuntimeException("异步加载失败: " + key, e);
            }
        };
    }

    /**
     * 创建批量优化的加载器
     */
    static <K, V> CacheLoader<K, V> batched(Function<Iterable<K>, Map<K, V>> batchLoader) {
        return new CacheLoader<K, V>() {
            @Override
            public V apply(K key) {
                Map<K, V> result = batchLoader.apply(java.util.Collections.singletonList(key));
                return result.get(key);
            }

            @Override
            public Map<K, V> loadAll(Iterable<K> keys) {
                return batchLoader.apply(keys);
            }
        };
    }
}