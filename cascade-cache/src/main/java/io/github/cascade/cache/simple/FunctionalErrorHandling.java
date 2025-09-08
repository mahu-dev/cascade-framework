package io.github.cascade.cache.simple;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.*;

/**
 * 函数式错误处理和恢复系统
 * <p>
 * 设计原则：
 * 1. 无异常传播：使用Result/Either模式封装错误
 * 2. 函数式恢复：可组合的错误恢复策略
 * 3. 类型安全：编译时错误处理保证
 * 4. 资源安全：自动资源管理和清理
 * 5. 可观测性：丰富的错误信息和监控数据
 *
 * @author cascade
 */
public class FunctionalErrorHandling {

    private static final Logger log = LoggerFactory.getLogger(FunctionalErrorHandling.class);

    // ==================== Either模式 - 替代异常的函数式错误处理 ====================

    /**
     * Either模式实现 - 表示成功(Right)或失败(Left)的结果
     */
    public static abstract class Either<L, R> {
        
        public abstract boolean isLeft();
        public abstract boolean isRight();
        public abstract L getLeft();
        public abstract R getRight();
        
        public static <L, R> Either<L, R> left(L value) {
            return new Left<>(value);
        }
        
        public static <L, R> Either<L, R> right(R value) {
            return new Right<>(value);
        }

        // 函数式操作
        public abstract <U> Either<L, U> map(Function<R, U> mapper);
        public abstract <U> Either<L, U> flatMap(Function<R, Either<L, U>> mapper);
        public abstract <U> Either<U, R> mapLeft(Function<L, U> mapper);
        public abstract Either<L, R> filter(Predicate<R> predicate, L leftValue);
        public abstract void forEach(Consumer<R> action);
        public abstract R getOrElse(R defaultValue);
        public abstract R getOrElse(Supplier<R> defaultSupplier);

        // 静态工厂方法
        public static <L, R> Either<L, R> of(Supplier<R> supplier, Function<Exception, L> errorMapper) {
            try {
                return right(supplier.get());
            } catch (Exception e) {
                return left(errorMapper.apply(e));
            }
        }

        private static class Left<L, R> extends Either<L, R> {
            private final L value;

            private Left(L value) {
                this.value = value;
            }

            @Override
            public boolean isLeft() { return true; }

            @Override
            public boolean isRight() { return false; }

            @Override
            public L getLeft() { return value; }

            @Override
            public R getRight() {
                throw new UnsupportedOperationException("Left value has no right");
            }

            @Override
            public <U> Either<L, U> map(Function<R, U> mapper) {
                return left(value);
            }

            @Override
            public <U> Either<L, U> flatMap(Function<R, Either<L, U>> mapper) {
                return left(value);
            }

            @Override
            public <U> Either<U, R> mapLeft(Function<L, U> mapper) {
                return left(mapper.apply(value));
            }

            @Override
            public Either<L, R> filter(Predicate<R> predicate, L leftValue) {
                return this;
            }

            @Override
            public void forEach(Consumer<R> action) {
                // Do nothing for Left
            }

            @Override
            public R getOrElse(R defaultValue) {
                return defaultValue;
            }

            @Override
            public R getOrElse(Supplier<R> defaultSupplier) {
                return defaultSupplier.get();
            }

            @Override
            public String toString() {
                return "Left(" + value + ")";
            }
        }

        private static class Right<L, R> extends Either<L, R> {
            private final R value;

            private Right(R value) {
                this.value = value;
            }

            @Override
            public boolean isLeft() { return false; }

            @Override
            public boolean isRight() { return true; }

            @Override
            public L getLeft() {
                throw new UnsupportedOperationException("Right value has no left");
            }

            @Override
            public R getRight() { return value; }

            @Override
            public <U> Either<L, U> map(Function<R, U> mapper) {
                return right(mapper.apply(value));
            }

            @Override
            public <U> Either<L, U> flatMap(Function<R, Either<L, U>> mapper) {
                return mapper.apply(value);
            }

            @Override
            public <U> Either<U, R> mapLeft(Function<L, U> mapper) {
                return right(value);
            }

