package io.github.cascade.cache.simple;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;

/**
 * SpEL表达式处理工具类
 *
 * @author cascade
 */
public class SpelExpressionHelper {
    private static final Logger LOGGER = LoggerFactory.getLogger(SpelExpressionHelper.class);

    private final ExpressionParser parser = new SpelExpressionParser();
    private final ConcurrentHashMap<String, Expression> expressionCache;
    private final int maxCacheSize;

    public SpelExpressionHelper(int maxCacheSize) {
        this.maxCacheSize = maxCacheSize;
        this.expressionCache = new ConcurrentHashMap<>();
    }

    public Object evaluate(String expression, JoinPoint joinPoint, Object result) {
        if (!StringUtils.hasText(expression)) {
            return generateDefaultKey(joinPoint);
        }
        try {
            Expression expr = getCachedExpression(expression);
            StandardEvaluationContext context = createContext(joinPoint, result);
            return expr.getValue(context);
        } catch (RuntimeException e) {
            LOGGER.debug("SpEL evaluation failed: {}", expression, e);
            return generateDefaultKey(joinPoint);
        }
    }

    /**
     * 获取缓存的SpEL表达式，如果不存在则解析并缓存
     */
    private Expression getCachedExpression(String expression) {
        return expressionCache.computeIfAbsent(expression, expr -> {
            // 检查缓存大小，超出限制时进行清理
            if (expressionCache.size() >= maxCacheSize) {
                // 简单的清理策略：清理25%最老的条目
                int removeCount = maxCacheSize / 4;
                expressionCache.entrySet().stream()
                        .limit(removeCount)
                        .forEach(entry -> expressionCache.remove(entry.getKey()));

                LOGGER.debug("SpEL表达式缓存已清理 {} 个条目，当前大小: {}", removeCount, expressionCache.size());
            }

            // 解析新表达式
            return parser.parseExpression(expr);
        });
    }

    private static StandardEvaluationContext createContext(JoinPoint joinPoint, Object result) {
        StandardEvaluationContext context = new StandardEvaluationContext();
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();

        // 批量设置变量
        String[] paramNames = signature.getParameterNames();
        Object[] args = joinPoint.getArgs();
        IntStream.range(0, Math.min(paramNames.length, args.length))
                .forEach(i -> {
                    context.setVariable(paramNames[i], args[i]);
                    context.setVariable("p" + i, args[i]);
                });

        if (result != null) {
            context.setVariable("result", result);
        }

        context.setRootObject(new MethodContext(joinPoint));
        return context;
    }

    /**
     * 生成默认缓存键
     */
    private static Object generateDefaultKey(JoinPoint joinPoint) {
        Object[] args = joinPoint.getArgs();

        if (args.length == 0) {
            return "default";
        }

        if (args.length == 1) {
            Object arg = args[0];
            return arg != null ? generateKeyFromObject(arg) : "null";
        }

        // 多参数时，生成组合键
        StringBuilder keyBuilder = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if (i > 0) {
                keyBuilder.append(":");
            }
            Object arg = args[i];
            keyBuilder.append(arg != null ? generateKeyFromObject(arg) : "null");
        }
        return keyBuilder.toString();
    }

    /**
     * 从对象生成缓存键字符串
     */
    private static String generateKeyFromObject(Object obj) {
        if (obj == null) {
            return "null";
        }

        // 使用策略分发，避免复杂嵌套条件
        String primitiveResult = handlePrimitiveTypes(obj);
        if (primitiveResult != null) {
            return primitiveResult;
        }

        String arrayResult = handleArrayTypes(obj);
        if (arrayResult != null) {
            return arrayResult;
        }

        String collectionResult = handleCollectionTypes(obj);
        if (collectionResult != null) {
            return collectionResult;
        }

        String mapResult = handleMapTypes(obj);
        if (mapResult != null) {
            return mapResult;
        }

        return obj.toString();
    }

    /**
     * 处理基本类型和字符串
     */
    private static String handlePrimitiveTypes(Object obj) {
        if (obj instanceof String || obj instanceof Number ||
                obj instanceof Boolean || obj instanceof Character) {
            return obj.toString();
        }
        return null;
    }

    /**
     * 处理数组类型
     */
    private static String handleArrayTypes(Object obj) {
        if (!obj.getClass().isArray()) {
            return null;
        }

        if (obj instanceof Object[] objectArray) {
            return Arrays.deepToString(objectArray);
        }

        return handlePrimitiveArrays(obj);
    }

    /**
     * 处理基本类型数组
     */
    private static String handlePrimitiveArrays(Object obj) {
        if (obj instanceof int[] intArray) {
            return Arrays.toString(intArray);
        } else if (obj instanceof long[] longArray) {
            return Arrays.toString(longArray);
        } else if (obj instanceof double[] doubleArray) {
            return Arrays.toString(doubleArray);
        } else if (obj instanceof byte[] byteArray) {
            return Arrays.toString(byteArray);
        } else {
            return Arrays.toString((Object[]) obj);
        }
    }

    /**
     * 处理集合类型
     */
    private static String handleCollectionTypes(Object obj) {
        if (!(obj instanceof Collection<?> collection)) {
            return null;
        }

        if (collection.isEmpty()) {
            return "[]";
        }

        return collection.stream()
                .map(item -> item != null ? item.toString() : "null")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    /**
     * 处理Map类型
     */
    private static String handleMapTypes(Object obj) {
        if (!(obj instanceof Map<?, ?> map)) {
            return null;
        }

        if (map.isEmpty()) {
            return "{}";
        }

        return map.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(java.util.stream.Collectors.joining(",", "{", "}"));
    }

    /**
     * 方法上下文，提供对方法信息的访问
     */
    private static class MethodContext {
        private final JoinPoint joinPoint;

        public MethodContext(JoinPoint joinPoint) {
            this.joinPoint = joinPoint;
        }

        public Object[] getArgs() {
            return joinPoint.getArgs();
        }

        public String getMethod() {
            return joinPoint.getSignature().getName();
        }

        public String getTarget() {
            return joinPoint.getTarget().getClass().getSimpleName();
        }

        public Object getArg(int index) {
            Object[] args = joinPoint.getArgs();
            return index >= 0 && index < args.length ? args[index] : null;
        }
    }
}