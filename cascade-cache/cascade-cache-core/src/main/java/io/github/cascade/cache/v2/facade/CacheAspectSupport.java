package io.github.cascade.cache.v2.facade;

import io.github.cascade.cache.configuration.CascadeCacheProperties;
import io.github.cascade.cache.v2.api.Cache;
import io.github.cascade.cache.v2.api.CacheManager;
import io.github.cascade.cache.v2.api.annotations.CachePut;
import io.github.cascade.cache.v2.api.annotations.Cacheable;
import io.github.cascade.cache.v2.common.exception.CacheConfigurationException;
import io.github.cascade.cache.v2.engine.EngineBackedCache;
import io.github.cascade.cache.v2.loader.LoaderPriority;
import io.github.cascade.cache.v2.policy.SyncMode;
import io.github.cascade.cache.v2.support.DefaultCacheKeyGenerator;
import io.github.cascade.cache.v2.support.DurationParser;
import io.github.cascade.cache.v2.support.TypeUtils;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.lang.reflect.Array;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 注解缓存切面的共享支持逻辑。
 * <p>
 * 负责：
 * 1. key / condition / unless 求值；
 * 2. 注解到配置对象的翻译；
 * 3. 缓存获取、回放 loader、写入与驱逐；
 * 4. 与快照服务协作完成自动刷新回放。
 */
public class CacheAspectSupport {

    private static final Logger LOGGER = LoggerFactory.getLogger(CacheAspectSupport.class);

    private final CacheManager cacheManager;
    private final CascadeCacheProperties defaultConfig;
    private final CacheExpressionEvaluator expressionEvaluator;
    private final CacheInvocationSnapshotSupport snapshotSupport;
    private final Set<String> refreshAliasMismatchWarnings = ConcurrentHashMap.newKeySet();

    public CacheAspectSupport(CacheManager cacheManager,
                              CascadeCacheProperties defaultConfig,
                              CacheInvocationSnapshotSupport snapshotSupport) {
        this.cacheManager = cacheManager;
        this.defaultConfig = defaultConfig != null ? defaultConfig : CascadeCacheProperties.defaults();
        this.snapshotSupport = snapshotSupport;
        this.expressionEvaluator = new CacheExpressionEvaluator();
    }

    public String resolveCacheName(String configured, JoinPoint joinPoint) {
        if (StringUtils.hasText(configured)) {
            return configured;
        }
        String className = joinPoint.getTarget().getClass().getSimpleName();
        String methodName = joinPoint.getSignature().getName();
        return className + "." + methodName;
    }

    public String resolveCacheName(String primaryConfigured, String secondaryConfigured, JoinPoint joinPoint) {
        if (StringUtils.hasText(primaryConfigured)) {
            return primaryConfigured;
        }
        return resolveCacheName(secondaryConfigured, joinPoint);
    }

    /**
     * 评估并生成缓存键。
     * <p>
     * 优先使用用户指定的 SpEL 表达式求值；若表达式为空，
     * 则回退到默认键生成器（基于方法签名和参数生成）。
     * 显式配置了 key 表达式但求值为 null 时，抛出配置异常并 fail-fast。
     *
     * @param joinPoint     切点连接点，包含方法信息
     * @param keyExpression SpEL 表达式，可为空
     * @return 缓存键对象
     */
    public Object evaluateCacheKey(JoinPoint joinPoint, String keyExpression) {
        LOGGER.debug("Evaluating cache key: {}", keyExpression);
        if (!StringUtils.hasText(keyExpression)) {
            return DefaultCacheKeyGenerator.generate(joinPoint);
        }
        Object evaluated = expressionEvaluator.evaluate(keyExpression, joinPoint, null);
        LOGGER.debug("Cache key evaluated to: {}", evaluated);
        if (evaluated == null) {
            String method = joinPoint != null && joinPoint.getSignature() != null
                    ? joinPoint.getSignature().toShortString()
                    : "unknown";
            throw new CacheConfigurationException(
                    "key",
                    keyExpression,
                    "缓存key表达式求值结果为null，请检查SpEL表达式: method=" + method,
                    null
            );
        }
        return evaluated;
    }

    public String evaluateStringCacheKey(JoinPoint joinPoint, String keyExpression) {
        Object cacheKey = evaluateCacheKey(joinPoint, keyExpression);
        if (cacheKey instanceof String key) {
            return key;
        }
        String method = joinPoint != null && joinPoint.getSignature() != null
                ? joinPoint.getSignature().toShortString()
                : "unknown";
        String actualType = cacheKey == null ? "null" : cacheKey.getClass().getName();
        throw new CacheConfigurationException(
                "key",
                keyExpression,
                "缓存key表达式结果必须是String: actualType=" + actualType + ", method=" + method,
                null
        );
    }

