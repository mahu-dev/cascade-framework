package cc.coderm.cascade.limiter.aspect;

import cc.coderm.cascade.limiter.annotation.RateLimit;
import cc.coderm.cascade.limiter.config.CascadeLimiterProperties;
import cc.coderm.cascade.limiter.exception.RateLimitException;
import cc.coderm.cascade.limiter.factory.RateLimiterFactory;
import cc.coderm.cascade.limiter.fallback.RateLimitFallbackHandler;
import cc.coderm.cascade.limiter.metrics.RateLimitMetrics;
import cc.coderm.cascade.limiter.model.RateLimitResult;
import cc.coderm.cascade.limiter.strategy.RateLimitStrategy;
import cc.coderm.cascade.limiter.support.LimiterBackendFailureTracker;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.lang.Nullable;

import java.lang.reflect.Method;

/**
 * 注解式限流切面：只负责流程编排，具体职责委托给协作器。
 */
@Slf4j
@Aspect
public class RateLimitAspect {

    // 无状态工具类：静态方法调用
    // 有状态组件：实例方法调用（依赖构造参数）

    private final RateLimitKeyResolver keyResolver;
    private final RateLimitAcquirePolicy acquirePolicy;

    @Nullable
    private final RateLimitFallbackHandler globalFallback;
    @Nullable
    private final RateLimitMetrics metrics;

    public RateLimitAspect(RateLimiterFactory limiterFactory,
                           CascadeLimiterProperties properties,
                           @Nullable RateLimitFallbackHandler globalFallback,
                           @Nullable RateLimitMetrics metrics,
                           BeanFactory beanFactory,
                           LimiterBackendFailureTracker backendFailureTracker) {
        this.globalFallback = globalFallback;
        this.metrics = metrics;
        this.keyResolver = new RateLimitKeyResolver(properties, beanFactory);
        this.acquirePolicy = new RateLimitAcquirePolicy(limiterFactory, properties, backendFailureTracker);
    }

    //  "@target(cc.coderm.cascade.limiter.annotation.RateLimit)"
    @Around(
            "@annotation(cc.coderm.cascade.limiter.annotation.RateLimit) || " +
                    "@within(cc.coderm.cascade.limiter.annotation.RateLimit) "
    )
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        // 静态工具类：直接调用静态方法
        RateLimitInvocationResolver.ResolvedInvocation invocation = RateLimitInvocationResolver.resolve(pjp);
        RateLimit rateLimit = invocation.rateLimit();
        if (rateLimit == null) {
            return pjp.proceed();
        }

        Method method = invocation.method();
        String resolvedKey = keyResolver.resolveKey(rateLimit, method, pjp.getArgs(), pjp.getTarget());
        // 静态工具类：直接调用静态方法
        RateLimitStrategy strategy = RateLimitStrategyFactory.fromAnnotation(rateLimit);
        RateLimitResult result = acquirePolicy.tryAcquire(resolvedKey, strategy);

        if (metrics != null) {
            metrics.recordRateLimitResult(result, method);
        }
        if (result.isAllowed()) {
            return pjp.proceed();
        }

        log.debug("[CascadeLimiter] Rejected key={} algorithm={} waitMs={}",
                resolvedKey, rateLimit.algorithm(), result.getWaitMillis());

        if (!rateLimit.fallbackMethod().isEmpty()) {
            return RateLimitFallbackInvoker.invoke(pjp, method, rateLimit.fallbackMethod(), result);
        }
        if (globalFallback != null) {
            String context = method.getDeclaringClass().getSimpleName() + "." + method.getName();
            return globalFallback.handle(result, context);
        }
        throw new RateLimitException(result, rateLimit.message());
    }
}
