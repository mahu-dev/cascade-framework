package cc.coderm.cascade.limiter.aspect;

import cc.coderm.cascade.limiter.config.CascadeLimiterProperties;
import cc.coderm.cascade.limiter.factory.RateLimiterFactory;
import cc.coderm.cascade.limiter.model.RateLimitResult;
import cc.coderm.cascade.limiter.strategy.RateLimitStrategy;
import cc.coderm.cascade.limiter.support.LimiterBackendFailureTracker;
import cc.coderm.cascade.limiter.support.LimiterExceptionClassifier;
import lombok.extern.slf4j.Slf4j;

/**
 * 限流配额获取策略组件
 * <p>
 * 负责执行限流判断并处理后端故障降级策略，是 {@link RateLimitAspect} 的核心协作器之一。
 * 与静态工具类不同，此类需要持有依赖组件的引用，因此设计为实例组件。
 * <p>
 * <b>职责范围：</b>
 * <ul>
 *   <li><b>配额获取</b>：委托 {@link RateLimiterFactory} 执行实际的限流算法</li>
 *   <li><b>异常分类</b>：区分后端异常和非后端异常，采取不同处理策略</li>
 *   <li><b>故障降级</b>：根据 {@code failOnError} 配置执行 fail-closed 或 fail-open 策略</li>
 *   <li><b>故障追踪</b>：记录连续故障次数，触发监控告警</li>
 * </ul>
 * <p>
 * <b>为什么设计为实例组件：</b>
 * <pre>
 * // 需要持有以下依赖组件的引用：
 * 1. RateLimiterFactory        - 执行限流算法
 * 2. CascadeLimiterProperties  - 读取 failOnError 配置
 * 3. LimiterBackendFailureTracker - 记录故障状态
 *
 * // 这些依赖在构造时注入，每个 RateLimitAspect 实例拥有独立的策略实例
 * </pre>
 * <p>
 * <b>降级策略对比：</b>
 * <table border="1">
 *   <tr><th>策略</th><th>行为</th><th>适用场景</th><th>优缺点</th></tr>
 *   <tr><td>fail-closed</td><td>拒绝所有请求</td><td>金融、支付等严格场景</td>
 *   <td>✓ 准确  ✗ 可用性低</td></tr>
 *   <tr><td>fail-open</td><td>允许所有请求</td><td>一般业务、高可用场景</td>
 *   <td>✓ 可用性高  ✗ 可能超限</td></tr>
 * </table>
 * <p>
 * <b>使用示例：</b>
 * <pre>
 * // 在 RateLimitAspect 中构造
 * RateLimitAcquirePolicy policy = new RateLimitAcquirePolicy(
 *     limiterFactory, properties, backendFailureTracker
 * );
 *
 * // 执行限流判断
 * RateLimitResult result = policy.tryAcquire("api:user:123", strategy);
 * if (result.isAllowed()) {
 *     // 请求通过
 * } else {
 *     // 请求被限流
 * }
 * </pre>
 *
 * @see RateLimiterFactory
 * @see LimiterBackendFailureTracker
 * @see CascadeLimiterProperties#isFailOnError()
 */
@Slf4j
final class RateLimitAcquirePolicy {

    private final RateLimiterFactory limiterFactory;
    private final CascadeLimiterProperties properties;
    private final LimiterBackendFailureTracker backendFailureTracker;

    RateLimitAcquirePolicy(RateLimiterFactory limiterFactory,
                           CascadeLimiterProperties properties,
                           LimiterBackendFailureTracker backendFailureTracker) {
        this.limiterFactory = limiterFactory;
        this.properties = properties;
        this.backendFailureTracker = backendFailureTracker;
    }

    /**
     * 尝试获取限流配额，并根据策略处理后端故障
     * <p>
     * 调用 {@link RateLimiterFactory#tryAcquire} 尝试获取限流配额，
     * 并在发生后端故障时根据 {@code failOnError} 配置决定降级策略：
     * <ul>
     *   <li><b>成功</b>：返回限流结果，记录成功指标</li>
     *   <li><b>后端故障（fail-closed）</b>：记录失败指标，返回拒绝结果（0 等待时间）</li>
     *   <li><b>后端故障（fail-open）</b>：记录失败指标，返回允许结果（-1 等待时间）</li>
     * </ul>
     * <p>
     * <b>降级策略说明：</b>
     * <pre>
     * fail-closed（默认）：保守策略，拒绝请求。适用于对限流要求严格的场景。
     *                      优点：保证限流准确性
     *                      缺点：后端故障期间所有请求被拒绝
     *
     * fail-open（可选）：宽松策略，允许请求。适用于对可用性要求高的场景。
     *                     优点：后端故障时不影响业务可用性
     *                     缺点：可能超出限流配额，需要额外的降级措施
     * </pre>
     * <p>
     * <b>异常分类处理：</b>
     * <ul>
     *   <li><b>后端异常</b>：Redis/Redisson 连接异常、超时等，记录失败并按策略降级</li>
     *   <li><b>非后端异常</b>：参数错误、配置错误等，直接向上抛出，不进行降级</li>
     * </ul>
     *
     * @param key      限流 key（已包含前缀）
     * @param strategy 限流策略，包含算法类型、窗口大小、配额等配置
     * @return 限流结果，包含是否允许、剩余配额、等待时间等信息
     * @throws RuntimeException 如果发生非后端异常（如参数错误）
     */
    RateLimitResult tryAcquire(String key, RateLimitStrategy strategy) {
        try {
            // 尝试获取限流配额
            RateLimitResult result = limiterFactory.tryAcquire(key, strategy);
            // 成功后记录成功状态，重置连续故障计数器
            backendFailureTracker.recordSuccess();
            return result;
        } catch (RuntimeException ex) {
            // 非后端异常（如参数错误）直接向上抛出，不进行降级处理
            if (!LimiterExceptionClassifier.isBackendException(ex)) {
                throw ex;
            }
            // 确认是后端异常后，记录故障事件用于监控告警
            // source="annotation" 表示故障来源为注解方式调用的限流（区别于手动调用）
            backendFailureTracker.recordBackendFailure("annotation", key, strategy.getAlgorithmType(), ex);
            if (properties.isFailOnError()) {
                // fail-closed 策略：保守拒绝，返回等待时间为 0 的拒绝结果
                log.warn("[CascadeLimiter] Limiter backend error, fail-closed. key={} algorithm={}",
                        key, strategy.getAlgorithmType(), ex);
                return RateLimitResult.rejected(key, strategy.getAlgorithmType(), 0);
            }
            // fail-open 策略：宽松允许，返回等待时间为 -1 的允许结果（-1 表示无实际配额限制）
            log.warn("[CascadeLimiter] Limiter backend error, fail-open. key={} algorithm={}",
                    key, strategy.getAlgorithmType(), ex);
            return RateLimitResult.allowed(key, strategy.getAlgorithmType(), -1);
        }
    }
}
