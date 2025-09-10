package io.github.cascade.cache.simple;

import io.github.cascade.cache.exception.CacheException;
import io.github.cascade.cache.exception.CacheLoadException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;

/**
 * 缓存函数组合工具类
 * <p>
 * 基于Function的缓存操作抽象，实现了文档中描述的函数式缓存链设计思想：
 * 1. orElse组合器：串联多个缓存层次
 * 2. 装饰器模式：writeBack、防击穿、异步等横切能力
 * 3. 函数式管道：将缓存操作组合成流水线
 *
 * @author cascade
 */
public final class CacheFunctions {

    private static final Logger log = LoggerFactory.getLogger(CacheFunctions.class);
    // 锁对象缓存，确保相同key使用相同锁对象
    private static final Map<Object, Object> lockObjects = new ConcurrentHashMap<>();
    private static final Map<Object, CompletableFuture<Object>> loadingCache = new ConcurrentHashMap<>();

    private CacheFunctions() {
    } // 工具类不允许实例化

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
            R result = first.apply(input);
            return result != null ? result : second.apply(input);
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
        return key -> cache.get(key).orElse(null);
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
            } catch (RuntimeException e) {
                log.warn("数据源加载失败: key={}, error={}", key, e.getMessage());
                return null;
            }
        };
    }

    // ==================== 写回装饰器 ====================

    /**
     * 缓存写回装饰器 - 文档核心思想的实现
     * 包装一个Function，当它返回非null值时，将结果写回到缓存
     *
     * @param cache          要写回的缓存
     * @param sourceFunction 源Function
     * @return 带写回功能的Function
     */
    public static <K, V> Function<K, V> withWriteBack(Cache<K, V> cache, Function<K, V> sourceFunction) {
        return (K key) -> {
            V value = sourceFunction.apply(key);
            if (value != null) {
                try {
                    cache.put(key, value);
                } catch (RuntimeException e) {
                    log.warn("缓存写回失败: key={}, cache={}, error={}",
                            key, cache.getName(), e.getMessage());
                }
            }
            return value;
        };
    }

    /**
     * 异步写回装饰器 - 文档扩展思想的实现
     * 不在主流程中同步写回，而是异步执行
     */
    public static <K, V> Function<K, V> withAsyncWriteBack(Cache<K, V> cache, Function<K, V> sourceFunction) {
        return key -> loadAndWriteBack(key, cache, sourceFunction);
    }

    private static <K, V> V loadAndWriteBack(K key, Cache<K, V> cache, Function<K, V> sourceFunction) {
        V value = sourceFunction.apply(key);
        if (value != null) {
            asyncCachePut(key, value, cache);
        }
        return value;
    }

    private static <K, V> void asyncCachePut(K key, V value, Cache<K, V> cache) {
        CompletableFuture.runAsync(() -> {
            try {
                cache.put(key, value);
            } catch (RuntimeException e) {
                log.warn("异步缓存写回失败: key={}, cache={}, error={}",
                        key, cache.getName(), e.getMessage());
            }
        });
    }

    /**
     * 批量写回装饰器
     * 支持批量操作优化
     */
    public static <K, V> Function<K, V> withBatchWriteBack(Cache<K, V> cache, Function<K, V> sourceFunction,
                                                           int batchSize, long delayMs) {
        return new BatchWriteBackFunction<>(cache, sourceFunction, batchSize, delayMs);
    }

    // ==================== 防击穿装饰器 ====================


    /**
     * 防击穿装饰器 - 文档扩展思想的实现
     * 使用synchronized锁防止相同key的并发加载
     */
    public static <K, V> Function<K, V> withLock(Function<K, V> sourceFunction) {
        return (K key) -> {
            // 获取或创建锁对象，确保相同key使用相同锁
            Object lockObject = lockObjects.computeIfAbsent(key, k -> new Object());
            synchronized (lockObject) {
                return sourceFunction.apply(key);
            }
        };
    }

    /**
     * 进阶防击穿装饰器
     * 使用分段锁和Future来避免重复加载
     */


    public static <K, V> Function<K, V> withAdvancedLock(Function<K, V> sourceFunction) {
        return key -> loadWithLock(key, sourceFunction);
    }

    private static <K, V> V loadWithLock(K key, Function<K, V> sourceFunction) {
        V result = null;
        // 1. 检查是否已有加载中的 future
        CompletableFuture<Object> existingFuture = loadingCache.get(key);
        if (existingFuture != null) {
            result = waitForFuture(key, existingFuture);
        } else {
            // 2. 创建新的加载任务
            CompletableFuture<Object> newFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return sourceFunction.apply(key);
                } finally {
                    loadingCache.remove(key);
                }
            });

            // 3. 尝试放入缓存，如果有竞争则取消当前任务
            CompletableFuture<Object> raceFuture = loadingCache.putIfAbsent(key, newFuture);
            if (raceFuture != null) {
                newFuture.cancel(false);
                result = waitForFuture(key, raceFuture);
            } else {
                // 4. 等待自己发起的加载任务结果
                try {
                    result = (V) newFuture.get();
                } catch (RuntimeException | InterruptedException | ExecutionException e) {
                    log.warn("防击穿加载失败: key={}, error={}", key, e.getMessage());
                    Thread.currentThread().interrupt();
                }
            }
        }

        return result;
    }

    private static <K, V> V waitForFuture(K key, CompletableFuture<Object> future) {
        try {
            return (V) future.get();
        } catch (RuntimeException | InterruptedException | ExecutionException e) {
            log.warn("等待加载结果失败: key={}, error={}", key, e.getMessage());
            loadingCache.remove(key, future);
            Thread.currentThread().interrupt();
            return null;
        }
    }

    // ==================== 监控装饰器 ====================

    /**
     * 性能监控装饰器
     * 记录执行时间和成功率
     */
    public static <K, V> Function<K, V> withMetrics(Function<K, V> sourceFunction, String operationName) {
        return key -> executeWithMetrics(key, sourceFunction, operationName);
    }

    private static <K, V> V executeWithMetrics(K key, Function<K, V> sourceFunction, String operationName) {
        long startTime = System.currentTimeMillis();
        boolean success = false;
        try {
            V result = sourceFunction.apply(key);
            success = (result != null);
            return result;
        } catch (RuntimeException e) {
            throw new CacheException(operationName, "执行", "操作执行失败: " + key, e);
        } finally {
            long duration = System.currentTimeMillis() - startTime;
            log.debug("操作执行完成: operation={}, key={}, duration={}ms, success={}",
                    operationName, key, duration, success);
        }
    }

    /**
     * 重试装饰器
     * 失败时自动重试
     */
    public static <K, V> Function<K, V> withRetry(Function<K, V> sourceFunction, int maxRetries, long retryDelayMs) {
        return key -> executeWithRetry(key, sourceFunction, maxRetries, retryDelayMs);
    }

    private static <K, V> V executeWithRetry(K key, Function<K, V> sourceFunction, int maxRetries, long retryDelayMs) {
        Exception lastException = null;

        for (int i = 0; i <= maxRetries; i++) {
            try {
                V result = sourceFunction.apply(key);
                if (i > 0) {
                    log.debug("重试成功: key={}, attempt={}", key, i + 1);
                }
                return result;
            } catch (RuntimeException e) {
                lastException = e;
                if (i < maxRetries) {
                    log.warn("执行失败，准备重试: key={}, attempt={}, error={}",
                            key, i + 1, e.getMessage());
                    sleepBetweenRetries(retryDelayMs);
                }
            }
        }

        log.error("重试次数耗尽: key={}, maxRetries={}", key, maxRetries);
        throw new CacheLoadException("重试失败", lastException);
    }

    private static void sleepBetweenRetries(long retryDelayMs) {
        try {
            Thread.sleep(retryDelayMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    // ==================== 便捷构造器 ====================

    /**
     * 构建标准的双缓存管道 - 文档思想的直接实现
     * L1(本地) -> L2(分布式) -> 数据源
     */
    public static <K, V> Function<K, V> buildTieredPipeline(Cache<K, V> l1Cache,
                                                            Cache<K, V> l2Cache,
                                                            CacheLoader<K, V> loader) {
        return orElse(
                loadFromCache(l1Cache),
                withWriteBack(l1Cache, loadFromCache(l2Cache)),
                withWriteBack(l2Cache, withWriteBack(l1Cache, loadFromSource(loader)))
        );
    }

    /**
     * 构建带防击穿的双缓存管道
     */
    public static <K, V> Function<K, V> buildProtectedTieredPipeline(Cache<K, V> l1Cache,
                                                                     Cache<K, V> l2Cache,
                                                                     CacheLoader<K, V> loader) {
        return orElse(
                loadFromCache(l1Cache),
                withWriteBack(l1Cache, loadFromCache(l2Cache)),
                withLock(withWriteBack(l2Cache, withWriteBack(l1Cache, loadFromSource(loader))))
        );
    }

    /**
     * 构建异步优化的双缓存管道
     */
    public static <K, V> Function<K, V> buildAsyncTieredPipeline(Cache<K, V> l1Cache,
                                                                 Cache<K, V> l2Cache,
                                                                 CacheLoader<K, V> loader) {
        return orElse(
                loadFromCache(l1Cache),
                withAsyncWriteBack(l1Cache, loadFromCache(l2Cache)),
                withAdvancedLock(withAsyncWriteBack(l2Cache, withAsyncWriteBack(l1Cache, loadFromSource(loader))))
        );
    }

}