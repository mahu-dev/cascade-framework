package cc.coderm.cascade.limiter.factory;

import cc.coderm.cascade.limiter.algorithm.RateLimiter;
import cc.coderm.cascade.limiter.model.AlgorithmType;
import cc.coderm.cascade.limiter.model.RateLimitResult;
import cc.coderm.cascade.limiter.strategy.RateLimitStrategy;
import cc.coderm.cascade.limiter.support.KeyAdmissionDecision;
import cc.coderm.cascade.limiter.support.KeyAdmissionGuard;
import lombok.extern.slf4j.Slf4j;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 限流器工厂：根据 {@link AlgorithmType} 路由到对应的算法实现。
 *
 * <p>使用 Spring 自动注入所有 {@link RateLimiter} 实现，无需手动注册。
 * 新增算法只需实现接口并声明为 Spring Bean，工厂自动感知。
 */
@Slf4j
public class RateLimiterFactory {

    private final Map<AlgorithmType, RateLimiter> limiterMap = new EnumMap<>(AlgorithmType.class);
    private final KeyAdmissionGuard keyAdmissionGuard;

    public RateLimiterFactory(List<RateLimiter> rateLimiters) {
        this(rateLimiters, KeyAdmissionGuard.allowAll());
    }

    public RateLimiterFactory(List<RateLimiter> rateLimiters,
                              KeyAdmissionGuard keyAdmissionGuard) {
        this.keyAdmissionGuard = keyAdmissionGuard == null ? KeyAdmissionGuard.allowAll() : keyAdmissionGuard;
        for (RateLimiter limiter : rateLimiters) {
            limiterMap.put(limiter.getAlgorithmType(), limiter);
            log.info("[CascadeLimiter] Registered algorithm: {}", limiter.getAlgorithmType());
        }
    }

    /**
     * 获取指定算法的限流器实例。
     */
    public RateLimiter getLimiter(AlgorithmType type) {
        RateLimiter limiter = limiterMap.get(type);
        if (limiter == null) {
            throw new IllegalArgumentException(
                    "[CascadeLimiter] No RateLimiter implementation found for: " + type);
        }
        return limiter;
    }

    /**
     * 便捷方法：按策略执行限流判断。
     *
     * @param key      完整的 Redis key（已含前缀）
     * @param strategy 策略参数
     * @return 限流结果
     */
    public RateLimitResult tryAcquire(String key, RateLimitStrategy strategy) {
        return tryAcquire(key, strategy, 1);
    }

    /**
     * 便捷方法：按策略执行限流判断（批量配额）。
     */
    public RateLimitResult tryAcquire(String key, RateLimitStrategy strategy, int permits) {
        if (strategy == null) {
            throw new IllegalArgumentException("RateLimitStrategy must not be null");
        }
        if (permits <= 0) {
            throw new IllegalArgumentException("Requested permits must be > 0");
        }
        KeyAdmissionDecision admission = keyAdmissionGuard.admit(key);
        if (!admission.allowed()) {
            return RateLimitResult.rejected(key, strategy.getAlgorithmType(), admission.waitMillis());
        }
        RateLimitResult raw = getLimiter(strategy.getAlgorithmType())
                .tryAcquire(admission.effectiveKey(), strategy, permits);
        return normalizeResultKey(raw, key);
    }

    /**
     * 阻塞式获取配额（含 key 准入守卫）。
     */
    public boolean acquire(String key, RateLimitStrategy strategy, long timeoutMs) throws InterruptedException {
        if (strategy == null) {
            throw new IllegalArgumentException("RateLimitStrategy must not be null");
        }
        KeyAdmissionDecision admission = keyAdmissionGuard.admit(key);
        if (!admission.allowed()) {
            return false;
        }
        return getLimiter(strategy.getAlgorithmType()).acquire(admission.effectiveKey(), strategy, timeoutMs);
    }

    /**
     * 规范化限流结果中的 key，确保返回原始请求的 key
     * <p>
     * 由于 {@link KeyAdmissionGuard} 可能会对 key 进行修改（例如添加命名空间前缀实现 key 隔离），
     * 限流算法返回的结果中包含的是修改后的 effectiveKey。
     * 此方法将结果中的 key 恢复为用户原始请求的 key，保持 API 的透明性和一致性。
     * <p>
     * <b>为什么要恢复 key：</b>
     * <ul>
     *   <li><b>API 一致性</b>：用户传入 key="api:user:123"，结果中也应该是这个 key，而不是带前缀的 "ns1:api:user:123"</li>
     *   <li><b>日志可读性</b>：日志中显示的 key 应与业务代码中使用的一致，便于问题排查</li>
     *   <li><b>隐藏实现细节</b>：key 准入守卫的实现细节（如前缀、哈希）不应暴露给业务层</li>
     * </ul>
     * <p>
     * <b>处理逻辑：</b>
     * <ol>
     *   <li>如果原始结果为 null 或 key 已一致，直接返回原结果（无需处理）</li>
     *   <li>如果限流通过，创建新的 allowed 结果，使用原始 key</li>
     *   <li>如果限流拒绝，创建新的 rejected 结果，使用原始 key</li>
     * </ol>
     * <p>
     * <b>示例：</b>
     * <pre>
     * // 用户请求
     * factory.tryAcquire("api:user:123", strategy)
     *
     * // KeyAdmissionGuard 添加命名空间前缀
     * admission.effectiveKey() = "tenant1:api:user:123"
     *
     * // 限流算法返回（使用 effectiveKey）
     * raw = RateLimitResult.allowed("tenant1:api:user:123", ...)
     *
     * // 本方法恢复原始 key
     * normalized = RateLimitResult.allowed("api:user:123", ...)
     * </pre>
     *
     * @param raw         限流算法返回的原始结果，其中的 key 可能已被 KeyAdmissionGuard 修改
     * @param originalKey 用户原始请求的 key（未经过 KeyAdmissionGuard 处理）
     * @return 规范化后的限流结果，其中的 key 恢复为原始值
     */
    private static RateLimitResult normalizeResultKey(RateLimitResult raw, String originalKey) {
        // 边界情况：结果为 null 或 key 已一致，无需处理
        if (raw == null || originalKey.equals(raw.getLimitKey())) {
            return raw;
        }
        // 限流通过：创建新的 allowed 结果，使用原始 key（保持剩余配额等信息）
        if (raw.isAllowed()) {
            return RateLimitResult.allowed(originalKey, raw.getAlgorithmType(), raw.getRemainingPermits());
        }
        // 限流拒绝：创建新的 rejected 结果，使用原始 key（保持等待时间等信息）
        return RateLimitResult.rejected(originalKey, raw.getAlgorithmType(), raw.getWaitMillis());
    }
}
