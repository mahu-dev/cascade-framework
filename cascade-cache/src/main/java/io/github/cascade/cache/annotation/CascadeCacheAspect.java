package io.github.cascade.cache.annotation;

import io.github.cascade.cache.annotation.processor.AnnotationConfigurationBuilder;
import io.github.cascade.cache.annotation.processor.AnnotationRefreshSchedulerManager;
import io.github.cascade.cache.annotation.processor.CacheLoaderRegistry;
import io.github.cascade.cache.api.Cache;
import io.github.cascade.cache.api.CacheLoader;
import io.github.cascade.cache.api.CacheManager;
import io.github.cascade.cache.config.CascadeCacheConfiguration;
import io.github.cascade.cache.config.CachePropertiesProvider;
import io.github.cascade.cache.core.unified.SmartCache;
import io.github.cascade.cache.core.unified.UnifiedCacheBuilder;
import io.github.cascade.cache.event.UnifiedCacheEvent;
import io.github.cascade.cache.event.UnifiedEventProcessor;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.Order;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cascade缓存注解切面处理器
 * 提供完整的缓存注解支持，包括：
 * - 基础缓存操作：@CascadeCacheable, @CascadeCacheEvict, @CascadeCachePut
 * - 高级功能：定时刷新、CacheLoader集成、多级缓存、防护机制
 * - 配置式功能对等：与配置式缓存提供完全一致的功能特性
 *
 * @author cascade
 */
@Slf4j
@Aspect
@Order(0) // 确保在其他切面之前执行
public class CascadeCacheAspect {

    private final ApplicationContext applicationContext;
    private final CacheManager cacheManager;
    private final UnifiedEventProcessor eventProcessor;
    private final CacheLoaderRegistry loaderRegistry;
    private final CachePropertiesProvider cachePropertiesProvider;
    private final ExpressionParser parser = new SpelExpressionParser();

    // 注解处理组件
    private AnnotationConfigurationBuilder configurationBuilder;
    private AnnotationRefreshSchedulerManager refreshSchedulerManager;

    // 缓存实例管理
    private final ConcurrentHashMap<String, Cache<Object, Object>> annotationCaches = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CascadeCacheConfiguration> cacheConfigurations = new ConcurrentHashMap<>();

    @Autowired
    public CascadeCacheAspect(ApplicationContext applicationContext,
                              CacheManager cacheManager,
                              UnifiedEventProcessor eventProcessor,
                              CacheLoaderRegistry loaderRegistry,
                              CachePropertiesProvider cachePropertiesProvider) {
        this.applicationContext = applicationContext;
        this.cacheManager = cacheManager;
        this.eventProcessor = eventProcessor;
        this.loaderRegistry = loaderRegistry;
        this.cachePropertiesProvider = cachePropertiesProvider;
    }

    @PostConstruct
    public void initialize() {
        log.info("Initializing Cascade Cache Aspect");

        // 初始化注解处理组件
        this.configurationBuilder = new AnnotationConfigurationBuilder(applicationContext);
        // loaderRegistry现在通过构造器注入，不需要再创建
        this.refreshSchedulerManager = new AnnotationRefreshSchedulerManager(applicationContext, loaderRegistry);

        log.info("Cascade Cache Aspect initialized successfully");
    }

    @PreDestroy
    public void destroy() {
        log.info("Shutting down Cascade Cache Aspect");

        if (refreshSchedulerManager != null) {
            refreshSchedulerManager.shutdown();
        }

        annotationCaches.clear();
        cacheConfigurations.clear();

        log.info("Cascade Cache Aspect shutdown completed");
    }

