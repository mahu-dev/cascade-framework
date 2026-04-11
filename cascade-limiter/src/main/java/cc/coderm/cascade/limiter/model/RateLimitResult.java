package cc.coderm.cascade.limiter.model;

import lombok.Builder;
import lombok.Getter;

/**
 * 限流判断结果
 */
@Getter
@Builder
public class RateLimitResult {

    /**
     * 是否允许通过
     */
    private final boolean allowed;

    /**
     * 剩余可用配额（令牌桶 / 固定窗口有效，其他算法返回 -1）
     */
    private final long remainingPermits;

    /**
     * 距离下次可获取配额的等待时间（毫秒），allowed=true 时为 0
     */
    private final long waitMillis;

    /**
     * 触发限流的 key
     */
    private final String limitKey;

    /**
     * 使用的算法
     */
    private final AlgorithmType algorithmType;

    public static RateLimitResult allowed(String key, AlgorithmType type, long remaining) {
        return RateLimitResult.builder()
                .allowed(true)
                .remainingPermits(remaining)
                .waitMillis(0)
                .limitKey(key)
                .algorithmType(type)
                .build();
    }

    public static RateLimitResult rejected(String key, AlgorithmType type, long waitMillis) {
        return RateLimitResult.builder()
                .allowed(false)
                .remainingPermits(0)
                .waitMillis(waitMillis)
                .limitKey(key)
                .algorithmType(type)
                .build();
    }
}