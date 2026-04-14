package cc.coderm.cascade.limiter.support;

import cc.coderm.cascade.limiter.config.CascadeLimiterProperties;
import cc.coderm.cascade.limiter.model.AlgorithmType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LimiterBackendFailureTrackerTest {

    @Test
    void shouldAccumulateConsecutiveBackendFailures() {
        CascadeLimiterProperties properties = new CascadeLimiterProperties();
        properties.setBackendFailureAlertThreshold(3);
        LimiterBackendFailureTracker tracker = new LimiterBackendFailureTracker(properties);

        tracker.recordBackendFailure("annotation", "k1", AlgorithmType.TOKEN_BUCKET, new RuntimeException("x"));
        tracker.recordBackendFailure("annotation", "k1", AlgorithmType.TOKEN_BUCKET, new RuntimeException("x"));

        assertThat(tracker.currentConsecutiveFailures()).isEqualTo(2L);
    }

    @Test
    void shouldResetConsecutiveFailuresAfterSuccess() {
        CascadeLimiterProperties properties = new CascadeLimiterProperties();
        properties.setBackendFailureAlertThreshold(3);
        LimiterBackendFailureTracker tracker = new LimiterBackendFailureTracker(properties);

        tracker.recordBackendFailure("programmatic", "k2", AlgorithmType.LEAKY_BUCKET, new RuntimeException("x"));
        tracker.recordBackendFailure("programmatic", "k2", AlgorithmType.LEAKY_BUCKET, new RuntimeException("x"));
        tracker.recordSuccess();

        assertThat(tracker.currentConsecutiveFailures()).isZero();
    }
}