    public boolean evaluateCondition(JoinPoint joinPoint, String condition, Object result) {
        if (!StringUtils.hasText(condition)) {
            return true;
        }
        try {
            return expressionEvaluator.evaluateBoolean(condition, joinPoint, result, true);
        } catch (CacheConfigurationException e) {
            throw e;
        } catch (Exception e) {
            LOGGER.warn("条件表达式求值失败，按true处理: condition={}, error={}", condition, e.getMessage());
            return true;
        }
    }

    public boolean shouldCacheResult(JoinPoint joinPoint, String unless, Object result) {
        if (!StringUtils.hasText(unless)) {
            return true;
        }
        try {
            return !expressionEvaluator.evaluateBoolean(unless, joinPoint, result, false);
        } catch (CacheConfigurationException e) {
            throw e;
        } catch (Exception e) {
            LOGGER.warn("unless表达式求值失败，按false处理: unless={}, error={}", unless, e.getMessage());
            return true;
        }
    }

    public boolean isPrimitiveValueReturnType(JoinPoint joinPoint) {
        if (joinPoint.getSignature() instanceof MethodSignature methodSignature) {
            return methodSignature.getMethod().getReturnType().isPrimitive();
        }
        return false;
    }

    public boolean isInternalInvocation() {
        return snapshotSupport.isInternalInvocation();
    }

    public List<String> evaluateEvictKeys(JoinPoint joinPoint, String keyExpression, boolean allEntries) {
        if (allEntries) {
            return List.of();
        }
        Object cacheKey = evaluateCacheKey(joinPoint, keyExpression);
        return resolveEvictKeys(cacheKey);
    }

    public void registerSnapshot(String cacheName, Object cacheKey, JoinPoint joinPoint) {
        snapshotSupport.register(cacheName, cacheKey, joinPoint);
    }

    public long snapshotCount() {
        return snapshotSupport.snapshotCount();
    }

    public long resolveCacheableHardTtl(Cacheable annotation) {
        long fallbackSeconds = annotation.ttlSeconds() > 0 ? annotation.ttlSeconds() : annotation.ttl();
        return DurationParser.parseToSeconds(annotation.ttlString(), fallbackSeconds);
    }

    /**
     * 从缓存读取数据，支持跳过加载器的模式。
     * <p>
     * 当 skipLoadOnMiss 为 false 时，直接调用 Cache.get()，缓存未命中会触发加载器。
     * 当 skipLoadOnMiss 为 true 时，调用 Cache.getIfPresent()，保证不触发加载器。
     * <p>
     * 此设计用于某些需要检查缓存但不希望触发数据加载的场景（如条件判断）。
     *
     * @param cache        缓存实例
     * @param cacheKey     缓存键
     * @param skipLoadOnMiss 是否在缓存未命中时跳过加载器
     * @return 缓存值的 Optional 包装，不存在时返回 Optional.empty()
     */
    public Optional<Object> readFromCache(Cache<Object, Object> cache, Object cacheKey, boolean skipLoadOnMiss) {
        if (!skipLoadOnMiss) {
            return cache.get(cacheKey);
        }
        return cache.getIfPresent(cacheKey);
    }

    @SuppressWarnings("unchecked")
    public Cache<Object, Object> getOrCreateCacheForCacheable(JoinPoint joinPoint,
                                                              String cacheName,
                                                              Cacheable cacheable,
                                                              Object cacheKey) {
        Class<Object> keyType = castStringClass();
        Class<Object> valueType = (Class<Object>) inferValueType(joinPoint);
        CascadeCacheProperties config = buildConfig(cacheable, joinPoint);
        Function<Object, Object> loader = key -> invokeSnapshot(cacheName, key, cacheable.unless());
        return getOrCreateCacheWithLoaderFallback(cacheName, keyType, valueType, config, loader);
    }

    @SuppressWarnings("unchecked")
    public Cache<Object, Object> getOrCreateCacheForCachePut(JoinPoint joinPoint,
                                                             String cacheName,
                                                             CachePut cachePut,
                                                             Object cacheKey) {
        Class<Object> keyType = castStringClass();
        Class<Object> valueType = (Class<Object>) inferValueType(joinPoint);
        CascadeCacheProperties config = buildConfig(cachePut);
        try {
            return (Cache<Object, Object>) cacheManager.getOrCreateCache(cacheName, keyType, valueType, config);
        } catch (CacheConfigurationException e) {
            throw e;
        } catch (Exception e) {
            LOGGER.warn("获取缓存失败: cache={}, error={}", cacheName, e.getMessage());
            return null;
        }
    }

