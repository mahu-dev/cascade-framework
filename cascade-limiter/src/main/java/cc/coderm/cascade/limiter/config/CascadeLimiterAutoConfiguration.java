package cc.coderm.cascade.limiter.config;

import cc.coderm.cascade.limiter.CascadeLimiter;
import cc.coderm.cascade.limiter.algorithm.*;
import cc.coderm.cascade.limiter.aspect.RateLimitAspect;
import cc.coderm.cascade.limiter.factory.RateLimiterFactory;
import cc.coderm.cascade.limiter.fallback.RateLimitFallbackHandler;
import cc.coderm.cascade.limiter.metrics.RateLimitMetrics;
import cc.coderm.cascade.limiter.support.LimiterBackendFailureTracker;
import cc.coderm.cascade.limiter.support.LimiterClockRollbackTracker;
import cc.coderm.cascade.limiter.support.KeyAdmissionGuard;
import cc.coderm.cascade.limiter.support.RedisLuaScriptExecutor;
import cc.coderm.cascade.limiter.support.RedisKeyAdmissionGuard;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.List;

/**
 * cascade-limiter Spring Boot 自动装配入口。
 *
 * <p>依赖条件：classpath 上必须存在 {@link RedissonClient}（即引入了 redisson-spring-boot-starter）。
 */
@AutoConfiguration
@ConditionalOnBean(RedissonClient.class)
@EnableConfigurationProperties(CascadeLimiterProperties.class)
public class CascadeLimiterAutoConfiguration {

    // ─────────────── Algorithm beans ───────────────

    @Bean
    @ConditionalOnMissingBean
    public FixedWindowRateLimiter fixedWindowRateLimiter(RedisLuaScriptExecutor scriptExecutor) {
        return new FixedWindowRateLimiter(scriptExecutor);
    }

    @Bean
    @ConditionalOnMissingBean
    public SlidingWindowRateLimiter slidingWindowRateLimiter(RedisLuaScriptExecutor scriptExecutor,
                                                             LimiterClockRollbackTracker clockRollbackTracker) {
        return new SlidingWindowRateLimiter(scriptExecutor, clockRollbackTracker);
    }

    @Bean
    @ConditionalOnMissingBean
    public TokenBucketRateLimiter tokenBucketRateLimiter(RedissonClient redissonClient) {
        return new TokenBucketRateLimiter(redissonClient);
    }

    @Bean
    @ConditionalOnMissingBean
    public LeakyBucketRateLimiter leakyBucketRateLimiter(RedisLuaScriptExecutor scriptExecutor,
                                                         LimiterClockRollbackTracker clockRollbackTracker) {
        return new LeakyBucketRateLimiter(scriptExecutor, clockRollbackTracker);
    }

    // ─────────────── Factory ───────────────

    @Bean
    @ConditionalOnMissingBean
    public RateLimiterFactory rateLimiterFactory(List<RateLimiter> rateLimiters,
                                                 KeyAdmissionGuard keyAdmissionGuard) {
        return new RateLimiterFactory(rateLimiters, keyAdmissionGuard);
    }

    @Bean
    @ConditionalOnMissingBean
    public CascadeLimiter cascadeLimiter(RateLimiterFactory rateLimiterFactory,
                                         CascadeLimiterProperties properties,
                                         LimiterBackendFailureTracker backendFailureTracker) {
        return new CascadeLimiter(rateLimiterFactory, properties, backendFailureTracker);
    }

    // ─────────────── Metrics (optional) ───────────────

    @Bean
    @ConditionalOnClass(MeterRegistry.class)
    @ConditionalOnBean(MeterRegistry.class)
    @ConditionalOnProperty(prefix = "cascade.limiter", name = "metrics-enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean
    public RateLimitMetrics rateLimitMetrics(MeterRegistry meterRegistry) {
        return new RateLimitMetrics(meterRegistry);
    }

    // ─────────────── AOP Aspect ───────────────

    @Bean
    @ConditionalOnMissingBean
    public RateLimitAspect rateLimitAspect(
            RateLimiterFactory rateLimiterFactory,
            CascadeLimiterProperties properties,
            ObjectProvider<RateLimitFallbackHandler> globalFallbackProvider,
            ObjectProvider<RateLimitMetrics> metricsProvider,
            BeanFactory beanFactory,
            LimiterBackendFailureTracker backendFailureTracker) {
        return new RateLimitAspect(rateLimiterFactory, properties,
                globalFallbackProvider.getIfAvailable(),
                metricsProvider.getIfAvailable(),
                beanFactory, backendFailureTracker);
    }

    @Bean
    @ConditionalOnMissingBean
    public LimiterBackendFailureTracker limiterBackendFailureTracker(CascadeLimiterProperties properties) {
        return new LimiterBackendFailureTracker(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public LimiterClockRollbackTracker limiterClockRollbackTracker(
            CascadeLimiterProperties properties,
            ObjectProvider<MeterRegistry> meterRegistryProvider) {
        return new LimiterClockRollbackTracker(properties, meterRegistryProvider.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    public KeyAdmissionGuard keyAdmissionGuard(RedisLuaScriptExecutor scriptExecutor,
                                               CascadeLimiterProperties properties) {
        return new RedisKeyAdmissionGuard(scriptExecutor, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public RedisLuaScriptExecutor redisLuaScriptExecutor(RedissonClient redissonClient) {
        return new RedisLuaScriptExecutor(redissonClient);
    }
}
