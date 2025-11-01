package io.github.cascade.cache.common;

import io.github.cascade.cache.api.Cache;
import io.github.cascade.cache.api.CacheLoader;
import io.github.cascade.cache.common.exception.CacheException;
import io.github.cascade.cache.common.exception.CacheExceptionHandler;
import io.github.cascade.cache.common.exception.CacheLoadException;
import io.github.cascade.cache.synchronization.CacheLockManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * @author lionel lionelk@163.com
 * =============================
 * Date: 2025/10/29
 * Time: 17:25
 * =============================
 */

/**
 * 缓存函数组合工具类
 * <p>
 * 基于Function的缓存操作抽象，实现了文档中描述的函数式缓存链设计思想：
 * 1. orElse组合器：串联多个缓存层次
 * 2. 装饰器模式：writeBack、防击穿、异步等横切能力
 * 3. 函数式管道：将缓存操作组合成流水线
 * <p>
 * P1级重构优化（2025-10-29）：
 * - 使用统一异常处理机制
 * - 优化锁管理，使用CacheLockManager
 * - 改进函数式异常处理
 * - 添加性能监控和统计支持
 * - 提供更好的可观测性
 */
public final class CacheFunctions {

    private static final Logger LOGGER = LoggerFactory.getLogger(CacheFunctions.class);

    // 正在加载的缓存Future，防止重复加载
    private static final Map<Object, CompletableFuture<Object>> LOADING_CACHE = new ConcurrentHashMap<>();

    // 最大加载缓存大小，防止内存泄漏
    private static final int MAX_LOADING_CACHE_SIZE = 1000;

    private CacheFunctions() {
        // 工具类不允许实例化
    }

    // ==================== 核心组合器 ====================

    /**
     * orElse组合器 - 文档核心思想的实现
     * 串联两个Function，当第一个返回null时执行第二个
     *
     * @param first  优先执行的Function
     * @param second 备选的Function
     * @return 组合后的Function
     */
    public static <T, R> Function<T, R> orElse(Function<T, R> first, Function<T, R> second) {
        return (T input) -> {
            try {
                R result = first.apply(input);
                return result != null ? result : second.apply(input);
            } catch (Exception e) {
                LOGGER.warn("第一个Function执行失败，尝试第二个: input={}, error={}", input, e.getMessage());
                return CacheExceptionHandler.safeExecute(() -> second.apply(input), null);
            }
        };
    }

    /**
     * orElse的多参数版本，支持链式组合
     */
    @SafeVarargs
    public static <T, R> Function<T, R> orElse(Function<T, R>... functions) {
        if (functions == null || functions.length == 0) {
            return input -> null;
        }

        Function<T, R> result = functions[0];
        for (int i = 1; i < functions.length; i++) {
            result = orElse(result, functions[i]);
        }
        return result;
    }

    // ==================== 缓存加载装饰器 ====================

    /**
     * 从缓存加载的Function
     *
     * @param cache 缓存实例
     * @return 从缓存获取数据的Function
     */
    public static <K, V> Function<K, V> loadFromCache(Cache<K, V> cache) {
        return key -> CacheExceptionHandler.safeExecute(() -> {
            return cache.get(key).orElse(null);
        }, null);
    }

    /**
     * 从数据源加载的Function
     *
     * @param loader 数据加载器
     * @return 从数据源加载的Function
     */
    public static <K, V> Function<K, V> loadFromSource(CacheLoader<K, V> loader) {
        return (K key) -> {
            try {
                return loader.apply(key);
            } catch (Exception e) {
                LOGGER.warn("数据源加载失败: key={}, error={}", key, e.getMessage());
                throw new CacheLoadException(key, "数据源加载失败", e);
            }
        };
    }

    // ==================== 写回装饰器 ====================

    /**
     * 缓存写回装饰器 - 文档核心思想的实现
     * 包装一个Function，当它返回非null值时，将结果写回到缓存
     *
     * @param cache  目标缓存
     * @param loader 数据加载Function
     * @param <K>    键类型
     * @param <V>    值类型
     * @return 增强的Function
     */
    public static <K, V> Function<K, V> withWriteBack(Cache<K, V> cache, Function<K, V> loader) {
        return (K key) -> {
            try {
                V value = loader.apply(key);
                if (value != null) {
                    CacheExceptionHandler.safeExecute(() -> {
                        cache.put(key, value);
                        return null;
                    }, null);
                    LOGGER.debug("缓存写回成功: cache={}, key={}", cache.getName(), key);
                }
                return value;
            } catch (CacheException e) {
                LOGGER.warn("缓存写回操作失败: cache={}, key={}, error={}",
                        cache.getName(), key, e.getMessage());
                throw e;
            } catch (Exception e) {
                LOGGER.error("数据加载失败: cache={}, key={}, error={}", cache.getName(), key, e.getMessage());
                throw new CacheLoadException(key, "数据加载失败", e);
            }
        };
    }