    public void putQuietly(Cache<Object, Object> cache,
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

    @SuppressWarnings("unchecked")
    public void evictQuietly(String cacheName, List<String> keysToEvict, boolean allEntries, boolean syncEnabled) {
        Object logKey;
        if (allEntries) {
            logKey = "*";
        } else if (keysToEvict.size() == 1) {
            logKey = keysToEvict.get(0);
        } else {
            logKey = keysToEvict;
        }
        LOGGER.debug("执行缓存清理: cache={}, allEntries={}, keys={}, sync={}",
                cacheName, allEntries, logKey, syncEnabled);

        try {
            Cache<Object, Object> cache = cacheManager.getCache(cacheName);
            if (cache != null) {
                if (!syncEnabled && cache instanceof EngineBackedCache<?, ?> engineBackedCache) {
                    EngineBackedCache<Object, Object> engine = (EngineBackedCache<Object, Object>) engineBackedCache;
                    if (allEntries) {
                        engine.clearWithoutSync();
                    } else {
                        for (String key : keysToEvict) {
                            engine.evictWithoutSync(key);
                        }
                    }
                } else {
                    if (allEntries) {
                        cache.clear();
                    } else {
                        for (String key : keysToEvict) {
                            cache.evict(key);
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.warn("清理缓存失败: cache={}, key={}, error={}", cacheName, logKey, e.getMessage());
        } finally {
            if (allEntries) {
                snapshotSupport.clear(cacheName);
            } else {
                for (String key : keysToEvict) {
                    snapshotSupport.remove(cacheName, key);
                }
            }
        }
    }

    private Object invokeSnapshot(String cacheName, Object cacheKey, String unlessExpression) {
        CacheInvocationSnapshotSupport.InvocationSnapshot snapshot = snapshotSupport.getSnapshot(cacheName, cacheKey);
        if (snapshot == null) {
            return null;
        }
        Object result = snapshotSupport.invoke(snapshot);
        if (result != null && shouldSkipCachingOnSnapshotReplay(snapshot, unlessExpression, result)) {
            return null;
        }
        return result;
    }

    private boolean shouldSkipCachingOnSnapshotReplay(CacheInvocationSnapshotSupport.InvocationSnapshot snapshot,
                                                      String unless,
                                                      Object result) {
        if (!StringUtils.hasText(unless)) {
            return false;
        }
        try {
            return expressionEvaluator.evaluateBoolean(
                    unless,
                    snapshot.method(),
                    snapshot.target(),
                    snapshot.args(),
                    result,
                    false
            );
        } catch (CacheConfigurationException e) {
            throw e;
        } catch (Exception e) {
            LOGGER.warn("unless表达式求值失败，按false处理: unless={}, error={}", unless, e.getMessage());
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private Cache<Object, Object> getOrCreateCacheWithLoaderFallback(String cacheName,
                                                                     Class<Object> keyType,
                                                                     Class<Object> valueType,
                                                                     CascadeCacheProperties config,
                                                                     Function<Object, Object> snapshotLoader) {
        try {
            Cache<Object, Object> cache = (Cache<Object, Object>) cacheManager.getOrCreateCache(
                    cacheName, keyType, valueType, config
            );
            if (cache == null) {
                return null;
            }
            if (cache instanceof EngineBackedCache<?, ?> engineBackedCache) {
                EngineBackedCache<Object, Object> engine = (EngineBackedCache<Object, Object>) engineBackedCache;
                engine.setLoaderIfAbsent(new SnapshotFallbackLoader(snapshotLoader));
            }
            return cache;
        } catch (CacheConfigurationException e) {
            throw e;
        } catch (Exception e) {
            LOGGER.warn("获取缓存失败: cache={}, error={}", cacheName, e.getMessage());
            return null;
        }
    }

    private static Class<?> inferValueType(JoinPoint joinPoint) {
        if (joinPoint.getSignature() instanceof MethodSignature methodSignature) {
            return TypeUtils.boxedType(methodSignature.getMethod().getReturnType());
        }
        return Object.class;
    }

    @SuppressWarnings("unchecked")
    private static Class<Object> castStringClass() {
        return (Class<Object>) (Class<?>) String.class;
    }

    private static List<String> resolveEvictKeys(Object cacheKey) {
        if (cacheKey == null) {
            return List.of();
        }
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        collectEvictKeys(cacheKey, keys);
        return new ArrayList<>(keys);
    }

    private static void collectEvictKeys(Object candidate, Set<String> keys) {
        if (candidate == null) {
            return;
        }
        if (candidate instanceof Optional<?> optional) {
            optional.ifPresent(value -> collectEvictKeys(value, keys));
            return;
        }
        if (candidate instanceof Iterable<?> iterable) {
            for (Object value : iterable) {
                collectEvictKeys(value, keys);
            }
            return;
        }
        Class<?> candidateType = candidate.getClass();
        if (candidateType.isArray()) {
            int length = Array.getLength(candidate);
            for (int i = 0; i < length; i++) {
                collectEvictKeys(Array.get(candidate, i), keys);
            }
            return;
        }
        if (!(candidate instanceof String key)) {
            String actualType = candidate.getClass().getName();
            throw new CacheConfigurationException(
                    "key",
                    candidate,
                    "驱逐键必须是String类型: actualType=" + actualType,
                    null
            );
        }
        keys.add(key);
    }

    private CascadeCacheProperties buildConfig(Cacheable annotation, JoinPoint joinPoint) {
        CascadeCacheProperties config = copyDefaultConfig();
        config.getL1().setEnabled(annotation.enableL1());
        config.getL2().setEnabled(annotation.enableL2());
        config.getSync().setEnabled(annotation.enableSync());
        config.getSync().setMode(annotation.enableSync() ? annotation.syncMode() : SyncMode.NONE);
        config.getSync().setUpdateEnabled(annotation.enableSync() && annotation.syncMode() == SyncMode.UPDATE);
        config.getRefresh().setEnabled(resolveCacheableRefreshEnabled(annotation, joinPoint));
        long softTtl = resolveCacheableSoftTtl(annotation);
        if (softTtl > 0) {
            config.getRefresh().setDefaultRefreshIntervalSeconds(softTtl);
        } else if (annotation.refreshInterval() > 0) {
            config.getRefresh().setDefaultRefreshIntervalSeconds(annotation.refreshInterval());
        }

        long ttl = resolveCacheableHardTtl(annotation);
        if (ttl != 0) {
            config.getL2().setDefaultTtlSeconds(ttl);
        }
        return config;
    }

    private boolean resolveCacheableRefreshEnabled(Cacheable annotation, JoinPoint joinPoint) {
        boolean enableRefresh = annotation.enableRefresh();
        boolean autoRefresh = annotation.autoRefresh();
        if (enableRefresh != autoRefresh && shouldLogRefreshAliasMismatch(joinPoint, enableRefresh, autoRefresh)) {
            LOGGER.warn("@Cacheable 的 enableRefresh 与 autoRefresh 配置不一致，将按禁用优先处理: enableRefresh={}, autoRefresh={}",
                    enableRefresh, autoRefresh);
        }
        return enableRefresh && autoRefresh;
    }

    private boolean shouldLogRefreshAliasMismatch(JoinPoint joinPoint, boolean enableRefresh, boolean autoRefresh) {
        String methodKey = joinPoint != null && joinPoint.getSignature() != null
                ? joinPoint.getSignature().toLongString()
                : "unknown-method";
        return refreshAliasMismatchWarnings.add(methodKey + "|" + enableRefresh + "|" + autoRefresh);
    }

    private CascadeCacheProperties buildConfig(CachePut annotation) {
        CascadeCacheProperties config = copyDefaultConfig();
        config.getL1().setEnabled(annotation.enableL1());
        config.getL2().setEnabled(annotation.enableL2());
        config.getSync().setEnabled(annotation.sync());
        config.getSync().setMode(annotation.sync() ? annotation.syncMode() : SyncMode.NONE);
        config.getSync().setUpdateEnabled(annotation.sync() && annotation.syncMode() == SyncMode.UPDATE);
        config.getRefresh().setEnabled(annotation.autoRefresh());
        if (annotation.refreshInterval() > 0) {
            config.getRefresh().setDefaultRefreshIntervalSeconds(annotation.refreshInterval());
        }
        if (annotation.ttl() > 0) {
            config.getL2().setDefaultTtlSeconds(annotation.ttl());
        }
        return config;
    }

    private CascadeCacheProperties copyDefaultConfig() {
        return defaultConfig != null ? defaultConfig.deepCopy() : CascadeCacheProperties.defaults();
    }

    private long resolveCacheableSoftTtl(Cacheable annotation) {
        return DurationParser.parseToSeconds(annotation.softTtl(), annotation.softTtlSeconds());
    }

    private static final class SnapshotFallbackLoader
            implements Function<Object, Object>, LoaderPriority.Prioritized {

        private final Function<Object, Object> delegate;

        private SnapshotFallbackLoader(Function<Object, Object> delegate) {
            this.delegate = delegate;
        }

        @Override
        public Object apply(Object key) {
            return delegate.apply(key);
        }

        @Override
        public int loaderPriority() {
            return LoaderPriority.SNAPSHOT_FALLBACK;
        }
    }
}
