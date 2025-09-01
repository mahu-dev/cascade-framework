package io.github.cascade.cache.simple;

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
public class CacheAspect {

    private static final Logger log = LoggerFactory.getLogger(CacheAspect.class);

    private final CacheManager cacheManager;
    private final SpelExpressionHelper spelHelper;
    private final TypeInferenceHelper typeHelper;

    public CacheAspect(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
        this.spelHelper = new SpelExpressionHelper(1000);
        this.typeHelper = new TypeInferenceHelper();
        log.info("缓存切面已初始化");
    }

    // ==================== @Cacheable 处理 ====================

    @Around("@annotation(cacheable)")
    public <K, V> Object handleCacheable(ProceedingJoinPoint joinPoint, Cacheable cacheable) throws Throwable {
        String cacheName = resolveCacheName(joinPoint, cacheable.value());
        K cacheKey = (K) evaluateSpelExpression(joinPoint, cacheable.key());

        logCacheableStart(cacheName, cacheKey);

        try {
            Cache<K, V> cache = getOrCreateCache(joinPoint, cacheName, cacheable);

            if (evaluateCondition(joinPoint, cacheable.condition(), null)) {
                log.debug("@Cacheable条件不满足，跳过缓存: {}", cacheable.condition());
                return joinPoint.proceed();
            }

            Optional<V> cachedValue = cache.get(cacheKey);
            if (cachedValue.isPresent()) {
                return handleCacheHit(cacheable, cache, cacheKey, cachedValue.get());
            }

            return handleCacheMiss(joinPoint, cacheable, cache, cacheKey);

        } catch (Exception e) {
            log.error("@Cacheable处理异常: cache={}, key={}, error={}", cacheName, cacheKey, e.getMessage());
            return joinPoint.proceed();
        }
    }

    // ==================== @CacheEvict 处理 ====================

