package cc.coderm.cascade.limiter.model;

/**
 * 限流算法类型
 *
 * <ul>
 *   <li>FIXED_WINDOW   - 固定窗口：实现最简单，存在窗口切换时突刺问题</li>
 *   <li>SLIDING_WINDOW - 滑动窗口：基于 ZSet 时间戳，精度高，内存稍大</li>
 *   <li>TOKEN_BUCKET   - 令牌桶：允许短时突发，速率平滑，Redisson 原生支持</li>
 *   <li>LEAKY_BUCKET   - 漏桶：严格匀速输出，适合保护下游服务</li>
 * </ul>
 */
public enum AlgorithmType {
    FIXED_WINDOW,
    SLIDING_WINDOW,
    TOKEN_BUCKET,
    LEAKY_BUCKET
}