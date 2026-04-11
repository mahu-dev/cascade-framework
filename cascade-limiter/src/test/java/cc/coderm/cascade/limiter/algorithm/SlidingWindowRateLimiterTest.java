package cc.coderm.cascade.limiter.algorithm;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class SlidingWindowRateLimiterTest {

    @Test
    void shouldUseMinSlotsForSmallWindow() {
        assertThat(SlidingWindowRateLimiter.calculateSlotCount(50L)).isEqualTo(10);
    }

    @Test
    void shouldUseTargetBasedSlotsForNormalWindow() {
        assertThat(SlidingWindowRateLimiter.calculateSlotCount(1_000L)).isEqualTo(10);
    }

    @Test
    void shouldCapSlotsForLargeWindow() {
        assertThat(SlidingWindowRateLimiter.calculateSlotCount(TimeUnit.MINUTES.toMillis(1))).isEqualTo(200);
    }

    @Test
    void shouldCapStateTtlToThirtyDays() {
        long ttlMs = SlidingWindowRateLimiter.calculateStateTtlMs(Long.MAX_VALUE);
        assertThat(ttlMs).isEqualTo(TimeUnit.DAYS.toMillis(30));
    }
}
