package cc.coderm.cascade.limiter;

import cc.coderm.cascade.limiter.config.CascadeLimiterProperties;
import cc.coderm.cascade.limiter.exception.RateLimitException;
import cc.coderm.cascade.limiter.factory.RateLimiterFactory;
import cc.coderm.cascade.limiter.model.AlgorithmType;
import cc.coderm.cascade.limiter.model.RateLimitResult;
import cc.coderm.cascade.limiter.strategy.RateLimitStrategy;
import cc.coderm.cascade.limiter.support.LimiterBackendFailureTracker;
import cc.coderm.cascade.limiter.support.LimiterExceptionClassifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * cascade-limiter 编程式 API 门面。
 *
 * <p>提供流式构建器风格的调用入口，适合在代码中动态控制限流逻辑。
 *
 * <h3>快速上手</h3>
 * <pre>{@code
 * // 注入门面
 * @Autowired
 * private CascadeLimiter cascadeLimiter;
 *
 * // 令牌桶：100 次/秒，超限抛异常
 * cascadeLimiter.key("api:order:" + userId)
 *               .algorithm(AlgorithmType.TOKEN_BUCKET)
 *               .maxPermits(100)
 *               .tryAcquireOrThrow();
 *
 * // 滑动窗口：60 次/分，超限执行降级逻辑
 * RateLimitResult result = cascadeLimiter.key("api:sms")
 *               .algorithm(AlgorithmType.SLIDING_WINDOW)
 *               .maxPermits(60)
 *               .window(Duration.ofMinutes(1))
 *               .tryAcquire();
 * if (!result.isAllowed()) {
 *     return fallbackResponse();
 * }
 *
 * // 固定窗口：wrapped 写法，超限返回降级值
 * String resp = cascadeLimiter.key("api:search")
 *               .algorithm(AlgorithmType.FIXED_WINDOW)
 *               .maxPermits(200)
 *               .execute(() -> searchService.query(q),
 *                        () -> "cached result");
 * }</pre>
 */
@Slf4j
@RequiredArgsConstructor
public class CascadeLimiter {

    private final RateLimiterFactory limiterFactory;
    private final CascadeLimiterProperties properties;
    private final LimiterBackendFailureTracker backendFailureTracker;

    /**
     * 开始构建一个限流上下文，返回链式 Builder。
     *
     * @param key 限流 key（不含前缀）
     */
    public Builder key(String key) {
        return new Builder(properties.getRedisKeyPrefix() + key);
    }

    // ──────────────────────────────────────────────────
    //  Builder
    // ──────────────────────────────────────────────────

    public final class Builder {

        private final String fullKey;
        private final Thread ownerThread = Thread.currentThread();
        private AlgorithmType algorithm = properties.getDefaultAlgorithm();
        private long maxPermits = 100L;
        private Duration window = Duration.ofSeconds(1);
        private long refillRate = 0;
        private long leakRate = 0;
        private String message = "Too many requests, please try again later.";

        private Builder(String fullKey) {
            this.fullKey = fullKey;
        }

        public Builder algorithm(AlgorithmType algorithm) {
            assertThreadBound();
            this.algorithm = algorithm;
            return this;
        }

        public Builder maxPermits(long maxPermits) {
            assertThreadBound();
            this.maxPermits = maxPermits;
            return this;
        }

        /**
         * 固定窗口 / 滑动窗口的时间窗口大小
         */
        public Builder window(Duration window) {
            assertThreadBound();
            this.window = window;
            return this;
        }

        /**
         * 令牌桶：每秒补充令牌数
         */
        public Builder refillRate(long refillRate) {
            assertThreadBound();
            this.refillRate = refillRate;
            return this;
        }

        /**
         * 漏桶：每秒漏出请求数
         */
        public Builder leakRate(long leakRate) {
            assertThreadBound();
            this.leakRate = leakRate;
            return this;
        }

        public Builder message(String message) {
            assertThreadBound();
            this.message = message;
            return this;
        }

