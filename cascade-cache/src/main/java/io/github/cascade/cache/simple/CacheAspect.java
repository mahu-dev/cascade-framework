package io.github.cascade.cache.simple;

import io.github.cascade.cache.exception.CacheException;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.util.StringUtils;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * 统一缓存切面实现（重构版）
 * <p>
 * 设计原则：
 * 1. 统一处理：一个切面处理所有缓存注解
 * 2. 性能优先：优化热点路径，使用工具类减少复杂度
 * 3. 异常安全：缓存异常不影响业务方法执行
 * 4. 职责分离：SpEL处理和类型推断独立为工具类
 *
 * @author cascade
 */
@Aspect
@Order(1) // 高优先级，确保在其他切面之前执行
public class CacheAspect<K, V> {

    private static final Logger LOGGER = LoggerFactory.getLogger(CacheAspect.class);

    private final CacheManager<K, V> cacheManager;
    private final SpelExpressionHelper spelHelper;
    private final TypeInferenceHelper typeHelper;
    private final FunctionalCacheOperations.SpelEvaluator functionalSpelEvaluator;

    public CacheAspect(CacheManager<K, V> cacheManager) {
        this.cacheManager = cacheManager;
        this.spelHelper = new SpelExpressionHelper(1000);
        this.typeHelper = new TypeInferenceHelper();
        this.functionalSpelEvaluator = new FunctionalCacheOperations.SpelEvaluator(spelHelper);
        LOGGER.info("缓存切面已初始化");
    }

    // ==================== @Cacheable 处理 ====================

    @Around("@annotation(cacheable)")
    public Object handleCacheable(ProceedingJoinPoint joinPoint, Cacheable cacheable) throws Throwable {
        String cacheName = resolveCacheName(joinPoint, cacheable.value());
        K cacheKey = evaluateCacheKey(joinPoint, cacheable.key());
        logCacheableStart(cacheName, cacheKey);
        try {
            if (evaluateCondition(joinPoint, cacheable.condition(), null)) {
                LOGGER.debug("@Cacheable条件不满足，跳过缓存: {}", cacheable.condition());
                return joinPoint.proceed();
            }
            Cache<K, V> cache = getOrCreateCache(joinPoint, cacheName, cacheable);
            Optional<V> cachedValue = cache.get(cacheKey);
            if (cachedValue.isPresent()) {
                return handleCacheHit(cacheable, cache, cacheKey, cachedValue.get());
            }
            return handleCacheMiss(joinPoint, cacheable, cache, cacheKey);

        } catch (RuntimeException e) {
            LOGGER.error("@Cacheable处理异常: cache={}, key={}, error={}", cacheName, cacheKey, e.getMessage());
            return joinPoint.proceed();
        }
    }

    // ==================== @CacheEvict 处理 ====================

    @Around("@annotation(cacheEvict)")
    public Object handleCacheEvict(ProceedingJoinPoint joinPoint, CacheEvict cacheEvict) throws Throwable {
        String cacheName = resolveCacheName(joinPoint, cacheEvict.value());

        LOGGER.debug("@CacheEvict处理开始: cache={}, allEntries={}", cacheName, cacheEvict.allEntries());

        try {
            Cache<K, V> cache = cacheManager.getCache(cacheName);

            // 方法执行前清除
            if (cacheEvict.beforeInvocation() && cache != null) {
                performEviction(joinPoint, cache, cacheEvict, null);
            }

            // 执行业务方法
            Object result = joinPoint.proceed();

            // 方法执行后清除
            if (!cacheEvict.beforeInvocation() && cache != null) {
                performEviction(joinPoint, cache, cacheEvict, result);
            }

            return result;

        } catch (RuntimeException e) {
            // 重新抛出运行时异常，在更高层进行日志记录
            throw new CacheException("@CacheEvict处理异常: cache=" + cacheName + ", error=" + e.getMessage(), e);
        } catch (Throwable t) {
            // 重新抛出检查异常，添加上下文信息
            throw new CacheException("@CacheEvict处理严重异常: cache=" + cacheName + ", error=" + t.getMessage(), t);
        }
    }

