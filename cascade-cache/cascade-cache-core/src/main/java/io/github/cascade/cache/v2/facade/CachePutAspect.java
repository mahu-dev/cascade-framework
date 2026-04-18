package io.github.cascade.cache.v2.facade;

import io.github.cascade.cache.v2.api.Cache;
import io.github.cascade.cache.v2.api.annotations.CachePut;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 处理 {@link CachePut} 的切面。
 */
@Aspect
@Order(1)
public class CachePutAspect {

    private static final Logger LOGGER = LoggerFactory.getLogger(CachePutAspect.class);

    private final CacheAspectSupport support;

    public CachePutAspect(CacheAspectSupport support) {
        this.support = support;
    }

    @Around("@annotation(cachePut)")
    public Object handleCachePut(ProceedingJoinPoint joinPoint, CachePut cachePut) throws Throwable {
        Object result = joinPoint.proceed();
        if (!support.evaluateCondition(joinPoint, cachePut.condition(), result)) {
            LOGGER.debug("@CachePut condition=false，跳过缓存写入: method={}, condition={}",
                    joinPoint.getSignature().toShortString(), cachePut.condition());
            return result;
        }
        String cacheName = support.resolveCacheName(cachePut.value(), joinPoint);
        String cacheKey = support.evaluateStringCacheKey(joinPoint, cachePut.key());
        LOGGER.debug("@CachePut start: method={}, cache={}, key={}, ttl={}, sync={}, syncMode={}",
                joinPoint.getSignature().toShortString(),
                cacheName,
                cacheKey,
                cachePut.ttl(),
                cachePut.sync(),
                cachePut.syncMode());

        Cache<Object, Object> cache = support.getOrCreateCacheForCachePut(joinPoint, cacheName, cachePut, cacheKey);
        if (cache != null && result != null) {
            support.putQuietly(cache, cacheName, cacheKey, result, cachePut.ttl());
            LOGGER.debug("@CachePut write cache done: cache={}, key={}, resultType={}",
                    cacheName, cacheKey, result.getClass().getName());
        } else {
            LOGGER.debug("@CachePut skip cache write: cache={}, key={}, cacheNull={}, resultNull={}",
                    cacheName, cacheKey, cache == null, result == null);
        }
        return result;
    }
}
