package cc.coderm.cascade.limiter.strategy;

import cc.coderm.cascade.limiter.model.AlgorithmType;
import lombok.Builder;
import lombok.Getter;

import java.time.Duration;

/**
 * 限流策略参数，聚合所有算法所需配置。
 *
 * <p>不同算法只使用其中部分字段：
 * <ul>
 *   <li>固定窗口 / 滑动窗口：maxPermits + window</li>
 *   <li>令牌桶：maxPermits + refillRate（每秒补充令牌数）</li>
 *   <li>漏桶：maxPermits（桶容量）+ leakRate（每秒漏出请求数）</li>
 * </ul>
 */
@Getter
public final class RateLimitStrategy {

    private static final AlgorithmType DEFAULT_ALGORITHM = AlgorithmType.TOKEN_BUCKET;
    private static final long DEFAULT_MAX_PERMITS = 100L;
    private static final Duration DEFAULT_WINDOW = Duration.ofSeconds(1);
    private static final String DEFAULT_MESSAGE = "Too many requests, please try again later.";

    /**
     * 算法类型
     */
    private final AlgorithmType algorithmType;

    /**
     * 窗口/桶内最大请求数（固定窗口、滑动窗口、漏桶）
     * 或令牌桶最大令牌容量
     */
    private final long maxPermits;

    /**
     * 固定/滑动窗口大小
     */
    private final Duration window;

    /**
     * 令牌桶：每秒补充令牌速率。0 表示自动回退为 maxPermits。
     */
    private final long refillRate;

    /**
     * 漏桶：每秒漏出（处理）的请求数。0 表示自动回退为 maxPermits。
     */
    private final long leakRate;

    /**
     * 自定义限流 key（注解场景由 SpEL 解析后传入）
     */
    private final String customKey;

    /**
     * 限流触发时的自定义提示信息
     */
    private final String message;

    @Builder
    private RateLimitStrategy(AlgorithmType algorithmType,
                              Long maxPermits,
                              Duration window,
                              Long refillRate,
                              Long leakRate,
                              String customKey,
                              String message) {
        this.algorithmType = algorithmType == null ? DEFAULT_ALGORITHM : algorithmType;
        this.maxPermits = maxPermits == null ? DEFAULT_MAX_PERMITS : maxPermits;
        this.window = window == null ? DEFAULT_WINDOW : window;
        this.refillRate = refillRate == null ? 0L : refillRate;
        this.leakRate = leakRate == null ? 0L : leakRate;
        this.customKey = customKey;
        this.message = message == null ? DEFAULT_MESSAGE : message;
        validate();
    }

    public long effectiveRefillRate() {
        return refillRate > 0 ? refillRate : maxPermits;
    }

    public long effectiveLeakRate() {
        return leakRate > 0 ? leakRate : maxPermits;
    }

    private void validate() {
        if (maxPermits <= 0) {
            throw new IllegalArgumentException("RateLimitStrategy maxPermits must be > 0");
        }
        if (window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("RateLimitStrategy window must be > 0");
        }
        if (refillRate < 0) {
            throw new IllegalArgumentException("RateLimitStrategy refillRate must be >= 0");
        }
        if (leakRate < 0) {
            throw new IllegalArgumentException("RateLimitStrategy leakRate must be >= 0");
        }
    }
}
