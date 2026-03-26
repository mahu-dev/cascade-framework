package io.github.cascade.cache.v2.facade;

import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.cascade.cache.configuration.CascadeCacheProperties;
import io.github.cascade.cache.v2.api.Cache;
import io.github.cascade.cache.v2.api.CacheManager;
import io.github.cascade.cache.v2.api.annotations.CacheEvict;
import io.github.cascade.cache.v2.api.annotations.CachePut;
import io.github.cascade.cache.v2.api.annotations.Cacheable;
import io.github.cascade.cache.v2.api.annotations.CascadeCached;
import io.github.cascade.cache.v2.policy.SyncMode;
import io.github.cascade.cache.v2.support.DefaultCacheKeyGenerator;
import io.github.cascade.cache.v2.support.DurationParser;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.util.StringUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * V2 统一缓存切面。
 * <p>
 * 目标：
 * 1. 注解式与编程式共享同一缓存引擎。
 * 2. 默认 cacheName/key 行为与注释一致。
 * 3. 修复 @CacheEvict 条件和 beforeInvocation 语义。
 */
@Aspect
@Order(1)
public class CacheAspect {

    private static final Logger LOGGER = LoggerFactory.getLogger(CacheAspect.class);
    private static final int SNAPSHOT_MAX_SIZE = 50_000;
    private static final long SNAPSHOT_EXPIRE_AFTER_ACCESS_MINUTES = 30L;

    private final CacheManager cacheManager;
    private final CascadeCacheProperties defaultConfig;
    private final CacheExpressionEvaluator expressionEvaluator;

    /**
     * (cacheName,key) -> invocation snapshot
     * 用于注解路径自动刷新时回放方法调用。
     * <p>
     * 内存防护：
     * 1) 最大容量限制
     * 2) 访问后过期淘汰
     */
    private final com.github.benmanes.caffeine.cache.Cache<SnapshotKey, InvocationSnapshot> invocationSnapshots =
            Caffeine.newBuilder()
                    .maximumSize(SNAPSHOT_MAX_SIZE)
                    .expireAfterAccess(SNAPSHOT_EXPIRE_AFTER_ACCESS_MINUTES, TimeUnit.MINUTES)
                    .build();
    private final ThreadLocal<Boolean> internalInvocation = ThreadLocal.withInitial(() -> false);

    public CacheAspect(CacheManager cacheManager, CascadeCacheProperties defaultConfig) {
        this.cacheManager = cacheManager;
        this.defaultConfig = defaultConfig != null ? defaultConfig : CascadeCacheProperties.defaults();
        this.expressionEvaluator = new CacheExpressionEvaluator();
    }

    @Around("@annotation(cacheable)")
    public Object handleCacheable(ProceedingJoinPoint joinPoint, Cacheable cacheable) throws Throwable {
        if (Boolean.TRUE.equals(internalInvocation.get())) {
            return joinPoint.proceed();
        }

        String cacheName = resolveCacheName(cacheable.value(), joinPoint);
        Object cacheKey = evaluateCacheKey(joinPoint, cacheable.key());

        if (!evaluateCondition(joinPoint, cacheable.condition(), null)) {
            return joinPoint.proceed();
        }

        registerSnapshot(cacheName, cacheKey, joinPoint);

        Cache<Object, Object> cache = getOrCreateCache(joinPoint, cacheName, cacheable, cacheKey);
        if (cache == null) {
            return joinPoint.proceed();
        }

        try {
            Optional<Object> cached = readFromCache(cache, cacheKey, StringUtils.hasText(cacheable.unless()));
            if (cached.isPresent()) {
                return cached.get();
            }
        } catch (Exception e) {
            LOGGER.warn("@Cacheable读取缓存失败，降级执行方法: cache={}, key={}, error={}",
                    cacheName, cacheKey, e.getMessage());
        }

        Object result = joinPoint.proceed();
        if (result != null && !evaluateUnless(joinPoint, cacheable.unless(), result)) {
            safePut(cache, cacheName, cacheKey, result, cacheable.ttl());
        }
        return result;
    }

