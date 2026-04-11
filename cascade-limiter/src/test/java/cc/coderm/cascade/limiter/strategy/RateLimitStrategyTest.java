package cc.coderm.cascade.limiter.strategy;

import cc.coderm.cascade.limiter.model.AlgorithmType;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RateLimitStrategyTest {

    @Test
    void shouldApplyDefaultValues() {
        RateLimitStrategy strategy = RateLimitStrategy.builder().build();

        assertThat(strategy.getAlgorithmType()).isEqualTo(AlgorithmType.TOKEN_BUCKET);
        assertThat(strategy.getMaxPermits()).isEqualTo(100L);
        assertThat(strategy.getWindow()).isEqualTo(Duration.ofSeconds(1));
        assertThat(strategy.getMessage()).isEqualTo("Too many requests, please try again later.");
    }

    @Test
    void shouldRejectNonPositiveMaxPermits() {
        assertThatThrownBy(() -> RateLimitStrategy.builder()
                .maxPermits(0L)
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxPermits");
    }

    @Test
    void shouldRejectNonPositiveWindow() {
        assertThatThrownBy(() -> RateLimitStrategy.builder()
                .window(Duration.ZERO)
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("window");
    }

    @Test
    void shouldRejectNegativeRefillRate() {
        assertThatThrownBy(() -> RateLimitStrategy.builder()
                .refillRate(-1L)
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("refillRate");
    }

    @Test
    void shouldRejectNegativeLeakRate() {
        assertThatThrownBy(() -> RateLimitStrategy.builder()
                .leakRate(-1L)
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("leakRate");
    }

    @Test
    void shouldAllowZeroRatesAsAutoFallback() {
        RateLimitStrategy strategy = RateLimitStrategy.builder()
                .maxPermits(42L)
                .refillRate(0L)
                .leakRate(0L)
                .build();

        assertThat(strategy.effectiveRefillRate()).isEqualTo(42L);
        assertThat(strategy.effectiveLeakRate()).isEqualTo(42L);
    }
}
