package io.github.cascade.cache.simple;

import java.time.Duration;

/**
 * 指数退避重试恢复策略实现
 * <p>
 * 将复杂的指数退避逻辑从lambda中提取出来，
 * 遵循SonarQube关于lambda复杂度的建议。
 *
 * @param <T> 操作返回值类型
 * @author cascade
 */
final class ExponentialBackoffRecoveryStrategy<T> implements RecoveryStrategy<T> {
    
    private final int maxRetries;
    private final Duration initialDelay;
    private final double multiplier;
    
    ExponentialBackoffRecoveryStrategy(int maxRetries, Duration initialDelay, double multiplier) {
        this.maxRetries = maxRetries;
        this.initialDelay = initialDelay;
        this.multiplier = multiplier;
    }
    
    @Override
    public AbstractTry<T> recover(Exception error, int attemptCount) {
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
    }
}