package io.github.cascade.cache.synchronization;

import io.github.cascade.cache.common.AbstractTry;
import io.github.cascade.cache.common.Try;
import java.time.Duration;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 错误恢复策略接口
 * <p>
 * 提供多种错误恢复策略，包括立即失败、默认值返回、重试和降级等，
 * 支持策略组合以构建复杂的错误处理逻辑。
 *
 * @param <T> 操作返回值类型
 * @author cascade
 */
@FunctionalInterface
public interface RecoveryStrategy<T> {

    /**
     * 执行错误恢复逻辑
     *
     * @param error        发生的异常
     * @param attemptCount 当前尝试次数
     * @return 恢复结果
     */
    AbstractTry<T> recover(Exception error, int attemptCount);

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
        return new RetryRecoveryStrategy<>(maxRetries, delay);
    }

    /**
     * 指数退避重试策略
     */
    static <T> RecoveryStrategy<T> exponentialBackoff(int maxRetries, Duration initialDelay, double multiplier) {
        return new ExponentialBackoffRecoveryStrategy<>(maxRetries, initialDelay, multiplier);
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
        return (Exception error, int attemptCount) -> {
            AbstractTry<T> result = this.recover(error, attemptCount);
            return result.isSuccess() ? result : other.recover(error, attemptCount);
        };
    }
}