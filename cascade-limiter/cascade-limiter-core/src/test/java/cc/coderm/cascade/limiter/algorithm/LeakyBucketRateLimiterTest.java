package cc.coderm.cascade.limiter.algorithm;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class LeakyBucketRateLimiterTest {

    @Test
    void shouldComputeExpectedTtlForNormalConfig() {
        long ttlMs = LeakyBucketRateLimiter.calculateStateTtlMs(100, 10);

        assertThat(ttlMs).isEqualTo(20_000L);
    }

    @Test
    void shouldCapTtlToThirtyDaysForExtremeConfig() {
        long ttlMs = LeakyBucketRateLimiter.calculateStateTtlMs(Long.MAX_VALUE, 1);

        assertThat(ttlMs).isEqualTo(TimeUnit.DAYS.toMillis(30));
    }

    @Test
    void shouldKeepTtlAtLeastOneMillisecond() {
        long ttlMs = LeakyBucketRateLimiter.calculateStateTtlMs(1, Long.MAX_VALUE);

        assertThat(ttlMs).isGreaterThanOrEqualTo(1L);
    }
}