    /**
     * 处理@CascadeCacheable注解 - 增强版本
     */
    @Around("@annotation(cascadeCacheable)")
    public Object handleCacheable(ProceedingJoinPoint joinPoint, CascadeCacheable cascadeCacheable) throws Throwable {
        log.info(">>> CascadeCacheAspect.handleCacheable 被调用: {}", joinPoint.getSignature());
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        Object[] args = joinPoint.getArgs();

        // 获取缓存名称
        String[] cacheNames = getCacheNames(cascadeCacheable.value(), cascadeCacheable.cacheNames());
        if (cacheNames.length == 0) {
            return joinPoint.proceed();
        }

        String primaryCacheName = cacheNames[0];

        // 生成缓存键
        String cacheKey = generateKey(cascadeCacheable.key(), cascadeCacheable.keyGenerator(), method, args);

        // 检查条件
        if (evaluateCondition(cascadeCacheable.condition(), method, args, null)) {
            return joinPoint.proceed();
        }

        // 获取或创建增强缓存
        Cache<Object, Object> cache = getOrCreateEnhancedCache(primaryCacheName, cascadeCacheable, method, args);

        long startTime = System.currentTimeMillis();

        // 尝试从缓存获取
        Object cachedValue = cache.get(cacheKey);
        log.debug("从缓存 = {} 中获取 key =  {},结果 = {}", cache, cacheKey, cachedValue);
        long duration = System.currentTimeMillis() - startTime;

        if (cachedValue != null) {
            // 缓存命中
            eventProcessor.publishEvent(UnifiedCacheEvent.hit(primaryCacheName, cacheKey, cachedValue, Duration.ofMillis(duration)));

            // 检查unless条件
            boolean unlessResult = evaluateCondition(cascadeCacheable.unless(), method, args, cachedValue);
            log.debug("缓存命中后检查unless条件: unless='{}', 结果={}, 缓存值={}",
                    cascadeCacheable.unless(), unlessResult, cachedValue);

            if (!unlessResult) {
                log.debug("Cache hit for key '{}' in cache '{}', 返回缓存值", cacheKey, primaryCacheName);
                return cachedValue;
            } else {
                log.debug("缓存命中但unless条件为true，继续执行方法");
            }
        } else {
            // 缓存未命中
            eventProcessor.publishEvent(UnifiedCacheEvent.miss(primaryCacheName, cacheKey, Duration.ofMillis(duration)));
        }

        // 执行目标方法
        Object result;
        if (cascadeCacheable.sync()) {
            synchronized (getSyncLock(primaryCacheName, cacheKey)) {
                // 再次检查缓存
                cachedValue = cache.get(cacheKey);
                if (cachedValue != null && !evaluateCondition(cascadeCacheable.unless(), method, args, cachedValue)) {
                    return cachedValue;
                }
                result = joinPoint.proceed();
            }
        } else {
            result = joinPoint.proceed();
        }

        // 缓存结果
        if (result != null && !evaluateCondition(cascadeCacheable.unless(), method, args, result)) {
            long putStartTime = System.currentTimeMillis();

            try {
                putToCache(cache, cacheKey, result, cascadeCacheable);

                long putDuration = System.currentTimeMillis() - putStartTime;
                eventProcessor.publishEvent(UnifiedCacheEvent.builder(primaryCacheName, UnifiedCacheEvent.Type.PUT)
                        .key(cacheKey)
                        .value(result)
                        .duration(Duration.ofMillis(putDuration))
                        .success(true)
                        .build());

                log.debug("Cached result for key '{}' in cache '{}'", cacheKey, primaryCacheName);

            } catch (Exception e) {
                eventProcessor.publishEvent(UnifiedCacheEvent.error(primaryCacheName, e));
                log.warn("Failed to cache result for key '{}' in cache '{}': {}", cacheKey, primaryCacheName, e.getMessage());
                // 不抛出异常，允许方法正常返回
            }
        }

        return result;
    }