            @Override
            public Either<L, R> filter(Predicate<R> predicate, L leftValue) {
                return predicate.test(value) ? this : left(leftValue);
            }

            @Override
            public void forEach(Consumer<R> action) {
                action.accept(value);
            }

            @Override
            public R getOrElse(R defaultValue) {
                return value;
            }

            @Override
            public R getOrElse(Supplier<R> defaultSupplier) {
                return value;
            }

            @Override
            public String toString() {
                return "Right(" + value + ")";
            }
        }
    }

    // ==================== Try模式 - 函数式异常处理 ====================

    /**
     * Try模式 - 封装可能失败的计算
     */
    public static abstract class Try<T> {
        
        public abstract boolean isSuccess();
        public abstract boolean isFailure();
        public abstract T get();
        public abstract Exception getException();

        public static <T> Try<T> success(T value) {
            return new Success<>(value);
        }

        public static <T> Try<T> failure(Exception exception) {
            return new Failure<>(exception);
        }

        public static <T> Try<T> of(Supplier<T> supplier) {
            try {
                return success(supplier.get());
            } catch (Exception e) {
                return failure(e);
            }
        }

        // 函数式操作
        public abstract <U> Try<U> map(Function<T, U> mapper);
        public abstract <U> Try<U> flatMap(Function<T, Try<U>> mapper);
        public abstract Try<T> recover(Function<Exception, T> recovery);
        public abstract Try<T> recoverWith(Function<Exception, Try<T>> recovery);
        public abstract Try<T> filter(Predicate<T> predicate);
        public abstract Optional<T> toOptional();
        public abstract Either<Exception, T> toEither();
        
        // 带超时的操作
        public Try<T> timeout(Duration timeout) {
            if (this.isFailure()) {
                return this;
            }
            // 对于已经成功的值，直接返回
            return this;
        }

        private static class Success<T> extends Try<T> {
            private final T value;

            private Success(T value) {
                this.value = value;
            }

            @Override
            public boolean isSuccess() { return true; }

            @Override
            public boolean isFailure() { return false; }

            @Override
            public T get() { return value; }

            @Override
            public Exception getException() {
                throw new UnsupportedOperationException("Success has no exception");
            }

            @Override
            public <U> Try<U> map(Function<T, U> mapper) {
                return Try.of(() -> mapper.apply(value));
            }

            @Override
            public <U> Try<U> flatMap(Function<T, Try<U>> mapper) {
                try {
                    return mapper.apply(value);
                } catch (Exception e) {
                    return failure(e);
                }
            }

            @Override
            public Try<T> recover(Function<Exception, T> recovery) {
                return this;
            }

            @Override
            public Try<T> recoverWith(Function<Exception, Try<T>> recovery) {
                return this;
            }

            @Override
            public Try<T> filter(Predicate<T> predicate) {
                try {
                    return predicate.test(value) ? this : 
                           failure(new IllegalArgumentException("Predicate failed"));
                } catch (Exception e) {
                    return failure(e);
                }
            }

            @Override
            public Optional<T> toOptional() {
                return Optional.of(value);
            }

            @Override
            public Either<Exception, T> toEither() {
                return Either.right(value);
            }

            @Override
            public String toString() {
                return "Success(" + value + ")";
            }
        }

        private static class Failure<T> extends Try<T> {
            private final Exception exception;

            private Failure(Exception exception) {
                this.exception = Objects.requireNonNull(exception);
            }

            @Override
            public boolean isSuccess() { return false; }

            @Override
            public boolean isFailure() { return true; }

            @Override
            public T get() {
                throw new RuntimeException("Try failed", exception);
            }

            @Override
            public Exception getException() { return exception; }

            @Override
            public <U> Try<U> map(Function<T, U> mapper) {
                return failure(exception);
            }

            @Override
            public <U> Try<U> flatMap(Function<T, Try<U>> mapper) {
                return failure(exception);
            }

            @Override
            public Try<T> recover(Function<Exception, T> recovery) {
                return Try.of(() -> recovery.apply(exception));
            }

            @Override
            public Try<T> recoverWith(Function<Exception, Try<T>> recovery) {
                try {
                    return recovery.apply(exception);
                } catch (Exception e) {
                    return failure(e);
                }
            }