    @Around("@annotation(cacheEvict)")
    public <K, V> Object handleCacheEvict(ProceedingJoinPoint joinPoint, CacheEvict cacheEvict) throws Throwable {
        String cacheName = resolveCacheName(joinPoint, cacheEvict.value());

        log.debug("@CacheEvict处理开始: cache={}, allEntries={}", cacheName, cacheEvict.allEntries());

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

        } catch (Exception e) {
            log.error("@CacheEvict处理异常: cache={}, error={}", cacheName, e.getMessage());
            throw e; // 清除异常需要传播，因为可能影响业务逻辑
        }
    }

    // ==================== @CachePut 处理 ====================

    @AfterReturning(value = "@annotation(cachePut)", returning = "result")
    public <K, V> void handleCachePut(JoinPoint joinPoint, CachePut cachePut, V result) {
        String cacheName = resolveCacheName(joinPoint, cachePut.value());
        K cacheKey = (K) evaluateSpelExpression(joinPoint, cachePut.key());

        log.debug("@CachePut处理: cache={}, key={}", cacheName, cacheKey);

        try {
            // 检查条件
            if (evaluateCondition(joinPoint, cachePut.condition(), result)) {
                log.debug("@CachePut条件不满足，跳过更新: {}", cachePut.condition());
                return;
            }

            if (result != null) {
                Cache<K, V> cache = getOrCreateCache(joinPoint, cacheName, cachePut);
                cache.put(cacheKey, result, cachePut.ttl());
                log.debug("@CachePut缓存更新完成: cache={}, key={}", cacheName, cacheKey);
            }

        } catch (Exception e) {
            log.error("@CachePut处理异常: cache={}, key={}, error={}", cacheName, cacheKey, e.getMessage());
            // CachePut异常不影响方法返回
        }
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 解析缓存名称
     */
    private String resolveCacheName(JoinPoint joinPoint, String annotationValue) {
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
    private Object evaluateSpelExpression(JoinPoint joinPoint, String expression) {
        return spelHelper.evaluate(expression, joinPoint, null);
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
        } catch (Exception e) {
            log.warn("条件表达式评估失败: {}, 默认返回false, 错误: {}", condition, e.getMessage());
            return false;
        }
    }

    /**
     * 获取或创建缓存实例
     */
    private <K, V> Cache<K, V> getOrCreateCache(JoinPoint joinPoint, String cacheName, Cacheable cacheable) {
        // 解析实际的键值类型
        Class<?> keyType = resolveKeyType(joinPoint, cacheable.key());
        Class<?> valueType = resolveValueType(joinPoint);

        log.debug("创建缓存: cache={}, keyType={}, valueType={}",
                cacheName, keyType.getSimpleName(), valueType.getSimpleName());

        return (Cache<K, V>) cacheManager.getOrCreateCache(cacheName, keyType, valueType);
    }

    /**
     * 获取或创建缓存实例
     */
    private <K, V> Cache<K, V> getOrCreateCache(JoinPoint joinPoint, String cacheName, CachePut cachePut) {
        // 解析实际的键值类型
        Class<?> keyType = resolveKeyType(joinPoint, cachePut.key());
        Class<?> valueType = resolveValueType(joinPoint);

        log.debug("创建缓存: cache={}, keyType={}, valueType={}",
                cacheName, keyType.getSimpleName(), valueType.getSimpleName());

        return (Cache<K, V>) cacheManager.getOrCreateCache(cacheName, keyType, valueType);
    }

    /**
     * 解析SpEL表达式的键类型
     */
    private Class<?> resolveKeyType(JoinPoint joinPoint, String keyExpression) {
        return typeHelper.inferType(keyExpression, joinPoint);
    }

    /**
     * 解析缓存值的类型（方法返回值类型）
     */
    private Class<?> resolveValueType(JoinPoint joinPoint) {
        return typeHelper.inferValueType(joinPoint);
    }

    /**
     * 执行清除操作
     */
    private <K, V> void performEviction(JoinPoint joinPoint, Cache<K, V> cache,
                                        CacheEvict cacheEvict, Object result) {
        // 检查条件
        if (evaluateCondition(joinPoint, cacheEvict.condition(), result)) {
            log.debug("@CacheEvict条件不满足，跳过清除: {}", cacheEvict.condition());
            return;
        }

        if (cacheEvict.allEntries()) {
            // 清空所有缓存
            cache.clear();
            log.debug("@CacheEvict清空所有缓存: cache={}", cache.getName());
        } else {
            // 清除指定键
            K cacheKey = (K) evaluateSpelExpression(joinPoint, cacheEvict.key());
            cache.evict(cacheKey);
            log.debug("@CacheEvict清除指定键: cache={}, key={}", cache.getName(), cacheKey);
        }
    }

    /**
     * 调度刷新任务
     */
    private <K, V> void scheduleRefresh(Cache<K, V> cache, K key, long intervalSeconds) {
        try {
            CacheRefresher<K, V> refresher = cacheManager.getOrCreateCacheRefresher(cache.getName());

            if (refresher != null) {
                refresher.addKey(key, intervalSeconds);
                log.info("缓存自动刷新已启用: cache={}, key={}, interval={}s", cache.getName(), key, intervalSeconds);
            } else {
                log.warn("无法创建或获取缓存刷新器: cache={}", cache.getName());
            }
        } catch (Exception e) {
            log.error("启用缓存自动刷新失败: cache={}, key={}, error={}",
                    cache.getName(), key, e.getMessage(), e);
        }
    }

    // ==================== @Cacheable 辅助方法 ====================

    /**
     * 记录缓存开始日志
     */
    private <K> void logCacheableStart(String cacheName, K cacheKey) {
        if (log.isTraceEnabled()) {
            log.trace("@Cacheable处理开始: cache={}, key={}", cacheName, cacheKey);
        }
    }

    /**
     * 处理缓存命中情况
     */
    private <K, V> V handleCacheHit(Cacheable cacheable, Cache<K, V> cache, K cacheKey, V cachedValue) {
        if (log.isTraceEnabled()) {
            log.trace("@Cacheable缓存命中: cache={}, key={}", cache.getName(), cacheKey);
        }

        if (cacheable.enableRefresh()) {
            log.info("缓存命中但需要启用自动刷新: cache={}, key={}, interval={}s",
                    cache.getName(), cacheKey, cacheable.refreshInterval());
            scheduleRefresh(cache, cacheKey, cacheable.refreshInterval());
        }

        return cachedValue;
    }

    /**
     * 处理缓存未命中情况
     */
    private <K, V> Object handleCacheMiss(ProceedingJoinPoint joinPoint, Cacheable cacheable,
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
    private <K, V> Object loadAndCacheAsync(ProceedingJoinPoint joinPoint, Cache<K, V> cache,
                                            K cacheKey, Cacheable cacheable) {
        CompletableFuture.runAsync(() -> {
            try {
                V result = (V) joinPoint.proceed();
                if (result != null) {
                    cache.put(cacheKey, result, cacheable.ttl());
                }
            } catch (Throwable e) {
                log.error("异步缓存加载失败: cache={}, key={}, error={}",
                        cache.getName(), cacheKey, e.getMessage());
            }
        });
        return null;
    }

    /**
     * 同步加载并缓存
     */
    private <K, V> V loadAndCacheSync(ProceedingJoinPoint joinPoint, Cache<K, V> cache,
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
    private <K, V> void enableRefreshIfNeeded(Cacheable cacheable, Cache<K, V> cache, K cacheKey) {
        if (cacheable.enableRefresh()) {
            log.info("准备启用自动刷新: cache={}, key={}, interval={}s",
                    cache.getName(), cacheKey, cacheable.refreshInterval());
            scheduleRefresh(cache, cacheKey, cacheable.refreshInterval());
        }
    }

}