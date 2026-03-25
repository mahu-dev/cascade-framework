package io.github.cascade.cache.aspect;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

/**
 * @author lionel lionelk@163.com
 *         =============================
 *         Date: 2025/10/29
 *         Time: 17:10
 *         =============================
 *
 *         缓存表达式求值器 - 专门处理SpEL表达式的解析和求值
 *         <p>
 *         设计原则：
 *         1. 性能优先：缓存编译后的表达式，避免重复解析
 *         2. 线程安全：使用Caffeine Cache (线程安全) 缓存表达式
 *         3. 错误处理：优雅处理表达式解析和求值异常
 *         4. 功能完整：支持变量、方法调用、条件判断等
 *         5. 资源管理：有限的缓存容量，避免内存溢出
 *
 *         <p>
 *         使用场景：
 *         - @Cacheable注解的key和condition表达式求值
 *         - @CachePut注解的condition表达式求值
 *         - @CacheEvict注解的key表达式求值
 */
public class CacheExpressionEvaluator {

    private static final Logger LOGGER = LoggerFactory.getLogger(CacheExpressionEvaluator.class);

    // SpEL表达式解析器
    private static final ExpressionParser EXPRESSION_PARSER = new SpelExpressionParser();

    // 参数名发现器
    private static final DefaultParameterNameDiscoverer PARAMETER_NAME_DISCOVERER = new DefaultParameterNameDiscoverer();

    // 表达式缓存，避免重复解析 (使用Caffeine)
    private static final int MAX_CACHE_SIZE = 5_000;
    private static final Cache<String, Expression> EXPRESSION_CACHE = Caffeine.newBuilder()
            .maximumSize(MAX_CACHE_SIZE)
            .expireAfterAccess(2, TimeUnit.HOURS)
            .recordStats()
            .build();

    /**
     * 求值SpEL表达式
     *
     * @param expressionString SpEL表达式字符串
     * @param joinPoint        切点信息
     * @param result           方法执行结果（用于after-returning通知）
     * @return 求值结果
     */
    public Object evaluate(String expressionString, JoinPoint joinPoint, Object result) {
        if (!StringUtils.hasText(expressionString)) {
            return null;
        }

        try {
            // 获取或创建编译后的表达式
            Expression expression = getOrCompileExpression(expressionString);

            // 创建求值上下文
            EvaluationContext context = createEvaluationContext(joinPoint, result);

            // 执行求值
            Object value = expression.getValue(context);

            LOGGER.debug("SpEL表达式求值成功: expression={}, result={}", expressionString, value);
            return value;

        } catch (Exception e) {
            LOGGER.warn("SpEL表达式求值失败: expression={}, error={}", expressionString, e.getMessage());
            return null;
        }
    }

    /**
     * 求值SpEL表达式并返回指定类型
     *
     * @param expressionString SpEL表达式字符串
     * @param joinPoint        切点信息
     * @param result           方法执行结果
     * @param desiredType      期望的返回类型
     * @return 求值结果
     */
    @SuppressWarnings("unchecked")
    public <T> T evaluate(String expressionString, JoinPoint joinPoint, Object result, Class<T> desiredType) {
        Object value = evaluate(expressionString, joinPoint, result);

        if (value == null) {
            return null;
        }

        try {
            if (desiredType.isInstance(value)) {
                return (T) value;
            }

            // 尝试类型转换
            return EXPRESSION_PARSER.parseExpression(expressionString).getValue(
                    createEvaluationContext(joinPoint, result), desiredType);

        } catch (Exception e) {
            LOGGER.warn("SpEL表达式类型转换失败: expression={}, desiredType={}, actualType={}, error={}",
                    expressionString, desiredType.getSimpleName(), value.getClass().getSimpleName(), e.getMessage());
            return null;
        }
    }

    /**
     * 求值布尔表达式
     *
     * @param expressionString 布尔表达式字符串
     * @param joinPoint        切点信息
     * @param result           方法执行结果
     * @return 布尔值，表达式无效时默认返回true
     */
    public boolean evaluateBoolean(String expressionString, JoinPoint joinPoint, Object result) {
        if (!StringUtils.hasText(expressionString)) {
            return true;
        }

        Boolean value = evaluate(expressionString, joinPoint, result, Boolean.class);
        return value != null ? value : true;
    }