            @Override
            public Try<T> filter(Predicate<T> predicate) {
                return this;
            }

            @Override
            public Optional<T> toOptional() {
                return Optional.empty();
            }

            @Override
            public Either<Exception, T> toEither() {
                return Either.left(exception);
            }

            @Override
            public String toString() {
                return "Failure(" + exception.getMessage() + ")";
            }
        }
    }

    // ==================== 错误恢复策略 ====================

    /**
     * 错误恢复策略接口
     */
    @FunctionalInterface
    public interface RecoveryStrategy<T> {
        Try<T> recover(Exception error, int attemptCount);

        /**
         * 立即失败策略
         */
        static <T> RecoveryStrategy<T> failFast() {
            return (error, attemptCount) -> Try.failure(error);
        }

        /**
         * 返回默认值策略
         */
        static <T> RecoveryStrategy<T> withDefault(T defaultValue) {
            return (error, attemptCount) -> Try.success(defaultValue);
        }

        /**
         * 返回默认值供应者策略
         */
        static <T> RecoveryStrategy<T> withDefault(Supplier<T> defaultSupplier) {
            return (error, attemptCount) -> Try.of(defaultSupplier);
        }

        /**
         * 重试策略
         */
        static <T> RecoveryStrategy<T> retry(int maxRetries, Duration delay) {
            return (error, attemptCount) -> {
                if (attemptCount <= maxRetries) {
                    try {
                        if (delay.toMillis() > 0) {
                            Thread.sleep(delay.toMillis());
                        }
                        return Try.failure(error); // 指示需要重试
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return Try.failure(new RuntimeException("重试被中断", ie));
                    }
                } else {
                    return Try.failure(new RuntimeException(
                        "重试" + maxRetries + "次后仍然失败", error));
                }
            };
        }

        /**
         * 指数退避重试策略
         */
        static <T> RecoveryStrategy<T> exponentialBackoff(int maxRetries, Duration initialDelay, double multiplier) {
            return (error, attemptCount) -> {
                if (attemptCount <= maxRetries) {
                    try {
                        long delayMs = (long) (initialDelay.toMillis() * Math.pow(multiplier, attemptCount - 1));
                        Thread.sleep(delayMs);
                        return Try.failure(error); // 指示需要重试
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return Try.failure(new RuntimeException("指数退避重试被中断", ie));
                    }
                } else {
                    return Try.failure(new RuntimeException(
                        "指数退避重试" + maxRetries + "次后仍然失败", error));
                }
            };
        }

        /**
         * 降级策略
         */
        static <T> RecoveryStrategy<T> fallback(Function<Exception, T> fallbackFunction) {
            return (error, attemptCount) -> Try.of(() -> fallbackFunction.apply(error));
        }

        /**
         * 组合策略
         */
        default RecoveryStrategy<T> orElse(RecoveryStrategy<T> other) {
            return (error, attemptCount) -> {
                Try<T> result = this.recover(error, attemptCount);
                return result.isSuccess() ? result : other.recover(error, attemptCount);
            };
        }
    }

    // ==================== 熔断器模式 ====================

    /**
     * 函数式熔断器实现
     */
    public static class CircuitBreaker<T> {
        private final int failureThreshold;
        private final Duration timeout;
        private final Duration retryAfter;
        private volatile State state = State.CLOSED;
        private volatile int failureCount = 0;
        private volatile long lastFailureTime = 0;

        public CircuitBreaker(int failureThreshold, Duration timeout, Duration retryAfter) {
            this.failureThreshold = failureThreshold;
            this.timeout = timeout;
            this.retryAfter = retryAfter;
        }

        public enum State {
            CLOSED,    // 正常状态
            OPEN,      // 熔断状态
            HALF_OPEN  // 半开状态
        }

        /**
         * 执行带熔断保护的操作
         */
        public Try<T> execute(Supplier<T> operation) {
            if (state == State.OPEN) {
                if (shouldAttemptReset()) {
                    state = State.HALF_OPEN;
                } else {
                    return Try.failure(new RuntimeException("熔断器开启，拒绝请求"));
                }
            }

            Try<T> result = Try.of(operation).timeout(timeout);
            
            if (result.isSuccess()) {
                onSuccess();
            } else {
                onFailure();
            }

            return result;
        }

