package io.github.cascade.cache.simple;

import io.github.cascade.cache.exception.CacheException;

import java.time.Duration;

/**
 * 重试加载器实现
 * <p>
 * 将复杂的重试逻辑从lambda中提取出来，
 * 遵循SonarQube关于lambda复杂度的建议。
 *
 * @param <K> 键类型
 * @param <V> 值类型
 * @author cascade
 */
record RetryingCacheLoader<K, V>(CacheLoader<K, V> delegate, int maxRetries,
                                 Duration delay) implements CacheLoader<K, V> {
    @Override
    public V apply(K key) {
        Exception lastException = null;
        for (int i = 0; i <= maxRetries; i++) {
            try {
                return delegate.apply(key);
            } catch (RuntimeException e) {
                lastException = e;
                if (i < maxRetries) {
                    try {
                        Thread.sleep(delay.toMillis());
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new CacheException("加载器重试中断: " + key, ie);
                    }
                }
            }
        }
        throw new CacheException("加载器重试失败: " + key + ", 次数: " + maxRetries, lastException);
    }
}