package cc.coderm.cascade.limiter.support;

import cc.coderm.cascade.limiter.config.CascadeLimiterProperties;
import cc.coderm.cascade.limiter.model.AlgorithmType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LimiterClockRollbackTrackerTest {

    @Test
    void shouldAccumulateRollbackCountAndPublishMetric() {
        CascadeLimiterProperties properties = new CascadeLimiterProperties();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LimiterClockRollbackTracker tracker = new LimiterClockRollbackTracker(properties, registry);

        tracker.recordClockRollback("k1", AlgorithmType.SLIDING_WINDOW, 2000L, 1800L);
        tracker.recordClockRollback("k1", AlgorithmType.SLIDING_WINDOW, 2200L, 1900L);

        assertThat(tracker.totalRollbacks()).isEqualTo(2L);
        assertThat(registry.get("cascade.limiter.clock.rollback")
                .tag("algorithm", AlgorithmType.SLIDING_WINDOW.name())
                .counter()
                .count()).isEqualTo(2.0d);
    }

    @Test
    void shouldWorkWithoutMeterRegistry() {
        CascadeLimiterProperties properties = new CascadeLimiterProperties();
        LimiterClockRollbackTracker tracker = new LimiterClockRollbackTracker(properties, null);

        tracker.recordClockRollback("k2", AlgorithmType.LEAKY_BUCKET, 3000L, 1000L);

        assertThat(tracker.totalRollbacks()).isEqualTo(1L);
    }
}

