package cc.coderm.cascade.limiter.support;

import cc.coderm.cascade.limiter.config.CascadeLimiterProperties;
import cc.coderm.cascade.limiter.model.AlgorithmType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 跟踪 Redis 服务端时钟回跳事件，并输出阈值告警。
 *
 * <p>说明：
 * <ul>
 *   <li>该跟踪器只负责统计与告警，不改变限流判定逻辑</li>
 *   <li>算法侧在检测到 Redis 时间回跳时会使用单调保护（使用上次时间）继续计算</li>
 *   <li>可选接入 Micrometer，指标名：{@code cascade.limiter.clock.rollback}</li>
 * </ul>
 */
@Slf4j
public class LimiterClockRollbackTracker {

    private static final String CLOCK_ROLLBACK_METRIC = "cascade.limiter.clock.rollback";

    private final CascadeLimiterProperties properties;
    @Nullable
    private final MeterRegistry meterRegistry;
    private final AtomicLong totalRollbackCount = new AtomicLong();
    private final ConcurrentHashMap<String, Counter> counterCache = new ConcurrentHashMap<>();

    public LimiterClockRollbackTracker(CascadeLimiterProperties properties, @Nullable MeterRegistry meterRegistry) {
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    public void recordClockRollback(String key, AlgorithmType algorithm, long previousNowMs, long redisNowMs) {
        long total = totalRollbackCount.incrementAndGet();
        incrementMetric(algorithm);

        if (total == 1L) {
            log.warn("[CascadeLimiter] Detected Redis clock rollback. algorithm={}, key={}, previousNowMs={}, redisNowMs={}",
                    algorithm, key, previousNowMs, redisNowMs);
        }

        int threshold = properties.getClockRollbackAlertThreshold();
        if (threshold > 0 && total % threshold == 0) {
            log.error("[CascadeLimiter] Redis clock rollback alert threshold reached. " +
                            "threshold={}, total={}, algorithm={}, key={}, previousNowMs={}, redisNowMs={}",
                    threshold, total, algorithm, key, previousNowMs, redisNowMs);
        }
    }

    long totalRollbacks() {
        return totalRollbackCount.get();
    }

    private void incrementMetric(AlgorithmType algorithm) {
        if (meterRegistry == null) {
            return;
        }
        String algorithmTag = algorithm.name();
        counterCache.computeIfAbsent(algorithmTag, tag ->
                Counter.builder(CLOCK_ROLLBACK_METRIC)
                        .tag("algorithm", tag)
                        .description("Redis clock rollback detections in limiter algorithms")
                        .register(meterRegistry)
        ).increment();
    }
}