    /**
     * 处理@CascadeCacheRefresh注解
     */
    @Around("@annotation(cacheRefresh)")
    public Object handleCacheRefresh(ProceedingJoinPoint joinPoint, CascadeCacheRefresh cacheRefresh) throws Throwable {
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        Object[] args = joinPoint.getArgs();

        // 获取缓存名称
        String[] cacheNames = getCacheNames(cacheRefresh.value(), cacheRefresh.cacheNames());
        if (cacheNames.length == 0) {
            return joinPoint.proceed();
        }

        String primaryCacheName = cacheNames[0];

        // 检查条件
        if (evaluateCondition(cacheRefresh.condition(), method, args, null)) {
            return joinPoint.proceed();
        }

        // 使用增强缓存，确保可以正确设置refreshScheduler
        Cache<Object, Object> cache = getOrCreateCache(primaryCacheName);

        // 创建刷新调度器
        refreshSchedulerManager.createRefreshScheduler(primaryCacheName, cache, cacheRefresh, method, args);

        log.info("为增强缓存 '{}' 配置了刷新调度器", primaryCacheName);

        return joinPoint.proceed();
    }

    /**
     * 处理@CascadeCacheEvict注解 - 增强版本
     */
    @Around("@annotation(cascadeCacheEvict)")
    public Object handleCacheEvict(ProceedingJoinPoint joinPoint, CascadeCacheEvict cascadeCacheEvict) throws Throwable {
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        Object[] args = joinPoint.getArgs();

        // 获取缓存名称
        String[] cacheNames = getCacheNames(cascadeCacheEvict.value(), cascadeCacheEvict.cacheNames());
        if (cacheNames.length == 0) {
            return joinPoint.proceed();
        }

        // 检查条件
        if (evaluateCondition(cascadeCacheEvict.condition(), method, args, null)) {
            return joinPoint.proceed();
        }

        // 方法执行前清除缓存
        if (cascadeCacheEvict.beforeInvocation()) {
            performEviction(cacheNames, cascadeCacheEvict, method, args);
        }

        Object result;
        try {
            result = joinPoint.proceed();
        } catch (Exception e) {
            if (!cascadeCacheEvict.beforeInvocation()) {
                throw e;
            }
            throw e;
        }

        // 方法执行后清除缓存
        if (!cascadeCacheEvict.beforeInvocation()) {
            performEviction(cacheNames, cascadeCacheEvict, method, args);
        }

        return result;
    }

    /**
     * 处理@CascadeCachePut注解 - 增强版本
     */
    @Around("@annotation(cascadeCachePut)")
    public Object handleCachePut(ProceedingJoinPoint joinPoint, CascadeCachePut cascadeCachePut) throws Throwable {
        log.debug("处理@CascadeCachePut注解 - 增强版本");
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        Object[] args = joinPoint.getArgs();

        // 获取缓存名称
        String[] cacheNames = getCacheNames(cascadeCachePut.value(), cascadeCachePut.cacheNames());
        if (cacheNames.length == 0) {
            return joinPoint.proceed();
        }

        // 检查条件
        if (evaluateCondition(cascadeCachePut.condition(), method, args, null)) {
            return joinPoint.proceed();
        }

        // 执行方法
        Object result = joinPoint.proceed();

        // 更新缓存
        if (result != null && !evaluateCondition(cascadeCachePut.unless(), method, args, result)) {
            String cacheKey = generateKey(cascadeCachePut.key(), cascadeCachePut.keyGenerator(), method, args);
            log.debug("生成缓存 key: {}", cacheKey);
            for (String cacheName : cacheNames) {
                Cache<Object, Object> cache = getOrCreateCache(cacheName);
                log.debug("获取到的缓存{}", cache);
                long putStartTime = System.currentTimeMillis();

                try {
                    if (StringUtils.hasText(cascadeCachePut.ttl())) {
                        Duration ttl = parseDuration(cascadeCachePut.ttl());
                        putWithTtl(cache, cacheKey, result, ttl);
                    } else {
                        cache.put(cacheKey, result);
                    }

                    long putDuration = System.currentTimeMillis() - putStartTime;
                    eventProcessor.publishEvent(UnifiedCacheEvent.builder(cacheName, UnifiedCacheEvent.Type.PUT)
                            .key(cacheKey)
                            .value(result)
                            .duration(Duration.ofMillis(putDuration))
                            .success(true)
                            .build());

                    log.debug("Updated cache '{}' with key '{}'", cacheName, cacheKey);

                } catch (Exception e) {
                    eventProcessor.publishEvent(UnifiedCacheEvent.error(cacheName, e));
                    log.warn("Failed to update cache '{}' with key '{}': {}", cacheName, cacheKey, e.getMessage());
                }
            }
        }

        return result;
    }

