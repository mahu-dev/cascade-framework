package io.github.cascade.cache.aspect;

import io.github.cascade.cache.annotation.CacheEvict;
import io.github.cascade.cache.annotation.CachePut;
import io.github.cascade.cache.annotation.Cacheable;
import io.github.cascade.cache.api.Cache;
import io.github.cascade.cache.api.CacheManager;
import io.github.cascade.cache.common.CacheTypeResolver;
import io.github.cascade.cache.common.exception.CacheExceptionHandler;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.util.StringUtils;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * @author lionel lionelk@163.com
 * =============================
 * Date: 2025/10/29
 * Time: 17:32
 * =============================
 */

/**
 * 统一缓存切面实现（重构版）
 * <p>
 * 设计原则：
 * 1. 统一处理：一个切面处理所有缓存注解
 * 2. 性能优先：优化热点路径，使用工具类减少复杂度
 * 3. 异常安全：缓存异常不影响业务方法执行
 * 4. 职责分离：使用专门工具类处理SpEL解析和类型推断
 * <p>
 * P1级重构优化（2025-10-29）：
 * - 使用CacheExceptionHandler统一异常处理
 * - 使用CacheTypeResolver优化类型推断
 * - 简化切面逻辑，专注AOP处理
 * - 统一Logger命名规范
 * - 增强异常处理和日志记录
 * - 支持自动刷新功能
 */
@Aspect
@Order(1) // 高优先级，确保在其他切面之前执行
public class CacheAspect {

    private static final Logger LOGGER = LoggerFactory.getLogger(CacheAspect.class);

    private final CacheManager cacheManager;
    private final CacheExpressionEvaluator expressionEvaluator;
    private final CacheTypeResolver typeResolver;

    public CacheAspect(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
        this.expressionEvaluator = new CacheExpressionEvaluator();
        this.typeResolver = new CacheTypeResolver();
        LOGGER.info("缓存切面已初始化，使用专用表达式求值器和类型解析器");
    }

    // ==================== @Cacheable 处理 ====================

    @Around("@annotation(cacheable)")
    public Object handleCacheable(ProceedingJoinPoint joinPoint, Cacheable cacheable) throws Throwable {
        String cacheName = resolveCacheName(cacheable.value());
        Object cacheKey = evaluateCacheKey(joinPoint, cacheable.key());

        logCacheableStart(cacheName, cacheKey);

        return CacheExceptionHandler.<Object>safeExecute((Supplier<Object>) () -> {
            try {
                if (!evaluateCondition(joinPoint, cacheable.condition(), null)) {
                    LOGGER.debug("@Cacheable条件不满足，跳过缓存: condition={}", cacheable.condition());
                    return joinPoint.proceed();
                }

                // 动态获取或创建缓存
                Cache<Object, Object> cache = getOrCreateCache(joinPoint, cacheName, cacheable);
                Optional<Object> cachedValue = cache.get(cacheKey);

                if (cachedValue.isPresent()) {
                    logCacheHit(cacheName, cacheKey);
                    return cachedValue.get();
                }

                logCacheMiss(cacheName, cacheKey);
                Object result = joinPoint.proceed();

                if (result != null) {
                    putToCache(cache, cacheName, cacheKey, result, cacheable.ttl());
                }

                return result;
            } catch (Throwable t) {
                LOGGER.error("缓存操作异常: cache={}, key={}", cacheName, cacheKey, t);
                try {
                    return joinPoint.proceed();
                } catch (Throwable t2) {
                    LOGGER.error("业务方法执行失败", t2);
                    throw new RuntimeException(t2);
                }
            }
        }, null);
    }

    // ==================== @CachePut 处理 ====================

