package cc.coderm.cascade.limiter.exception;

import cc.coderm.cascade.limiter.model.RateLimitResult;
import lombok.Getter;

/**
 * 触发限流时抛出的异常。
 * HTTP 场景建议全局捕获并返回 429 状态码。
 */
@Getter
public class RateLimitException extends RuntimeException {

    private final RateLimitResult result;

    public RateLimitException(RateLimitResult result, String message) {
        super(message);
        this.result = result;
    }

    public RateLimitException(RateLimitResult result) {
        this(result, "Rate limit exceeded for key: " + result.getLimitKey());
    }
}