    /**
     * 获取或创建增强缓存
     * 此方法创建带有 cacheLoader 和 refreshScheduler 的完整功能缓存
     */
    private Cache<Object, Object> getOrCreateEnhancedCache(String cacheName, CascadeCacheable annotation,
                                                           Method method, Object[] args) {
        return annotationCaches.computeIfAbsent(cacheName, name -> {
            try {
                // 构建配置
                CascadeCacheConfiguration config = configurationBuilder.buildConfiguration(annotation, method, args);
                cacheConfigurations.put(cacheName, config);

                Class<?>[] parameterTypes = method.getParameterTypes();
                Class<?> returnType = method.getReturnType();
                Class<?> keyType = null;
                if (parameterTypes.length > 0) {
                    keyType = parameterTypes[0];
                } else {
                    keyType = Object.class;
                }

                // 创建增强缓存
                UnifiedCacheBuilder.SegmentedBuilderImpl<Object, Object> builder =
                        (UnifiedCacheBuilder.SegmentedBuilderImpl<Object, Object>) UnifiedCacheBuilder.forCache(cacheName, keyType, returnType);

                // 根据注解配置缓存层级
                if (annotation.enableL1() && annotation.enableL2()) {
                    // 获取RedissonClient
                    try {
                        RedissonClient redissonClient = applicationContext.getBean(RedissonClient.class);
                        log.debug("获取 redissonClient {}", redissonClient);
                        builder.withL1AndL2(config.getL1(), config.getL2(), redissonClient);
                        log.debug("Enabled L1+L2 cache for '{}' with RedissonClient", cacheName);
                    } catch (Exception e) {
                        log.warn("RedissonClient not available, falling back to L1 only for cache '{}': {}", cacheName, e.getMessage());
                        builder.withL1Only(config.getL1());
                    }
                } else if (annotation.enableL1()) {
                    builder.withL1Only(config.getL1());
                } else {
                    log.warn("No cache layers enabled for cache '{}', using default L1", cacheName);
                    builder.withL1Only();
                }

                // 设置CacheLoader
                CacheLoader<Object, Object> loader = resolveCacheLoader(annotation, cacheName);
                if (loader != null) {
                    builder.withCacheLoader(loader);
                }

                Cache<Object, Object> cache = builder.build(config);

                // 注意：刷新功能现在由@CascadeCacheRefresh注解单独处理

                log.info("Created enhanced cache '{}' with configuration: {}", cacheName, config.getName());
                return cache;

            } catch (Exception e) {
                log.error("Failed to create enhanced cache '{}': {}", cacheName, e.getMessage(), e);
                // 降级到普通缓存（注意：这个普通缓存没有 loader 和 scheduler）
                log.warn("降级创建普通缓存 '{}'，功能可能不完整", cacheName);
                return cacheManager.getOrCreateCache(cacheName, Object.class, Object.class);
            }
        });
    }

    /**
     * 解析CacheLoader
     */
    @SuppressWarnings("unchecked")
    private CacheLoader<Object, Object> resolveCacheLoader(CascadeCacheable annotation, String cacheName) {
        if (StringUtils.hasText(annotation.loader())) {
            // 优先使用指定的加载器
            CacheLoader<Object, Object> loader = loaderRegistry.getLoader(annotation.loader());
            if (loader != null) {
                return loader;
            }

            // 尝试从Spring容器获取
            try {
                return applicationContext.getBean(annotation.loader(), CacheLoader.class);
            } catch (Exception e) {
                log.warn("Failed to get loader bean '{}': {}", annotation.loader(), e.getMessage());
            }
        }

        // 尝试根据缓存名称查找
        return loaderRegistry.getLoaderForCache(cacheName);
    }