        private boolean shouldAttemptReset() {
            return System.currentTimeMillis() - lastFailureTime >= retryAfter.toMillis();
        }

        private void onSuccess() {
            failureCount = 0;
            state = State.CLOSED;
        }

        private void onFailure() {
            failureCount++;
            lastFailureTime = System.currentTimeMillis();
            
            if (failureCount >= failureThreshold) {
                state = State.OPEN;
            }
        }

        public State getState() {
            return state;
        }
    }

    // ==================== Try扩展方法 ====================

    public static class TryExtensions {
        
        /**
         * 带超时的Try操作
         */
        public static <T> Try<T> withTimeout(Supplier<T> operation, Duration timeout) {
            CompletableFuture<T> future = CompletableFuture.supplyAsync(operation);
            try {
                T result = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                return Try.success(result);
            } catch (Exception e) {
                future.cancel(true);
                return Try.failure(e);
            }
        }

        /**
         * 带重试的Try操作
         */
        public static <T> Try<T> withRetry(Supplier<T> operation, RecoveryStrategy<T> strategy) {
            int attemptCount = 0;
            Exception lastException = null;

            while (attemptCount < 10) { // 最大重试限制
                attemptCount++;
                Try<T> result = Try.of(operation);
                
                if (result.isSuccess()) {
                    return result;
                }

                lastException = result.getException();
                Try<T> recovery = strategy.recover(lastException, attemptCount);
                
                if (recovery.isSuccess()) {
                    return recovery;
                }

                // 如果恢复策略返回失败，检查是否应该继续重试
                if (recovery.getException().getMessage().contains("重试")) {
                    continue; // 继续重试
                } else {
                    return recovery; // 不再重试
                }
            }

            return Try.failure(new RuntimeException("超过最大重试次数", lastException));
        }

        /**
         * 带资源管理的Try操作
         */
        public static <T, R extends AutoCloseable> Try<T> withResource(
                Supplier<R> resourceSupplier, 
                Function<R, T> operation) {
            
            return Try.of(resourceSupplier).flatMap(resource -> {
                try (R r = resource) {
                    return Try.of(() -> operation.apply(r));
                } catch (Exception e) {
                    return Try.failure(e);
                }
            });
        }
    }

    // ==================== 缓存特定的错误处理 ====================

    /**
     * 缓存操作错误处理器
     */
    public static class CacheErrorHandler {
        
        private final RecoveryStrategy<Object> defaultStrategy;
        private final CircuitBreaker<Object> circuitBreaker;

        public CacheErrorHandler() {
            this.defaultStrategy = RecoveryStrategy.<Object>retry(3, Duration.ofMillis(100))
                    .orElse(RecoveryStrategy.withDefault(() -> null));
            this.circuitBreaker = new CircuitBreaker<>(5, Duration.ofSeconds(30), Duration.ofMinutes(1));
        }

        /**
         * 处理缓存获取操作
         */
        public <T> Try<Optional<T>> handleCacheGet(Supplier<Optional<T>> operation) {
            return circuitBreaker.execute(operation)
                    .recover(error -> {
                        log.warn("缓存获取失败，返回空值: {}", error.getMessage());
                        return Optional.<T>empty();
                    });
        }

        /**
         * 处理缓存存储操作
         */
        public Try<Void> handleCachePut(Supplier<Void> operation) {
            return TryExtensions.withRetry(operation, 
                    RecoveryStrategy.<Void>retry(2, Duration.ofMillis(50))
                            .orElse(RecoveryStrategy.withDefault(() -> {
                                log.error("缓存存储失败，操作被忽略");
                                return null;
                            })));
        }

        /**
         * 处理缓存加载操作
         */
        @SuppressWarnings("unchecked")
        public <T> Try<T> handleCacheLoad(Supplier<T> loader, T fallbackValue) {
            return ((CircuitBreaker<T>) circuitBreaker).execute(loader)
                    .recoverWith(error -> {
                        log.warn("缓存加载失败，使用降级值: {}", error.getMessage());
                        return Try.success(fallbackValue);
                    });
        }
    }
}