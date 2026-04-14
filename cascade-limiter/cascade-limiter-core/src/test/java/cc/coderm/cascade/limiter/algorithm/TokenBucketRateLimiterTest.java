package cc.coderm.cascade.limiter.algorithm;

import cc.coderm.cascade.limiter.model.AlgorithmType;
import cc.coderm.cascade.limiter.model.RateLimitResult;
import cc.coderm.cascade.limiter.strategy.RateLimitStrategy;
import org.junit.jupiter.api.Test;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateLimiterConfig;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;

import java.lang.reflect.Proxy;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class TokenBucketRateLimiterTest {

    @Test
    void shouldRefreshRedisRateWhenStrategyChangesForSameKey() {
        FakeRateLimiterState state = new FakeRateLimiterState();
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(createRedissonClient(state));

        limiter.tryAcquire("rl:k", tokenBucketStrategy(10), 1);
        limiter.tryAcquire("rl:k", tokenBucketStrategy(10), 1);
        limiter.tryAcquire("rl:k", tokenBucketStrategy(20), 1);

        assertThat(state.trySetRateCalls).isEqualTo(1);
        assertThat(state.setRateCalls).isEqualTo(1);
        assertThat(state.redisConfig.getRate()).isEqualTo(20L);
        assertThat(state.redisConfig.getRateInterval()).isEqualTo(1000L);
        assertThat(state.redisConfig.getRateType()).isEqualTo(RateType.OVERALL);
    }

    @Test
    void shouldBackfillLocalCacheFromRedisConfigWhenCacheMissed() {
        FakeRateLimiterState state = new FakeRateLimiterState();
        state.redisConfig = new RateLimiterConfig(RateType.OVERALL, 1000L, 15L);
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(createRedissonClient(state));

        limiter.tryAcquire("rl:k", tokenBucketStrategy(15), 1);
        limiter.tryAcquire("rl:k", tokenBucketStrategy(15), 1);

        assertThat(state.getConfigCalls).isEqualTo(1);
        assertThat(state.trySetRateCalls).isZero();
        assertThat(state.setRateCalls).isZero();
    }

    @Test
    void shouldUpdateRedisConfigWhenBackfilledConfigDiffersFromStrategy() {
        FakeRateLimiterState state = new FakeRateLimiterState();
        state.redisConfig = new RateLimiterConfig(RateType.OVERALL, 1000L, 5L);
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(createRedissonClient(state));

        limiter.tryAcquire("rl:k", tokenBucketStrategy(30), 1);

        assertThat(state.getConfigCalls).isEqualTo(1);
        assertThat(state.trySetRateCalls).isZero();
        assertThat(state.setRateCalls).isEqualTo(1);
        assertThat(state.redisConfig.getRate()).isEqualTo(30L);
    }

    @Test
    void shouldEstimateWaitMillisByAvailablePermits() {
        FakeRateLimiterState state = new FakeRateLimiterState();
        state.allowAcquire = false;
        state.availablePermits = 7L;
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(createRedissonClient(state));

        RateLimitResult result = limiter.tryAcquire("rl:k", tokenBucketStrategy(5), 10);

        assertThat(result.isAllowed()).isFalse();
        assertThat(result.getWaitMillis()).isEqualTo(600L);
    }

    @Test
    void shouldFallbackToConservativeWaitWhenAvailablePermitsReadFails() {
        FakeRateLimiterState state = new FakeRateLimiterState();
        state.allowAcquire = false;
        state.throwOnAvailablePermits = true;
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(createRedissonClient(state));

        RateLimitResult result = limiter.tryAcquire("rl:k", tokenBucketStrategy(5), 10);

        assertThat(result.isAllowed()).isFalse();
        assertThat(result.getWaitMillis()).isEqualTo(2000L);
    }

    private static RateLimitStrategy tokenBucketStrategy(long refillRate) {
        return RateLimitStrategy.builder()
                .algorithmType(AlgorithmType.TOKEN_BUCKET)
                .maxPermits(100L)
                .refillRate(refillRate)
                .build();
    }

    private static RedissonClient createRedissonClient(FakeRateLimiterState state) {
        RRateLimiter rateLimiter = createRateLimiter(state);
        return (RedissonClient) Proxy.newProxyInstance(
                RedissonClient.class.getClassLoader(),
                new Class<?>[]{RedissonClient.class},
                (proxy, method, args) -> {
                    if ("getRateLimiter".equals(method.getName())) {
                        return rateLimiter;
                    }
                    if ("toString".equals(method.getName())) {
                        return "RedissonClientStub";
                    }
                    return defaultValue(method.getReturnType());
                }
        );
    }

    private static RRateLimiter createRateLimiter(FakeRateLimiterState state) {
        return (RRateLimiter) Proxy.newProxyInstance(
                RRateLimiter.class.getClassLoader(),
                new Class<?>[]{RRateLimiter.class},
                (proxy, method, args) -> {
                    String methodName = method.getName();

                    if ("trySetRate".equals(methodName) && args != null && args.length == 3) {
                        state.trySetRateCalls++;
                        if (state.redisConfig != null) {
                            return false;
                        }
                        state.redisConfig = toConfig(args);
                        return true;
                    }
                    if ("setRate".equals(methodName) && args != null && args.length == 3) {
                        state.setRateCalls++;
                        state.redisConfig = toConfig(args);
                        return null;
                    }
                    if ("getConfig".equals(methodName)) {
                        state.getConfigCalls++;
                        return state.redisConfig;
                    }
                    if ("tryAcquire".equals(methodName)) {
                        state.tryAcquireCalls++;
                        return state.allowAcquire;
                    }
                    if ("availablePermits".equals(methodName)) {
                        if (state.throwOnAvailablePermits) {
                            throw new IllegalStateException("unavailable");
                        }
                        return state.availablePermits;
                    }
                    if ("acquire".equals(methodName)) {
                        return null;
                    }
                    if ("toString".equals(methodName)) {
                        return "RRateLimiterStub";
                    }
                    return defaultValue(method.getReturnType());
                }
        );
    }

    private static RateLimiterConfig toConfig(Object[] args) {
        RateType type = (RateType) args[0];
        long rate = ((Number) args[1]).longValue();
        long intervalMs = ((Duration) args[2]).toMillis();
        return new RateLimiterConfig(type, intervalMs, rate);
    }

    private static Object defaultValue(Class<?> returnType) {
        if (returnType == void.class) {
            return null;
        }
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == byte.class) {
            return (byte) 0;
        }
        if (returnType == short.class) {
            return (short) 0;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == float.class) {
            return 0F;
        }
        if (returnType == double.class) {
            return 0D;
        }
        if (returnType == char.class) {
            return '\0';
        }
        return null;
    }

    private static final class FakeRateLimiterState {
        private boolean allowAcquire = true;
        private boolean throwOnAvailablePermits;
        private long availablePermits = 100L;
        private RateLimiterConfig redisConfig;
        private int trySetRateCalls;
        private int setRateCalls;
        private int getConfigCalls;
        private int tryAcquireCalls;
    }
}
