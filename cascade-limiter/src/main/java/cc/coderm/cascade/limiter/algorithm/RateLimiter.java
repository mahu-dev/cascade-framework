package cc.coderm.cascade.limiter.algorithm;

import cc.coderm.cascade.limiter.model.AlgorithmType;
import cc.coderm.cascade.limiter.model.RateLimitResult;
import cc.coderm.cascade.limiter.strategy.RateLimitStrategy;

/**
 * 限流器核心接口，所有算法实现此接口。
 */
public interface RateLimiter {

    /**
     * 尝试获取 1 个配额（非阻塞）。
     *
     * @param key      限流 key（已拼接前缀）
     * @param strategy 策略参数
     * @return 限流结果
     */
    default RateLimitResult tryAcquire(String key, RateLimitStrategy strategy) {
        return tryAcquire(key, strategy, 1);
    }

    /**
     * 尝试获取 permits 个配额（非阻塞）。
     *
     * @param key      限流 key
     * @param strategy 策略参数
     * @param permits  请求的配额数量
     * @return 限流结果
     */
    RateLimitResult tryAcquire(String key, RateLimitStrategy strategy, int permits);

    /**
     * 阻塞直到获取到配额或超时。
     *
     * @param key       限流 key
     * @param strategy  策略参数
     * @param timeoutMs 最大等待时间（毫秒），<= 0 表示无限等待
     * @return 是否成功获取
     */
    boolean acquire(String key, RateLimitStrategy strategy, long timeoutMs) throws InterruptedException;

    /**
     * 返回此实现支持的算法类型。
     */
    AlgorithmType getAlgorithmType();
}