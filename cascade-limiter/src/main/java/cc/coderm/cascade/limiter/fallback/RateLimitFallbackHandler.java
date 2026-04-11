package cc.coderm.cascade.limiter.fallback;

import cc.coderm.cascade.limiter.model.RateLimitResult;

/**
 * 全局限流 fallback SPI 接口。
 *
 * <p>实现此接口并注册为 Spring Bean，可作为全局兜底策略。
 * 优先级低于注解中 {@code fallbackMethod} 指定的本地方法。
 *
 * <p>典型用途：统一返回降级响应 / 发送告警 / 写入审计日志。
 */
public interface RateLimitFallbackHandler {

    /**
     * 处理限流触发事件。
     *
     * @param result  限流结果（含 key / algorithm / waitMs）
     * @param context 触发限流的上下文描述（类名 + 方法名）
     * @return 可选的降级返回值（AOP 会尝试将其作为方法返回值；void 方法忽略）
     */
    Object handle(RateLimitResult result, String context);
}