    /**
     * 获取或编译表达式
     * <p>
     * 使用缓存避免重复解析相同的表达式
     *
     * @param expressionString 表达式字符串
     * @return 编译后的表达式
     */
    private Expression getOrCompileExpression(String expressionString) {
        return EXPRESSION_CACHE.get(expressionString, expr -> {
            LOGGER.debug("编译SpEL表达式: {}", expr);
            return EXPRESSION_PARSER.parseExpression(expr);
        });
    }

    /**
     * 创建求值上下文
     *
     * @param joinPoint 切点信息
     * @param result    方法执行结果
     * @return 求值上下文
     */
    private EvaluationContext createEvaluationContext(JoinPoint joinPoint, Object result) {
        StandardEvaluationContext context = new StandardEvaluationContext();

        // 获取方法对象
        Method method = null;
        if (joinPoint.getSignature() instanceof MethodSignature) {
            method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        }

        // 设置方法参数
        if (method != null) {
            String[] parameterNames = PARAMETER_NAME_DISCOVERER.getParameterNames(method);
            Object[] args = joinPoint.getArgs();

            if (parameterNames != null) {
                for (int i = 0; i < Math.min(parameterNames.length, args.length); i++) {
                    context.setVariable(parameterNames[i], args[i]);
                }
            }
        }

        // 设置特殊变量
        context.setVariable("target", joinPoint.getTarget());
        context.setVariable("method", joinPoint.getSignature().getName());
        context.setVariable("args", joinPoint.getArgs());
        context.setVariable("result", result);
        context.setVariable("joinPoint", joinPoint);

        // 设置根对象为目标对象
        context.setRootObject(joinPoint.getTarget());

        return context;
    }

    /**
     * 清理表达式缓存
     */
    public static void clearCache() {
        long count = EXPRESSION_CACHE.estimatedSize();
        EXPRESSION_CACHE.invalidateAll();
        LOGGER.info("SpEL表达式缓存已清理，共清理约: {} 个表达式", count);
    }

    /**
     * 获取缓存统计信息
     *
     * @return 缓存统计信息
     */
    public static ExpressionCacheStats getCacheStats() {
        return ExpressionCacheStats.builder()
                .cachedExpressionCount((int) EXPRESSION_CACHE.estimatedSize())
                .maxCacheSize(MAX_CACHE_SIZE)
                .cacheUsageRatio((double) EXPRESSION_CACHE.estimatedSize() / MAX_CACHE_SIZE)
                .build();
    }

    /**
     * 表达式缓存统计信息
     */
    public static class ExpressionCacheStats {
        private final int cachedExpressionCount;
        private final int maxCacheSize;
        private final double cacheUsageRatio;

        private ExpressionCacheStats(Builder builder) {
            this.cachedExpressionCount = builder.cachedExpressionCount;
            this.maxCacheSize = builder.maxCacheSize;
            this.cacheUsageRatio = builder.cacheUsageRatio;
        }

        public static Builder builder() {
            return new Builder();
        }

        // Getters
        public int getCachedExpressionCount() {
            return cachedExpressionCount;
        }

        public int getMaxCacheSize() {
            return maxCacheSize;
        }

        public double getCacheUsageRatio() {
            return cacheUsageRatio;
        }

        @Override
        public String toString() {
            return String.format(
                    "ExpressionCacheStats{cachedExpressionCount=%d, maxCacheSize=%d, cacheUsageRatio=%.2f}",
                    cachedExpressionCount, maxCacheSize, cacheUsageRatio);
        }

        public static class Builder {
            private int cachedExpressionCount;
            private int maxCacheSize;
            private double cacheUsageRatio;

            public Builder cachedExpressionCount(int cachedExpressionCount) {
                this.cachedExpressionCount = cachedExpressionCount;
                return this;
            }

            public Builder maxCacheSize(int maxCacheSize) {
                this.maxCacheSize = maxCacheSize;
                return this;
            }

            public Builder cacheUsageRatio(double cacheUsageRatio) {
                this.cacheUsageRatio = cacheUsageRatio;
                return this;
            }

            public ExpressionCacheStats build() {
                return new ExpressionCacheStats(this);
            }
        }
    }
}