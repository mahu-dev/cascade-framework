package io.github.cascade.cache.simple;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * 函数式缓存操作工具类
 * <p>
 * 设计原则：
 * 1. 函数组合：将复杂逻辑分解为可组合的函数
 * 2. 错误处理：使用Optional和Try模式替代异常
 * 3. 不变性：所有操作都是无副作用的纯函数
 * 4. 装饰器模式：提供可组合的操作装饰器
 *
 * @author cascade
 */
public class FunctionalCacheOperations {

    private static final Logger log = LoggerFactory.getLogger(FunctionalCacheOperations.class);

    // ==================== 核心函数式操作 ====================

    /**
     * 函数式结果包装器 - 类似于Try模式
     */
    public static class Result<T> {
        private final T value;
        private final Exception error;

        private Result(T value, Exception error) {
            this.value = value;
            this.error = error;
        }

        public static <T> Result<T> success(T value) {
            return new Result<>(value, null);
        }

        public static <T> Result<T> failure(Exception error) {
            return new Result<>(null, error);
        }

        public boolean isSuccess() {
            return error == null;
        }

        public boolean isFailure() {
            return error != null;
        }

        public T getValue() {
            return value;
        }
        
        public Optional<T> getOptionalValue() {
            return Optional.ofNullable(value);
        }

        public Optional<Exception> getError() {
            return Optional.ofNullable(error);
        }

        public <R> Result<R> map(Function<T, R> mapper) {
            if (isSuccess()) {
                try {
                    return success(mapper.apply(value));
                } catch (Exception e) {
                    return failure(e);
                }
            } else {
                return failure(error);
            }
        }

        public <R> Result<R> flatMap(Function<T, Result<R>> mapper) {
            if (isSuccess()) {
                try {
                    return mapper.apply(value);
                } catch (Exception e) {
                    return failure(e);
                }
            } else {
                return failure(error);
            }
        }

        public T orElse(T defaultValue) {
            return isSuccess() ? value : defaultValue;
        }

        public T orElseGet(Supplier<T> supplier) {
            return isSuccess() ? value : supplier.get();
        }
    }

    // ==================== 缓存操作函数式包装器 ====================

    /**
     * 安全执行函数 - 将可能抛出异常的操作包装为Result
     */
    public static <T> Result<T> safely(Supplier<T> operation) {
        try {
            return Result.success(operation.get());
        } catch (Exception e) {
            return Result.failure(e);
        }
    }

    /**
     * 条件执行函数
     */
    public static <T> Function<T, Optional<T>> when(Predicate<T> condition) {
        return input -> condition.test(input) ? Optional.of(input) : Optional.empty();
    }

    /**
     * 缓存名称解析器
     */
    public static Function<JoinPoint, String> cacheNameResolver(String annotationValue) {
        return joinPoint -> {
            if (StringUtils.hasText(annotationValue)) {
                return annotationValue;
            }
            String className = joinPoint.getTarget().getClass().getSimpleName();
            String methodName = joinPoint.getSignature().getName();
            return className + "." + methodName;
        };
    }

    /**
     * SpEL表达式求值器 - 函数式封装
     */
    public static class SpelEvaluator {
        private final SpelExpressionHelper spelHelper;

        public SpelEvaluator(SpelExpressionHelper spelHelper) {
            this.spelHelper = spelHelper;
        }

        public Function<JoinPoint, Result<Object>> evaluateExpression(String expression) {
            return joinPoint -> safely(() -> spelHelper.evaluate(expression, joinPoint, null));
        }

        public Function<JoinPoint, Result<Boolean>> evaluateCondition(String condition, Object result) {
            return joinPoint -> {
                if (!StringUtils.hasText(condition)) {
                    return Result.success(true); // 空条件默认为true
                }

                return safely(() -> {
                    Object value = spelHelper.evaluate(condition, joinPoint, result);
                    return !(value instanceof Boolean boolValue && !boolValue);
                });
            };
        }
    }

    // ==================== 缓存操作管道 ====================

    /**
     * 缓存获取管道
     */
    public static class CacheGetPipeline<K, V> {
        private final Cache<K, V> cache;
        private final K key;

        public CacheGetPipeline(Cache<K, V> cache, K key) {
            this.cache = cache;
            this.key = key;
        }

        public Result<Optional<V>> execute() {
            return safely(() -> cache.get(key));
        }

        public Result<V> executeOrLoad(Supplier<V> loader) {
            return execute()
                    .flatMap(optional -> optional
                            .map(Result::success)
                            .orElseGet(() -> safely(loader)));
        }

        public Result<V> executeOrLoadAsync(Supplier<CompletableFuture<V>> asyncLoader) {
            return execute()
                    .flatMap(optional -> optional
                            .map(Result::success)
                            .orElseGet(() -> safely(() -> asyncLoader.get().join())));
        }
    }

    /**
     * 缓存存储管道
     */
    public static class CachePutPipeline<K, V> {
        private final Cache<K, V> cache;
        private final K key;
        private final V value;

        public CachePutPipeline(Cache<K, V> cache, K key, V value) {
            this.cache = cache;
            this.key = key;
            this.value = value;
        }

        public Result<Void> execute() {
            return safely(() -> {
                cache.put(key, value);
                return null;
            });
        }

