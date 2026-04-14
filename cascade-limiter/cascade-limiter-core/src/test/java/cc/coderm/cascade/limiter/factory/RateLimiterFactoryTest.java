package cc.coderm.cascade.limiter.factory;

import cc.coderm.cascade.limiter.algorithm.RateLimiter;
import cc.coderm.cascade.limiter.model.AlgorithmType;
import cc.coderm.cascade.limiter.model.RateLimitResult;
import cc.coderm.cascade.limiter.strategy.RateLimitStrategy;
import cc.coderm.cascade.limiter.support.KeyAdmissionDecision;
import cc.coderm.cascade.limiter.support.KeyAdmissionGuard;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterFactoryTest {

    @Test
    void shouldRejectImmediatelyWhenAdmissionDenied() {
        CapturingLimiter limiter = new CapturingLimiter();
        KeyAdmissionGuard guard = key -> KeyAdmissionDecision.rejected(321L);
        RateLimiterFactory factory = new RateLimiterFactory(List.of(limiter), guard);

        RateLimitResult result = factory.tryAcquire("rl:k1", strategy(), 1);

        assertThat(result.isAllowed()).isFalse();
        assertThat(result.getWaitMillis()).isEqualTo(321L);
        assertThat(result.getLimitKey()).isEqualTo("rl:k1");
        assertThat(limiter.tryAcquireCalls).isZero();
    }

    @Test
    void shouldUseCoalescedKeyInternallyButKeepOriginalKeyInResult() {
        CapturingLimiter limiter = new CapturingLimiter();
        limiter.tryAcquireResult = RateLimitResult.allowed("rl:overflow", AlgorithmType.TOKEN_BUCKET, 9);
        KeyAdmissionGuard guard = key -> KeyAdmissionDecision.coalesced("rl:overflow");
        RateLimiterFactory factory = new RateLimiterFactory(List.of(limiter), guard);

        RateLimitResult result = factory.tryAcquire("rl:k2", strategy(), 1);

        assertThat(limiter.lastTryAcquireKey).isEqualTo("rl:overflow");
        assertThat(result.isAllowed()).isTrue();
        assertThat(result.getLimitKey()).isEqualTo("rl:k2");
    }

    @Test
    void shouldBlockAcquireWhenAdmissionDenied() throws InterruptedException {
        CapturingLimiter limiter = new CapturingLimiter();
        KeyAdmissionGuard guard = key -> KeyAdmissionDecision.rejected(100L);
        RateLimiterFactory factory = new RateLimiterFactory(List.of(limiter), guard);

        boolean acquired = factory.acquire("rl:k3", strategy(), 1000L);

        assertThat(acquired).isFalse();
        assertThat(limiter.acquireCalls).isZero();
    }

    @Test
    void shouldUseCoalescedKeyInAcquirePath() throws InterruptedException {
        CapturingLimiter limiter = new CapturingLimiter();
        limiter.acquireResult = true;
        KeyAdmissionGuard guard = key -> KeyAdmissionDecision.coalesced("rl:overflow");
        RateLimiterFactory factory = new RateLimiterFactory(List.of(limiter), guard);

        boolean acquired = factory.acquire("rl:k4", strategy(), 1000L);

        assertThat(acquired).isTrue();
        assertThat(limiter.lastAcquireKey).isEqualTo("rl:overflow");
    }

    private static RateLimitStrategy strategy() {
        return RateLimitStrategy.builder()
                .algorithmType(AlgorithmType.TOKEN_BUCKET)
                .maxPermits(100L)
                .window(Duration.ofSeconds(1))
                .build();
    }

    private static final class CapturingLimiter implements RateLimiter {
        private int tryAcquireCalls;
        private int acquireCalls;
        private String lastTryAcquireKey;
        private String lastAcquireKey;
        private RateLimitResult tryAcquireResult = RateLimitResult.allowed("default", AlgorithmType.TOKEN_BUCKET, 0);
        private boolean acquireResult = true;

        @Override
        public RateLimitResult tryAcquire(String key, RateLimitStrategy strategy, int permits) {
            tryAcquireCalls++;
            lastTryAcquireKey = key;
            return tryAcquireResult;
        }

        @Override
        public boolean acquire(String key, RateLimitStrategy strategy, long timeoutMs) {
            acquireCalls++;
            lastAcquireKey = key;
            return acquireResult;
        }

        @Override
        public AlgorithmType getAlgorithmType() {
            return AlgorithmType.TOKEN_BUCKET;
        }
    }
}

