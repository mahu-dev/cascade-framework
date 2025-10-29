package io.github.cascade.cache.strategy;

import io.github.cascade.cache.function.AbstractTry;
import io.github.cascade.cache.function.Try;
import lombok.Getter;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * 函数式熔断器实现
 * <p>
 * 熔断器模式用于在分布式系统中防止级联故障，当检测到失败率过高时，
 * 会暂时阻止对失败服务的调用，给系统恢复的机会。
 *
 * @param <T> 操作返回值类型
 * @author cascade
 */
public class CircuitBreaker<T> {
    private final int failureThreshold;
    private final Duration timeout;
    private final Duration retryAfter;
    @Getter
    private volatile State state = State.CLOSED;
    private final AtomicInteger failureCount = new AtomicInteger();
    private volatile long lastFailureTime;

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
    public AbstractTry<T> execute(Supplier<T> operation) {
        if (state == State.OPEN) {
            if (shouldAttemptReset()) {
                state = State.HALF_OPEN;
            } else {
                return Try.failure(new RuntimeException("熔断器开启，拒绝请求"));
            }
        }

        AbstractTry<T> result = Try.of(operation).timeout(timeout);

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
        failureCount.set(0);
        state = State.CLOSED;
    }

    private void onFailure() {
        int currentFailures = failureCount.incrementAndGet();
        lastFailureTime = System.currentTimeMillis();

        if (currentFailures >= failureThreshold) {
            state = State.OPEN;
        }
    }

}