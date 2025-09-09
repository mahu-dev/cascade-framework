package io.github.cascade.cache.simple;

import java.time.Duration;

/**
 * 重试恢复策略实现
 * <p>
 * 将复杂的重试逻辑从lambda中提取出来，
 * 遵循SonarQube关于lambda复杂度的建议。
 *
 * @param <T> 操作返回值类型
 * @author cascade
 */
final class RetryRecoveryStrategy<T> implements RecoveryStrategy<T> {
    
    private final int maxRetries;
    private final Duration delay;
    
    RetryRecoveryStrategy(int maxRetries, Duration delay) {
        this.maxRetries = maxRetries;
        this.delay = delay;
    }
    
    @Override
    public AbstractTry<T> recover(Exception error, int attemptCount) {
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
    }
}