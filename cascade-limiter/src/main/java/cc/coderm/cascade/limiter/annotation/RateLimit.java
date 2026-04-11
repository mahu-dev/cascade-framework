package cc.coderm.cascade.limiter.annotation;

import cc.coderm.cascade.limiter.model.AlgorithmType;

import java.lang.annotation.*;
import java.time.temporal.ChronoUnit;

/**
 * 声明式限流注解，基于 AOP 拦截。
 *
 * <p>使用示例：
 * <pre>{@code
 * // 基于 IP + userId 的令牌桶限流，100 次/秒
 * @RateLimit(
 *     key = "'api:order:' + #userId",
 *     maxPermits = 100,
 *     algorithm = AlgorithmType.TOKEN_BUCKET
 * )
 * public OrderDTO createOrder(String userId, OrderRequest req) { ... }
 *
 * // 固定窗口，整个方法共享 key
 * @RateLimit(
 *     key = "'api:sms:send'",
 *     maxPermits = 50,
 *     windowSize = 60,
 *     windowUnit = ChronoUnit.SECONDS,
 *     algorithm = AlgorithmType.FIXED_WINDOW,
 *     fallbackMethod = "sendSmsFallback"
 * )
 * public void sendSms(String phone) { ... }
 * }</pre>
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RateLimit {

    /**
     * 限流 key 表达式（SpEL）。
     * 可引用方法参数（#paramName）。
     * 当配置 {@code cascade.limiter.key-expression-safe-mode=false} 时，
     * 才允许使用 Spring Bean（@beanName）等高风险能力。
     * 默认使用 类名:方法名 作为 key。
     */
    String key() default "";

    /**
     * 时间窗口或桶内最大允许请求数
     */
    long maxPermits() default 100;

    /**
     * 窗口大小（固定窗口 / 滑动窗口有效）
     */
    long windowSize() default 1;

    /**
     * 窗口时间单位
     */
    ChronoUnit windowUnit() default ChronoUnit.SECONDS;

    /**
     * 令牌桶：每秒补充令牌数。默认 = maxPermits（满速补充）。
     * 仅 TOKEN_BUCKET 算法有效。
     */
    long refillRate() default 0;

    /**
     * 漏桶：每秒漏出请求数。默认 = maxPermits。
     * 仅 LEAKY_BUCKET 算法有效。
     */
    long leakRate() default 0;

    /**
     * 限流算法
     */
    AlgorithmType algorithm() default AlgorithmType.TOKEN_BUCKET;

    /**
     * 限流触发时执行的 fallback 方法名。
     * 方法签名必须与原方法相同，可额外追加 {@link cc.coderm.cascade.limiter.model.RateLimitResult} 参数。
     * 若不指定，则抛出 {@link cc.coderm.cascade.limiter.exception.RateLimitException}。
     */
    String fallbackMethod() default "";

    /**
     * 触发限流时的提示消息（异常消息 / fallback 可读取）
     */
    String message() default "Too many requests, please try again later.";
}