    @Around("@annotation(cachePut)")
    public Object handleCachePut(ProceedingJoinPoint joinPoint, CachePut cachePut) throws Throwable {
        String cacheName = resolveCacheName(cachePut.value());
        Object cacheKey = evaluateCacheKey(joinPoint, cachePut.key());

        logCachePutStart(cacheName, cacheKey);

        return CacheExceptionHandler.safeExecute(() -> {
            try {
                if (!evaluateCondition(joinPoint, cachePut.condition(), null)) {
                    LOGGER.debug("@CachePut条件不满足，跳过缓存: condition={}", cachePut.condition());
                    return joinPoint.proceed();
                }

                Object result = joinPoint.proceed();

                if (result != null) {
                    Cache<Object, Object> cache = getOrCreateCache(joinPoint, cacheName, cachePut);
                    putToCache(cache, cacheName, cacheKey, result, cachePut.ttl());
                }

                return result;
            } catch (Throwable t) {
                LOGGER.error("缓存操作异常: cache={}, key={}", cacheName, cacheKey, t);
                try {
                    return joinPoint.proceed();
                } catch (Throwable t2) {
                    LOGGER.error("业务方法执行失败", t2);
                    throw new RuntimeException(t2);
                }
            }
        }, null);
    }

    // ==================== @CacheEvict 处理 ====================

    @Around("@annotation(cacheEvict)")
    public Object handleCacheEvict(ProceedingJoinPoint joinPoint, CacheEvict cacheEvict) throws Throwable {
        String cacheName = resolveCacheName(cacheEvict.value());
        Object cacheKey = evaluateCacheKey(joinPoint, cacheEvict.key());

        logCacheEvictStart(cacheName, cacheKey);

        return CacheExceptionHandler.<Object>safeExecute((Supplier<Object>) () -> {
            try {
                // 根据allEntries属性决定处理方式
                if (cacheEvict.allEntries()) {
                    evictAllCache(cacheName);
                } else if (cacheKey != null) {
                    evictCache(cacheName, cacheKey);
                }

                // 根据beforeInvocation属性决定执行时机
                if (cacheEvict.beforeInvocation()) {
                    return joinPoint.proceed();
                } else {
                    try {
                        return joinPoint.proceed();
                    } catch (Exception e) {
                        // 方法执行失败，记录警告但不回滚缓存清除操作
                        LOGGER.warn("业务方法执行失败: cache={}, key={}, error={}", cacheName, cacheKey, e.getMessage());
                        throw e;
                    }
                }
            } catch (Throwable t) {
                LOGGER.error("缓存操作异常: cache={}, key={}", cacheName, cacheKey, t);
                try {
                    return joinPoint.proceed();
                } catch (Throwable t2) {
                    LOGGER.error("业务方法执行失败", t2);
                    throw new RuntimeException(t2);
                }
            }
        }, null);
    }

    // ==================== 工具方法 ====================

    /**
     * 解析缓存名称
     * 优化：移除不必要的异常包装，直接检查
     */
    private String resolveCacheName(String cacheName) {
        if (!StringUtils.hasText(cacheName)) {
            throw new IllegalArgumentException("缓存名称不能为空");
        }
        return cacheName;
    }

    /**
     * 评估缓存键
     */
    private Object evaluateCacheKey(JoinPoint joinPoint, String keyExpression) {
        return CacheExceptionHandler.<Object>safeExecute((Supplier<Object>) () -> {
            return expressionEvaluator.evaluate(keyExpression, joinPoint, null);
        }, joinPoint.getSignature().toShortString());
    }

    /**
     * 评估条件表达式
     */
    private boolean evaluateCondition(JoinPoint joinPoint, String condition, Object result) {
        if (!StringUtils.hasText(condition)) {
            return true;
        }

        return CacheExceptionHandler.<Boolean>safeExecute((Supplier<Boolean>) () -> {
            boolean value = expressionEvaluator.evaluateBoolean(condition, joinPoint, result);
            LOGGER.debug("条件表达式求值结果: condition={}, result={}", condition, value);
            return value;
        }, true);
    }

