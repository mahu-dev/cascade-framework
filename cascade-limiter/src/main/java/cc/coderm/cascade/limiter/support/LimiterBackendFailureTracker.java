package cc.coderm.cascade.limiter.support;

import cc.coderm.cascade.limiter.config.CascadeLimiterProperties;
import cc.coderm.cascade.limiter.model.AlgorithmType;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 跟踪限流后端（Redis/Redisson）连续故障次数，并在达到阈值时输出告警日志。
 *
 * <p>说明：
 * <ul>
 *   <li>仅统计被 {@link LimiterExceptionClassifier} 识别为后端异常的错误</li>
 *   <li>任一限流请求成功后会将连续故障计数清零</li>
 *   <li>告警阈值由配置项 {@code cascade.limiter.backend-failure-alert-threshold} 控制</li>
 * </ul>
 */
@Slf4j
public class LimiterBackendFailureTracker {

    private final CascadeLimiterProperties properties;
    private final AtomicLong consecutiveFailures = new AtomicLong(0);

    public LimiterBackendFailureTracker(CascadeLimiterProperties properties) {
        this.properties = properties;
    }

    /**
     * 记录限流后端故障，并在连续故障达到阈值时输出告警日志
     * <p>
     * 当限流算法与 Redis/Redisson 后端通信失败时，应调用此方法
     * 记录故障事件并递增连续故障计数器。
     * <p>
     * <b>告警策略：</b>
     * <ul>
     *   <li>只有在连续故障次数<b>精确等于</b>配置的告警阈值时才输出告警日志</li>
     *   <li>这样设计是为了避免在持续故障期间产生重复的告警日志（日志洪水）</li>
     *   <li>当故障恢复时，会输出一次恢复日志，与告警日志形成对应</li>
     * </ul>
     * <p>
     * <b>原子性保证：</b> 使用 {@code incrementAndGet()} 原子操作递增计数器，
     * 确保在高并发场景下：
     * <ul>
     *   <li>计数器准确递增，不会丢失故障事件</li>
     *   <li>多个线程同时故障时能够正确计数</li>
     *   <li>告警阈值判断具有线程安全性</li>
     * </ul>
     * <p>
     * <b>日志级别选择：</b> 使用 ERROR 级别记录告警，因为后端连续故障
     * 可能导致限流失效（fail-open）或服务不可用（fail-closed），属于严重故障。
     * <p>
     * <b>使用示例：</b>
     * <pre>
     * try {
     *     RateLimitResult result = limiterFactory.tryAcquire(key, strategy);
     *     backendFailureTracker.recordSuccess();
     *     return result;
     * } catch (RedisException ex) {
     *     backendFailureTracker.recordBackendFailure("annotation", key, algorithm, ex);
     *     // 后续降级处理...
     * }
     * </pre>
     *
     * @param source    故障来源标识，如 "annotation"、"manual" 等，用于追踪故障发生点
     * @param key       限流 key，帮助定位具体是哪个限流器发生故障
     * @param algorithm 限流算法类型，帮助分析是否有特定算法的系统性问题
     * @param ex        导致故障的异常对象，用于问题排查和根因分析
     * @see #recordSuccess()
     */
    public void recordBackendFailure(String source, String key, AlgorithmType algorithm, Throwable ex) {
        // 原子递增连续故障计数器，并获取递增后的最新值
        long current = consecutiveFailures.incrementAndGet();

        // 获取配置的告警阈值，用于判断是否需要输出告警日志
        int threshold = properties.getBackendFailureAlertThreshold();

        // 只有在精确命中阈值时才告警，避免持续故障期间产生重复日志（日志洪水）
        if (threshold > 0 && current == threshold) {
            log.error("[CascadeLimiter] Consecutive limiter backend failures reached threshold. " +
                            "threshold={}, count={}, source={}, key={}, algorithm={}",
                    threshold, current, source, key, algorithm, ex);
        }
    }

    /**
     * 记录限流后端请求成功，并重置连续故障计数器
     * <p>
     * 当任一限流算法成功与 Redis/Redisson 后端通信后，应调用此方法
     * 将连续故障计数器清零，表示后端已恢复正常。
     * <p>
     * <b>执行流程：</b>
     * <ol>
     *   <li>原子地读取并重置连续故障计数器（getAndSet 操作）</li>
     *   <li>检查重置前的故障次数是否达到告警阈值</li>
     *   <li>如果达到阈值，输出恢复日志，记录此次故障持续的总次数</li>
     * </ol>
     * <p>
     * <b>日志策略：</b>
     * 只有在连续故障次数达到或超过配置的告警阈值时才会输出恢复日志。
     * 这是为了避免在后端频繁抖动（快速故障/恢复）时产生日志洪水。
     * <p>
     * <b>原子性保证：</b> 使用 {@code getAndSet(0)} 原子操作，确保在高并发场景下：
     * <ul>
     *   <li>计数器正确归零，不会出现竞态条件</li>
     *   <li>能够获取到真实的累积故障次数</li>
     *   <li>多个线程同时调用时不会丢失恢复事件</li>
     * </ul>
     * <p>
     * <b>使用示例：</b>
     * <pre>
     * try {
     *     RateLimitResult result = limiterFactory.tryAcquire(key, strategy);
     *     backendFailureTracker.recordSuccess();  // 成功后重置故障计数
     *     return result;
     * } catch (RedisException ex) {
     *     backendFailureTracker.recordBackendFailure(...);
     * }
     * </pre>
     *
     * @see #recordBackendFailure(String, String, AlgorithmType, Throwable)
     */
    public void recordSuccess() {
        // 原子操作：获取当前值并重置为 0，确保多线程场景下的正确性
        long previous = consecutiveFailures.getAndSet(0);

        // 获取配置的告警阈值，用于判断是否需要记录恢复日志
        int threshold = properties.getBackendFailureAlertThreshold();

        // 只有在故障累积到一定程度后才记录恢复日志，避免频繁抖动时产生日志洪水
        if (threshold > 0 && previous >= threshold) {
            log.info("[CascadeLimiter] Limiter backend recovered after consecutive failures. previousCount={}", previous);
        }
    }

    long currentConsecutiveFailures() {
        return consecutiveFailures.get();
    }
}