    @Around("@annotation(cascadeCached)")
    public Object handleCascadeCached(ProceedingJoinPoint joinPoint, CascadeCached cascadeCached) throws Throwable {
        if (Boolean.TRUE.equals(internalInvocation.get())) {
            return joinPoint.proceed();
        }

        String cacheName = resolveCacheName(cascadeCached.name(), joinPoint);
        Object cacheKey = evaluateCacheKey(joinPoint, cascadeCached.key());

        if (!evaluateCondition(joinPoint, cascadeCached.condition(), null)) {
            return joinPoint.proceed();
        }

        registerSnapshot(cacheName, cacheKey, joinPoint);

        Cache<Object, Object> cache = getOrCreateCache(joinPoint, cacheName, cascadeCached, cacheKey);
        if (cache == null) {
            return joinPoint.proceed();
        }

        try {
            Optional<Object> cached = readFromCache(cache, cacheKey, StringUtils.hasText(cascadeCached.unless()));
            if (cached.isPresent()) {
                return cached.get();
            }
        } catch (Exception e) {
            LOGGER.warn("@CascadeCached读取缓存失败，降级执行方法: cache={}, key={}, error={}",
                    cacheName, cacheKey, e.getMessage());
        }

        Object result = joinPoint.proceed();
        if (result != null && !evaluateUnless(joinPoint, cascadeCached.unless(), result)) {
            safePut(cache, cacheName, cacheKey, result, cascadeCached.ttlSeconds());
        }
        return result;
    }

    @Around("@annotation(cachePut)")
    public Object handleCachePut(ProceedingJoinPoint joinPoint, CachePut cachePut) throws Throwable {
        String cacheName = resolveCacheName(cachePut.value(), joinPoint);
        Object cacheKey = evaluateCacheKey(joinPoint, cachePut.key());
        registerSnapshot(cacheName, cacheKey, joinPoint);

        Object result = joinPoint.proceed();
        if (!evaluateCondition(joinPoint, cachePut.condition(), result)) {
            return result;
        }

        Cache<Object, Object> cache = getOrCreateCache(joinPoint, cacheName, cachePut, cacheKey);
        if (cache != null && result != null) {
            safePut(cache, cacheName, cacheKey, result, cachePut.ttl());
        }
        return result;
    }