    /**
     * 获取或创建缓存（智能增强版本）
     * 优先返回已存在的增强缓存，否则创建具有基本增强功能的缓存
     */
    private Cache<Object, Object> getOrCreateCache(String cacheName) {
        return annotationCaches.computeIfAbsent(cacheName, name -> {
            try {
                log.info("为@CascadeCachePut创建增强缓存: {}", cacheName);

                // 优先从YAML配置获取缓存配置，如果没有则使用默认配置
                CascadeCacheConfiguration config = getConfigurationFromYaml(cacheName);
                if (config == null) {
                    config = createDefaultCacheConfiguration(cacheName);
                }
                cacheConfigurations.put(cacheName, config);

                // 创建增强缓存
                UnifiedCacheBuilder.SegmentedBuilderImpl<Object, Object> builder =
                        (UnifiedCacheBuilder.SegmentedBuilderImpl<Object, Object>) UnifiedCacheBuilder.forCache(cacheName, Object.class, Object.class);

                // 配置缓存层级（默认L1+L2如果Redis可用）
                try {
                    RedissonClient redissonClient = applicationContext.getBean(RedissonClient.class);
                    log.debug("获取 redissonClient {}", redissonClient);
                    builder.withL1AndL2(config.getL1(), config.getL2(), redissonClient);
                    log.debug("为缓存 '{}' 启用 L1+L2", cacheName);
                } catch (Exception e) {
                    log.warn("RedissonClient不可用，缓存 '{}' 降级为仅L1模式: {}", cacheName, e.getMessage());
                    builder.withL1Only(config.getL1());
                }

                // 尝试设置CacheLoader（如果存在的话）
                CacheLoader<Object, Object> loader = tryResolveCacheLoader(cacheName);
                if (loader != null) {
                    builder.withCacheLoader(loader);
                    log.debug("为缓存 '{}' 设置了CacheLoader", cacheName);
                }

                Cache<Object, Object> cache = builder.build(config);
                log.info("成功创建增强缓存 '{}' 用于@CascadeCachePut", cacheName);
                return cache;

            } catch (Exception e) {
                log.error("创建增强缓存 '{}' 失败: {}", cacheName, e.getMessage(), e);
                log.warn("降级创建基础缓存 '{}'", cacheName);
                // 降级到基础缓存
                return cacheManager.getOrCreateCache(cacheName, Object.class, Object.class);
            }
        });
    }

    /**
     * 创建默认缓存配置
     */
    private CascadeCacheConfiguration createDefaultCacheConfiguration(String cacheName) {
        CascadeCacheConfiguration config = new CascadeCacheConfiguration();
        config.setName(cacheName).setEnabled(true);

        // 默认L1配置
        config.getL1().setEnabled(true)
                .setMaximumSize(10000)
                .setExpireAfterWrite(java.time.Duration.ofMinutes(30))
                .setRecordStats(true);

        // 默认L2配置（如果有Redis）
        try {
            RedissonClient redissonClient = applicationContext.getBean(RedissonClient.class);
            if (redissonClient != null) {
                config.getL2().setEnabled(true)
                        .setDefaultTtl(java.time.Duration.ofHours(1));
            }
        } catch (Exception e) {
            // Redis不可用，不启用L2
            config.getL2().setEnabled(false);
        }

        return config;
    }

    /**
     * 从YAML配置获取缓存配置
     */
    private CascadeCacheConfiguration getConfigurationFromYaml(String cacheName) {
        try {
            if (cachePropertiesProvider != null && cachePropertiesProvider.isEnabled()) {
                CascadeCacheConfiguration config = cachePropertiesProvider.toCascadeCacheConfiguration(cacheName);
                if (config != null) {
                    log.debug("从YAML配置获取到缓存 '{}' 的配置", cacheName);
                    return config;
                }
            }
        } catch (Exception e) {
            log.debug("从YAML配置获取缓存 '{}' 配置失败: {}", cacheName, e.getMessage());
        }
        return null;
    }