    // ==================== @CachePut 处理 ====================

    @AfterReturning(value = "@annotation(cachePut)", returning = "result")
    public void handleCachePut(JoinPoint joinPoint, CachePut cachePut, V result) {
        String cacheName = resolveCacheName(joinPoint, cachePut.value());
        K cacheKey = evaluateCacheKey(joinPoint, cachePut.key());

        LOGGER.debug("@CachePut处理: cache={}, key={}", cacheName, cacheKey);

        try {
            // 检查条件
            if (evaluateCondition(joinPoint, cachePut.condition(), result)) {
                LOGGER.debug("@CachePut条件不满足，跳过更新: {}", cachePut.condition());
                return;
            }

            if (result != null) {
                Cache<K, V> cache = getOrCreateCache(joinPoint, cacheName, cachePut);
                cache.put(cacheKey, result, cachePut.ttl());
                LOGGER.debug("@CachePut缓存更新完成: cache={}, key={}", cacheName, cacheKey);
            }

        } catch (RuntimeException e) {
            LOGGER.error("@CachePut处理异常: cache={}, key={}, error={}", cacheName, cacheKey, e.getMessage());
            // CachePut异常不影响方法返回
        }
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 解析缓存名称
     */
    private static String resolveCacheName(JoinPoint joinPoint, String annotationValue) {
        if (annotationValue != null && !annotationValue.isEmpty()) {
            return annotationValue;
        }
        // 使用类名.方法名作为默认缓存名
        String className = joinPoint.getTarget().getClass().getSimpleName();
        String methodName = joinPoint.getSignature().getName();
        return className + "." + methodName;
    }

    /**
     * 评估SpEL表达式
     */
    private Object evaluateSPELExpression(JoinPoint joinPoint, String expression) {
        return spelHelper.evaluate(expression, joinPoint, null);
    }

    /**
     * 类型安全的缓存键评估
     *
     * @param joinPoint  AOP连接点
     * @param expression SpEL表达式
     * @return 评估结果，如果类型不匹配返回null
     */
    private K evaluateCacheKey(JoinPoint joinPoint, String expression) {
        try {
            Object result = evaluateSPELExpression(joinPoint, expression);
            // 简单的类型检查，虽然不能完全避免ClassCastException，但提供了更好的错误信息
            return (K) result;
        } catch (ClassCastException e) {
            // 不记录日志，直接抛出包含详细信息的异常，避免日志重复
            throw new CacheException("缓存键类型不匹配: expression=" + expression + ", error=" + e.getMessage(), e);
        }
    }

    /**
     * 评估条件表达式
     */
    private boolean evaluateCondition(JoinPoint joinPoint, String condition, Object result) {
        if (!StringUtils.hasText(condition)) {
            return false;
        }

        try {
            Object value = spelHelper.evaluate(condition, joinPoint, result);
            return value instanceof Boolean boolValue && !boolValue;
        } catch (RuntimeException e) {
            LOGGER.warn("条件表达式评估失败: {}, 默认返回false, 错误: {}", condition, e.getMessage());
            return false;
        }
    }

    /**
     * 获取或创建缓存实例 (Cacheable)
     */
    private Cache<K, V> getOrCreateCache(JoinPoint joinPoint, String cacheName, Cacheable cacheable) {
        return getOrCreateCache(joinPoint, cacheName, cacheable.key());
    }

    /**
     * 获取或创建缓存实例 (CachePut)
     */
    private Cache<K, V> getOrCreateCache(JoinPoint joinPoint, String cacheName, CachePut cachePut) {
        return getOrCreateCache(joinPoint, cacheName, cachePut.key());
    }

    /**
     * 获取或创建缓存实例的通用实现
     */
    private Cache<K, V> getOrCreateCache(JoinPoint joinPoint, String cacheName, String keyExpression) {
        // 解析实际的键值类型
        Class<K> keyType = resolveKeyType(joinPoint, keyExpression);
        Class<V> valueType = resolveValueType(joinPoint);

        LOGGER.debug("创建缓存: cache={}, keyType={}, valueType={}",
                cacheName, keyType.getSimpleName(), valueType.getSimpleName());

        return cacheManager.getOrCreateCache(cacheName, keyType, valueType);
    }

    /**
     * 解析SpEL表达式的键类型
     */
    private Class<K> resolveKeyType(JoinPoint joinPoint, String keyExpression) {
        return (Class<K>) typeHelper.inferType(keyExpression, joinPoint);
    }

    /**
     * 解析缓存值的类型（方法返回值类型）
     */
    private Class<V> resolveValueType(JoinPoint joinPoint) {
        return (Class<V>) typeHelper.inferValueType(joinPoint);
    }

    /**
     * 执行清除操作
     */
    private void performEviction(JoinPoint joinPoint, Cache<K, V> cache,
                                 CacheEvict cacheEvict, Object result) {
        // 检查条件
        if (evaluateCondition(joinPoint, cacheEvict.condition(), result)) {
            LOGGER.debug("@CacheEvict条件不满足，跳过清除: {}", cacheEvict.condition());
            return;
        }

        if (cacheEvict.allEntries()) {
            // 清空所有缓存
            cache.clear();
            LOGGER.debug("@CacheEvict清空所有缓存: cache={}", cache.getName());
        } else {
            // 清除指定键
            K cacheKey = evaluateCacheKey(joinPoint, cacheEvict.key());
            cache.evict(cacheKey);
            LOGGER.debug("@CacheEvict清除指定键: cache={}, key={}", cache.getName(), cacheKey);
        }
    }

    /**
     * 调度刷新任务
     */
    private void scheduleRefresh(Cache<K, V> cache, K key, long intervalSeconds) {
        try {
            CacheRefresher<K, V> refresher = cacheManager.getOrCreateCacheRefresher(cache.getName());

            if (refresher != null) {
                refresher.addKey(key, intervalSeconds);
                LOGGER.info("缓存自动刷新已启用: cache={}, key={}, interval={}s", cache.getName(), key, intervalSeconds);
            } else {
                LOGGER.warn("无法创建或获取缓存刷新器: cache={}", cache.getName());
            }
        } catch (RuntimeException e) {
            LOGGER.error("启用缓存自动刷新失败: cache={}, key={}, error={}",
                    cache.getName(), key, e.getMessage(), e);
        }
    }

    // ==================== 函数式@Cacheable处理（示例） ====================

    /**
     * 函数式@Cacheable处理示例 - 展示如何使用函数组合简化逻辑
     * 注意：这是一个展示性的方法，实际使用时可以替换原有的handleCacheable方法
     */
    private Object handleCacheableFunctional(ProceedingJoinPoint joinPoint, Cacheable cacheable) {
        return FunctionalCacheOperations.cacheNameResolver(cacheable.value())
                .andThen(cacheName -> processFunctionalCacheable(joinPoint, cacheable, cacheName))
                .apply(joinPoint);
    }

    /**
     * 处理函数式缓存操作的核心逻辑
     */
    private Object processFunctionalCacheable(ProceedingJoinPoint joinPoint, Cacheable cacheable, String cacheName) {
        return functionalSpelEvaluator.evaluateExpression(cacheable.key())
                .apply(joinPoint)
                .map(key -> executeFunctionalCacheOperation(joinPoint, cacheable, cacheName, (K) key))
                .orElseGet(() -> executeFailsafeProceed(joinPoint));
    }

    /**
     * 执行函数式缓存操作
     */
    private Object executeFunctionalCacheOperation(ProceedingJoinPoint joinPoint, Cacheable cacheable,
                                                   String cacheName, K key) {
        Cache<K, V> cache = getOrCreateCache(joinPoint, cacheName, cacheable);

        // 使用支持刷新功能的完整版本
        return FunctionalCacheOperations.cacheableOperation(
                cache,
                key,
                jp -> !evaluateCondition(jp, cacheable.condition(), null),
                cacheable.enableRefresh(),
                cacheable.ttl(),
                cacheManager,
                cacheable.refreshInterval()
        ).apply(joinPoint);
    }

    /**
     * 安全执行业务方法的降级处理
     */
    private static Object executeFailsafeProceed(ProceedingJoinPoint joinPoint) {
        return FunctionalCacheOperations.safely(() -> {
            try {
                return joinPoint.proceed();
            } catch (Throwable e) {
                throw new CacheException("方法执行失败", e);
            }
        });
    }

    // ==================== @Cacheable 辅助方法 ====================

    /**
     * 记录缓存开始日志
     */
    private static <K> void logCacheableStart(String cacheName, K cacheKey) {
        if (LOGGER.isTraceEnabled()) {
            LOGGER.trace("@Cacheable处理开始: cache={}, key={}", cacheName, cacheKey);
        }
    }

    /**
     * 处理缓存命中情况
     */
    private V handleCacheHit(Cacheable cacheable, Cache<K, V> cache, K cacheKey, V cachedValue) {
        if (LOGGER.isTraceEnabled()) {
            LOGGER.trace("@Cacheable缓存命中: cache={}, key={}", cache.getName(), cacheKey);
        }

        if (cacheable.enableRefresh()) {
            LOGGER.info("缓存命中但需要启用自动刷新: cache={}, key={}, interval={}s",
                    cache.getName(), cacheKey, cacheable.refreshInterval());
            scheduleRefresh(cache, cacheKey, cacheable.refreshInterval());
        }

        return cachedValue;
    }

    /**
     * 处理缓存未命中情况
     */
    private Object handleCacheMiss(ProceedingJoinPoint joinPoint, Cacheable cacheable,
                                   Cache<K, V> cache, K cacheKey) throws Throwable {
        if (cacheable.asyncLoad()) {
            return loadAndCacheAsync(joinPoint, cache, cacheKey, cacheable);
        } else {
            return loadAndCacheSync(joinPoint, cache, cacheKey, cacheable);
        }
    }

    /**
     * 异步加载并缓存
     */
    private static <K, V> Object loadAndCacheAsync(ProceedingJoinPoint joinPoint, Cache<K, V> cache,
                                                   K cacheKey, Cacheable cacheable) {
        CompletableFuture.runAsync(() -> executeAsyncCacheLoad(joinPoint, cache, cacheKey, cacheable));
        return null;
    }

    /**
     * 执行异步缓存加载操作
     */
    private static <K, V> void executeAsyncCacheLoad(ProceedingJoinPoint joinPoint, Cache<K, V> cache,
                                                     K cacheKey, Cacheable cacheable) {
        try {
            V result = (V) joinPoint.proceed();
            if (result != null) {
                cache.put(cacheKey, result, cacheable.ttl());
            }
        } catch (Throwable e) {
            LOGGER.error("异步缓存加载失败: cache={}, key={}, error={}",
                    cache.getName(), cacheKey, e.getMessage());
        }
    }

    /**
     * 同步加载并缓存
     */
    private V loadAndCacheSync(ProceedingJoinPoint joinPoint, Cache<K, V> cache,
                               K cacheKey, Cacheable cacheable) throws Throwable {
        V result = (V) joinPoint.proceed();
        if (result != null) {
            cache.put(cacheKey, result, cacheable.ttl());
            enableRefreshIfNeeded(cacheable, cache, cacheKey);
        }
        return result;
    }

    /**
     * 启用刷新（如果需要）
     */
    private void enableRefreshIfNeeded(Cacheable cacheable, Cache<K, V> cache, K cacheKey) {
        if (cacheable.enableRefresh()) {
            LOGGER.info("准备启用自动刷新: cache={}, key={}, interval={}s",
                    cache.getName(), cacheKey, cacheable.refreshInterval());
            scheduleRefresh(cache, cacheKey, cacheable.refreshInterval());
        }
    }

}