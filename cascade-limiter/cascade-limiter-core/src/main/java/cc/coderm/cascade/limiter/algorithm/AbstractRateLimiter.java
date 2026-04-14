package cc.coderm.cascade.limiter.algorithm;

import cc.coderm.cascade.limiter.model.RateLimitResult;
import cc.coderm.cascade.limiter.strategy.RateLimitStrategy;

/**
 * 限流算法抽象基类。
 *
 * <p>提供 {@link #acquire} 的通用轮询实现：反复调用 {@link #tryAcquire}，
 * 按返回的 {@code waitMillis} 自适应 sleep，直到获取到配额或超时。
 *
 * <p>各算法子类只需实现 {@link #tryAcquire(String, RateLimitStrategy, int)}
 * 和 {@link #getAlgorithmType()}，无需重复编写阻塞逻辑。
 *
 * <p>若某算法有更高效的阻塞方式（如 Redisson RRateLimiter 原生支持），
 * 可以直接在子类 override {@link #acquire}。
 */
public abstract class AbstractRateLimiter implements RateLimiter {

    /**
     * 轮询最小间隔（毫秒），防止 waitMillis=0 时 CPU 空转
     */
    private static final long MIN_SLEEP_MS = 5;

    /**
     * 轮询最大间隔（毫秒），避免 waitMillis 过大导致响应迟钝
     */
    private static final long MAX_SLEEP_MS = 100;

    /**
     * 阻塞式获取配额。
     *
     * <p>算法：
     * <pre>
     * deadline = now + timeoutMs
     * loop:
     *   result = tryAcquire(key, strategy, 1)
     *   if allowed  → return true
     *   sleep min(result.waitMillis, remaining, MAX_SLEEP_MS)
     *   if now >= deadline → return false
     * </pre>
     *
     * @param key       限流 key
     * @param strategy  策略参数
     * @param timeoutMs 最大等待毫秒，{@code <= 0} 表示无限等待
     */
    @Override
    public boolean acquire(String key, RateLimitStrategy strategy, long timeoutMs)
            throws InterruptedException {
        boolean infinite = timeoutMs <= 0;
        long deadline = infinite ? Long.MAX_VALUE : System.currentTimeMillis() + timeoutMs;

        while (true) {
            RateLimitResult result = tryAcquire(key, strategy, 1);
            if (result.isAllowed()) {
                return true;
            }

            long now = System.currentTimeMillis();
            if (!infinite && now >= deadline) {
                return false;
            }

            long remaining = infinite ? MAX_SLEEP_MS : (deadline - now);
            long hint = result.getWaitMillis();
            long sleepMs = clamp(hint > 0 ? hint : MIN_SLEEP_MS, MIN_SLEEP_MS, Math.min(remaining, MAX_SLEEP_MS));

            Thread.sleep(sleepMs);
        }
    }

    /**
     * 将值限制在指定范围内
     * <p>
     * 如果 value 小于 min，返回 min；如果 value 大于 max，返回 max；
     * 否则返回 value 本身。
     *
     * @param value 待限制的值
     * @param min   最小值边界
     * @param max   最大值边界
     * @return 限制后的值，保证在 [min, max] 范围内
     */
    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(value, max));
    }
}