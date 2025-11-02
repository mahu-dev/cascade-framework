package io.github.cascade.cache.common;

import io.github.cascade.cache.api.Cache;
import io.github.cascade.cache.api.CacheManager;
import io.github.cascade.cache.api.CacheRefresher;
import io.github.cascade.cache.core.CachePutPipeline;
import io.github.cascade.cache.common.exception.CacheException;
import io.github.cascade.cache.common.SpelExpressionHelper;
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
 * @author lionel lionelk@163.com
 * =============================
 * Date: 2025/10/29
 * Time: 17:15
 * =============================
 * <p>
 * 函数式缓存操作工具类
 * <p>
 * 设计原则：
 * 1. 函数组合：将复杂逻辑分解为可组合的函数
 * 2. 错误处理：使用Optional和Try模式替代异常
 * 3. 不变性：所有操作都是无副作用的纯函数
 * 4. 装饰器模式：提供可组合的操作装饰器
 * <p>
 * P0级重构优化（2025-10-29）：
 * - 统一Logger命名规范，使用LOGGER替代log
 * - 优化异常处理，使用CacheException替代RuntimeException
 * - 添加详细的JavaDoc文档和示例
 * - 修复泛型类型推导问题
 */
public class FunctionalCacheOperations {

    private static final Logger LOGGER = LoggerFactory.getLogger(FunctionalCacheOperations.class);

    // ==================== 核心函数式操作 ====================

    /**
     * 函数式结果包装器 - 类似于Try模式
     * <p>
     * 提供函数式的错误处理，避免异常传播
     */
    public static class Result<T> {
        private final T value;
        private final Exception error;

        private Result(T value, Exception error) {
            this.value = value;
            this.error = error;
        }

        /**
         * 创建成功结果
         */
        public static <T> Result<T> success(T value) {
            return new Result<>(value, null);
        }

        /**
         * 创建失败结果
         */
        public static <T> Result<T> failure(Exception error) {
            return new Result<>(null, error);
        }

        /**
         * 检查是否成功
         */
        public boolean isSuccess() {
            return error == null;
        }

        /**
         * 检查是否失败
         */
        public boolean isFailure() {
            return error != null;
        }

        /**
         * 获取结果值
         */
        public T getValue() {
            return value;
        }

        /**
         * 获取Optional包装的结果值
         */
        public Optional<T> getOptionalValue() {
            return Optional.ofNullable(value);
        }

        /**
         * 获取Optional包装的异常信息
         */
        public Optional<Exception> getError() {
            return Optional.ofNullable(error);
        }

        /**
         * 函数式映射：对成功结果应用转换函数
         */
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

        /**
         * 函数式平面映射：支持链式Result操作
         */
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

        /**
         * 获取值或默认值
         */
        public T orElse(T defaultValue) {
            return isSuccess() ? value : defaultValue;
        }