        // ── terminal operations ──

        /**
         * 非阻塞判断，返回限流结果。
         */
        public RateLimitResult tryAcquire() {
            assertThreadBound();
            return tryAcquire(1);
        }

        public RateLimitResult tryAcquire(int permits) {
            assertThreadBound();
            if (permits <= 0) {
                throw new IllegalArgumentException("Requested permits must be > 0");
            }
            try {
                RateLimitResult result = limiterFactory.tryAcquire(fullKey, buildStrategy(), permits);
                backendFailureTracker.recordSuccess();
                return result;
            } catch (RuntimeException ex) {
                if (!LimiterExceptionClassifier.isBackendException(ex)) {
                    throw ex;
                }
                return handleLimiterBackendError(ex, "programmatic.tryAcquire");
            }
        }

        /**
         * 非阻塞判断，超限直接抛 {@link RateLimitException}。
         */
        public void tryAcquireOrThrow() {
            assertThreadBound();
            RateLimitResult result = tryAcquire();
            if (!result.isAllowed()) {
                throw new RateLimitException(result, message);
            }
        }

        /**
         * 阻塞直到获取配额或超时。
         *
         * @param timeoutMs 超时时间（毫秒），<=0 表示无限等待
         * @return 是否成功获取
         */
        public boolean acquire(long timeoutMs) {
            assertThreadBound();
            try {
                boolean acquired = limiterFactory.acquire(fullKey, buildStrategy(), timeoutMs);
                backendFailureTracker.recordSuccess();
                return acquired;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } catch (RuntimeException ex) {
                if (!LimiterExceptionClassifier.isBackendException(ex)) {
                    throw ex;
                }
                backendFailureTracker.recordBackendFailure("programmatic.acquire", fullKey, algorithm, ex);
                log.warn("[CascadeLimiter] Limiter backend error in acquire, failOnError={}, key={}, algorithm={}",
                        properties.isFailOnError(), fullKey, algorithm, ex);
                return !properties.isFailOnError();
            }
        }

        /**
         * 包装执行：允许则执行 {@code action}，超限则执行 {@code fallback}。
         *
         * @param action   正常业务逻辑
         * @param fallback 降级逻辑
         * @param <T>      返回类型
         */
        public <T> T execute(Supplier<T> action, Supplier<T> fallback) {
            assertThreadBound();
            RateLimitResult result = tryAcquire();
            return result.isAllowed() ? action.get() : fallback.get();
        }

        /**
         * 包装执行（无返回值）。
         */
        public void execute(Runnable action, Runnable fallback) {
            assertThreadBound();
            RateLimitResult result = tryAcquire();
            if (result.isAllowed()) {
                action.run();
            } else {
                fallback.run();
            }
        }

        private RateLimitStrategy buildStrategy() {
            return RateLimitStrategy.builder()
                    .algorithmType(algorithm)
                    .maxPermits(maxPermits)
                    .window(window)
                    .refillRate(refillRate)
                    .leakRate(leakRate)
                    .message(message)
                    .build();
        }

        private RateLimitResult handleLimiterBackendError(RuntimeException ex, String source) {
            backendFailureTracker.recordBackendFailure(source, fullKey, algorithm, ex);
            if (properties.isFailOnError()) {
                log.warn("[CascadeLimiter] Limiter backend error, fail-closed. key={}, algorithm={}",
                        fullKey, algorithm, ex);
                return RateLimitResult.rejected(fullKey, algorithm, 0);
            }
            log.warn("[CascadeLimiter] Limiter backend error, fail-open. key={}, algorithm={}",
                    fullKey, algorithm, ex);
            return RateLimitResult.allowed(fullKey, algorithm, -1);
        }

        private void assertThreadBound() {
            if (Thread.currentThread() != ownerThread) {
                throw new IllegalStateException(
                        "CascadeLimiter.Builder is thread-confined and must not be shared across threads");
            }
        }
    }
}