    /**
     * 获取或创建缓存
     */
    @SuppressWarnings("unchecked")
    private Cache<Object, Object> getOrCreateCache(JoinPoint joinPoint, String cacheName, Object annotation) {
        return CacheExceptionHandler.<Cache<Object, Object>>safeExecute((Supplier<Cache<Object, Object>>) () -> {
            Class<?> keyType = typeResolver.inferKeyType(joinPoint);
            Class<?> valueType = typeResolver.inferValueType(joinPoint);

            LOGGER.debug("推断的缓存类型: cacheName={}, keyType={}, valueType={}",
                    cacheName, keyType.getSimpleName(), valueType.getSimpleName());

            // 注意：新的设计中，通过配置自动应用装饰器（自动刷新、分布式同步等）
            if (annotation instanceof Cacheable) {
                return (Cache<Object, Object>) cacheManager.getOrCreateCache(cacheName, keyType, valueType);
            } else if (annotation instanceof CachePut) {
                return (Cache<Object, Object>) cacheManager.getOrCreateCache(cacheName, keyType, valueType);
            } else if (annotation instanceof CacheEvict) {
                return (Cache<Object, Object>) cacheManager.getCache(cacheName);
            }

            throw new IllegalArgumentException("未知的缓存注解类型: " + annotation.getClass());
        }, (Cache<Object, Object>) cacheManager.getOrCreateCache(cacheName, Object.class, Object.class));
    }

    /**
     * 将值放入缓存
     */
    private void putToCache(Cache<Object, Object> cache, String cacheName, Object key, Object value, long ttl) {
        CacheExceptionHandler.safeExecute(() -> {
            if (ttl > 0) {
                cache.put(key, value, ttl);
                LOGGER.debug("缓存已更新（带TTL）: cache={}, key={}, ttl={}s", cacheName, key, ttl);
            } else {
                cache.put(key, value);
                LOGGER.debug("缓存已更新: cache={}, key={}", cacheName, key);
            }
            return null;
        }, null);
    }

    /**
     * 清除指定缓存
     */
    private void evictCache(String cacheName, Object key) {
        CacheExceptionHandler.safeExecute(() -> {
            Cache<Object, Object> cache = (Cache<Object, Object>) cacheManager.getCache(cacheName);
            if (cache != null) {
                cache.evict(key);
                LOGGER.debug("缓存已清除: cache={}, key={}", cacheName, key);
            } else {
                LOGGER.warn("缓存不存在，跳过清除: cache={}", cacheName);
            }
            return null;
        }, null);
    }

    /**
     * 清除所有缓存
     */
    private void evictAllCache(String cacheName) {
        CacheExceptionHandler.safeExecute(() -> {
            Cache<Object, Object> cache = (Cache<Object, Object>) cacheManager.getCache(cacheName);
            if (cache != null) {
                cache.clear();
                LOGGER.debug("缓存已全部清除: cache={}", cacheName);
            } else {
                LOGGER.warn("缓存不存在，跳过清除: cache={}", cacheName);
            }
            return null;
        }, null);
    }

    // ==================== 日志方法 ====================

    private void logCacheableStart(String cacheName, Object cacheKey) {
        LOGGER.debug("@Cacheable开始处理: cache={}, key={}", cacheName, cacheKey);
    }

    private void logCacheHit(String cacheName, Object cacheKey) {
        LOGGER.debug("缓存命中: cache={}, key={}", cacheName, cacheKey);
    }

    private void logCacheMiss(String cacheName, Object cacheKey) {
        LOGGER.debug("缓存未命中: cache={}, key={}", cacheName, cacheKey);
    }

    private void logCachePutStart(String cacheName, Object cacheKey) {
        LOGGER.debug("@CachePut开始处理: cache={}, key={}", cacheName, cacheKey);
    }

    private void logCacheEvictStart(String cacheName, Object cacheKey) {
        LOGGER.debug("@CacheEvict开始处理: cache={}, key={}", cacheName, cacheKey);
    }
}