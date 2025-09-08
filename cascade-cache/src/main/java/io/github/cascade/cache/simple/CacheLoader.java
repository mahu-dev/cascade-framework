package io.github.cascade.cache.simple;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

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

    // ==================== 函数式组合与装饰器 ====================

    /**
     * 创建带缓存的记忆化加载器
     */
    default CacheLoader<K, V> memoized() {
        Map<K, V> cache = new ConcurrentHashMap<>();
        return key -> cache.computeIfAbsent(key, this);
    }

    /**
     * 创建带重试机制的加载器
     */
    default CacheLoader<K, V> withRetry(int maxRetries, Duration delay) {
        return key -> {
            Exception lastException = null;
            for (int i = 0; i <= maxRetries; i++) {
                try {
                    return this.apply(key);
                } catch (Exception e) {
                    lastException = e;
                    if (i < maxRetries) {
                        try {
                            Thread.sleep(delay.toMillis());
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException("加载器重试中断: " + key, ie);
                        }
                    }
                }
            }
            throw new RuntimeException("加载器重试失败: " + key + ", 次数: " + maxRetries, lastException);
        };
    }

    /**
     * 创建带条件过滤的加载器
     */
    default CacheLoader<K, V> filtered(Predicate<K> condition, V defaultValue) {
        return key -> condition.test(key) ? this.apply(key) : defaultValue;
    }

    /**
     * 创建带结果转换的加载器
     */
    default <R> CacheLoader<K, R> mapped(Function<V, R> mapper) {
        return key -> {
            V value = this.apply(key);
            return value != null ? mapper.apply(value) : null;
        };
    }

    /**
     * 创建带Optional支持的加载器
     */
    default CacheLoader<K, Optional<V>> optional() {
        return key -> Optional.ofNullable(this.apply(key));
    }

    /**
     * 函数组合 - 链式调用其他加载器
     */
    default CacheLoader<K, V> orElse(CacheLoader<K, V> fallback) {
        return key -> {
            try {
                V result = this.apply(key);
                return result != null ? result : fallback.apply(key);
            } catch (Exception e) {
                return fallback.apply(key);
            }
        };
    }

    /**
     * 懒加载装饰器
     */
    default CacheLoader<K, Supplier<V>> lazy() {
        return key -> () -> this.apply(key);
    }

    // ==================== 高级工厂方法 ====================

    /**
     * 创建带熔断器的加载器
     */
    static <K, V> CacheLoader<K, V> circuitBreaker(Function<K, V> loader, 
                                                  int failureThreshold, 
                                                  Duration recoveryTime) {
        return new CircuitBreakerCacheLoader<>(loader, failureThreshold, recoveryTime);
    }

    /**
     * 创建组合加载器 - 按顺序尝试多个加载器
     */
    @SafeVarargs
    static <K, V> CacheLoader<K, V> compose(CacheLoader<K, V>... loaders) {
        return key -> {
            Exception lastException = null;
            for (CacheLoader<K, V> loader : loaders) {
                try {
                    V result = loader.apply(key);
                    if (result != null) {
                        return result;
                    }
                } catch (Exception e) {
                    lastException = e;
                }
            }
            if (lastException != null) {
                throw new RuntimeException("所有组合加载器都失败: " + key, lastException);
            }
            return null;
        };
    }

    /**
     * 创建条件加载器
     */
    static <K, V> CacheLoader<K, V> conditional(Predicate<K> condition,
                                                CacheLoader<K, V> trueLoader,
                                                CacheLoader<K, V> falseLoader) {
        return key -> condition.test(key) ? trueLoader.apply(key) : falseLoader.apply(key);
    }

    // ==================== 内部熔断器实现 ====================

    /**
     * 熔断器加载器实现
     */
    class CircuitBreakerCacheLoader<K, V> implements CacheLoader<K, V> {
        private final Function<K, V> loader;
        private final int failureThreshold;
        private final Duration recoveryTime;
        private volatile int failureCount = 0;
        private volatile long lastFailureTime = 0;
        private volatile boolean circuitOpen = false;

        public CircuitBreakerCacheLoader(Function<K, V> loader, int failureThreshold, Duration recoveryTime) {
            this.loader = loader;
            this.failureThreshold = failureThreshold;
            this.recoveryTime = recoveryTime;
        }

        @Override
        public V apply(K key) {
            if (circuitOpen && !shouldAttemptRecovery()) {
                throw new RuntimeException("熔断器开启，拒绝请求: " + key);
            }

            try {
                V result = loader.apply(key);
                onSuccess();
                return result;
            } catch (Exception e) {
                onFailure();
                throw e;
            }
        }

        private boolean shouldAttemptRecovery() {
            return System.currentTimeMillis() - lastFailureTime >= recoveryTime.toMillis();
        }

        private void onSuccess() {
            failureCount = 0;
            circuitOpen = false;
        }

        private void onFailure() {
            failureCount++;
            lastFailureTime = System.currentTimeMillis();
            if (failureCount >= failureThreshold) {
                circuitOpen = true;
            }
        }
    }
}