    /**
     * 尝试解析CacheLoader（容错版本）
     */
    @SuppressWarnings("unchecked")
    private CacheLoader<Object, Object> tryResolveCacheLoader(String cacheName) {
        try {
            // 尝试根据缓存名称查找
            return loaderRegistry.getLoaderForCache(cacheName);
        } catch (Exception e) {
            log.debug("无法为缓存 '{}' 解析CacheLoader: {}", cacheName, e.getMessage());
            return null;
        }
    }

    /**
     * 执行缓存清除操作
     */
    private void performEviction(String[] cacheNames, CascadeCacheEvict cascadeCacheEvict, Method method, Object[] args) {
        for (String cacheName : cacheNames) {
            Cache<Object, Object> cache = getOrCreateCache(cacheName);

            if (cascadeCacheEvict.allEntries()) {
                // 清除所有条目
                long clearStartTime = System.currentTimeMillis();
                try {
                    cache.clear();

                    // 取消所有刷新任务
                    refreshSchedulerManager.cancelRefresh(cacheName, null);

                    long clearDuration = System.currentTimeMillis() - clearStartTime;
                    eventProcessor.publishEvent(UnifiedCacheEvent.builder(cacheName, UnifiedCacheEvent.Type.CLEAR)
                            .duration(Duration.ofMillis(clearDuration))
                            .success(true)
                            .build());

                    log.debug("Cleared all entries from cache '{}'", cacheName);

                } catch (Exception e) {
                    eventProcessor.publishEvent(UnifiedCacheEvent.error(cacheName, e));
                    log.warn("Failed to clear cache '{}': {}", cacheName, e.getMessage());
                }
            } else {
                // 清除指定键
                String cacheKey = generateKey(cascadeCacheEvict.key(), cascadeCacheEvict.keyGenerator(), method, args);
                long evictStartTime = System.currentTimeMillis();

                try {
                    cache.evict(cacheKey);

                    // 取消该键的刷新任务
                    refreshSchedulerManager.cancelRefresh(cacheName, cacheKey);

                    long evictDuration = System.currentTimeMillis() - evictStartTime;
                    eventProcessor.publishEvent(UnifiedCacheEvent.builder(cacheName, UnifiedCacheEvent.Type.EVICT)
                            .key(cacheKey)
                            .duration(Duration.ofMillis(evictDuration))
                            .success(true)
                            .build());

                    log.debug("Evicted key '{}' from cache '{}'", cacheKey, cacheName);

                } catch (Exception e) {
                    eventProcessor.publishEvent(UnifiedCacheEvent.error(cacheName, e));
                    log.warn("Failed to evict key '{}' from cache '{}': {}", cacheKey, cacheName, e.getMessage());
                }
            }
        }
    }

    /**
     * 存储到缓存
     */
    private void putToCache(Cache<Object, Object> cache, Object key, Object value,
                            CascadeCacheable annotation) {
        if (StringUtils.hasText(annotation.ttl())) {
            Duration ttl = parseDuration(annotation.ttl());
            putWithTtl(cache, key, value, ttl);
        } else {
            cache.put(key, value);
        }
    }

    /**
     * 获取缓存名称
     */
    private String[] getCacheNames(String[] value, String[] cacheNames) {
        if (value.length > 0) {
            return value;
        }
        return cacheNames;
    }

    /**
     * 生成缓存键
     */
    private String generateKey(String keyExpression, String keyGenerator, Method method, Object[] args) {
        if (StringUtils.hasText(keyExpression)) {
            // 使用SpEL表达式生成键
            EvaluationContext context = createEvaluationContext(method, args);
            Expression expression = parser.parseExpression(keyExpression);
            Object key = expression.getValue(context);
            log.debug("生成缓存键: 表达式='{}', 解析结果='{}', 方法={}, 参数={}",
                    keyExpression, key, method.getName(), java.util.Arrays.toString(args));
            return key != null ? key.toString() : null;
        }

        if (StringUtils.hasText(keyGenerator)) {
            // TODO: 实现自定义键生成器逻辑
            log.debug("Custom key generator '{}' not yet implemented", keyGenerator);
        }

        // 默认键生成策略
        return generateDefaultKey(method, args);
    }

