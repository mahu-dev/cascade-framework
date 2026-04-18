package io.github.cascade.cache.v2.facade;

import io.github.cascade.cache.v2.api.Cache;
import io.github.cascade.cache.v2.api.annotations.Cacheable;
import io.github.cascade.cache.v2.common.exception.CacheLoadException;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.util.StringUtils;

import java.util.Optional;

/**
 * 处理 {@link Cacheable} 的切面。
 */
@Aspect
@Order(1)
public class CacheableAspect {

    private static final Logger LOGGER = LoggerFactory.getLogger(CacheableAspect.class);

    private final CacheAspectSupport support;

    public CacheableAspect(CacheAspectSupport support) {
        this.support = support;
    }

    @Around("@annotation(cacheable)")
    public Object handleCacheable(ProceedingJoinPoint joinPoint, Cacheable cacheable) throws Throwable {
        if (support.isInternalInvocation()) {
            return joinPoint.proceed();
        }
        if (!support.evaluateCondition(joinPoint, cacheable.condition(), null)) {
            LOGGER.debug("@Cacheable condition=false，绕过缓存链路: method={}, condition={}",
                    joinPoint.getSignature().toShortString(), cacheable.condition());
            return joinPoint.proceed();
        }

        String cacheName = support.resolveCacheName(cacheable.value(), cacheable.name(), joinPoint);
        String cacheKey = support.evaluateStringCacheKey(joinPoint, cacheable.key());
        LOGGER.debug("@Cacheable start: method={}, cache={}, key={}, asyncLoad={}, condition={}, unless={}",
                joinPoint.getSignature().toShortString(),
                cacheName,
                cacheKey,
                cacheable.asyncLoad(),
                cacheable.condition(),
                cacheable.unless());

        Cache<Object, Object> cache = support.getOrCreateCacheForCacheable(joinPoint, cacheName, cacheable, cacheKey);
        if (cache == null) {
            LOGGER.debug("@Cacheable cache unavailable，降级执行方法: cache={}, key={}", cacheName, cacheKey);
            return joinPoint.proceed();
        }
        support.registerSnapshot(cacheName, cacheKey, joinPoint);

        boolean primitiveReturnType = support.isPrimitiveValueReturnType(joinPoint);
        try {
            boolean skipLoadOnMiss = StringUtils.hasText(cacheable.unless()) || cacheable.asyncLoad();
            Optional<Object> cached = support.readFromCache(cache, cacheKey, skipLoadOnMiss);
            if (cached.isPresent()) {
                LOGGER.debug("@Cacheable cache hit: cache={}, key={}", cacheName, cacheKey);
                return cached.get();
            }
            LOGGER.debug("@Cacheable cache miss: cache={}, key={}, skipLoadOnMiss={}",
                    cacheName, cacheKey, skipLoadOnMiss);
            if (tryHandleAsyncLoadOnMiss(cacheable, primitiveReturnType, cache, cacheName, cacheKey)) {
                LOGGER.debug("@Cacheable asyncLoad miss handled: cache={}, key={}", cacheName, cacheKey);
                return null;
            }
        } catch (CacheLoadException e) {
            // 加载器（快照回放）执行失败：业务方法已经被调用过一次并抛出了异常，
            // 不能吞掉后再 fallthrough 到 joinPoint.proceed()，否则业务方法会被重复执行。
            // 解包原始异常，保持语义透明。
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            if (cause instanceof Error err) {
                throw err;
            }
            throw e;
        } catch (Exception e) {
            // 缓存基础设施故障（如 Redis 连接失败、序列化异常等），降级执行业务方法。
            LOGGER.warn("@Cacheable读取缓存失败，降级执行方法: cache={}, key={}, error={}",
                    cacheName, cacheKey, e.getMessage());
        }

        Object result = joinPoint.proceed();
        if (result != null && support.shouldCacheResult(joinPoint, cacheable.unless(), result)) {
            LOGGER.debug("@Cacheable write cache: cache={}, key={}, resultType={}",
                    cacheName, cacheKey, result.getClass().getName());
            support.putQuietly(cache, cacheName, cacheKey, result, support.resolveCacheableHardTtl(cacheable));
        } else {
            LOGGER.debug("@Cacheable skip cache write: cache={}, key={}, resultNullOrUnless=true",
                    cacheName, cacheKey);
        }
        return result;
    }

    private static boolean tryHandleAsyncLoadOnMiss(Cacheable cacheable,
                                                    boolean primitiveReturnType,
                                                    Cache<Object, Object> cache,
                                                    String cacheName,
                                                    Object cacheKey) {
        if (!cacheable.asyncLoad()) {
            return false;
        }
        if (!primitiveReturnType) {
            triggerAsyncLoad(cache, cacheName, cacheKey);
            return true;
        }
        LOGGER.warn("@Cacheable异步加载不支持primitive返回类型，降级为同步执行: cache={}, key={}",
                cacheName, cacheKey);
        return false;
    }

    private static void triggerAsyncLoad(Cache<Object, Object> cache, String cacheName, Object cacheKey) {
        if (cacheKey == null) {
            return;
        }
        try {
            cache.getAsync(cacheKey).whenComplete((ignored, throwable) -> {
                if (throwable != null) {
                    LOGGER.warn("@Cacheable异步加载失败: cache={}, key={}, error={}",
                            cacheName, cacheKey, throwable.getMessage());
                }
            });
        } catch (Exception e) {
            LOGGER.warn("@Cacheable触发异步加载失败: cache={}, key={}, error={}",
                    cacheName, cacheKey, e.getMessage());
        }
    }
}
