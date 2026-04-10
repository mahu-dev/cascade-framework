package cc.coderm.cascade.bloom.aspect;


import cc.coderm.cascade.bloom.annotation.BloomFilter;
import cc.coderm.cascade.bloom.config.BloomFilterProperties;
import cc.coderm.cascade.bloom.core.BloomFilterManager;
import cc.coderm.cascade.bloom.core.CascadeBloomFilter;
import cc.coderm.cascade.bloom.exception.BloomFilterException;
import cc.coderm.cascade.bloom.util.BloomFilterKeyUtil;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 布隆过滤器 AOP 切面
 * <p>
 * 拦截标注了 {@link BloomFilter} 注解的方法，在方法执行前进行布隆过滤器前置校验。
 *
 * @author lionel lionelk@163.com
 * =============================
 * Date: 2025-01-10
 * Time: 16:00:00
 * =============================
 */
@Slf4j
@Aspect
public class BloomFilterAspect {

    private static final int INITIAL_CAPACITY = 16;
    private static final float LOAD_FACTOR = 0.75F;

    private final BloomFilterManager bloomFilterManager;
    private final BloomFilterProperties properties;

    private final ExpressionParser expressionParser = new SpelExpressionParser();
    private final ParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();

    /**
     * SpEL 表达式 LRU 缓存
     * <p>
     * Key: SpEL 表达式字符串
     * Value: 编译后的 Expression 对象
     * <p>
     * 使用 LinkedHashMap 实现 LRU 淘汰策略，超过 maxExpressionCacheSize 时自动淘汰最久未使用的表达式。
     */
    private final LinkedHashMap<String, Expression> expressionCache;

    /**
     * 缓存访问锁
     */
    private final Object cacheLock = new Object();

    /**
     * 缓存命中次数统计
     */
    private final AtomicLong cacheHits = new AtomicLong(0);

    /**
     * 缓存未命中次数统计
     */
    private final AtomicLong cacheMisses = new AtomicLong(0);

    /**
     * 缓存淘汰次数统计
     */
    private final AtomicLong evictions = new AtomicLong(0);


    /**
     * ThreadLocal 缓存 EvaluationContext
     * <p>
     * 每个线程复用自己的 EvaluationContext，避免重复创建对象。
     * 使用时需要重新设置变量值，确保状态正确。
     */
    private final ThreadLocal<EvaluationContext> evaluationContextThreadLocal =
            ThreadLocal.withInitial(StandardEvaluationContext::new);


    public BloomFilterAspect(BloomFilterManager bloomFilterManager,
                             BloomFilterProperties properties) {
        this.bloomFilterManager = bloomFilterManager;
        this.properties = properties;
        this.expressionCache = new LinkedHashMap<>(INITIAL_CAPACITY, LOAD_FACTOR, true);

        log.info("[cascade-bloom] BloomFilterAspect initialized with maxExpressionCacheSize={}",
                properties.getMaxExpressionCacheSize());
    }

    @Around("@annotation(bloomFilter)")
    public Object around(ProceedingJoinPoint joinPoint, BloomFilter bloomFilter) throws Throwable {
        String filterName = bloomFilter.name();
        String keyExpression = bloomFilter.key();

        // 1. 解析 SpEL 表达式得到过滤 key
        String key = resolveKey(joinPoint, keyExpression);
        log.debug("[cascade-bloom] @BloomFilter intercepted: filter=[{}], key=[{}]", filterName, key);

        // 2. 获取过滤器实例
        CascadeBloomFilter<Object> filter = bloomFilterManager.getFilter(filterName);

        // 3. 布隆过滤器判断
        boolean mightContain = filter.mightContain(key);

        if (!mightContain) {
            log.debug("[cascade-bloom] BloomFilter [{}] confirmed absent for key [{}]", filterName, key);

            // 可选：在 miss 场景执行一次真实探测（自动刷新）
            if (bloomFilter.autoRefreshOnAbsent()) {
                Object refreshed = joinPoint.proceed();
                if (refreshed != null) {
                    writeBackIfNecessary(filter, filterName, key, refreshed, bloomFilter);
                    return refreshed;
                }
            }

            return onAbsent(joinPoint, bloomFilter);
        }

        // 4. 可能存在，执行原方法；成功时可自动回填
        Object result = joinPoint.proceed();
        writeBackIfNecessary(filter, filterName, key, result, bloomFilter);
        return result;
    }

