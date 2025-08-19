package io.github.cascade.cache.annotation;

import io.github.cascade.cache.api.Cache;
import io.github.cascade.cache.api.CacheManager;
import io.github.cascade.cache.core.unified.UnifiedCache;
import io.github.cascade.cache.event.UnifiedEventProcessor;
import io.github.cascade.cache.event.UnifiedCacheEvent;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.time.Duration;

/**
 * Cascade缓存注解切面处理器
 */
@Aspect
@Component
public class CascadeCacheAspect {

    private final CacheManager cacheManager;
    private final UnifiedEventProcessor eventProcessor;
    private final ExpressionParser parser = new SpelExpressionParser();

    @Autowired
    public CascadeCacheAspect(CacheManager cacheManager, UnifiedEventProcessor eventProcessor) {
        this.cacheManager = cacheManager;
        this.eventProcessor = eventProcessor;
    }

    /**
     * 处理@CascadeCacheable注解
     */
    @Around("@annotation(cascadeCacheable)")
    public Object handleCacheable(ProceedingJoinPoint joinPoint, CascadeCacheable cascadeCacheable) throws Throwable {
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        Object[] args = joinPoint.getArgs();

        // 获取缓存名称
        String[] cacheNames = getCacheNames(cascadeCacheable.value(), cascadeCacheable.cacheNames());
        if (cacheNames.length == 0) {
            return joinPoint.proceed();
        }

        // 生成缓存键
        String cacheKey = generateKey(cascadeCacheable.key(), cascadeCacheable.keyGenerator(), method, args);

        // 检查条件
        if (!evaluateCondition(cascadeCacheable.condition(), method, args, null)) {
            return joinPoint.proceed();
        }

        // 获取缓存
        Cache<String, Object> cache = cacheManager.getCache(cacheNames[0]);

        // 尝试从缓存获取
        long startTime = System.currentTimeMillis();
        Object cachedValue = cache.get(cacheKey);
        long duration = System.currentTimeMillis() - startTime;

        if (cachedValue != null) {
            // 发布缓存命中事件
            eventProcessor.publishEvent(UnifiedCacheEvent.hit(cacheNames[0], cacheKey, cachedValue, Duration.ofMillis(duration)));

            // 检查unless条件
            if (!evaluateCondition(cascadeCacheable.unless(), method, args, cachedValue)) {
                return cachedValue;
            }
        } else {
            // 发布缓存未命中事件
            eventProcessor.publishEvent(UnifiedCacheEvent.miss(cacheNames[0], cacheKey, Duration.ofMillis(duration)));
        }

        // 执行方法
        Object result;
        if (cascadeCacheable.sync()) {
            synchronized (this) {
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
                if (StringUtils.hasText(cascadeCacheable.ttl())) {
                    Duration ttl = parseDuration(cascadeCacheable.ttl());
                    putWithTtl(cache, cacheKey, result, ttl);
                } else {
                    cache.put(cacheKey, result);
                }
                long putDuration = System.currentTimeMillis() - putStartTime;
                eventProcessor.publishEvent(UnifiedCacheEvent.builder(cacheNames[0], UnifiedCacheEvent.Type.PUT)
                    .key(cacheKey)
                    .value(result)
                    .duration(Duration.ofMillis(putDuration))
                    .success(true)
                    .build());
            } catch (Exception e) {
                long putDuration = System.currentTimeMillis() - putStartTime;
                eventProcessor.publishEvent(UnifiedCacheEvent.error(cacheNames[0], e));
                throw e;
            }
        }

        return result;
    }

    /**
     * 处理@CascadeCacheEvict注解
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
        if (!evaluateCondition(cascadeCacheEvict.condition(), method, args, null)) {
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
            // 如果方法执行失败且beforeInvocation=false，不执行清除操作
            if (cascadeCacheEvict.beforeInvocation()) {
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
     * 处理@CascadeCachePut注解
     */
    @Around("@annotation(cascadeCachePut)")
    public Object handleCachePut(ProceedingJoinPoint joinPoint, CascadeCachePut cascadeCachePut) throws Throwable {
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        Object[] args = joinPoint.getArgs();

        // 获取缓存名称
        String[] cacheNames = getCacheNames(cascadeCachePut.value(), cascadeCachePut.cacheNames());
        if (cacheNames.length == 0) {
            return joinPoint.proceed();
        }

        // 检查条件
        if (!evaluateCondition(cascadeCachePut.condition(), method, args, null)) {
            return joinPoint.proceed();
        }

        // 执行方法
        Object result = joinPoint.proceed();

        // 更新缓存
        if (result != null && !evaluateCondition(cascadeCachePut.unless(), method, args, result)) {
            String cacheKey = generateKey(cascadeCachePut.key(), cascadeCachePut.keyGenerator(), method, args);

            for (String cacheName : cacheNames) {
                Cache<String, Object> cache = cacheManager.getCache(cacheName);
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
                } catch (Exception e) {
                    long putDuration = System.currentTimeMillis() - putStartTime;
                    eventProcessor.publishEvent(UnifiedCacheEvent.error(cacheName, e));
                    throw e;
                }
            }
        }