    /**
     * 生成默认缓存键
     */
    private String generateDefaultKey(Method method, Object[] args) {
        StringBuilder keyBuilder = new StringBuilder();
        keyBuilder.append(method.getDeclaringClass().getSimpleName())
                .append("#")
                .append(method.getName());

        if (args.length > 0) {
            keyBuilder.append("#");
            for (int i = 0; i < args.length; i++) {
                if (i > 0) {
                    keyBuilder.append("_");
                }
                if (args[i] != null) {
                    keyBuilder.append(args[i].toString());
                } else {
                    keyBuilder.append("null");
                }
            }
        }

        return keyBuilder.toString();
    }

    /**
     * 评估条件表达式
     */
    private boolean evaluateCondition(String condition, Method method, Object[] args, Object result) {
        if (!StringUtils.hasText(condition)) {
            return false;  // 空条件表示不满足条件，对于unless来说意味着不跳过缓存
        }

        try {
            EvaluationContext context = createEvaluationContext(method, args);
            if (result != null) {
                context.setVariable("result", result);
            }
            Expression expression = parser.parseExpression(condition);
            Boolean value = expression.getValue(context, Boolean.class);
            return Boolean.TRUE.equals(value);
        } catch (Exception e) {
            log.debug("Failed to evaluate condition '{}': {}", condition, e.getMessage());
            return false;  // 评估失败时默认为false，对于unless来说意味着不跳过缓存
        }
    }

    /**
     * 创建SpEL评估上下文
     */
    private EvaluationContext createEvaluationContext(Method method, Object[] args) {
        StandardEvaluationContext context = new StandardEvaluationContext();

        // 设置方法参数
        if (args != null && args.length > 0) {
            Parameter[] parameters = method.getParameters();
            for (int i = 0; i < args.length; i++) {
                // 支持索引形式访问
                context.setVariable("p" + i, args[i]);
                context.setVariable("a" + i, args[i]);

                // 支持参数名访问
                if (i < parameters.length) {
                    String paramName = parameters[i].getName();
                    context.setVariable(paramName, args[i]);
                    log.debug("Set SpEL variable '{}' = '{}' (parameter type: {})", paramName, args[i], parameters[i].getType().getSimpleName());

                    // 如果参数名是arg0等，说明编译时没有保留参数名
                    if (paramName.startsWith("arg")) {
                        log.warn("检测到参数名为'{}'，可能编译时没有保留参数名。建议使用#p{}或#a{}", paramName, i, i);
                    }
                }
            }
        }

        // 设置方法信息
        context.setVariable("method", method);
        context.setVariable("target", method.getDeclaringClass());

        return context;
    }

    /**
     * 解析持续时间
     */
    private Duration parseDuration(String ttl) {
        try {
            return Duration.parse(ttl);
        } catch (Exception e) {
            try {
                long seconds = Long.parseLong(ttl);
                return Duration.ofSeconds(seconds);
            } catch (NumberFormatException nfe) {
                log.warn("Invalid TTL format '{}', using default 1 hour", ttl);
                return Duration.ofHours(1);
            }
        }
    }

    /**
     * 带TTL的put操作
     */
    @SuppressWarnings("unchecked")
    private void putWithTtl(Cache<Object, Object> cache, Object key, Object value, Duration ttl) {
        try {
            if (cache instanceof SmartCache) {
                ((SmartCache<Object, Object>) cache).putWithTtl(key, value, ttl);
            } else {
                cache.put(key, value);
            }
        } catch (Exception e) {
            cache.put(key, value);
        }
    }

    /**
     * 获取同步锁
     */
    private Object getSyncLock(String cacheName, String key) {
        return (cacheName + ":" + key).intern();
    }
}