    // -------------------------------------------------------------------------
    // SpEL 解析工具
    // -------------------------------------------------------------------------

    private String resolveKey(ProceedingJoinPoint joinPoint, String keyExpression) {
        try {
            MethodSignature signature = (MethodSignature) joinPoint.getSignature();
            Method method = signature.getMethod();
            Object[] args = joinPoint.getArgs();

            EvaluationContext context = getOrCreateEvaluationContext(method, args);
            Expression expression = getOrParseExpression(keyExpression);
            Object value = expression.getValue(context);
            return normalizeResolvedKey(value, keyExpression, "spel");
        } catch (Exception e) {
            if (e instanceof BloomFilterException bloomFilterException) {
                throw bloomFilterException;
            }
            if (properties.isStrictSpEL()) {
                throw new BloomFilterException(
                        "SpEL key expression resolution failed: [" + keyExpression + "]. " +
                                "Please check: 1) parameter names match, 2) compile with -parameters flag, " +
                                "3) expression syntax is correct. " +
                                "Or set cascade.bloom.strict-spel=false to use fallback mode.",
                        e);
            }
            log.debug("[cascade-bloom] Failed to resolve key expression [{}], fallback to args[0]", keyExpression, e);
            Object[] args = joinPoint.getArgs();
            Object fallbackValue = args != null && args.length > 0 ? args[0] : null;
            return normalizeResolvedKey(fallbackValue, keyExpression, "args[0]");
        }
    }

    private Object resolveFallback(String fallbackExpression, ProceedingJoinPoint joinPoint) {
        if ("null".equals(fallbackExpression)) {
            return null;
        }
        try {
            MethodSignature signature = (MethodSignature) joinPoint.getSignature();
            Method method = signature.getMethod();
            Object[] args = joinPoint.getArgs();
            EvaluationContext context = getOrCreateEvaluationContext(method, args);
            Expression expression = getOrParseExpression(fallbackExpression);
            return expression.getValue(context);
        } catch (Exception e) {
            if (properties.isStrictSpEL()) {
                throw new BloomFilterException(
                        "SpEL fallback expression resolution failed: [" + fallbackExpression + "]. " +
                                "Check expression syntax or set cascade.bloom.strict-spel=false.",
                        e);
            }
            log.debug("[cascade-bloom] Failed to resolve fallback expression [{}], returning null", fallbackExpression, e);
            return null;
        }
    }

    /**
     * 获取或创建 EvaluationContext
     * <p>
     * 使用 ThreadLocal 缓存 EvaluationContext，避免重复创建对象。
     * 每次使用前重新设置变量值，确保状态正确。
     *
     * @param method 方法对象
     * @param args   方法参数
     * @return 配置好的 EvaluationContext
     */
    private EvaluationContext getOrCreateEvaluationContext(Method method, Object[] args) {
        EvaluationContext context = evaluationContextThreadLocal.get();

        // 清空之前的变量设置
        clearEvaluationContextVariables(context);

        // 重新设置变量
        context.setVariable("args", args);

        String[] paramNames = parameterNameDiscoverer.getParameterNames(method);
        if (paramNames != null) {
            for (int i = 0; i < paramNames.length; i++) {
                if (i < args.length) {
                    context.setVariable(paramNames[i], args[i]);
                }
            }
        }

        return context;
    }

    /**
     * 清空 EvaluationContext 中的变量
     * <p>
     * 避免变量污染，确保每次使用时状态干净。
     *
     * @param context EvaluationContext
     */
    private static void clearEvaluationContextVariables(EvaluationContext context) {
        if (context instanceof StandardEvaluationContext standardContext) {
            standardContext.setVariable("args", null);
        }
    }

    private static String normalizeResolvedKey(Object resolvedValue, String keyExpression, String source) {
        try {
            return BloomFilterKeyUtil.toKey(resolvedValue);
        } catch (NullPointerException e) {
            throw new BloomFilterException(
                    "BloomFilter key resolved to null from [" + source + "] for expression [" + keyExpression + "]. " +
                            "Null key is not allowed.",
                    e
            );
        }
    }

