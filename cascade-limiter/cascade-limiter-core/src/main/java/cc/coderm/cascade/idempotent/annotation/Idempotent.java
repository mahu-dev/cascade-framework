package cc.coderm.cascade.idempotent.annotation;

import cc.coderm.cascade.idempotent.model.ConflictStrategy;

import java.lang.annotation.*;
import java.util.concurrent.TimeUnit;

/**
 * 声明式幂等注解，基于 AOP + Redis 实现分布式幂等控制。
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 基于请求参数的幂等，成功后保留记录 10 分钟
 * @Idempotent(
 *     key = "'order:create:' + #req.orderNo",
 *     ttl = 10, ttlUnit = TimeUnit.MINUTES,
 *     scene = "order"
 * )
 * public OrderDTO createOrder(CreateOrderRequest req) { ... }
 *
 * // 一次性操作（成功后立即删除 key，允许重新执行）
 * @Idempotent(
 *     key = "'sms:' + #phone + ':' + #templateId",
 *     ttl = 5, ttlUnit = TimeUnit.MINUTES,
 *     deleteOnSuccess = true,
 *     scene = "sms"
 * )
 * public void sendSms(String phone, String templateId) { ... }
 *
 * // 并发冲突时执行 fallback，而不是等待
 * @Idempotent(
 *     key = "'pay:' + #paymentId",
 *     conflictStrategy = ConflictStrategy.FALLBACK,
 *     fallbackMethod = "payFallback",
 *     scene = "payment"
 * )
 * public PayResult pay(String paymentId) { ... }
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Idempotent {

    /**
     * 幂等 key 表达式（SpEL）。
     * 支持方法参数（#paramName）、Spring Bean（@bean.method()）、
     * 以及从 HttpServletRequest Header 取值（使用 {@code #idempotentHeader} 内置变量，
     * Header 名可通过配置 {@code cascade.idempotent.idempotent-header-name} 调整）。
     *
     * <p>若不填，默认使用 {@code 类名:方法名:参数hash} 作为 key。
     */
    String key() default "";

    /**
     * 幂等记录的存活时间
     */
    long ttl() default 24;

    /**
     * TTL 时间单位，默认小时
     */
    TimeUnit ttlUnit() default TimeUnit.HOURS;

    /**
     * 场景标识，用于在 Redis key 中区分不同业务，
     * 也用于日志/指标的 tag。
     */
    String scene() default "default";

    /**
     * 业务成功后是否立即删除幂等 key。
     * true  = 一次性操作（发短信、发邮件），成功后可以重新触发。
     * false = 持久幂等（创建订单），TTL 内重复请求一律返回第一次结果。
     */
    boolean deleteOnSuccess() default false;

    /**
     * 业务失败（抛出异常）后是否删除幂等 key。
     * true  = 允许重试（网络超时等可重试异常）。
     * false = 失败后锁定，TTL 内重复请求直接返回失败（防止重复扣款等）。
     */
    boolean deleteOnFailure() default true;

    /**
     * 同一 key 处于 PROCESSING（并发请求正在执行）时的冲突策略。
     */
    ConflictStrategy conflictStrategy() default ConflictStrategy.WAIT;

    /**
     * WAIT 策略的最大等待时间（毫秒）。
     * 超过此时间仍未完成则抛出 {@link cc.coderm.cascade.idempotent.exception.IdempotentConflictException}。
     */
    long waitTimeoutMs() default 5000;

    /**
     * 并发冲突时执行的本地 fallback 方法名。
     * 仅当 {@link #conflictStrategy()} 为 {@link ConflictStrategy#FALLBACK} 时生效。
     * 方法签名须与原方法相同，或额外追加 {@link cc.coderm.cascade.idempotent.model.IdempotentRecord} 参数。
     * 不指定则抛出对应异常。
     */
    String fallbackMethod() default "";

    /**
     * 自定义提示消息（异常 message 或 fallback 可读取）
     */
    String message() default "Duplicate request, please do not submit again.";
}