    /**
     * 异步写回装饰器
     */
    public static <K, V> Function<K, V> withAsyncWriteBack(Cache<K, V> cache, Function<K, V> loader) {
        return (K key) -> {
            try {
                V value = loader.apply(key);
                if (value != null) {
                    cache.putAsync(key, value).whenComplete((result, ex) -> {
                        if (ex != null) {
                            LOGGER.warn("异步缓存写回失败: cache={}, key={}, error={}",
                                    cache.getName(), key, ex.getMessage());
                        } else {
                            LOGGER.debug("异步缓存写回成功: cache={}, key={}", cache.getName(), key);
                        }
                    });
                }
                return value;
            } catch (Exception e) {
                LOGGER.error("数据加载失败: cache={}, key={}, error={}", cache.getName(), key, e.getMessage());
                throw new CacheLoadException(key, "数据加载失败", e);
            }
        };
    }

    // ==================== 防击穿装饰器 ====================

    /**
     * 防击穿装饰器 - 使用单机锁防止缓存击穿
     */
    public static <K, V> Function<K, V> withLock(Function<K, V> fn, Object key) {
        return input -> {
            Object lock = CacheLockManager.getLock(key);
            synchronized (lock) {
                return fn.apply(input);
            }
        };
    }

    /**
     * 进阶防击穿装饰器 - 使用Future防止重复加载
     */
    public static <K, V> Function<K, V> withAdvancedLock(Function<K, V> fn, K key) {
        return input -> {
            // 检查加载缓存大小限制
            if (LOADING_CACHE.size() >= MAX_LOADING_CACHE_SIZE) {
                LOGGER.warn("加载缓存已达到上限: {}, 清理缓存", MAX_LOADING_CACHE_SIZE);
                clearLoadingCache();
            }

            CompletableFuture<V> loadingFuture = (CompletableFuture<V>) LOADING_CACHE.get(key);

            if (loadingFuture == null) {
                synchronized (CacheLockManager.getLock(key)) {
                    loadingFuture = (CompletableFuture<V>) LOADING_CACHE.get(key);
                    if (loadingFuture == null) {
                        loadingFuture = CompletableFuture.supplyAsync(() -> fn.apply(input));
                        LOADING_CACHE.put(key, (CompletableFuture<Object>) loadingFuture);

                        // 清理Future引用
                        loadingFuture.whenComplete((result, ex) -> {
                            LOADING_CACHE.remove(key);
                            if (ex != null) {
                                LOGGER.warn("异步加载失败: key={}, error={}", key, ex.getMessage());
                            }
                        });
                    }
                }
            }

            try {
                return loadingFuture.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CacheException("缓存加载被中断", e);
            } catch (ExecutionException e) {
                LOGGER.warn("缓存加载执行异常: key={}, error={}", key, e.getCause().getMessage());
                return CacheExceptionHandler.safeExecute(() -> null, null);
            }
        };
    }

    // ==================== 重试装饰器 ====================

    /**
     * 重试装饰器
     */
    public static <K, V> Function<K, V> withRetry(Function<K, V> fn, int maxRetries) {
        return input -> {
            Supplier<V> retryableOperation = CacheExceptionHandler.withRetry(
                    () -> fn.apply(input), maxRetries, 100L);
            return retryableOperation.get();
        };
    }

    /**
     * 带指数退避的重试装饰器
     */
    public static <K, V> Function<K, V> withRetry(Function<K, V> fn, int maxRetries, Duration delay) {
        return input -> {
            Supplier<V> retryableOperation = CacheExceptionHandler.withRetry(
                    () -> fn.apply(input), maxRetries, delay.toMillis());
            return retryableOperation.get();
        };
    }

    // ==================== 性能监控装饰器 ====================

