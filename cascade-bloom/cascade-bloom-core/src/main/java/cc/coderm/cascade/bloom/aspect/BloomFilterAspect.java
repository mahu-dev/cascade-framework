package cc.coderm.cascade.bloom.aspect;


import cc.coderm.cascade.bloom.annotation.BloomFilter;
import cc.coderm.cascade.bloom.config.BloomFilterProperties;
import cc.coderm.cascade.bloom.core.BloomFilterManager;
import cc.coderm.cascade.bloom.core.CascadeBloomFilter;
import cc.coderm.cascade.bloom.exception.BloomFilterException;
import cc.coderm.cascade.bloom.util.BloomFilterArgumentValidator;
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

import java.beans.Introspector;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
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
    private static final String ARGUMENT_ARRAY_VARIABLE = "args";
    private static final Set<String> RESERVED_VARIABLE_NAMES = Set.of(ARGUMENT_ARRAY_VARIABLE);

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
    private final ExpressionLruCache expressionCache;

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
     * 构造布隆过滤器 AOP 切面实例
     * <p>
     * 初始化切面所需的依赖组件和缓存结构，包括：
     * - 布隆过滤器管理器
     * - 配置属性
     * - SpEL 表达式 LRU 缓存
     *
     * @param bloomFilterManager 布隆过滤器管理器，用于获取和管理过滤器实例
     * @param properties         布隆过滤器配置属性，包含缓存大小、严格模式等配置
     */
    public BloomFilterAspect(BloomFilterManager bloomFilterManager,
                             BloomFilterProperties properties) {
        this.bloomFilterManager = bloomFilterManager;
        this.properties = properties;
        this.expressionCache = new ExpressionLruCache(properties.getMaxExpressionCacheSize(), this::onExpressionEvicted);

        log.info("[cascade-bloom] BloomFilterAspect initialized with maxExpressionCacheSize={}",
                properties.getMaxExpressionCacheSize());
    }

    @Around("@annotation(bloomFilter)")
    public Object around(ProceedingJoinPoint joinPoint, BloomFilter bloomFilter) throws Throwable {
        String filterName = BloomFilterArgumentValidator.normalizeFilterName(bloomFilter.name());

        String keyExpression = BloomFilterArgumentValidator.requireKeyExpression(bloomFilter.key());

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

    /**
     * 解析 SpEL 表达式得到布隆过滤器的 key
     * <p>
     * 通过 SpEL 表达式引擎解析 keyExpression，从方法参数中提取布隆过滤器的 key 值。
     * 支持两种模式：
     * 1. 严格模式（strict-spel=true）：解析失败直接抛出异常
     * 2. 非严格模式（strict-spel=false）：解析失败时降级使用 args[0] 作为 key
     *
     * @param joinPoint      AOP 连接点，用于获取方法签名和参数
     * @param keyExpression  SpEL 表达式字符串，用于从方法参数中提取 key
     * @return 解析并标准化后的 key 字符串
     * @throws BloomFilterException 当 strict-spel=true 且表达式解析失败时抛出，
     *                              或者 normalizeResolvedKey 检测到 null key 时抛出
     */
    private String resolveKey(ProceedingJoinPoint joinPoint, String keyExpression) {
        try {
            MethodSignature signature = (MethodSignature) joinPoint.getSignature();
            Method method = signature.getMethod();
            Object[] args = joinPoint.getArgs();

            // 构建 SpEL 上下文并解析表达式
            EvaluationContext context = getOrCreateEvaluationContext(method, args);
            Expression expression = getOrParseExpression(keyExpression);
            Object value = expression.getValue(context);
            return normalizeResolvedKey(value, keyExpression, "spel");
        } catch (Exception e) {
            // 保留已有的 BloomFilterException，不重复包装
            if (e instanceof BloomFilterException bloomFilterException) {
                throw bloomFilterException;
            }
            // 严格模式下直接抛出异常
            if (properties.isStrictSpEL()) {
                throw new BloomFilterException(
                        "SpEL key expression resolution failed: [" + keyExpression + "]. " +
                                "Please check: 1) parameter names match, 2) compile with -parameters flag, " +
                                "3) expression syntax is correct. " +
                                "Or set cascade.bloom.strict-spel=false to use fallback mode.",
                        e);
            }
            // 非严格模式下降级使用第一个参数
            log.debug("[cascade-bloom] Failed to resolve key expression [{}], fallback to args[0]", keyExpression, e);
            Object[] args = joinPoint.getArgs();
            Object fallbackValue = args != null && args.length > 0 ? args[0] : null;
            return normalizeResolvedKey(fallbackValue, keyExpression, "args[0]");
        }
    }

    /**
     * 解析降级表达式并返回结果
     * <p>
     * 当布隆过滤器判定元素不存在时，使用该方法解析用户配置的降级表达式（fallbackValue），
     * 通过 SpEL 表达式引擎计算出降级返回值。支持特殊值 "null" 直接返回 null。
     * <p>
     * 在严格模式下（strict-spel=true），表达式解析失败会抛出异常；
     * 在非严格模式下，解析失败仅记录调试日志并返回 null，不影响主流程。
     *
     * @param fallbackExpression 降级表达式字符串，支持 SpEL 表达式，特殊值 "null" 表示直接返回 null
     * @param joinPoint          AOP 连接点，用于获取方法签名、参数等信息以构建 SpEL 上下文
     * @return 表达式解析结果；当表达式为 "null" 或解析失败且非严格模式时返回 null
     * @throws BloomFilterException 当 strict-spel=true 且表达式解析失败时抛出
     */
    private Object resolveFallback(String fallbackExpression, ProceedingJoinPoint joinPoint) {
        // 处理特殊值 "null"，直接返回 null
        if ("null".equals(fallbackExpression)) {
            return null;
        }
        try {
            MethodSignature signature = (MethodSignature) joinPoint.getSignature();
            Method method = signature.getMethod();
            Object[] args = joinPoint.getArgs();
            // 创建 EvaluationContext，绑定方法参数到 SpEL 上下文
            EvaluationContext context = getOrCreateEvaluationContext(method, args);
            // 从缓存获取或解析 SpEL 表达式
            Expression expression = getOrParseExpression(fallbackExpression);
            // 执行表达式求值并返回结果
            return expression.getValue(context);
        } catch (Exception e) {
            // 严格模式下抛出异常，非严格模式下降级为返回 null
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
     * 每次调用都创建独立的 EvaluationContext，确保参数引用仅在当前调用链内存活，
     * 避免线程池复用线程时出现 ThreadLocal 残留引用。
     * <p>
     * 支持两种SpEL表达式写法：
     * 1. #paramName.fieldName - 标准对象属性访问
     * 2. #fieldName - 当只有一个DTO参数时，直接访问DTO的属性（智能解析）
     *
     * @param method 方法对象
     * @param args   方法参数
     * @return 配置好的 EvaluationContext
     */
    private EvaluationContext getOrCreateEvaluationContext(Method method, Object[] args) {
        StandardEvaluationContext context = new StandardEvaluationContext();
        Set<String> occupiedVariableNames = new HashSet<>();

        // 保留变量：#args 始终指向参数数组，禁止被覆盖
        bindVariable(context, occupiedVariableNames, ARGUMENT_ARRAY_VARIABLE, args);

        String[] paramNames = parameterNameDiscoverer.getParameterNames(method);
        if (paramNames != null && args != null) {
            for (int i = 0; i < paramNames.length; i++) {
                if (i < args.length) {
                    bindNamedParameter(context, occupiedVariableNames, paramNames[i], args[i], i);
                }
            }
        }
        bindIndexedParameters(context, occupiedVariableNames, args);

        // 智能解析：当只有一个DTO参数时，将其属性也绑定到上下文
        // 这样既支持 #dto.userId，也支持 #userId
        if (args != null && args.length == 1 && args[0] != null) {
            bindDtoProperties(context, args[0], occupiedVariableNames);
        }

        return context;
    }

    private void bindNamedParameter(StandardEvaluationContext context,
                                    Set<String> occupiedVariableNames,
                                    String parameterName,
                                    Object parameterValue,
                                    int parameterIndex) {
        if (parameterName == null || parameterName.isBlank()) {
            return;
        }
        if (RESERVED_VARIABLE_NAMES.contains(parameterName)) {
            log.debug("[cascade-bloom] Parameter name [{}] conflicts with reserved SpEL variable; " +
                            "skip parameter-name binding. use #p{} / #a{} / #args[{}] instead.",
                    parameterName, parameterIndex, parameterIndex, parameterIndex);
            return;
        }
        bindVariableIfAbsent(context, occupiedVariableNames, parameterName, parameterValue);
    }

    private static void bindIndexedParameters(StandardEvaluationContext context,
                                              Set<String> occupiedVariableNames,
                                              Object[] args) {
        if (args == null) {
            return;
        }
        for (int i = 0; i < args.length; i++) {
            Object arg = args[i];
            bindVariableIfAbsent(context, occupiedVariableNames, "p" + i, arg);
            bindVariableIfAbsent(context, occupiedVariableNames, "a" + i, arg);
        }
    }

    /**
     * 将DTO对象的属性绑定到SpEL上下文
     * <p>
     * 当方法只有一个DTO参数时，将其所有getter方法对应的属性绑定到上下文中，
     * 支持直接使用属性名（如 #userId）而不是必须使用对象.属性（如 #dto.userId）。
     * <p>
     * 只绑定简单类型的属性（基本类型、String、Number等），忽略复杂对象和集合。
     *
     * @param context               EvaluationContext
     * @param dto                   DTO对象
     * @param occupiedVariableNames 已占用变量名集合（用于避免覆盖）
     */
    private void bindDtoProperties(StandardEvaluationContext context,
                                   Object dto,
                                   Set<String> occupiedVariableNames) {
        if (shouldSkipDtoBinding(dto)) {
            log.debug("[cascade-bloom] Skipping DTO property binding: dto={}", dto.getClass().getSimpleName());
            return;
        }

        log.debug("[cascade-bloom] Starting DTO property binding for: {}", dto.getClass().getSimpleName());
        Method[] methods = dto.getClass().getMethods();
        int boundCount = 0;
        for (Method method : methods) {
            if (tryBindDtoProperty(context, dto, method, occupiedVariableNames)) {
                boundCount++;
            }
        }
        log.debug("[cascade-bloom] DTO property binding completed: bound {} properties", boundCount);
    }

    /**
     * 判断是否应该跳过DTO属性绑定
     *
     * @param dto DTO对象
     * @return 如果应该跳过返回true，否则返回false
     */
    private boolean shouldSkipDtoBinding(Object dto) {
        return dto == null || isSimpleType(dto.getClass());
    }

    /**
     * 尝试绑定DTO的单个属性到SpEL上下文
     *
     * @param context               EvaluationContext
     * @param dto                   DTO对象
     * @param method                方法对象
     * @param occupiedVariableNames 已占用变量名集合（用于避免覆盖）
     * @return 如果成功绑定返回true，否则返回false
     */
    private boolean tryBindDtoProperty(StandardEvaluationContext context,
                                       Object dto,
                                       Method method,
                                       Set<String> occupiedVariableNames) {
        if (!isBindableGetter(method)) {
            return false;
        }

        String propertyName = extractPropertyName(method.getName());
        if (propertyName == null || propertyName.isEmpty()) {
            return false;
        }
        if (occupiedVariableNames.contains(propertyName)) {
            log.debug("[cascade-bloom] Skip DTO property [{}] binding: variable already occupied", propertyName);
            return false;
        }

        Object value = invokeGetterSafely(dto, method);
        if (shouldBindProperty(value)) {
            bindVariable(context, occupiedVariableNames, propertyName, value);
            log.debug("[cascade-bloom] Bound DTO property: {}={}", propertyName, value);
            return true;
        }
        return false;
    }

    /**
     * 判断方法是否是可绑定的getter方法
     *
     * @param method 方法对象
     * @return 如果是可绑定的getter方法返回true，否则返回false
     */
    private boolean isBindableGetter(Method method) {
        return isGetterMethod(method) && method.getParameterCount() == 0;
    }

    /**
     * 安全地调用getter方法获取属性值
     *
     * @param dto    DTO对象
     * @param getter getter方法
     * @return 属性值，调用失败时返回null
     */
    private Object invokeGetterSafely(Object dto, Method getter) {
        try {
            return getter.invoke(dto);
        } catch (Exception e) {
            log.trace("[cascade-bloom] Failed to invoke getter: {}", getter.getName(), e);
            return null;
        }
    }

    /**
     * 判断属性值是否应该绑定到上下文
     *
     * @param value 属性值
     * @return 如果应该绑定返回true，否则返回false
     */
    private boolean shouldBindProperty(Object value) {
        return value == null || isSimpleType(value.getClass());
    }

    /**
     * 判断是否是getter方法
     * <p>
     * getter方法的特征：
     * - 方法名以 "get" 开头（长度大于3）
     * - 方法名以 "is" 开头且返回boolean类型（长度大于2）
     * - 方法返回类型不是void
     *
     * @param method 方法对象
     * @return 如果是getter方法返回true，否则返回false
     */
    private boolean isGetterMethod(java.lang.reflect.Method method) {
        String methodName = method.getName();
        Class<?> returnType = method.getReturnType();

        // 排除返回void的方法
        if (returnType == void.class || returnType == Void.class) {
            return false;
        }

        // 排除getClass()方法
        if ("getClass".equals(methodName)) {
            return false;
        }

        // getXxx() 形式的getter
        if (methodName.startsWith("get") && methodName.length() > 3) {
            return true;
        }

        // isXxx() 形式的布尔getter
        if (methodName.startsWith("is") && methodName.length() > 2
                && (returnType == boolean.class || returnType == Boolean.class)) {
            return true;
        }

        return false;
    }

    /**
     * 从getter方法名中提取属性名
     * <p>
     * 使用 JavaBean 规范 {@link Introspector#decapitalize(String)} 提取属性名。
     * 提取示例：
     * - getUserId -> userId
     * - isActive -> active
     * - getURL -> URL
     *
     * @param methodName getter方法名
     * @return 提取的属性名，如果无法提取返回null
     */
    private static String extractPropertyName(String methodName) {
        if (methodName.startsWith("get") && methodName.length() > 3) {
            return Introspector.decapitalize(methodName.substring(3));
        }

        if (methodName.startsWith("is") && methodName.length() > 2) {
            return Introspector.decapitalize(methodName.substring(2));
        }

        return null;
    }

    /**
     * 判断是否是简单类型
     * <p>
     * 简单类型包括：
     * - 基本类型及其包装类
     * - String
     * - Number及其子类
     * - 枚举类型
     *
     * @param clazz 类对象
     * @return 如果是简单类型返回true，否则返回false
     */
    private static boolean isSimpleType(Class<?> clazz) {
        if (clazz.isPrimitive()) {
            return true;
        }

        return isCoreType(clazz) || isNumberType(clazz) || isEnumType(clazz);
    }

    /**
     * 判断是否是核心类型
     *
     * @param clazz 类对象
     * @return 如果是核心类型返回true，否则返回false
     */
    private static boolean isCoreType(Class<?> clazz) {
        return clazz == String.class
                || clazz == Integer.class || clazz == int.class
                || clazz == Long.class || clazz == long.class
                || clazz == Double.class || clazz == double.class
                || clazz == Float.class || clazz == float.class
                || clazz == Boolean.class || clazz == boolean.class
                || clazz == Character.class || clazz == char.class
                || clazz == Byte.class || clazz == byte.class
                || clazz == Short.class || clazz == short.class;
    }

    /**
     * 判断是否是数字类型
     *
     * @param clazz 类对象
     * @return 如果是数字类型返回true，否则返回false
     */
    private static boolean isNumberType(Class<?> clazz) {
        return Number.class.isAssignableFrom(clazz)
                || clazz == BigDecimal.class
                || clazz == BigInteger.class;
    }

    /**
     * 判断是否是枚举类型
     *
     * @param clazz 类对象
     * @return 如果是枚举类型返回true，否则返回false
     */
    private static boolean isEnumType(Class<?> clazz) {
        return Enum.class.isAssignableFrom(clazz);
    }

    /**
     * 绑定变量到 EvaluationContext
     * <p>
     * 将变量名和值设置到 SpEL 上下文中。
     *
     * @param context               EvaluationContext
     * @param occupiedVariableNames 已占用变量名集合（用于避免覆盖）
     * @param variableName          变量名称，用于在 SpEL 表达式中引用
     * @param value                 变量值，可以是任意对象
     */
    private static void bindVariable(StandardEvaluationContext context,
                                     Set<String> occupiedVariableNames,
                                     String variableName,
                                     Object value) {
        context.setVariable(variableName, value);
        occupiedVariableNames.add(variableName);
    }

    private static void bindVariableIfAbsent(StandardEvaluationContext context,
                                             Set<String> occupiedVariableNames,
                                             String variableName,
                                             Object value) {
        if (occupiedVariableNames.contains(variableName)) {
            return;
        }
        bindVariable(context, occupiedVariableNames, variableName, value);
    }

    /**
     * 将解析后的 key 值标准化为字符串
     * <p>
     * 调用 BloomFilterKeyUtil.toKey() 将 SpEL 表达式解析结果转换为布隆过滤器的 key 字符串。
     * 如果解析结果为 null，则抛出 BloomFilterException 异常，因为布隆过滤器不允许 null key。
     *
     * @param resolvedValue  SpEL 表达式解析后的值，可能为任意类型或 null
     * @param keyExpression  原始 SpEL 表达式字符串，用于异常信息提示
     * @param source         值来源标识（如 "spel"、"args[0]" 等），用于异常信息提示
     * @return 标准化后的 key 字符串
     * @throws BloomFilterException 当 resolvedValue 为 null 时抛出，包含详细的错误信息
     */
    private static String normalizeResolvedKey(Object resolvedValue, String keyExpression, String source) {
        try {
            return BloomFilterKeyUtil.toKey(resolvedValue);
        } catch (NullPointerException e) {
            // 捕获 null 值异常，转换为业务异常并提供详细的调试信息
            throw new BloomFilterException(
                    "BloomFilter key resolved to null from [" + source + "] for expression [" + keyExpression + "]. " +
                            "Null key is not allowed.",
                    e
            );
        }
    }

    /**
     * 处理布隆过滤器判定元素不存在的场景
     * <p>
     * 当布隆过滤器确定元素不存在时，根据配置采取两种处理策略：
     * 1. 如果 throwOnAbsent=true，则抛出异常，中断方法执行
     * 2. 如果 throwOnAbsent=false，则解析并返回 fallbackValue 降级值
     *
     * @param joinPoint    AOP 连接点，用于在降级时传递给 resolveFallback 方法
     * @param bloomFilter  布隆过滤器注解对象，包含 throwOnAbsent、message、fallbackValue 等配置
     * @return 当不抛出异常时，返回降级表达式解析的结果；否则不返回（直接抛异常）
     * @throws BloomFilterException 当 throwOnAbsent=true 时抛出，异常信息来自 bloomFilter.message()
     */
    private Object onAbsent(ProceedingJoinPoint joinPoint, BloomFilter bloomFilter) {
        // 配置为抛出异常时，直接中断执行
        if (bloomFilter.throwOnAbsent()) {
            throw new BloomFilterException(bloomFilter.message());
        }
        // 否则解析并返回降级值
        return resolveFallback(bloomFilter.fallbackValue(), joinPoint);
    }

    /**
     * 在方法执行成功后将 key 回填到布隆过滤器
     * <p>
     * 当配置了 writeBackOnSuccess=true 且方法返回值不为 null 时，
     * 将 key 添加到布隆过滤器中，实现自动刷新功能。
     * 回填失败时仅记录警告日志，不影响主流程的正常执行。
     *
     * @param filter       布隆过滤器实例，用于执行 add 操作
     * @param filterName   过滤器名称，用于日志输出
     * @param key          需要回填的 key 字符串
     * @param result       方法执行结果，为 null 时不执行回填
     * @param bloomFilter  布隆过滤器注解对象，包含 writeBackOnSuccess 配置
     */
    private void writeBackIfNecessary(CascadeBloomFilter<Object> filter,
                                      String filterName,
                                      String key,
                                      Object result,
                                      BloomFilter bloomFilter) {
        // 检查是否需要回填：未开启或结果为 null 则跳过
        if (!bloomFilter.writeBackOnSuccess() || result == null) {
            return;
        }
        try {
            // 执行 key 回填操作
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
        Expression expression = expressionCache.get(expressionString);
        if (expression != null) {
            cacheHits.incrementAndGet();
            return expression;
        }

        cacheMisses.incrementAndGet();
        Expression parsedExpression = expressionParser.parseExpression(expressionString);
        return expressionCache.putIfAbsent(expressionString, parsedExpression);
    }

    private void onExpressionEvicted(String expression, int sizeAfterPut, int maxSize) {
        evictions.incrementAndGet();
        log.info("[cascade-bloom] Expression cache full (size={}), evicting oldest expression [{}], maxExpressionCacheSize={}",
                sizeAfterPut, expression, maxSize);
    }

    /**
     * 清空表达式缓存
     * <p>
     * 主要用于测试或特殊场景，生产环境一般不需要调用。
     */
    public void clearExpressionCache() {
        int previousSize = expressionCache.size();
        expressionCache.clear();
        cacheHits.set(0);
        cacheMisses.set(0);
        evictions.set(0);
        log.info("[cascade-bloom] SpEL expression cache cleared. previousSize={}", previousSize);
    }

    /**
     * 获取表达式缓存大小
     *
     * @return 缓存中的表达式数量
     */
    public int getExpressionCacheSize() {
        return expressionCache.size();
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

    @FunctionalInterface
    private interface ExpressionEvictionListener {
        void onEvicted(String expression, int sizeAfterPut, int maxSize);
    }

    private static final class ExpressionLruCache {
        private final Object lock = new Object();
        private final int maxSize;
        private final ExpressionEvictionListener evictionListener;
        private final LinkedHashMap<String, Expression> cache;

        private ExpressionLruCache(int maxSize, ExpressionEvictionListener evictionListener) {
            this.maxSize = maxSize;
            this.evictionListener = evictionListener;
            this.cache = new LinkedHashMap<>(INITIAL_CAPACITY, LOAD_FACTOR, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Expression> eldest) {
                    boolean shouldRemove = size() > ExpressionLruCache.this.maxSize;
                    if (shouldRemove) {
                        ExpressionLruCache.this.evictionListener.onEvicted(
                                eldest.getKey(),
                                size(),
                                ExpressionLruCache.this.maxSize
                        );
                    }
                    return shouldRemove;
                }
            };
        }

        private Expression get(String expressionString) {
            synchronized (lock) {
                return cache.get(expressionString);
            }
        }

        private Expression putIfAbsent(String expressionString, Expression expression) {
            synchronized (lock) {
                Expression existing = cache.get(expressionString);
                if (existing != null) {
                    return existing;
                }
                cache.put(expressionString, expression);
                return expression;
            }
        }

        private int size() {
            synchronized (lock) {
                return cache.size();
            }
        }

        private void clear() {
            synchronized (lock) {
                cache.clear();
            }
        }
    }

}
