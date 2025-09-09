package io.github.cascade.cache.simple;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Try扩展工具类
 * <p>
 * 提供Try模式的扩展操作，包括超时、重试和资源管理功能
 *
 * @author cascade
 */
public final class TryExtensions {
    
    private TryExtensions() {
        // 私有构造函数防止实例化工具类
    }

    /**
     * 带超时的Try操作
     */
    public static <T> AbstractTry<T> withTimeout(Supplier<T> operation, Duration timeout) {
        CompletableFuture<T> future = CompletableFuture.supplyAsync(operation);
        try {
            T result = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return AbstractTry.success(result);
        } catch (Exception e) {
            future.cancel(true);
            return AbstractTry.failure(e);
        }
    }

    /**
     * 带重试的Try操作
     */
    public static <T> AbstractTry<T> withRetry(Supplier<T> operation, 
                                                               RecoveryStrategy<T> strategy) {
        int attemptCount = 0;
        Exception lastException = null;
        final int maxAttempts = 10; // 最大重试限制

        while (attemptCount < maxAttempts) {
            attemptCount++;
            AbstractTry<T> result = AbstractTry.of(operation);

            if (result.isSuccess()) {
                return result;
            }

            lastException = result.getException();
            AbstractTry<T> recovery = strategy.recover(lastException, attemptCount);

            if (recovery.isSuccess()) {
                return recovery;
            }

            // 如果恢复策略返回失败，检查是否应该继续重试
            if (!recovery.getException().getMessage().contains("重试")) {
                return recovery; // 不再重试
            }
            // 否则继续重试
        }

        return AbstractTry.failure(new RuntimeException("超过最大重试次数", lastException));
    }

    /**
     * 带资源管理的Try操作
     */
    public static <T, R extends AutoCloseable> AbstractTry<T> withResource(
            Supplier<R> resourceSupplier,
            Function<R, T> operation) {

        return AbstractTry.of(resourceSupplier).flatMap(resource -> {
            try (R r = resource) {
                return AbstractTry.of(() -> operation.apply(r));
            } catch (Exception e) {
                return AbstractTry.failure(e);
            }
        });
    }
}