    /**
     * 性能监控装饰器
     */
    public static <K, V> Function<K, V> withMetrics(Function<K, V> fn, String operationName) {
        return input -> {
            long startTime = System.nanoTime();
            try {
                V result = fn.apply(input);
                long duration = System.nanoTime() - startTime;
                LOGGER.debug("操作完成: operation={}, input={}, duration={}ns", operationName, input, duration);
                return result;
            } catch (Exception e) {
                long duration = System.nanoTime() - startTime;
                LOGGER.warn("操作失败: operation={}, input={}, duration={}ns, error={}",
                        operationName, input, duration, e.getMessage());
                throw e;
            }
        };
    }

    // ==================== 缓存管道构建器 ====================

    /**
     * 缓存管道构建器 - 提供流式API构建复杂的缓存管道
     */
    public static <K, V> CachePipelineBuilder<K, V> pipeline() {
        return new CachePipelineBuilder<>();
    }

    /**
     * 缓存管道构建器
     */
    public static class CachePipelineBuilder<K, V> {
        private Function<K, V> currentFunction;

        private CachePipelineBuilder() {
            this.currentFunction = input -> null; // 默认返回null的函数
        }

        /**
         * 添加缓存加载步骤
         */
        public CachePipelineBuilder<K, V> loadFrom(Cache<K, V> cache) {
            this.currentFunction = orElse(this.currentFunction, loadFromCache(cache));
            return this;
        }

        /**
         * 添加数据源加载步骤
         */
        public CachePipelineBuilder<K, V> loadFrom(CacheLoader<K, V> loader) {
            this.currentFunction = orElse(this.currentFunction, loadFromSource(loader));
            return this;
        }

        /**
         * 添加写回装饰器
         */
        public CachePipelineBuilder<K, V> withWriteBackTo(Cache<K, V> cache) {
            this.currentFunction = withWriteBack(cache, this.currentFunction);
            return this;
        }

        /**
         * 添加防击穿保护
         */
        public CachePipelineBuilder<K, V> withProtection(K key) {
            this.currentFunction = withAdvancedLock(this.currentFunction, key);
            return this;
        }

        /**
         * 添加重试机制
         */
        public CachePipelineBuilder<K, V> withRetry(int maxRetries) {
            this.currentFunction = CacheFunctions.withRetry(this.currentFunction, maxRetries);
            return this;
        }

        /**
         * 添加重试机制（带延迟）
         */
        public CachePipelineBuilder<K, V> withRetry(int maxRetries, Duration delay) {
            this.currentFunction = CacheFunctions.withRetry(this.currentFunction, maxRetries, delay);
            return this;
        }

        /**
         * 添加性能监控
         */
        public CachePipelineBuilder<K, V> withMetrics(String operationName) {
            this.currentFunction = CacheFunctions.withMetrics(this.currentFunction, operationName);
            return this;
        }

        /**
         * 构建最终的缓存管道
         */
        public Function<K, V> build() {
            return currentFunction;
        }
    }

    // ==================== 工具方法 ====================

    /**
     * 清理加载缓存
     */
    public static void clearLoadingCache() {
        int count = LOADING_CACHE.size();
        LOADING_CACHE.clear();
        LOGGER.info("加载缓存已清理，共清理: {} 个条目", count);
    }

    /**
     * 获取加载缓存统计信息
     */
    public static LoadingCacheStats getLoadingCacheStats() {
        return LoadingCacheStats.builder()
                .loadingCount(LOADING_CACHE.size())
                .maxLoadingSize(MAX_LOADING_CACHE_SIZE)
                .build();
    }

    /**
     * 加载缓存统计信息
     */
    public static class LoadingCacheStats {
        private final int loadingCount;
        private final int maxLoadingSize;

        private LoadingCacheStats(Builder builder) {
            this.loadingCount = builder.loadingCount;
            this.maxLoadingSize = builder.maxLoadingSize;
        }

        public static Builder builder() {
            return new Builder();
        }

        public int getLoadingCount() {
            return loadingCount;
        }

        public int getMaxLoadingSize() {
            return maxLoadingSize;
        }

        @Override
        public String toString() {
            return String.format("LoadingCacheStats{loadingCount=%d, maxLoadingSize=%d}",
                    loadingCount, maxLoadingSize);
        }

        public static class Builder {
            private int loadingCount;
            private int maxLoadingSize;

            public Builder loadingCount(int loadingCount) {
                this.loadingCount = loadingCount;
                return this;
            }

            public Builder maxLoadingSize(int maxLoadingSize) {
                this.maxLoadingSize = maxLoadingSize;
                return this;
            }

            public LoadingCacheStats build() {
                return new LoadingCacheStats(this);
            }
        }
    }
}