    @Around("@annotation(cacheEvict)")
    public Object handleCacheEvict(ProceedingJoinPoint joinPoint, CacheEvict cacheEvict) throws Throwable {
        String cacheName = resolveCacheName(cacheEvict.value(), joinPoint);
        Object cacheKey = evaluateCacheKey(joinPoint, cacheEvict.key());

        if (cacheEvict.beforeInvocation()) {
            if (evaluateCondition(joinPoint, cacheEvict.condition(), null)) {
                safeEvict(cacheName, cacheKey, cacheEvict.allEntries());
            }
            return joinPoint.proceed();
        }

        Object result = joinPoint.proceed();
        if (evaluateCondition(joinPoint, cacheEvict.condition(), result)) {
            safeEvict(cacheName, cacheKey, cacheEvict.allEntries());
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Cache<Object, Object> getOrCreateCache(JoinPoint joinPoint,
                                                   String cacheName,
                                                   Object annotation,
                                                   Object cacheKey) {
        Class<Object> keyType = (Class<Object>) (cacheKey != null ? cacheKey.getClass() : Object.class);
        Class<Object> valueType = (Class<Object>) inferValueType(joinPoint);

        try {
            if (annotation instanceof Cacheable cacheable) {
                CascadeCacheProperties config = buildConfig(cacheable);
                Function<Object, Object> loader = key -> invokeSnapshot(cacheName, key, cacheable.unless());
                return getOrCreateCacheWithLoaderFallback(cacheName, keyType, valueType, config, loader);
            }
            if (annotation instanceof CachePut cachePut) {
                CascadeCacheProperties config = buildConfig(cachePut);
                Function<Object, Object> loader = key -> invokeSnapshot(cacheName, key, "");
                return getOrCreateCacheWithLoaderFallback(cacheName, keyType, valueType, config, loader);
            }
            if (annotation instanceof CascadeCached cascadeCached) {
                CascadeCacheProperties config = buildConfig(cascadeCached);
                Function<Object, Object> loader = key -> invokeSnapshot(cacheName, key, cascadeCached.unless());
                return getOrCreateCacheWithLoaderFallback(cacheName, keyType, valueType, config, loader);
            }
            if (annotation instanceof CacheEvict) {
                return (Cache<Object, Object>) cacheManager.getCache(cacheName);
            }
        } catch (Exception e) {
            LOGGER.warn("获取缓存失败: cache={}, error={}", cacheName, e.getMessage());
        }

        return null;
    }

    /**
     * 注解路径加载器策略：
     * 1) 先创建/获取无显式loader缓存（触发注册/自动发现loader解析）
     * 2) 再以snapshot loader调用一次，用于仅在loader缺失时回填兜底
     */
    @SuppressWarnings("unchecked")
    private Cache<Object, Object> getOrCreateCacheWithLoaderFallback(String cacheName,
                                                                     Class<Object> keyType,
                                                                     Class<Object> valueType,
                                                                     CascadeCacheProperties config,
                                                                     Function<Object, Object> snapshotLoader) {
        Cache<Object, Object> cache = cacheManager.getOrCreateCache(
                cacheName, keyType, valueType, config
        );
        if (cache == null) {
            return null;
        }
        return cacheManager.getOrCreateCache(
                cacheName, keyType, valueType, config, snapshotLoader
        );
    }

    private static void safePut(Cache<Object, Object> cache,
                                String cacheName,
                                Object key,
                                Object value,
                                long ttlSeconds) {
        try {
            if (ttlSeconds > 0) {
                cache.put(key, value, ttlSeconds);
            } else {
                cache.put(key, value);
            }
        } catch (Exception e) {
            LOGGER.warn("写缓存失败: cache={}, key={}, error={}", cacheName, key, e.getMessage());
        }
    }

    private void safeEvict(String cacheName, Object cacheKey, boolean allEntries) {
        boolean shouldClearSnapshots = allEntries;
        boolean shouldEvictSnapshotKey = !allEntries && cacheKey != null;
        try {
            Cache<Object, Object> cache = cacheManager.getCache(cacheName);
            if (cache != null) {
                if (allEntries) {
                    cache.clear();
                } else if (cacheKey != null) {
                    cache.evict(cacheKey);
                }
            }
        } catch (Exception e) {
            LOGGER.warn("清理缓存失败: cache={}, key={}, error={}", cacheName, cacheKey, e.getMessage());
        } finally {
            if (shouldClearSnapshots) {
                clearSnapshots(cacheName);
            } else if (shouldEvictSnapshotKey) {
                removeSnapshot(cacheName, cacheKey);
            }
        }
    }

    private static String resolveCacheName(String configured, JoinPoint joinPoint) {
        if (StringUtils.hasText(configured)) {
            return configured;
        }
        String className = joinPoint.getTarget().getClass().getSimpleName();
        String methodName = joinPoint.getSignature().getName();
        return className + "." + methodName;
    }

    private Object evaluateCacheKey(JoinPoint joinPoint, String keyExpression) {
        if (!StringUtils.hasText(keyExpression)) {
            return DefaultCacheKeyGenerator.generate(joinPoint);
        }
        Object evaluated = expressionEvaluator.evaluate(keyExpression, joinPoint, null);
        return evaluated != null ? evaluated : DefaultCacheKeyGenerator.generate(joinPoint);
    }

    private boolean evaluateCondition(JoinPoint joinPoint, String condition, Object result) {
        if (!StringUtils.hasText(condition)) {
            return true;
        }
        try {
            return expressionEvaluator.evaluateBoolean(condition, joinPoint, result, true);
        } catch (Exception e) {
            LOGGER.warn("条件表达式求值失败，按true处理: condition={}, error={}", condition, e.getMessage());
            return true;
        }
    }

    private boolean evaluateUnless(JoinPoint joinPoint, String unless, Object result) {
        if (!StringUtils.hasText(unless)) {
            return false;
        }
        try {
            return expressionEvaluator.evaluateBoolean(unless, joinPoint, result, false);
        } catch (Exception e) {
            LOGGER.warn("unless表达式求值失败，按false处理: unless={}, error={}", unless, e.getMessage());
            return false;
        }
    }

    private boolean evaluateUnless(InvocationSnapshot snapshot, String unless, Object result) {
        if (!StringUtils.hasText(unless)) {
            return false;
        }
        try {
            return expressionEvaluator.evaluateBoolean(
                    unless,
                    snapshot.method,
                    snapshot.target,
                    snapshot.args,
                    result,
                    false
            );
        } catch (Exception e) {
            LOGGER.warn("unless表达式求值失败，按false处理: unless={}, error={}", unless, e.getMessage());
            return false;
        }
    }

    private Class<?> inferValueType(JoinPoint joinPoint) {
        if (joinPoint.getSignature() instanceof MethodSignature methodSignature) {
            return methodSignature.getMethod().getReturnType();
        }
        return Object.class;
    }

    private CascadeCacheProperties buildConfig(Cacheable annotation) {
        CascadeCacheProperties config = copyDefaultConfig();
        config.getL1().setEnabled(annotation.enableL1());
        config.getL2().setEnabled(annotation.enableL2());
        config.getSync().setEnabled(annotation.enableSync());
        config.getSync().setMode(annotation.enableSync() ? annotation.syncMode() : SyncMode.NONE);
        config.getSync().setUpdateEnabled(annotation.enableSync() && annotation.syncMode() == SyncMode.UPDATE);
        config.getRefresh().setEnabled(annotation.enableRefresh() || annotation.autoRefresh());
        config.getRefresh().setDefaultRefreshIntervalSeconds(annotation.refreshInterval());
        if (annotation.ttl() > 0) {
            config.getL2().setDefaultTtlSeconds(annotation.ttl());
        }
        return config;
    }

    private CascadeCacheProperties buildConfig(CachePut annotation) {
        CascadeCacheProperties config = copyDefaultConfig();
        config.getL1().setEnabled(annotation.enableL1());
        config.getL2().setEnabled(annotation.enableL2());
        config.getSync().setEnabled(annotation.sync());
        config.getSync().setMode(annotation.sync() ? annotation.syncMode() : SyncMode.NONE);
        config.getSync().setUpdateEnabled(annotation.sync() && annotation.syncMode() == SyncMode.UPDATE);
        config.getRefresh().setEnabled(annotation.autoRefresh());
        config.getRefresh().setDefaultRefreshIntervalSeconds(annotation.refreshInterval());
        if (annotation.ttl() > 0) {
            config.getL2().setDefaultTtlSeconds(annotation.ttl());
        }
        return config;
    }

    private CascadeCacheProperties buildConfig(CascadeCached annotation) {
        CascadeCacheProperties config = copyDefaultConfig();
        config.getL1().setEnabled(annotation.enableL1());
        config.getL2().setEnabled(annotation.enableL2());
        config.getSync().setEnabled(annotation.syncMode() != SyncMode.NONE);
        config.getSync().setMode(annotation.syncMode());
        config.getSync().setUpdateEnabled(annotation.syncMode() == SyncMode.UPDATE);
        config.getRefresh().setEnabled(annotation.autoRefresh());
        long softTtl = DurationParser.parseToSeconds(annotation.softTtl(), annotation.softTtlSeconds());
        if (softTtl > 0) {
            config.getRefresh().setDefaultRefreshIntervalSeconds(softTtl);
        }
        long ttl = DurationParser.parseToSeconds(annotation.ttl(), annotation.ttlSeconds());
        if (ttl > 0) {
            config.getL2().setDefaultTtlSeconds(ttl);
        }
        return config;
    }

    private CascadeCacheProperties copyDefaultConfig() {
        CascadeCacheProperties target = CascadeCacheProperties.defaults();
        CascadeCacheProperties source = this.defaultConfig;

        target.setEnabled(source.isEnabled());
        target.setDefaultCacheName(source.getDefaultCacheName());

        target.getL1().setEnabled(source.getL1().isEnabled());
        target.getL1().setMaximumSize(source.getL1().getMaximumSize());
        target.getL1().setExpireAfterWriteSeconds(source.getL1().getExpireAfterWriteSeconds());
        target.getL1().setExpireAfterAccessSeconds(source.getL1().getExpireAfterAccessSeconds());
        target.getL1().setRecordStats(source.getL1().isRecordStats());
        target.getL1().setInitialCapacity(source.getL1().getInitialCapacity());
        target.getL1().setConcurrencyLevel(source.getL1().getConcurrencyLevel());

        target.getL2().setEnabled(source.getL2().isEnabled());
        target.getL2().setKeyPrefix(source.getL2().getKeyPrefix());
        target.getL2().setDefaultTtlSeconds(source.getL2().getDefaultTtlSeconds());
        target.getL2().setEnableBatch(source.getL2().isEnableBatch());
        target.getL2().setBatchSize(source.getL2().getBatchSize());
        target.getL2().setSerializer(source.getL2().getSerializer());
        target.getL2().setTimeoutSeconds(source.getL2().getTimeoutSeconds());

        target.getSync().setEnabled(source.getSync().isEnabled());
        target.getSync().setMode(source.getSync().getMode());
        target.getSync().setType(source.getSync().getType());
        target.getSync().setTopicPrefix(source.getSync().getTopicPrefix());
        target.getSync().setAsyncPublish(source.getSync().isAsyncPublish());
        target.getSync().setTimeoutMs(source.getSync().getTimeoutMs());
        target.getSync().setBatchSize(source.getSync().getBatchSize());
        target.getSync().setPublishThreadPoolSize(source.getSync().getPublishThreadPoolSize());
        target.getSync().setPublishQueueCapacity(source.getSync().getPublishQueueCapacity());
        target.getSync().setPublishMaxRetries(source.getSync().getPublishMaxRetries());
        target.getSync().setPublishRetryBackoffMs(source.getSync().getPublishRetryBackoffMs());
        target.getSync().setUpdateEnabled(source.getSync().isUpdateEnabled());
        target.getSync().setUpdateMaxPayloadBytes(source.getSync().getUpdateMaxPayloadBytes());

        target.getRefresh().setEnabled(source.getRefresh().isEnabled());
        target.getRefresh().setDefaultRefreshIntervalSeconds(source.getRefresh().getDefaultRefreshIntervalSeconds());
        target.getRefresh().setMinRefreshIntervalSeconds(source.getRefresh().getMinRefreshIntervalSeconds());
        target.getRefresh().setMaxRefreshIntervalSeconds(source.getRefresh().getMaxRefreshIntervalSeconds());
        target.getRefresh().setDistributedRefresh(source.getRefresh().isDistributedRefresh());
        target.getRefresh().setThreadPoolSize(source.getRefresh().getThreadPoolSize());
        target.getRefresh().setQueueCapacity(source.getRefresh().getQueueCapacity());
        target.getRefresh().setAllowConcurrentRefresh(source.getRefresh().isAllowConcurrentRefresh());
        target.getRefresh().setRefreshTimeoutSeconds(source.getRefresh().getRefreshTimeoutSeconds());
        target.getRefresh().setMaxRetries(source.getRefresh().getMaxRetries());
        target.getRefresh().setRetryIntervalSeconds(source.getRefresh().getRetryIntervalSeconds());
        target.getRefresh().setStartOnInit(source.getRefresh().isStartOnInit());
        target.getRefresh().setShutdownTimeoutSeconds(source.getRefresh().getShutdownTimeoutSeconds());

        target.getLoader().setAutoDiscover(source.getLoader().isAutoDiscover());
        target.getLoader().setEnableStats(source.getLoader().isEnableStats());
        target.getLoader().setTimeoutSeconds(source.getLoader().getTimeoutSeconds());

        target.getProtection().setSingleFlightEnabled(source.getProtection().isSingleFlightEnabled());
        target.getProtection().setDistributedLockEnabled(source.getProtection().isDistributedLockEnabled());
        target.getProtection().setDistributedLockWaitMs(source.getProtection().getDistributedLockWaitMs());
        target.getProtection().setDistributedLockLeaseMs(source.getProtection().getDistributedLockLeaseMs());
        target.getProtection().setLockFailureStrategy(source.getProtection().getLockFailureStrategy());
        target.getProtection().setHotKeyAccessThreshold(source.getProtection().getHotKeyAccessThreshold());
        target.getProtection().setMaxTrackedKeys(source.getProtection().getMaxTrackedKeys());

        return target;
    }

    /**
     * 注册方法调用快照，用于缓存自动刷新时回放调用。
     * <p>
     * 将方法调用上下文（目标对象、方法、参数）保存到内存缓存中，
     * 当缓存触发自动刷新时，可以通过快照重新执行原始方法来获取最新数据。
     *
     * @param cacheName 缓存名称
     * @param cacheKey  缓存键
     * @param joinPoint 连接点，包含方法调用信息
     */
    private void registerSnapshot(String cacheName, Object cacheKey, JoinPoint joinPoint) {
        if (cacheKey == null) {
            return;
        }
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        if (!method.canAccess(joinPoint.getTarget())) {
            method.setAccessible(true);
        }
        InvocationSnapshot snapshot = new InvocationSnapshot(joinPoint.getTarget(), method, joinPoint.getArgs());
        invocationSnapshots.put(new SnapshotKey(cacheName, cacheKey), snapshot);
    }

    private static Optional<Object> readFromCache(Cache<Object, Object> cache, Object cacheKey, boolean skipLoadOnMiss) {
        if (!skipLoadOnMiss) {
            return cache.get(cacheKey);
        }
        if (!cache.containsKey(cacheKey)) {
            return Optional.empty();
        }
        return cache.get(cacheKey);
    }

    private Object invokeSnapshot(String cacheName, Object cacheKey, String unlessExpression) {
        InvocationSnapshot snapshot = invocationSnapshots.getIfPresent(new SnapshotKey(cacheName, cacheKey));
        if (snapshot == null) {
            return null;
        }
        try {
            internalInvocation.set(true);
            Object result = snapshot.method.invoke(snapshot.target, snapshot.args);
            if (result != null && evaluateUnless(snapshot, unlessExpression, result)) {
                return null;
            }
            return result;
        } catch (InvocationTargetException e) {
            Throwable cause = e.getTargetException();
            throw new RuntimeException(cause != null ? cause : e);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            internalInvocation.set(false);
        }
    }

    private void removeSnapshot(String cacheName, Object cacheKey) {
        if (cacheName == null || cacheKey == null) {
            return;
        }
        invocationSnapshots.invalidate(new SnapshotKey(cacheName, cacheKey));
    }

    private void clearSnapshots(String cacheName) {
        if (!StringUtils.hasText(cacheName)) {
            return;
        }
        invocationSnapshots.asMap().keySet().removeIf(key -> key.cacheName.equals(cacheName));
    }

    /**
     * 快照键，用于唯一标识一个缓存快照。
     * <p>
     * 由缓存名称和缓存键组成，作为存储方法调用快照的索引。
     */
    private record SnapshotKey(String cacheName, Object cacheKey) {
        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof SnapshotKey that)) {
                return false;
            }
            return Objects.equals(cacheName, that.cacheName)
                    && Objects.equals(cacheKey, that.cacheKey);
        }

    }

    /**
     * 方法调用快照，保存方法执行的完整上下文。
     * <p>
     * 记录目标对象、方法和参数，用于在缓存刷新时回放原始方法调用。
     */
    private record InvocationSnapshot(Object target, Method method, Object[] args) {
    }
}
