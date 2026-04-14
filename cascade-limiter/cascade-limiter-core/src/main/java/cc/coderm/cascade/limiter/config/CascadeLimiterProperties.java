package cc.coderm.cascade.limiter.config;

import cc.coderm.cascade.limiter.model.AlgorithmType;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * cascade-limiter 配置属性。
 *
 * <p>application.yml 示例：
 * <pre>
 * cascade:
 *   limiter:
 *     redis-key-prefix: "cascade:limiter:"
 *     default-algorithm: TOKEN_BUCKET
 *     fail-on-error: false      # Redis 故障时是否拒绝（true=拒绝，false=放行）
 *     metrics-enabled: true
 *     backend-failure-alert-threshold: 20 # 连续后端故障达到阈值时输出告警日志，<=0 表示关闭
 *     clock-rollback-alert-threshold: 10   # Redis 时钟回跳累计达到阈值时输出告警日志，<=0 表示关闭
 *     key-expression-safe-mode: true       # 限制 @bean/T()/方法调用，避免 SpEL 注入风险
 *     key-admission-enabled: false         # 是否启用高 churn key 准入保护
 *     key-admission-max-distinct-keys: 200000
 *     key-admission-registry-ttl: 10m
 *     key-admission-overflow-policy: REJECT
 *     key-admission-blacklist-patterns:
 *       - ".*:__debug:.*"
 * </pre>
 */
@Data
@ConfigurationProperties(prefix = "cascade.limiter")
public class CascadeLimiterProperties {

    /**
     * Redis key 统一前缀
     */
    private String redisKeyPrefix = "cascade:limiter:";

    /**
     * 默认限流算法（注解未指定时使用）
     */
    private AlgorithmType defaultAlgorithm = AlgorithmType.TOKEN_BUCKET;

    /**
     * Redis 故障时的策略：
     * true  = fail-closed（拒绝请求，保守策略）
     * false = fail-open（放行请求，宽松策略，默认）
     */
    private boolean failOnError = false;

    /**
     * 是否启用 Micrometer 指标
     */
    private boolean metricsEnabled = true;

    /**
     * 连续后端故障告警阈值（仅针对 Redis/Redisson 后端异常）。
     * 当连续故障次数达到该阈值时输出告警日志；<=0 表示关闭该告警。
     */
    private int backendFailureAlertThreshold = 20;

    /**
     * Redis 时钟回跳告警阈值（累计）。
     * 当检测到的时钟回跳累计次数达到该阈值及其倍数时输出告警日志；<=0 表示关闭该告警。
     */
    private int clockRollbackAlertThreshold = 10;

    /**
     * Key SpEL 安全模式。
     * true：禁用 @bean / T() / 方法调用 / 构造器调用，仅允许变量与属性读取（默认）。
     * false：兼容旧行为，允许 BeanResolver 与方法调用，存在表达式注入风险。
     */
    private boolean keyExpressionSafeMode = true;

    /**
     * 是否启用 key 准入保护。
     * 主要用于缓解恶意随机 key 导致的 Redis 内存膨胀。
     */
    private boolean keyAdmissionEnabled = false;

    /**
     * key 准入允许的最大 distinct key 数（按 registry 维度）。
     */
    private long keyAdmissionMaxDistinctKeys = 200_000L;

    /**
     * key 准入 registry 条目 TTL，超过该时间未访问的 key 将被回收。
     */
    private Duration keyAdmissionRegistryTtl = Duration.ofMinutes(10);

    /**
     * key 准入 registry 的 Redis key，留空时自动使用：
     * redis-key-prefix + "__admission:keys"。
     */
    private String keyAdmissionRegistryKey = "";

    /**
     * 超限后 COALESCE 策略使用的兜底 key，留空时自动使用：
     * redis-key-prefix + "__admission:overflow"。
     */
    private String keyAdmissionOverflowKey = "";

    /**
     * 超限新 key 的处理策略：REJECT / COALESCE。
     */
    private KeyAdmissionOverflowPolicy keyAdmissionOverflowPolicy = KeyAdmissionOverflowPolicy.REJECT;

    /**
     * key 黑名单正则列表（匹配 full key）。
     */
    private List<String> keyAdmissionBlacklistPatterns = new ArrayList<>();
}
