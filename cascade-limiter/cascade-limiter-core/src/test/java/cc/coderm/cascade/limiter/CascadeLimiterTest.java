package cc.coderm.cascade.limiter;

import cc.coderm.cascade.limiter.algorithm.RateLimiter;
import cc.coderm.cascade.limiter.config.CascadeLimiterProperties;
import cc.coderm.cascade.limiter.factory.RateLimiterFactory;
import cc.coderm.cascade.limiter.model.AlgorithmType;
import cc.coderm.cascade.limiter.model.RateLimitResult;
import cc.coderm.cascade.limiter.strategy.RateLimitStrategy;
import cc.coderm.cascade.limiter.support.LimiterBackendFailureTracker;
import org.junit.jupiter.api.Test;
import org.redisson.client.RedisException;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CascadeLimiterTest {

    @Test
    void shouldFailOpenForProgrammaticTryAcquireWhenLimiterThrows() {
        TestRateLimiterFactory factory = new TestRateLimiterFactory();
        factory.tryAcquireError = new RedisException("redis down");

        CascadeLimiterProperties properties = new CascadeLimiterProperties();
        properties.setRedisKeyPrefix("rl:");
        properties.setFailOnError(false);

        CascadeLimiter limiter = new CascadeLimiter(factory, properties, new LimiterBackendFailureTracker(properties));
        RateLimitResult result = limiter.key("k1").tryAcquire();

        assertThat(result.isAllowed()).isTrue();
        assertThat(result.getLimitKey()).isEqualTo("rl:k1");
        assertThat(result.getAlgorithmType()).isEqualTo(AlgorithmType.TOKEN_BUCKET);
    }

    @Test
    void shouldFailClosedForProgrammaticTryAcquireWhenLimiterThrows() {
        TestRateLimiterFactory factory = new TestRateLimiterFactory();
        factory.tryAcquireError = new RedisException("redis down");

        CascadeLimiterProperties properties = new CascadeLimiterProperties();
        properties.setRedisKeyPrefix("rl:");
        properties.setFailOnError(true);

        CascadeLimiter limiter = new CascadeLimiter(factory, properties, new LimiterBackendFailureTracker(properties));
        RateLimitResult result = limiter.key("k2").tryAcquire();

        assertThat(result.isAllowed()).isFalse();
        assertThat(result.getLimitKey()).isEqualTo("rl:k2");
        assertThat(result.getAlgorithmType()).isEqualTo(AlgorithmType.TOKEN_BUCKET);
    }

    @Test
    void shouldFailOpenForProgrammaticAcquireWhenLimiterThrows() {
        TestRateLimiterFactory factory = new TestRateLimiterFactory();
        factory.rateLimiter.acquireError = new RedisException("redis down");

        CascadeLimiterProperties properties = new CascadeLimiterProperties();
        properties.setRedisKeyPrefix("rl:");
        properties.setFailOnError(false);

        CascadeLimiter limiter = new CascadeLimiter(factory, properties, new LimiterBackendFailureTracker(properties));
        boolean acquired = limiter.key("k3").acquire(10);

        assertThat(acquired).isTrue();
    }

    @Test
    void shouldFailClosedForProgrammaticAcquireWhenLimiterThrows() {
        TestRateLimiterFactory factory = new TestRateLimiterFactory();
        factory.rateLimiter.acquireError = new RedisException("redis down");

        CascadeLimiterProperties properties = new CascadeLimiterProperties();
        properties.setRedisKeyPrefix("rl:");
        properties.setFailOnError(true);

        CascadeLimiter limiter = new CascadeLimiter(factory, properties, new LimiterBackendFailureTracker(properties));
        boolean acquired = limiter.key("k4").acquire(10);

        assertThat(acquired).isFalse();
    }

    @Test
    void shouldNotSwallowLocalProgrammingErrorsInTryAcquireWhenFailOpen() {
        TestRateLimiterFactory factory = new TestRateLimiterFactory();
        factory.tryAcquireError = new IllegalArgumentException("bad strategy");

        CascadeLimiterProperties properties = new CascadeLimiterProperties();
        properties.setRedisKeyPrefix("rl:");
        properties.setFailOnError(false);

        CascadeLimiter limiter = new CascadeLimiter(factory, properties, new LimiterBackendFailureTracker(properties));

        assertThatThrownBy(() -> limiter.key("k5").tryAcquire())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldNotSwallowLocalProgrammingErrorsInAcquireWhenFailOpen() {
        TestRateLimiterFactory factory = new TestRateLimiterFactory();
        factory.rateLimiter.acquireError = new IllegalArgumentException("bad timeout");

        CascadeLimiterProperties properties = new CascadeLimiterProperties();
        properties.setRedisKeyPrefix("rl:");
        properties.setFailOnError(false);

        CascadeLimiter limiter = new CascadeLimiter(factory, properties, new LimiterBackendFailureTracker(properties));

        assertThatThrownBy(() -> limiter.key("k6").acquire(10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldFailFastWhenBuilderIsAccessedFromDifferentThread() {
        TestRateLimiterFactory factory = new TestRateLimiterFactory();
        CascadeLimiterProperties properties = new CascadeLimiterProperties();
        properties.setRedisKeyPrefix("rl:");
        CascadeLimiter limiter = new CascadeLimiter(factory, properties, new LimiterBackendFailureTracker(properties));

        CascadeLimiter.Builder builder = limiter.key("shared");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> future = executor.submit(() -> builder.algorithm(AlgorithmType.FIXED_WINDOW));
            assertThatThrownBy(future::get)
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(IllegalStateException.class)
                    .hasRootCauseMessage("CascadeLimiter.Builder is thread-confined and must not be shared across threads");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void shouldRejectNonPositivePermitsInProgrammaticTryAcquire() {
        TestRateLimiterFactory factory = new TestRateLimiterFactory();
        CascadeLimiterProperties properties = new CascadeLimiterProperties();
        properties.setRedisKeyPrefix("rl:");
        CascadeLimiter limiter = new CascadeLimiter(factory, properties, new LimiterBackendFailureTracker(properties));

        assertThatThrownBy(() -> limiter.key("k7").tryAcquire(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("permits");
    }

    private static final class TestRateLimiterFactory extends RateLimiterFactory {
        private final ThrowingRateLimiter rateLimiter = new ThrowingRateLimiter();
        private RuntimeException tryAcquireError;

        private TestRateLimiterFactory() {
            super(List.of(new ThrowingRateLimiter()));
        }

        @Override
        public RateLimiter getLimiter(AlgorithmType type) {
            return rateLimiter;
        }

        @Override
        public RateLimitResult tryAcquire(String key, RateLimitStrategy strategy, int permits) {
            if (tryAcquireError != null) {
                throw tryAcquireError;
            }
            return RateLimitResult.allowed(key, strategy.getAlgorithmType(), 0);
        }
    }

    private static final class ThrowingRateLimiter implements RateLimiter {
        private RuntimeException acquireError;

        @Override
        public RateLimitResult tryAcquire(String key, RateLimitStrategy strategy, int permits) {
            return RateLimitResult.allowed(key, strategy.getAlgorithmType(), 0);
        }

        @Override
        public boolean acquire(String key, RateLimitStrategy strategy, long timeoutMs) {
            if (acquireError != null) {
                throw acquireError;
            }
            return true;
        }

        @Override
        public AlgorithmType getAlgorithmType() {
            return AlgorithmType.TOKEN_BUCKET;
        }
    }
}
