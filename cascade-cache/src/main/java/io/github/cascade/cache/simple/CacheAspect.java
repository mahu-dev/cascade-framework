package io.github.cascade.cache.simple;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 统一缓存切面实现
 * <p>
 * 设计原则：
 * 1. 统一处理：一个切面处理所有缓存注解
 * 2. 性能优先：优化热点路径，减少反射调用
 * 3. 异常安全：缓存异常不影响业务方法执行
 * 4. Lambda优化：使用Lambda表达式简化代码
 *
 * @author cascade
 */
@Aspect
@Component
@Order(1) // 高优先级，确保在其他切面之前执行
public class CacheAspect {

    private static final Logger log = LoggerFactory.getLogger(CacheAspect.class);

    private final CacheManager cacheManager;
    private final ExpressionParser expressionParser = new SpelExpressionParser();
    
    // SpEL表达式缓存，提升性能并限制内存占用
    private final ConcurrentHashMap<String, Expression> expressionCache = new ConcurrentHashMap<>();
    private static final int MAX_EXPRESSION_CACHE_SIZE = 1000; // 最大缓存1000个表达式

    public CacheAspect(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
        log.info("缓存切面已初始化");
    }

    // ==================== @Cacheable 处理 ====================

    @Around("@annotation(cacheable)")
    public <K, V> Object handleCacheable(ProceedingJoinPoint joinPoint, Cacheable cacheable) throws Throwable {
        String cacheName = resolveCacheName(joinPoint, cacheable.value());
        K cacheKey = (K) evaluateSpelExpression(joinPoint, cacheable.key());
        if (log.isTraceEnabled()) {
            log.trace("@Cacheable处理开始: cache={}, key={}", cacheName, cacheKey);
        }

        try {
            // 获取缓存实例
            Cache<K, V> cache = getOrCreateCache(joinPoint, cacheName, cacheable);

            // 检查条件
            if (evaluateCondition(joinPoint, cacheable.condition(), null)) {
                log.debug("@Cacheable条件不满足，跳过缓存: {}", cacheable.condition());
                return joinPoint.proceed();
            }
            // 尝试从缓存获取
            Optional<V> cachedValue = cache.get(cacheKey);
            if (cachedValue.isPresent()) {
                if (log.isTraceEnabled()) {
                    log.trace("@Cacheable缓存命中: cache={}, key={}", cacheName, cacheKey);
                }

                if (cacheable.enableRefresh()) {
                    log.info("缓存命中但需要启用自动刷新: cache={}, key={}, interval={}s",
                            cacheName, cacheKey, cacheable.refreshInterval());
                    scheduleRefresh(cache, cacheKey, cacheable.refreshInterval());
                }

                return cachedValue.get();
            }

            // 缓存未命中
            if (cacheable.asyncLoad()) {
                // 异步加载：立即返回null，后台加载
                CompletableFuture.runAsync(() -> {
                    try {
                        V result = (V) joinPoint.proceed();
                        if (result != null) {
                            cache.put(cacheKey, result, cacheable.ttl());
                        }
                    } catch (Throwable e) {
                        log.error("异步缓存加载失败: cache={}, key={}, error={}",
                                cacheName, cacheKey, e.getMessage());
                    }
                });
                return null;
            } else {
                // 同步加载
                V result = (V) joinPoint.proceed();
                if (result != null) {
                    cache.put(cacheKey, result, cacheable.ttl());

                    // 启用自动刷新
                    if (cacheable.enableRefresh()) {
                        log.info("准备启用自动刷新: cache={}, key={}, interval={}s",
                                cacheName, cacheKey, cacheable.refreshInterval());
                        scheduleRefresh(cache, cacheKey, cacheable.refreshInterval());
                    }
                }
                return result;
            }

        } catch (Exception e) {
            log.error("@Cacheable处理异常: cache={}, key={}, error={}", cacheName, cacheKey, e.getMessage());
            // 缓存异常不影响业务方法执行
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
     * 使用Spring标准SpEL解析器评估表达式
     */
    private Object evaluateSpelExpression(JoinPoint joinPoint, String expression) {
        if (expression == null || expression.isEmpty()) {
            return generateDefaultCacheKey(joinPoint);
        }
        try {
            // 创建标准评估上下文
            StandardEvaluationContext context = createEvaluationContext(joinPoint);

            // 从缓存获取已解析的表达式，避免重复解析
            Expression spelExpression = getCachedExpression(expression);
            Object result = spelExpression.getValue(context);

            return result != null ? result : "null";
        } catch (Exception e) {
            log.warn("SpEL表达式解析失败: {}, 使用默认键生成策略, 错误: {}", expression, e.getMessage());
            return generateDefaultCacheKey(joinPoint);
        }
    }
    
    /**
     * 获取缓存的SpEL表达式，如果不存在则解析并缓存
     * 使用LRU清理策略防止内存泄漏
     */
    private Expression getCachedExpression(String expression) {
        return expressionCache.computeIfAbsent(expression, expr -> {
            // 检查缓存大小，超出限制时进行清理
            if (expressionCache.size() >= MAX_EXPRESSION_CACHE_SIZE) {
                // 简单的清理策略：清理25%最老的条目
                int removeCount = MAX_EXPRESSION_CACHE_SIZE / 4;
                expressionCache.entrySet().stream()
                    .limit(removeCount)
                    .forEach(entry -> expressionCache.remove(entry.getKey()));
                
                log.debug("SpEL表达式缓存已清理 {} 个条目，当前大小: {}", removeCount, expressionCache.size());
            }
            
            // 解析新表达式
            return expressionParser.parseExpression(expr);
        });
    }

    /**
     * 创建SpEL评估上下文
     */
    private StandardEvaluationContext createEvaluationContext(JoinPoint joinPoint) {
        StandardEvaluationContext context = new StandardEvaluationContext();

        // 设置方法参数
        MethodSignature methodSignature = (MethodSignature) joinPoint.getSignature();
        String[] paramNames = methodSignature.getParameterNames();
        Object[] args = joinPoint.getArgs();

        // 绑定参数名到参数值
        for (int i = 0; i < paramNames.length && i < args.length; i++) {
            context.setVariable(paramNames[i], args[i]);
            // 同时支持 #p0, #p1 等格式
            context.setVariable("p" + i, args[i]);
        }

        // 设置根对象（包含方法信息）
        context.setRootObject(new SpelRootObject(joinPoint));

        return context;
    }

    /**
     * SpEL根对象，提供对方法信息的访问
     */
    private static class SpelRootObject {
        private final JoinPoint joinPoint;

        public SpelRootObject(JoinPoint joinPoint) {
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

    /**
     * 生成默认缓存键
     */
    private Object generateDefaultCacheKey(JoinPoint joinPoint) {
        Object[] args = joinPoint.getArgs();

        if (args.length == 0) {
            return "default";
        }

        if (args.length == 1) {
            Object arg = args[0];
            return arg != null ? generateKeyFromObject(arg) : null;
        }

        // 多参数时，生成组合键
        StringBuilder keyBuilder = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if (i > 0) {
                keyBuilder.append(":");
            }
            Object arg = args[i];
            keyBuilder.append(arg != null ? generateKeyFromObject(arg) : null);
        }
        return keyBuilder.toString();
    }

    /**
     * 从对象生成缓存键字符串
     */
    private String generateKeyFromObject(Object obj) {
        if (obj == null) {
            return null;
        }
        // 基本类型和字符串直接返回
        if (obj instanceof String || obj instanceof Number ||
                obj instanceof Boolean || obj instanceof Character) {
            return obj.toString();
        }

        // 数组处理
        if (obj.getClass().isArray()) {
            if (obj instanceof Object[]) {
                return Arrays.deepToString((Object[]) obj);
            } else if (obj instanceof int[]) {
                return Arrays.toString((int[]) obj);
            } else if (obj instanceof long[]) {
                return Arrays.toString((long[]) obj);
            } else if (obj instanceof double[]) {
                return Arrays.toString((double[]) obj);
            } else if (obj instanceof byte[]) {
                return Arrays.toString((byte[]) obj);
            } else {
                return Arrays.toString((Object[]) obj);
            }
        }

        // Collection处理
        if (obj instanceof Collection<?> collection) {
            if (collection.isEmpty()) {
                return "[]";
            }
            return collection.stream()
                    .map(item -> item != null ? item.toString() : "null")
                    .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        }

        // Map处理
        if (obj instanceof Map<?, ?> map) {
            if (map.isEmpty()) {
                return "{}";
            }
            return map.entrySet().stream()
                    .map(entry -> entry.getKey() + "=" + entry.getValue())
                    .collect(java.util.stream.Collectors.joining(",", "{", "}"));
        }

        // 其他对象使用toString()
        return obj.toString();
    }

    /**
     * 使用Spring标准SpEL评估条件表达式
     */
    private boolean evaluateCondition(JoinPoint joinPoint, String condition, Object result) {
        if (condition == null || condition.isEmpty()) {
            return false;
        }

        try {
            StandardEvaluationContext context = createEvaluationContext(joinPoint);
            // 设置结果变量，用于@CachePut和@CacheEvict的条件评估
            if (result != null) {
                context.setVariable("result", result);
            }
            Expression expression = expressionParser.parseExpression(condition);
            Object value = expression.getValue(context);

            return value instanceof Boolean && !((Boolean) value);
        } catch (Exception e) {
            log.warn("条件表达式评估失败: {}, 默认返回true, 错误: {}", condition, e.getMessage());
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
        if (keyExpression == null || keyExpression.isEmpty()) {
            // 没有指定key表达式，使用默认逻辑推断类型
            return resolveDefaultKeyType(joinPoint);
        }

        // 尝试通过SpEL表达式推断类型
        return inferSpelExpressionType(joinPoint, keyExpression);
    }

    /**
     * 推断默认键的类型（基于方法参数）
     */
    private Class<?> resolveDefaultKeyType(JoinPoint joinPoint) {
        MethodSignature methodSignature = (MethodSignature) joinPoint.getSignature();
        Class<?>[] paramTypes = methodSignature.getParameterTypes();

        if (paramTypes.length == 0) {
            return String.class; // 无参数时使用 "default" 字符串
        } else if (paramTypes.length == 1) {
            return paramTypes[0]; // 单参数时使用参数类型
        } else {
            return String.class; // 多参数时生成组合字符串
        }
    }

    /**
     * 通过SpEL表达式推断键的类型
     */
    private Class<?> inferSpelExpressionType(JoinPoint joinPoint, String expression) {
        String expr = expression.trim();
        
        try {
            // 1. 字符串字面量
            if (expr.startsWith("'") && expr.endsWith("'") || 
                expr.startsWith("\"") && expr.endsWith("\"")) {
                return String.class;
            }

            // 2. 数字字面量
            if (expr.matches("\\d+[LlFfDd]?")) {
                if (expr.endsWith("L") || expr.endsWith("l")) {
                    return Long.class;
                } else if (expr.endsWith("F") || expr.endsWith("f")) {
                    return Float.class;
                } else if (expr.endsWith("D") || expr.endsWith("d")) {
                    return Double.class;
                } else if (expr.contains(".")) {
                    return Double.class;
                } else {
                    return Integer.class;
                }
            }

            // 3. 布尔字面量
            if ("true".equals(expr) || "false".equals(expr)) {
                return Boolean.class;
            }

            // 4. 参数引用 #p0, #p1 等
            if (expr.matches("#p\\d+")) {
                int paramIndex = Integer.parseInt(expr.substring(2));
                MethodSignature methodSignature = (MethodSignature) joinPoint.getSignature();
                Class<?>[] paramTypes = methodSignature.getParameterTypes();
                
                if (paramIndex >= 0 && paramIndex < paramTypes.length) {
                    return paramTypes[paramIndex];
                }
                return Object.class;
            }

            // 5. 单独的参数名引用 #user, #paramName 等
            if (expr.startsWith("#") && !expr.contains(".")) {
                String paramName = expr.substring(1); // 去掉开头的#
                MethodSignature methodSignature = (MethodSignature) joinPoint.getSignature();
                String[] paramNames = methodSignature.getParameterNames();
                Class<?>[] paramTypes = methodSignature.getParameterTypes();
                
                for (int i = 0; i < paramNames.length; i++) {
                    if (paramName.equals(paramNames[i])) {
                        return paramTypes[i];
                    }
                }
                return Object.class;
            }

            // 6. 参数属性访问 #p0.field, #paramName.field
            if (expr.startsWith("#") && expr.contains(".")) {
                return resolvePropertyType(joinPoint, expr);
            }

            // 7. 字符串拼接表达式
            if (expr.contains(" + ")) {
                return String.class; // 拼接结果总是字符串
            }

            // 8. 条件表达式 condition ? value1 : value2
            if (expr.contains(" ? ") && expr.contains(" : ")) {
                // 简化处理：返回通用类型
                return Object.class;
            }

            // 9. 静态方法调用 T(Class).method()
            if (expr.startsWith("T(") && expr.contains(").")) {
                return inferStaticMethodReturnType(expr);
            }

            // 9. 其他复杂表达式
            return Object.class;

        } catch (Exception e) {
            log.debug("SpEL表达式类型推断失败: {}, 使用Object类型, 错误: {}", expr, e.getMessage());
            return Object.class;
        }
    }

    /**
     * 解析属性访问类型 #p0.field, #paramName.field
     */
    private Class<?> resolvePropertyType(JoinPoint joinPoint, String expr) {
        try {
            // 解析参数部分
            String paramPart;
            String propertyPath;
            
            int dotIndex = expr.indexOf('.');
            if (dotIndex > 0) {
                paramPart = expr.substring(1, dotIndex); // 去掉开头的#
                propertyPath = expr.substring(dotIndex + 1);
            } else {
                return Object.class;
            }

            // 获取参数类型
            Class<?> paramType = null;
            MethodSignature methodSignature = (MethodSignature) joinPoint.getSignature();
            
            if (paramPart.matches("p\\d+")) {
                // #p0, #p1 格式
                int paramIndex = Integer.parseInt(paramPart.substring(1));
                Class<?>[] paramTypes = methodSignature.getParameterTypes();
                if (paramIndex >= 0 && paramIndex < paramTypes.length) {
                    paramType = paramTypes[paramIndex];
                }
            } else {
                // #paramName 格式
                String[] paramNames = methodSignature.getParameterNames();
                Class<?>[] paramTypes = methodSignature.getParameterTypes();
                for (int i = 0; i < paramNames.length; i++) {
                    if (paramPart.equals(paramNames[i])) {
                        paramType = paramTypes[i];
                        break;
                    }
                }
            }

            if (paramType != null) {
                return resolveFieldType(paramType, propertyPath);
            }

        } catch (Exception e) {
            log.debug("属性类型解析失败: {}, 错误: {}", expr, e.getMessage());
        }
        
        return Object.class;
    }

    /**
     * 解析字段类型（通过反射）
     */
    private Class<?> resolveFieldType(Class<?> rootType, String fieldPath) {
        Class<?> currentType = rootType;
        String[] fieldNames = fieldPath.split("\\.");

        for (String fieldName : fieldNames) {
            try {
                // 尝试查找getter方法
                Method getter = findGetterMethod(currentType, fieldName);
                if (getter != null) {
                    currentType = getter.getReturnType();
                } else {
                    // 尝试直接字段访问
                    java.lang.reflect.Field field = currentType.getDeclaredField(fieldName);
                    currentType = field.getType();
                }
            } catch (Exception e) {
                log.debug("字段类型解析失败: {}.{}, 使用Object类型", currentType.getSimpleName(), fieldName);
                return Object.class;
            }
        }

        return currentType;
    }

    /**
     * 查找getter方法
     */
    private Method findGetterMethod(Class<?> clazz, String fieldName) {
        String capitalizedFieldName = Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);

        try {
            return clazz.getMethod("get" + capitalizedFieldName);
        } catch (NoSuchMethodException e1) {
            try {
                return clazz.getMethod("is" + capitalizedFieldName);
            } catch (NoSuchMethodException e2) {
                return null;
            }
        }
    }

    /**
     * 推断静态方法调用的返回类型
     */
    private Class<?> inferStaticMethodReturnType(String expr) {
        if (expr.contains("String.valueOf")) {
            return String.class;
        } else if (expr.contains("Integer.valueOf") || expr.contains("Integer.parseInt")) {
            return Integer.class;
        } else if (expr.contains("Long.valueOf") || expr.contains("Long.parseLong")) {
            return Long.class;
        } else {
            return Object.class;
        }
    }

    /**
     * 解析缓存值的类型（方法返回值类型）
     */
    private Class<?> resolveValueType(JoinPoint joinPoint) {
        MethodSignature methodSignature = (MethodSignature) joinPoint.getSignature();
        Method method = methodSignature.getMethod();
        return method.getReturnType();
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

}