        public Result<Void> executeWithTtl(long ttlSeconds) {
            return safely(() -> {
                cache.put(key, value, ttlSeconds);
                return null;
            });
        }

        public CachePutPipeline<K, V> whenNotNull() {
            return value != null ? this : new CachePutPipeline<>(cache, key, null) {
                @Override
                public Result<Void> execute() {
                    return Result.success(null); // Skip operation
                }

                @Override
                public Result<Void> executeWithTtl(long ttlSeconds) {
                    return Result.success(null); // Skip operation
                }
            };
        }
    }

    // ==================== 静态工厂方法 ====================

    /**
     * 创建缓存获取管道
     */
    public static <K, V> CacheGetPipeline<K, V> get(Cache<K, V> cache, K key) {
        return new CacheGetPipeline<>(cache, key);
    }

    /**
     * 创建缓存存储管道
     */
    public static <K, V> CachePutPipeline<K, V> put(Cache<K, V> cache, K key, V value) {
        return new CachePutPipeline<>(cache, key, value);
    }

    // ==================== 高阶函数组合器 ====================

    /**
     * 日志装饰器
     */
    public static <T> Function<T, T> withLogging(String operation, Function<T, String> keyExtractor) {
        return input -> {
            String key = keyExtractor.apply(input);
            log.debug("{}开始: key={}", operation, key);
            try {
                // 这里实际上只是透传，真正的操作在调用方
                log.debug("{}完成: key={}", operation, key);
                return input;
            } catch (Exception e) {
                log.error("{}失败: key={}, error={}", operation, key, e.getMessage());
                throw e;
            }
        };
    }

    /**
     * 条件装饰器
     */
    public static <T> Function<Function<T, T>, Function<T, T>> conditional(Predicate<T> condition) {
        return operation -> input -> condition.test(input) ? operation.apply(input) : input;
    }

    /**
     * 重试装饰器
     */
    public static <T> Function<Supplier<T>, Supplier<T>> withRetry(int maxRetries) {
        return supplier -> () -> {
            Exception lastException = null;
            for (int i = 0; i <= maxRetries; i++) {
                try {
                    return supplier.get();
                } catch (Exception e) {
                    lastException = e;
                    if (i == maxRetries) {
                        throw new RuntimeException("操作重试失败，次数: " + maxRetries, e);
                    }
                }
            }
            throw new RuntimeException("不应该到达这里", lastException);
        };
    }

    /**
     * 异步装饰器
     */
    public static <T> Function<Supplier<T>, CompletableFuture<T>> async() {
        return supplier -> CompletableFuture.supplyAsync(supplier);
    }

    // ==================== 组合式缓存操作 ====================

    /**
     * 函数式@Cacheable操作
     */
    public static <K, V> Function<ProceedingJoinPoint, Result<V>> cacheableOperation(
            Cache<K, V> cache,
            K key,
            Predicate<JoinPoint> condition,
            boolean enableRefresh,
            long ttlSeconds) {

        return joinPoint -> {
            // 条件检查
            if (!condition.test(joinPoint)) {
                return safely(() -> {
                    try {
                        return (V) joinPoint.proceed();
                    } catch (Throwable e) {
                        throw new RuntimeException("方法执行失败", e);
                    }
                });
            }

            // 尝试从缓存获取
            Result<Optional<V>> cacheResult = get(cache, key).execute();
            if (cacheResult.isSuccess() && cacheResult.getValue().isPresent()) {
                V cachedValue = cacheResult.getValue().get();
                log.debug("缓存命中: cache={}, key={}", cache.getName(), key);
                return Result.success(cachedValue);
            }

            // 缓存未命中，执行业务方法
            Result<V> methodResult = safely(() -> {
                try {
                    return (V) joinPoint.proceed();
                } catch (Throwable e) {
                    throw new RuntimeException("方法执行失败", e);
                }
            });
            if (methodResult.isSuccess()) {
                V result = methodResult.getValue();
                if (result != null) {
                    put(cache, key, result).executeWithTtl(ttlSeconds);
                }
            }

            return methodResult;
        };
    }

    /**
     * 函数式@CachePut操作
     */
    public static <K, V> Function<V, Result<V>> cachePutOperation(
            Cache<K, V> cache,
            K key,
            Predicate<V> condition,
            long ttlSeconds) {

        return result -> {
            if (condition.test(result)) {
                put(cache, key, result).whenNotNull().executeWithTtl(ttlSeconds);
                log.debug("缓存更新: cache={}, key={}", cache.getName(), key);
            }
            return Result.success(result);
        };
    }

    /**
     * 函数式@CacheEvict操作  
     */
    public static <K, V> Function<JoinPoint, Result<Void>> cacheEvictOperation(
            Cache<K, V> cache,
            Optional<K> key,
            Predicate<JoinPoint> condition,
            boolean allEntries) {

        return joinPoint -> {
            if (!condition.test(joinPoint)) {
                return Result.success(null);
            }

            return safely(() -> {
                if (allEntries) {
                    cache.clear();
                    log.debug("缓存全部清除: cache={}", cache.getName());
                } else if (key.isPresent()) {
                    cache.evict(key.get());
                    log.debug("缓存键清除: cache={}, key={}", cache.getName(), key.get());
                }
                return null;
            });
        };
    }
}