        /**
         * 获取值或通过Supplier获取默认值
         */
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
        } catch (CacheException e) {
            return Result.failure(e);
        } catch (Exception e) {
            return Result.failure(new CacheException("操作执行失败", e));
        }
    }

    /**
     * 条件执行函数 - 当条件满足时返回输入值，否则返回空Optional
     */
    public static <T> Function<T, Optional<T>> when(Predicate<T> condition) {
        return input -> condition.test(input) ? Optional.of(input) : Optional.empty();
    }

    /**
     * 缓存名称解析器 - 基于注解值或类方法名生成缓存名称
     */
    public static Function<JoinPoint, String> cacheNameResolver(String annotationValue) {
        return (JoinPoint joinPoint) -> {
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
     * 提供线程安全的SpEL表达式求值功能
     */
    public record SpelEvaluator(SpelExpressionHelper spelHelper) {

        /**
         * 创建表达式求值函数
         */
        public Function<JoinPoint, Result<Object>> evaluateExpression(String expression) {
            return joinPoint -> safely(() -> spelHelper.evaluate(expression, joinPoint, null));
        }

        /**
         * 创建条件求值函数
         */
        public Function<JoinPoint, Result<Boolean>> evaluateCondition(String condition, Object result) {
            return (JoinPoint joinPoint) -> {
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
     * 缓存获取管道 - 封装缓存获取操作的函数式管道
     */
    public record CacheGetPipeline<K, V>(Cache<K, V> cache, K key) {

        /**
         * 执行缓存获取操作
         */
        public Result<Optional<V>> execute() {
            return safely(() -> cache.get(key));
        }

        /**
         * 执行获取或加载操作
         */
        public Result<V> executeOrLoad(Supplier<V> loader) {
            return execute()
                    .flatMap(optional -> optional
                            .map(Result::success)
                            .orElseGet(() -> safely(loader)));
        }

        /**
         * 执行获取或异步加载操作
         */
        public Result<V> executeOrLoadAsync(Supplier<CompletableFuture<V>> asyncLoader) {
            return execute()
                    .flatMap(optional -> optional
                            .map(Result::success)
                            .orElseGet(() -> safely(() -> asyncLoader.get().join())));
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
     * 日志装饰器 - 为操作添加日志记录
     */
    public static <T> Function<T, T> withLogging(String operation, Function<T, String> keyExtractor) {
        return (T input) -> {
            String key = keyExtractor.apply(input);
            LOGGER.debug("{}开始: key={}", operation, key);
            try {
                LOGGER.debug("{}完成: key={}", operation, key);
                return input;
            } catch (CacheException e) {
                throw new CacheException(operation, "执行", "操作执行失败: " + key, e);
            }
        };
    }

    /**
     * 条件装饰器 - 根据条件决定是否应用操作
     */
    public static <T> Function<Function<T, T>, Function<T, T>> conditional(Predicate<T> condition) {
        return operation -> input -> condition.test(input) ? operation.apply(input) : input;
    }

    /**
     * 重试装饰器 - 为操作添加重试机制
     */
    public static <T> Function<Supplier<T>, Supplier<T>> withRetry(int maxRetries) {
        return (Supplier<T> supplier) -> () -> {
            CacheException lastException = null;
            for (int i = 0; i <= maxRetries; i++) {
                try {
                    return supplier.get();
                } catch (CacheException e) {
                    lastException = e;
                    if (i == maxRetries) {
                        throw new CacheException("操作重试失败，次数: " + maxRetries, e);
                    }
                }
            }
            throw new CacheException("不应该到达这里", lastException);
        };
    }

    /**
     * 异步装饰器 - 将同步操作转换为异步执行
     */
    public static <T> Function<Supplier<T>, CompletableFuture<T>> async() {
        return supplier -> CompletableFuture.supplyAsync(supplier);
    }

    // ==================== 组合式缓存操作 ====================

    /**
     * 函数式@Cacheable操作
     * 提供缓存查询的完整函数式实现
     */
    public static <K, V> Function<ProceedingJoinPoint, Result<V>> cacheableOperation(
            Cache<K, V> cache,
            K key,
            Predicate<JoinPoint> condition,
            boolean enableRefresh,
            long ttlSeconds) {
        // 向后兼容版本：不支持刷新功能
        return cacheableOperation(cache, key, condition, enableRefresh, ttlSeconds, null, 300L);
    }

    /**
     * 函数式@Cacheable操作（完整版本，支持刷新功能）
     * P0级重构修复：使用原始CacheManager接口避免泛型问题
     *
     * @param cache                  缓存实例
     * @param key                    缓存键
     * @param condition              执行条件
     * @param enableRefresh          是否启用自动刷新
     * @param ttlSeconds             TTL时间
     * @param cacheManager           缓存管理器（用于刷新功能）
     * @param refreshIntervalSeconds 刷新间隔
     * @return 函数式操作结果
     */
    public static <K, V> Function<ProceedingJoinPoint, Result<V>> cacheableOperation(
            Cache<K, V> cache,
            K key,
            Predicate<JoinPoint> condition,
            boolean enableRefresh,
            long ttlSeconds,
            CacheManager cacheManager,
            long refreshIntervalSeconds) {

        return (ProceedingJoinPoint joinPoint) -> {
            if (!condition.test(joinPoint)) {
                return executeMethodDirectly(joinPoint);
            }

            Result<V> result = executeCacheableMethod(cache, key, joinPoint, ttlSeconds);

            // 如果启用刷新并且有CacheManager，则启用自动刷新
            if (enableRefresh && cacheManager != null && result.isSuccess()) {
                enableCacheRefresh(cache, key, cacheManager, refreshIntervalSeconds);
            }

            return result;
        };
    }

    /**
     * 直接执行方法，不使用缓存
     */
    private static <V> Result<V> executeMethodDirectly(ProceedingJoinPoint joinPoint) {
        return safely(() -> {
            try {
                return (V) joinPoint.proceed();
            } catch (Throwable e) {
                throw new CacheException("方法执行失败", e);
            }
        });
    }

    /**
     * 执行可缓存方法：先查缓存，缓存未命中则执行方法并缓存结果
     */
    private static <K, V> Result<V> executeCacheableMethod(Cache<K, V> cache, K key,
                                                           ProceedingJoinPoint joinPoint,
                                                           long ttlSeconds) {
        // 尝试从缓存获取
        Result<Optional<V>> cacheResult = get(cache, key).execute();
        if (cacheResult.isSuccess() && cacheResult.getValue().isPresent()) {
            return handleCacheHit(cache, key, cacheResult.getValue().get());
        }

        // 缓存未命中，执行业务方法
        return handleCacheMiss(cache, key, joinPoint, ttlSeconds);
    }

    /**
     * 处理缓存命中情况
     */
    private static <K, V> Result<V> handleCacheHit(Cache<K, V> cache, K key, V cachedValue) {
        LOGGER.debug("缓存命中: cache={}, key={}", cache.getName(), key);
        return Result.success(cachedValue);
    }

    /**
     * 处理缓存未命中情况：执行方法并缓存结果
     */
    private static <K, V> Result<V> handleCacheMiss(Cache<K, V> cache, K key,
                                                    ProceedingJoinPoint joinPoint,
                                                    long ttlSeconds) {
        Result<V> methodResult = executeMethodDirectly(joinPoint);
        if (methodResult.isSuccess()) {
            V result = methodResult.getValue();
            if (result != null) {
                put(cache, key, result).executeWithTtl(ttlSeconds);
            }
        }
        return methodResult;
    }

    /**
     * 启用缓存自动刷新功能
     * <p>
     * 注意：在新的设计中（2025-11-01重构），自动刷新通过配置自动应用，
     * 不需要显式调用此方法。此方法保留用于兼容性。
     *
     * @deprecated 使用配置方式启用自动刷新（cascade.refresh.enabled=true）
     */
    @Deprecated
    @SuppressWarnings("unchecked")
    public static <K, V> void enableCacheRefresh(Cache<K, V> cache, K key,
                                                 CacheManager cacheManager,
                                                 long refreshIntervalSeconds) {
        LOGGER.warn("enableCacheRefresh 方法已过时，请使用配置方式启用自动刷新: cache={}, key={}",
                cache.getName(), key);
        LOGGER.info("提示：在 application.yml 中配置 cascade.refresh.enabled=true 来启用自动刷新");
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
                LOGGER.debug("缓存更新: cache={}, key={}", cache.getName(), key);
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
                    LOGGER.debug("缓存全部清除: cache={}", cache.getName());
                } else if (key.isPresent()) {
                    cache.evict(key.get());
                    LOGGER.debug("缓存键清除: cache={}, key={}", cache.getName(), key.get());
                }
                return null;
            });
        };
    }
}