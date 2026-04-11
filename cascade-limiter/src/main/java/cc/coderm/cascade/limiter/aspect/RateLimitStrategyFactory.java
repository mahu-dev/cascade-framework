package cc.coderm.cascade.limiter.aspect;

import cc.coderm.cascade.limiter.annotation.RateLimit;
import cc.coderm.cascade.limiter.strategy.RateLimitStrategy;

import java.time.Duration;

/**
 * 限流策略构建工具类
 * <p>
 * 负责从 {@link RateLimit} 注解构建 {@link RateLimitStrategy} 对象。
 * 无状态工具类，所有方法均为静态方法。
 *
 * <p>功能职责：
 * <ul>
 *   <li>将注解配置转换为限流策略对象</li>
 *   <li>计算窗口时长（ChronoUnit × windowSize）</li>
 *   <li>设置所有策略参数（算法类型、配额、速率等）</li>
 * </ul>
 *
 * @see RateLimit
 * @see RateLimitStrategy
 */
final class RateLimitStrategyFactory {

    private RateLimitStrategyFactory() {
        /* This utility class should not be instantiated */
    }

    /**
     * 从 @RateLimit 注解构建限流策略对象
     * <p>
     * 将注解中配置的各个参数转换为 {@link RateLimitStrategy} 对象，
     * 用于后续的限流判断。
     * <p>
     * <b>转换映射：</b>
     * <ul>
     *   <li>{@code algorithm()} → {@code RateLimitStrategy.algorithmType}</li>
     *   <li>{@code maxPermits()} → {@code RateLimitStrategy.maxPermits}</li>
     *   <li>{@code windowSize() × windowUnit()} → {@code RateLimitStrategy.window}</li>
     *   <li>{@code refillRate()} → {@code RateLimitStrategy.refillRate}</li>
     *   <li>{@code leakRate()} → {@code RateLimitStrategy.leakRate}</li>
     *   <li>{@code message()} → {@code RateLimitStrategy.message}</li>
     * </ul>
     *
     * @param rateLimit @RateLimit 注解实例，包含用户配置的限流参数
     * @return 构建好的限流策略对象，可直接用于限流判断
     */
    static RateLimitStrategy fromAnnotation(RateLimit rateLimit) {
        // 计算窗口时长：ChronoUnit.getDuration() 返回单位对应标准 Duration
        // 例如：SECONDS.getDuration() = PT1S，乘以 windowSize 得到总时长
        Duration window = rateLimit.windowUnit().getDuration().multipliedBy(rateLimit.windowSize());
        return RateLimitStrategy.builder()
                .algorithmType(rateLimit.algorithm())
                .maxPermits(rateLimit.maxPermits())
                .window(window)
                .refillRate(rateLimit.refillRate())
                .leakRate(rateLimit.leakRate())
                .message(rateLimit.message())
                .build();
    }
}
