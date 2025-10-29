package io.github.cascade.cache.loader;

import io.github.cascade.cache.core.CacheLoader;
import io.github.cascade.cache.exception.CacheLoadException;

import java.util.Arrays;

/**
 * 组合缓存加载器实现
 * <p>
 * 将复杂的组合逻辑从lambda中提取出来，
 * 遵循SonarQube关于lambda复杂度的建议。
 * 按顺序尝试多个加载器，直到获得非null结果。
 *
 * @param <K> 键类型
 * @param <V> 值类型
 * @author cascade
 */
public record ComposedCacheLoader<K, V>(CacheLoader<K, V>... loaders) implements CacheLoader<K, V> {

    @SafeVarargs
    public ComposedCacheLoader {
    }

    @Override
    public V apply(K key) {
        Exception lastException = null;
        for (CacheLoader<K, V> loader : loaders) {
            try {
                V result = loader.apply(key);
                if (result != null) {
                    return result;
                }
            } catch (RuntimeException e) {
                lastException = e;
            }
        }
        if (lastException != null) {
            throw new CacheLoadException("所有组合加载器都失败: " + key, lastException);
        }
        return null;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        ComposedCacheLoader<?, ?> that = (ComposedCacheLoader<?, ?>) obj;
        return Arrays.equals(loaders, that.loaders);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(loaders);
    }

    @Override
    public String toString() {
        return "ComposedCacheLoader{loaders=" + Arrays.toString(loaders) + "}";
    }
}