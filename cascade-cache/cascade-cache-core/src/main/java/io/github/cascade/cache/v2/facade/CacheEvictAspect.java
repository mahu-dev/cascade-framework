package io.github.cascade.cache.v2.facade;

import io.github.cascade.cache.v2.api.annotations.CacheEvict;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 处理 {@link CacheEvict} 的切面。
 */
@Aspect
@Order(1)
public class CacheEvictAspect {

    private static final Logger LOGGER = LoggerFactory.getLogger(CacheEvictAspect.class);

    private final CacheAspectSupport support;

    public CacheEvictAspect(CacheAspectSupport support) {
        this.support = support;
    }

    @Around("@annotation(cacheEvict)")
    public Object handleCacheEvict(ProceedingJoinPoint joinPoint, CacheEvict cacheEvict) throws Throwable {
        String cacheName = support.resolveCacheName(cacheEvict.value(), joinPoint);

        if (cacheEvict.beforeInvocation()) {
            if (support.evaluateCondition(joinPoint, cacheEvict.condition(), null)) {
                List<String> keysToEvict = support.evaluateEvictKeys(joinPoint, cacheEvict.key(), cacheEvict.allEntries());
                LOGGER.debug("@CacheEvict beforeInvocation: method={}, cache={}, allEntries={}, keys={}, sync={}",
                        joinPoint.getSignature().toShortString(),
                        cacheName,
                        cacheEvict.allEntries(),
                        keysToEvict,
                        cacheEvict.sync());
                support.evictQuietly(cacheName, keysToEvict, cacheEvict.allEntries(), cacheEvict.sync());
            } else {
                LOGGER.debug("@CacheEvict beforeInvocation condition=false，跳过清理: method={}, condition={}",
                        joinPoint.getSignature().toShortString(), cacheEvict.condition());
            }
            return joinPoint.proceed();
        }

        Object result = joinPoint.proceed();
        if (support.evaluateCondition(joinPoint, cacheEvict.condition(), result)) {
            List<String> keysToEvict = support.evaluateEvictKeys(joinPoint, cacheEvict.key(), cacheEvict.allEntries());
            LOGGER.debug("@CacheEvict afterInvocation: method={}, cache={}, allEntries={}, keys={}, sync={}",
                    joinPoint.getSignature().toShortString(),
                    cacheName,
                    cacheEvict.allEntries(),
                    keysToEvict,
                    cacheEvict.sync());
            support.evictQuietly(cacheName, keysToEvict, cacheEvict.allEntries(), cacheEvict.sync());
        } else {
            LOGGER.debug("@CacheEvict afterInvocation condition=false，跳过清理: method={}, condition={}",
                    joinPoint.getSignature().toShortString(), cacheEvict.condition());
        }
        return result;
    }
}
