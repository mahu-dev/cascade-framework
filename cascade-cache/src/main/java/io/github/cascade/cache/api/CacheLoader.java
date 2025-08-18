package io.github.cascade.cache.api;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * 缓存加载器接口，定义如何加载缓存值
 *
 * @param <K> 缓存键类型
 * @param <V> 缓存值类型
 * @author cascade
 */
public interface CacheLoader<K, V> {

    /**
     * 加载单个缓存值
     *
     * @param key 缓存键
     * @return 缓存值
     * @throws Exception 如果加载失败
     */
    V load(K key) throws Exception;

    /**
     * 批量加载缓存值
     * 默认实现会逐个调用load方法
     *
     * @param keys 缓存键集合
     * @return 键值对映射
     * @throws Exception 如果加载失败
     */
    default Map<K, V> loadAll(Set<K> keys) throws Exception {
        // 默认实现：逐个调用load方法
        Map<K, V> result = new HashMap<>();
        for (K key : keys) {
            try {
                V value = load(key);
                if (value != null) {
                    result.put(key, value);
                }
            } catch (Exception e) {
                // 记录异常但继续处理其他键
                handleLoadException(key, e);
            }
        }
        return result;
    }

    /**
     * 异步加载单个缓存值
     * 默认实现会在异步线程中调用load方法
     *
     * @param key 缓存键
     * @return CompletableFuture包装的缓存值
     */
    default CompletableFuture<V> loadAsync(K key) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return load(key);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * 异步批量加载缓存值
     * 默认实现会在异步线程中调用loadAll方法
     *
     * @param keys 缓存键集合
     * @return CompletableFuture包装的键值对映射
     */
    default CompletableFuture<Map<K, V>> loadAllAsync(Set<K> keys) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return loadAll(keys);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * 重新加载缓存值（用于刷新）
     * 默认实现会调用load方法
     *
     * @param key      缓存键
     * @param oldValue 旧值
     * @return 新的缓存值
     * @throws Exception 如果加载失败
     */
    default V reload(K key, V oldValue) throws Exception {
        return load(key);
    }

    /**
     * 异步重新加载缓存值
     * 默认实现会在异步线程中调用reload方法
     *
     * @param key      缓存键
     * @param oldValue 旧值
     * @return CompletableFuture包装的新缓存值
     */
    default CompletableFuture<V> reloadAsync(K key, V oldValue) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return reload(key, oldValue);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * 检查是否支持批量加载
     *
     * @return 如果支持批量加载返回true
     */
    default boolean supportsBatchLoading() {
        return false;
    }

    /**
     * 检查是否支持异步加载
     *
     * @return 如果支持异步加载返回true
     */
    default boolean supportsAsyncLoading() {
        return true;
    }

    /**
     * 获取加载器名称
     *
     * @return 加载器名称
     */
    default String getName() {
        return this.getClass().getSimpleName();
    }

    /**
     * 获取加载超时时间（毫秒）
     * 返回0表示无超时限制
     *
     * @return 超时时间
     */
    default long getLoadTimeoutMillis() {
        return 0;
    }

    /**
     * 处理加载异常
     * 默认实现会重新抛出异常
     *
     * @param key       缓存键
     * @param exception 加载异常
     * @return 默认值或重新抛出异常
     * @throws Exception 处理后的异常
     */
    default V handleLoadException(K key, Exception exception) throws Exception {
        throw exception;
    }
}