    private Object onAbsent(ProceedingJoinPoint joinPoint, BloomFilter bloomFilter) {
        if (bloomFilter.throwOnAbsent()) {
            throw new BloomFilterException(bloomFilter.message());
        }
        return resolveFallback(bloomFilter.fallbackValue(), joinPoint);
    }

    private void writeBackIfNecessary(CascadeBloomFilter<Object> filter,
                                      String filterName,
                                      String key,
                                      Object result,
                                      BloomFilter bloomFilter) {
        if (!bloomFilter.writeBackOnSuccess() || result == null) {
            return;
        }
        try {
            filter.add(key);
            log.debug("[cascade-bloom] BloomFilter [{}] write-back key [{}]", filterName, key);
        } catch (Exception e) {
            // 回填失败不应影响主流程
            log.warn("[cascade-bloom] BloomFilter [{}] write-back key [{}] failed", filterName, key, e);
        }
    }

    // -------------------------------------------------------------------------
    // SpEL 表达式缓存管理
    // -------------------------------------------------------------------------

    /**
     * 从缓存获取或解析 SpEL 表达式
     * <p>
     * 使用 LRU 缓存策略，超过 maxExpressionCacheSize 时自动淘汰最久未使用的表达式。
     *
     * @param expressionString SpEL 表达式字符串
     * @return 编译后的 Expression 对象
     */
    private Expression getOrParseExpression(String expressionString) {
        synchronized (cacheLock) {
            Expression expression = expressionCache.get(expressionString);
            if (expression != null) {
                cacheHits.incrementAndGet();
                return expression;
            }

            cacheMisses.incrementAndGet();
            ensureCapacityBeforeCreate();

            Expression newExpression = expressionParser.parseExpression(expressionString);
            expressionCache.put(expressionString, newExpression);
            return newExpression;
        }
    }

    /**
     * 确保创建新表达式前有足够容量
     * <p>
     * 如果缓存已满，淘汰最久未使用的表达式。
     */
    private void ensureCapacityBeforeCreate() {
        int maxSize = properties.getMaxExpressionCacheSize();
        if (expressionCache.size() >= maxSize) {
            Map.Entry<String, Expression> eldest = expressionCache.entrySet().iterator().next();
            String evictedExpression = eldest.getKey();
            expressionCache.remove(evictedExpression);
            evictions.incrementAndGet();
            log.warn("[cascade-bloom] Expression cache full (size={}), evicting oldest expression [{}], maxExpressionCacheSize={}",
                    expressionCache.size(), evictedExpression, maxSize);
        }
    }

    /**
     * 清空表达式缓存
     * <p>
     * 主要用于测试或特殊场景，生产环境一般不需要调用。
     */
    public void clearExpressionCache() {
        synchronized (cacheLock) {
            int previousSize = expressionCache.size();
            expressionCache.clear();
            cacheHits.set(0);
            cacheMisses.set(0);
            evictions.set(0);
            log.info("[cascade-bloom] SpEL expression cache cleared. previousSize={}", previousSize);
        }
    }

    /**
     * 清空 ThreadLocal 缓存
     * <p>
     * 防止内存泄漏，建议在应用关闭或不再使用时调用。
     */
    public void clearThreadLocalCache() {
        evaluationContextThreadLocal.remove();
        log.info("[cascade-bloom] ThreadLocal EvaluationContext cache cleared");
    }

    /**
     * 获取表达式缓存大小
     *
     * @return 缓存中的表达式数量
     */
    public int getExpressionCacheSize() {
        synchronized (cacheLock) {
            return expressionCache.size();
        }
    }

    /**
     * 获取缓存命中次数
     *
     * @return 命中次数
     */
    public long getCacheHits() {
        return cacheHits.get();
    }

    /**
     * 获取缓存未命中次数
     *
     * @return 未命中次数
     */
    public long getCacheMisses() {
        return cacheMisses.get();
    }

    /**
     * 获取缓存淘汰次数
     *
     * @return 淘汰次数
     */
    public long getEvictions() {
        return evictions.get();
    }

    /**
     * 获取缓存命中率
     *
     * @return 命中率（0-1之间的double值）
     */
    public double getCacheHitRate() {
        long hits = cacheHits.get();
        long misses = cacheMisses.get();
        long total = hits + misses;
        if (total == 0) {
            return 0;
        }
        return (double) hits / total;
    }
}