        return result;
    }

    /**
     * 执行缓存清除操作
     */
    private void performEviction(String[] cacheNames, CascadeCacheEvict cascadeCacheEvict, Method method, Object[] args) {
        for (String cacheName : cacheNames) {
            Cache<String, Object> cache = cacheManager.getCache(cacheName);

            if (cascadeCacheEvict.allEntries()) {
                long clearStartTime = System.currentTimeMillis();
                try {
                    cache.clear();
                    long clearDuration = System.currentTimeMillis() - clearStartTime;
                    eventProcessor.publishEvent(UnifiedCacheEvent.builder(cacheName, UnifiedCacheEvent.Type.CLEAR)
                        .duration(Duration.ofMillis(clearDuration))
                        .success(true)
                        .build());
                } catch (Exception e) {
                    long clearDuration = System.currentTimeMillis() - clearStartTime;
                    eventProcessor.publishEvent(UnifiedCacheEvent.error(cacheName, e));
                    throw e;
                }
            } else {
                String cacheKey = generateKey(cascadeCacheEvict.key(), cascadeCacheEvict.keyGenerator(), method, args);
                long evictStartTime = System.currentTimeMillis();
                try {
                    cache.evict(cacheKey);
                    long evictDuration = System.currentTimeMillis() - evictStartTime;
                    eventProcessor.publishEvent(UnifiedCacheEvent.builder(cacheName, UnifiedCacheEvent.Type.EVICT)
                        .key(cacheKey)
                        .duration(Duration.ofMillis(evictDuration))
                        .success(true)
                        .build());
                } catch (Exception e) {
                    long evictDuration = System.currentTimeMillis() - evictStartTime;
                    eventProcessor.publishEvent(UnifiedCacheEvent.error(cacheName, e));
                    throw e;
                }
            }
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
            return key != null ? key.toString() : "null";
        }

        if (StringUtils.hasText(keyGenerator)) {
            // 使用自定义键生成器（这里简化处理）
            // 实际实现中应该从Spring容器中获取对应的KeyGenerator bean
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
            return true;
        }

        try {
            EvaluationContext context = createEvaluationContext(method, args);
            if (result != null) {
                context.setVariable("result", result);
            }
            Expression expression = parser.parseExpression(condition);
            Boolean value = expression.getValue(context, Boolean.class);
            return value != null ? value : true;
        } catch (Exception e) {
            // 表达式评估失败时默认返回true
            return true;
        }
    }

    /**
     * 创建SpEL评估上下文
     */
    private EvaluationContext createEvaluationContext(Method method, Object[] args) {
        StandardEvaluationContext context = new StandardEvaluationContext();

        // 设置方法参数
        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                context.setVariable("p" + i, args[i]);
                context.setVariable("a" + i, args[i]);
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
            // 尝试解析为秒数
            try {
                long seconds = Long.parseLong(ttl);
                return Duration.ofSeconds(seconds);
            } catch (NumberFormatException nfe) {
                throw new IllegalArgumentException("Invalid TTL format: " + ttl, e);
            }
        }
    }

    /**
     * 带TTL的put操作，处理不同类型的Cache实现
     */
    @SuppressWarnings("unchecked")
    private void putWithTtl(Cache<String, Object> cache, String key, Object value, Duration ttl) {
        try {
            if (cache instanceof UnifiedCache) {
                ((UnifiedCache<String, Object>) cache).putWithTtl(key, value, ttl);
            } else {
                // 如果缓存不支持TTL，则使用普通put方法
                cache.put(key, value);
            }
        } catch (Exception e) {
            // 降级到普通put方法
            cache.put(key, value);
        }
    }
}