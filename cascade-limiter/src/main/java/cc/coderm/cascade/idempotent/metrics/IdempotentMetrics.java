package cc.coderm.cascade.idempotent.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 幂等指标上报（Micrometer）。
 *
 * <p>暴露以下计数器（tag: scene）：
 * <ul>
 *   <li>{@code cascade.idempotent.hit}      - 幂等命中（重复请求直接重放）</li>
 *   <li>{@code cascade.idempotent.miss}     - 首次请求</li>
 *   <li>{@code cascade.idempotent.conflict} - 并发冲突（PROCESSING 状态）</li>
 *   <li>{@code cascade.idempotent.success}  - 业务执行成功</li>
 *   <li>{@code cascade.idempotent.failure}  - 业务执行失败</li>
 * </ul>
 */
@RequiredArgsConstructor
public class IdempotentMetrics {

    private final MeterRegistry registry;
    private final ConcurrentHashMap<String, Counter> cache = new ConcurrentHashMap<>();

    public void recordHit(String scene) {
        counter("cascade.idempotent.hit", scene).increment();
    }

    public void recordMiss(String scene) {
        counter("cascade.idempotent.miss", scene).increment();
    }

    public void recordConflict(String scene) {
        counter("cascade.idempotent.conflict", scene).increment();
    }

    public void recordSuccess(String scene) {
        counter("cascade.idempotent.success", scene).increment();
    }

    public void recordFailure(String scene) {
        counter("cascade.idempotent.failure", scene).increment();
    }

    private Counter counter(String name, String scene) {
        return cache.computeIfAbsent(name + "|" + scene, k ->
                Counter.builder(name)
                        .tag("scene", scene)
                        .register(registry));
    }
}