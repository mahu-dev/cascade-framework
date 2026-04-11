package cc.coderm.cascade.limiter.metrics;

import cc.coderm.cascade.limiter.model.RateLimitResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于 Micrometer 的限流指标收集器
 * <p>
 * 将限流判断结果记录为 Micrometer Counter 指标，支持 Prometheus、Graphite 等监控系统。
 * 通过指标数据可以实时观察限流效果，及时发现异常和优化配置。
 * <p>
 * <b>暴露的指标：</b>
 * <ul>
 *   <li><b>cascade.limiter.allowed</b> - 放行请求计数器</li>
 *   <li><b>cascade.limiter.rejected</b> - 拒绝请求计数器</li>
 * </ul>
 * <p>
 * <b>指标标签：</b>
 * <ul>
 *   <li><b>algorithm</b>：限流算法类型（TOKEN_BUCKET、SLIDING_WINDOW、FIXED_WINDOW、LEAKY_BUCKET）</li>
 *   <li><b>method</b>：目标方法标识（类名.方法名）</li>
 * </ul>
 * <p>
 * <b>使用场景：</b>
 * <pre>
 * # 在 Prometheus 中查询放行速率（每秒请求数）
 * rate(cascade_limiter_allowed[5m])
 *
 * # 查询某方法的拒绝率
 * sum(rate(cascade_limiter_rejected{method="UserService.getUser"}[5m])) /
 * sum(rate(cascade_limiter_allowed{method="UserService.getUser"}[5m]))
 *
 * # 在 Grafana 中可视化限流效果
 * # - 对比不同算法的拒绝率
 * # - 监控高频限流的方法
 * # - 设置告警规则（如拒绝率超过 50%）
 * </pre>
 * <p>
 * <b>性能优化：</b>
 * <ul>
 *   <li>使用 {@code ConcurrentHashMap} 缓存已创建的 Counter 实例</li>
 *   <li>避免重复注册相同标签的 Counter（Micrometer 不允许重复注册）</li>
 *   <li>缓存键格式：{@code "metricName|algorithm|method"}</li>
 * </ul>
 * <p>
 * <b>启用条件：</b>
 * <ul>
 *   <li>classpath 上存在 {@code io.micrometer.core.instrument.MeterRegistry}</li>
 *   <li>Spring 容器中存在 {@code MeterRegistry} Bean</li>
 *   <li>配置 {@code cascade.limiter.metrics-enabled=true}（默认启用）</li>
 * </ul>
 *
 * @see io.micrometer.core.instrument.Counter
 * @see io.micrometer.core.instrument.MeterRegistry
 * @see cc.coderm.cascade.limiter.model.RateLimitResult
 */
@Slf4j
public class RateLimitMetrics {

    private static final String ALLOWED = "cascade.limiter.allowed";
    private static final String REJECTED = "cascade.limiter.rejected";

    private final MeterRegistry registry;
    private final ConcurrentHashMap<String, Counter> counterCache = new ConcurrentHashMap<>();

    public RateLimitMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * 记录限流结果到 Micrometer 指标系统
     * <p>
     * 将限流判断结果记录为 Micrometer Counter 指标，用于监控和告警。
     * 根据限流结果（允许/拒绝）选择不同的指标名称，并附加算法和方法标签。
     * <p>
     * <b>暴露的指标：</b>
     * <ul>
     *   <li><b>cascade.limiter.allowed</b> - 放行请求计数器</li>
     *   <li><b>cascade.limiter.rejected</b> - 拒绝请求计数器</li>
     * </ul>
     * <p>
     * <b>指标标签：</b>
     * <ul>
     *   <li><b>algorithm</b>：限流算法类型（TOKEN_BUCKET、SLIDING_WINDOW 等）</li>
     *   <li><b>method</b>：目标方法标识（类名.方法名）</li>
     * </ul>
     * <p>
     * <b>性能优化：</b>
     * <ul>
     *   <li>使用 {@code ConcurrentHashMap} 缓存已创建的 Counter 实例</li>
     *   <li>缓存键格式：{@code "metricName|algorithm|method"}</li>
     *   <li>避免重复注册相同标签的 Counter（Micrometer 不允许重复注册）</li>
     * </ul>
     * <p>
     * <b>Prometheus 示例查询：</b>
     * <pre>
     * # 查询某方法的放行速率（最近5分钟）
     * rate(cascade_limiter_allowed{algorithm="TOKEN_BUCKET",method="UserService.getUser"}[5m])
     *
     * # 查询所有拒绝请求的总数
     * sum(cascade_limiter_rejected)
     *
     * # 查询拒绝率（拒绝/总请求）
     * sum(cascade_limiter_rejected) / (sum(cascade_limiter_allowed) + sum(cascade_limiter_rejected))
     * </pre>
     *
     * @param result 限流结果，包含是否允许、算法类型等信息
     * @param method 目标方法，用于生成 method 标签
     */
    public void recordRateLimitResult(RateLimitResult result, Method method) {
        // 生成 method 标签：类名.方法名（如 UserService.getUser）
        String methodTag = method.getDeclaringClass().getSimpleName() + "." + method.getName();
        // 生成 algorithm 标签：算法类型名称（如 TOKEN_BUCKET）
        String algorithmTag = result.getAlgorithmType().name();
        // 根据限流结果选择指标名称
        String metricName = result.isAllowed() ? ALLOWED : REJECTED;

        // 构建缓存键，避免重复注册相同标签的 Counter
        String cacheKey = metricName + "|" + algorithmTag + "|" + methodTag;

        // 从缓存获取或创建新的 Counter，然后递增计数
        counterCache.computeIfAbsent(cacheKey, k ->
                Counter.builder(metricName)
                        .tag("algorithm", algorithmTag)   // 算法类型标签
                        .tag("method", methodTag)           // 目标方法标签
                        .description(result.isAllowed() ? "Rate limit allowed requests" : "Rate limit rejected requests")
                        .register(registry)
        ).increment();
    }
}