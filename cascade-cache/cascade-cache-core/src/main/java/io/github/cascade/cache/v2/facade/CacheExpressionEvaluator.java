package io.github.cascade.cache.v2.facade;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.cascade.cache.v2.common.exception.CacheConfigurationException;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.expression.AccessException;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.PropertyAccessor;
import org.springframework.expression.TypedValue;
import org.springframework.expression.spel.SpelNode;
import org.springframework.expression.spel.ast.OpEQ;
import org.springframework.expression.spel.ast.OpNE;
import org.springframework.expression.spel.ast.OpPlus;
import org.springframework.expression.spel.ast.OperatorMatches;
import org.springframework.expression.spel.ast.StringLiteral;
import org.springframework.expression.spel.ast.VariableReference;
import org.springframework.expression.spel.standard.SpelExpression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.util.StringUtils;

import java.beans.PropertyDescriptor;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * @author lionel lionelk@163.com
 * =============================
 * Date: 2025/10/29
 * Time: 17:10
 * =============================
 * <p>
 * 缓存表达式求值器 - 专门处理SpEL表达式的解析和求值
 * <p>
 * 设计原则：
 * 1. 性能优先：缓存编译后的表达式，避免重复解析
 * 2. 线程安全：使用Caffeine Cache (线程安全) 缓存表达式
 * 3. 错误处理：优雅处理表达式解析和求值异常
 * 4. 功能完整：支持变量、方法调用、条件判断等
 * 5. 资源管理：有限的缓存容量，避免内存溢出
 *
 * <p>
 * 使用场景：
 * - @Cacheable注解的key和condition表达式求值
 * - @CachePut注解的condition表达式求值
 * - @CacheEvict注解的key表达式求值
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
    private static final Cache<String, ExpressionBindingPlan> EXPRESSION_BINDING_PLAN_CACHE = Caffeine.newBuilder()
            .maximumSize(MAX_CACHE_SIZE)
            .expireAfterAccess(2, TimeUnit.HOURS)
            .build();
    private static final int DTO_PROPERTY_CACHE_MAX_SIZE = 512;
    private static final Cache<Class<?>, Map<String, Method>> DTO_READABLE_PROPERTIES = Caffeine.newBuilder()
            .maximumSize(DTO_PROPERTY_CACHE_MAX_SIZE)
            .expireAfterAccess(2, TimeUnit.HOURS)
            .build();
    private static final Cache<AccessorPathCacheKey, AccessorPathAccessor> ACCESSOR_PATH_CACHE = Caffeine.newBuilder()
            .maximumSize(2_048)
            .expireAfterAccess(2, TimeUnit.HOURS)
            .build();
    static final int PROPERTY_READ_MEMO_MAX_ENTRIES = 256;
    private static final Set<String> RESERVED_VARIABLE_NAMES = Set.of("args", "result", "target", "method", "methodName", "joinPoint");
    private static final Set<String> BUILTIN_VARIABLE_NAMES = Set.of("this", "root");

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
            ExpressionBindingPlan bindingPlan = getOrCompileBindingPlan(expressionString, expression);

            // 创建求值上下文
            EvaluationContext context = createEvaluationContext(joinPoint, result, bindingPlan);

            // 执行求值
            Object value = expression.getValue(context);

            LOGGER.debug("SpEL表达式求值成功: expression={}, result={}", expressionString, value);
            return value;

        } catch (CacheConfigurationException e) {
            throw e;
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
        if (!StringUtils.hasText(expressionString)) {
            return null;
        }

        try {
            Expression expression = getOrCompileExpression(expressionString);
            ExpressionBindingPlan bindingPlan = getOrCompileBindingPlan(expressionString, expression);
            EvaluationContext context = createEvaluationContext(joinPoint, result, bindingPlan);
            return expression.getValue(context, desiredType);

        } catch (CacheConfigurationException e) {
            throw e;
        } catch (Exception e) {
            LOGGER.warn("SpEL表达式类型转换失败: expression={}, desiredType={}, error={}",
                    expressionString, desiredType.getSimpleName(), e.getMessage());
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
        return evaluateBoolean(expressionString, joinPoint, result, true);
    }

    public boolean evaluateBoolean(String expressionString,
                                   JoinPoint joinPoint,
                                   Object result,
                                   boolean defaultOnError) {
        if (!StringUtils.hasText(expressionString)) {
            return defaultOnError;
        }
        Boolean value = evaluate(expressionString, joinPoint, result, Boolean.class);
        return value != null ? value : defaultOnError;
    }

    /**
     * 在无JoinPoint场景（如方法快照回放）下求值布尔表达式。
     */
    public boolean evaluateBoolean(String expressionString,
                                   Method method,
                                   Object target,
                                   Object[] args,
                                   Object result) {
        return evaluateBoolean(expressionString, method, target, args, result, true);
    }

    public boolean evaluateBoolean(String expressionString,
                                   Method method,
                                   Object target,
                                   Object[] args,
                                   Object result,
                                   boolean defaultOnError) {
        if (!StringUtils.hasText(expressionString)) {
            return defaultOnError;
        }
        try {
            Expression expression = getOrCompileExpression(expressionString);
            ExpressionBindingPlan bindingPlan = getOrCompileBindingPlan(expressionString, expression);
            EvaluationContext context = createEvaluationContext(method, target, args, result, bindingPlan);
            Boolean value = expression.getValue(context, Boolean.class);
            return value != null ? value : defaultOnError;
        } catch (CacheConfigurationException e) {
            throw e;
        } catch (Exception e) {
            LOGGER.warn("SpEL表达式求值失败: expression={}, error={}", expressionString, e.getMessage());
            return defaultOnError;
        }
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
            Expression parsed = EXPRESSION_PARSER.parseExpression(expr);
            validateMethodVariableUsage(expr, parsed);
            return parsed;
        });
    }

    private ExpressionBindingPlan getOrCompileBindingPlan(String expressionString, Expression expression) {
        return EXPRESSION_BINDING_PLAN_CACHE.get(expressionString, ignored -> {
            try {
                if (expression instanceof SpelExpression spelExpression) {
                    return ExpressionBindingPlan.fromSpelAst(spelExpression.getAST());
                }
            } catch (Exception e) {
                LOGGER.debug("构建SpEL绑定计划失败，将降级为最小绑定: expression={}, error={}",
                        expressionString, e.getMessage());
            }
            return ExpressionBindingPlan.empty();
        });
    }

    private static void validateMethodVariableUsage(String expressionString, Expression expression) {
        if (!(expression instanceof SpelExpression spelExpression)) {
            return;
        }
        if (!containsLegacyMethodStringPattern(spelExpression.getAST())) {
            return;
        }
        throw new CacheConfigurationException(
                "expression",
                expressionString,
                "检测到将#method按字符串使用。#method语义为Method对象，请改用#methodName进行字符串比较。",
                null
        );
    }

    private static boolean containsLegacyMethodStringPattern(SpelNode node) {
        if (node == null) {
            return false;
        }
        if (isMethodComparedOrConcatenatedWithString(node)) {
            return true;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            if (containsLegacyMethodStringPattern(node.getChild(i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isMethodComparedOrConcatenatedWithString(SpelNode node) {
        if (!(node instanceof OpEQ
                || node instanceof OpNE
                || node instanceof OpPlus
                || node instanceof OperatorMatches)) {
            return false;
        }
        if (node.getChildCount() != 2) {
            return false;
        }
        SpelNode left = node.getChild(0);
        SpelNode right = node.getChild(1);
        return (isMethodVariableReference(left) && isStringLiteralNode(right))
                || (isMethodVariableReference(right) && isStringLiteralNode(left));
    }

    private static boolean isMethodVariableReference(SpelNode node) {
        if (!(node instanceof VariableReference variableReference)) {
            return false;
        }
        return "method".equals(normalizeVariableReference(variableReference));
    }

    private static boolean isStringLiteralNode(SpelNode node) {
        return node instanceof StringLiteral;
    }

    /**
     * 创建求值上下文
     *
     * @param joinPoint 切点信息
     * @param result    方法执行结果
     * @return 求值上下文
     */
    private static EvaluationContext createEvaluationContext(JoinPoint joinPoint,
                                                             Object result,
                                                             ExpressionBindingPlan bindingPlan) {
        Method method = null;
        Object target = null;
        Object[] args = null;
        if (joinPoint != null) {
            target = joinPoint.getTarget();
            args = joinPoint.getArgs();
            if (joinPoint.getSignature() instanceof MethodSignature methodSignature) {
                method = methodSignature.getMethod();
            }
        }
        StandardEvaluationContext context = createEvaluationContext(method, target, args, result, bindingPlan);
        bindVariable(context, new LinkedHashSet<>(), "joinPoint", joinPoint);
        if (joinPoint != null && joinPoint.getSignature() != null && context.lookupVariable("methodName") == null) {
            context.setVariable("methodName", joinPoint.getSignature().getName());
        }
        return context;
    }

    private static StandardEvaluationContext createEvaluationContext(Method method,
                                                                     Object target,
                                                                     Object[] args,
                                                                     Object result,
                                                                     ExpressionBindingPlan bindingPlan) {
        StandardEvaluationContext context = new StandardEvaluationContext();
        installMemoizingPropertyAccessor(context);
        Object[] safeArgs = args != null ? args : new Object[0];
        LinkedHashSet<String> explicitVariableNames = new LinkedHashSet<>();

        bindIndexedArguments(context, safeArgs, explicitVariableNames);
        bindNamedArguments(context, method, safeArgs, explicitVariableNames);
        bindReservedVariables(context, target, method, safeArgs, result, explicitVariableNames);
        applyBindingPlan(context, bindingPlan, safeArgs, explicitVariableNames);

        if (target != null) {
            context.setRootObject(target);
        }
        return context;
    }

    private static void installMemoizingPropertyAccessor(StandardEvaluationContext context) {
        List<PropertyAccessor> accessors = new ArrayList<>();
        accessors.add(new MemoizingBeanPropertyAccessor());
        for (PropertyAccessor accessor : context.getPropertyAccessors()) {
            if (!(accessor instanceof MemoizingBeanPropertyAccessor)) {
                accessors.add(accessor);
            }
        }
        context.setPropertyAccessors(accessors);
    }

    private static void bindIndexedArguments(StandardEvaluationContext context,
                                             Object[] args,
                                             Set<String> explicitVariableNames) {
        for (int i = 0; i < args.length; i++) {
            Object arg = args[i];
            // 兼容 Spring 常见写法，避免必须依赖 -parameters 才能用 #dto / #id 这种参数名。
            bindVariable(context, explicitVariableNames, "p" + i, arg);
            bindVariable(context, explicitVariableNames, "a" + i, arg);
            bindVariable(context, explicitVariableNames, "arg" + i, arg);
        }
    }

    private static void bindNamedArguments(StandardEvaluationContext context,
                                           Method method,
                                           Object[] args,
                                           Set<String> explicitVariableNames) {
        if (method == null) {
            return;
        }
        String[] parameterNames = PARAMETER_NAME_DISCOVERER.getParameterNames(method);
        if (parameterNames == null) {
            return;
        }
        for (int i = 0; i < Math.min(parameterNames.length, args.length); i++) {
            bindVariable(context, explicitVariableNames, parameterNames[i], args[i]);
        }
    }

    private static void bindReservedVariables(StandardEvaluationContext context,
                                              Object target,
                                              Method method,
                                              Object[] args,
                                              Object result,
                                              Set<String> explicitVariableNames) {
        bindVariable(context, explicitVariableNames, "target", target);
        bindVariable(context, explicitVariableNames, "method", method);
        bindVariable(context, explicitVariableNames, "methodName", method != null ? method.getName() : null);
        bindVariable(context, explicitVariableNames, "args", args);
        bindVariable(context, explicitVariableNames, "result", result);
        bindVariable(context, explicitVariableNames, "joinPoint", null);
    }

    private static void applyBindingPlan(StandardEvaluationContext context,
                                         ExpressionBindingPlan bindingPlan,
                                         Object[] args,
                                         Set<String> explicitVariableNames) {
        if (bindingPlan == null || bindingPlan.referencedVariables().isEmpty()) {
            return;
        }
        Object singleDtoCandidate = resolveSingleDtoCandidate(args);
        if (singleDtoCandidate == null) {
            return;
        }

        Class<?> dtoType = singleDtoCandidate.getClass();
        Map<String, AccessorResolution> localMemo = new HashMap<>();
        for (String variableName : bindingPlan.referencedVariables()) {
            if (explicitVariableNames.contains(variableName)
                    || RESERVED_VARIABLE_NAMES.contains(variableName)
                    || BUILTIN_VARIABLE_NAMES.contains(variableName)) {
                continue;
            }
            AccessorResolution resolution = resolvePathValue(singleDtoCandidate, dtoType, variableName, localMemo);
            if (resolution.resolvable()) {
                bindVariable(context, explicitVariableNames, variableName, resolution.value());
            }
        }
    }

    private static void bindVariable(StandardEvaluationContext context,
                                     Set<String> explicitVariableNames,
                                     String name,
                                     Object value) {
        context.setVariable(name, value);
        explicitVariableNames.add(name);
    }

    private static Object resolveSingleDtoCandidate(Object[] args) {
        if (args == null || args.length != 1 || args[0] == null) {
            return null;
        }
        Object candidate = args[0];
        if (BeanUtils.isSimpleValueType(candidate.getClass())) {
            return null;
        }
        return candidate;
    }

    private static AccessorResolution resolvePathValue(Object root,
                                                       Class<?> rootType,
                                                       String path,
                                                       Map<String, AccessorResolution> localMemo) {
        AccessorResolution memoized = localMemo.get(path);
        if (memoized != null) {
            return memoized;
        }
        AccessorPathCacheKey cacheKey = new AccessorPathCacheKey(rootType, path);
        AccessorPathAccessor accessor = ACCESSOR_PATH_CACHE.get(cacheKey,
                key -> compileAccessorPath(key.rootType(), key.path()));
        AccessorResolution resolved = accessor.resolve(root);
        localMemo.put(path, resolved);
        return resolved;
    }

    private static AccessorPathAccessor compileAccessorPath(Class<?> rootType, String path) {
        if (!StringUtils.hasText(path)) {
            return AccessorPathAccessor.unresolvable(path, "empty path");
        }
        String[] segments = path.split("\\.");
        if (segments.length == 0) {
            return AccessorPathAccessor.unresolvable(path, "no segments");
        }

        AccessorInvoker[] chain = new AccessorInvoker[segments.length];
        Class<?> currentType = rootType;
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            Method readMethod = resolveReadableProperties(currentType).get(segment);
            if (readMethod == null) {
                return AccessorPathAccessor.unresolvable(path,
                        "missing getter: type=" + currentType.getName() + ", property=" + segment);
            }
            AccessorInvoker invoker = compileAccessorInvoker(readMethod);
            if (invoker == null) {
                return AccessorPathAccessor.unresolvable(path,
                        "getter not invokable: type=" + currentType.getName() + ", property=" + segment);
            }
            chain[i] = invoker;
            currentType = boxedType(readMethod.getReturnType());
        }
        return AccessorPathAccessor.resolvable(path, chain);
    }

    private static AccessorInvoker compileAccessorInvoker(Method readMethod) {
        MethodHandle methodHandle = tryBuildMethodHandle(readMethod);
        if (methodHandle != null) {
            return AccessorInvoker.methodHandle(methodHandle);
        }
        try {
            readMethod.trySetAccessible();
        } catch (RuntimeException ignored) {
            // 跨模块场景可能抛出 InaccessibleObjectException，继续尝试反射常规访问
        }
        return AccessorInvoker.reflective(readMethod);
    }

    private static MethodHandle tryBuildMethodHandle(Method readMethod) {
        MethodType adaptedType = MethodType.methodType(Object.class, Object.class);
        try {
            return MethodHandles.privateLookupIn(readMethod.getDeclaringClass(), MethodHandles.lookup())
                    .unreflect(readMethod)
                    .asType(adaptedType);
        } catch (Throwable ignored) {
        }
        try {
            return MethodHandles.publicLookup()
                    .unreflect(readMethod)
                    .asType(adaptedType);
        } catch (Throwable ignored) {
        }
        try {
            return MethodHandles.lookup()
                    .unreflect(readMethod)
                    .asType(adaptedType);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isSupportedPropertyReadTarget(Object target, String property) {
        if (target == null || !StringUtils.hasText(property)) {
            return false;
        }
        Class<?> targetType = target.getClass();
        if (BeanUtils.isSimpleValueType(targetType)) {
            return false;
        }
        return !(target instanceof Map<?, ?>) && !(target instanceof Class<?>) && !targetType.isArray();
    }

    private static Class<?> boxedType(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == boolean.class) {
            return Boolean.class;
        }
        if (type == byte.class) {
            return Byte.class;
        }
        if (type == short.class) {
            return Short.class;
        }
        if (type == char.class) {
            return Character.class;
        }
        if (type == int.class) {
            return Integer.class;
        }
        if (type == long.class) {
            return Long.class;
        }
        if (type == float.class) {
            return Float.class;
        }
        if (type == double.class) {
            return Double.class;
        }
        return Void.class;
    }

    private static Map<String, Method> resolveReadableProperties(Class<?> dtoType) {
        return DTO_READABLE_PROPERTIES.get(dtoType, CacheExpressionEvaluator::scanReadableProperties);
    }

    private static Map<String, Method> scanReadableProperties(Class<?> dtoType) {
        Map<String, Method> readable = new HashMap<>();
        for (PropertyDescriptor descriptor : BeanUtils.getPropertyDescriptors(dtoType)) {
            Method readMethod = descriptor.getReadMethod();
            String propertyName = descriptor.getName();
            if (readMethod == null || "class".equals(propertyName)) {
                continue;
            }
            readable.put(propertyName, readMethod);
        }
        return Map.copyOf(readable);
    }

    private static String normalizeVariableReference(VariableReference variableReference) {
        String ast = variableReference.toStringAST();
        if (!StringUtils.hasText(ast)) {
            return null;
        }
        if (ast.charAt(0) == '#') {
            return ast.substring(1);
        }
        return ast;
    }

    private static final class ExpressionBindingPlan {
        private final Set<String> referencedVariables;

        private ExpressionBindingPlan(Set<String> referencedVariables) {
            this.referencedVariables = referencedVariables;
        }

        static ExpressionBindingPlan empty() {
            return new ExpressionBindingPlan(Set.of());
        }

        static ExpressionBindingPlan fromSpelAst(SpelNode root) {
            if (root == null) {
                return empty();
            }
            LinkedHashSet<String> variables = new LinkedHashSet<>();
            collect(root, variables);
            return new ExpressionBindingPlan(Set.copyOf(variables));
        }

        Set<String> referencedVariables() {
            return referencedVariables;
        }

        private static void collect(SpelNode node, Set<String> variables) {
            if (node == null) {
                return;
            }
            if (node instanceof VariableReference variableReference) {
                String variableName = normalizeVariableReference(variableReference);
                if (StringUtils.hasText(variableName)
                        && !BUILTIN_VARIABLE_NAMES.contains(variableName)
                        && !RESERVED_VARIABLE_NAMES.contains(variableName)) {
                    variables.add(variableName);
                }
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                collect(node.getChild(i), variables);
            }
        }
    }

    private record AccessorPathCacheKey(Class<?> rootType, String path) {
    }

    private static final class AccessorPathAccessor {
        private final String path;
        private final AccessorInvoker[] chain;
        private final boolean resolvable;
        private final String unresolvableReason;

        private AccessorPathAccessor(String path,
                                     AccessorInvoker[] chain,
                                     boolean resolvable,
                                     String unresolvableReason) {
            this.path = path;
            this.chain = chain;
            this.resolvable = resolvable;
            this.unresolvableReason = unresolvableReason;
        }

        static AccessorPathAccessor resolvable(String path, AccessorInvoker[] chain) {
            return new AccessorPathAccessor(path, chain, true, null);
        }

        static AccessorPathAccessor unresolvable(String path, String reason) {
            return new AccessorPathAccessor(path, new AccessorInvoker[0], false, reason);
        }

        AccessorResolution resolve(Object root) {
            if (!resolvable) {
                return AccessorResolution.unresolvable();
            }
            Object current = root;
            try {
                for (AccessorInvoker accessorInvoker : chain) {
                    if (current == null) {
                        return AccessorResolution.resolved(null);
                    }
                    current = accessorInvoker.read(current);
                }
                return AccessorResolution.resolved(current);
            } catch (Throwable e) {
                LOGGER.debug("执行路径访问器失败: path={}, reason={}, error={}",
                        path, unresolvableReason, e.getMessage());
                return AccessorResolution.unresolvable();
            }
        }

        boolean resolvable() {
            return resolvable;
        }
    }

    private static final class AccessorInvoker {
        private final MethodHandle methodHandle;
        private final Method reflectiveMethod;

        private AccessorInvoker(MethodHandle methodHandle, Method reflectiveMethod) {
            this.methodHandle = methodHandle;
            this.reflectiveMethod = reflectiveMethod;
        }

        static AccessorInvoker methodHandle(MethodHandle methodHandle) {
            return new AccessorInvoker(methodHandle, null);
        }

        static AccessorInvoker reflective(Method method) {
            return new AccessorInvoker(null, method);
        }

        Object read(Object target) throws Throwable {
            if (methodHandle != null) {
                return methodHandle.invoke(target);
            }
            try {
                return reflectiveMethod.invoke(target);
            } catch (InvocationTargetException e) {
                throw e.getTargetException();
            }
        }
    }

    private record AccessorResolution(boolean resolvable, Object value) {
        static AccessorResolution resolved(Object value) {
            return new AccessorResolution(true, value);
        }

        static AccessorResolution unresolvable() {
            return new AccessorResolution(false, null);
        }
    }

    private static final class MemoizingBeanPropertyAccessor implements PropertyAccessor {
        private final Map<PropertyReadMemoKey, AccessorResolution> localPropertyReadMemo =
                new LinkedHashMap<>(64, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<PropertyReadMemoKey, AccessorResolution> eldest) {
                        return size() > PROPERTY_READ_MEMO_MAX_ENTRIES;
                    }
                };

        @Override
        public Class<?>[] getSpecificTargetClasses() {
            return null;
        }

        @Override
        public boolean canRead(EvaluationContext context, Object target, String name) {
            if (!isSupportedPropertyReadTarget(target, name)) {
                return false;
            }
            AccessorPathAccessor accessor = ACCESSOR_PATH_CACHE.get(
                    new AccessorPathCacheKey(target.getClass(), name),
                    key -> compileAccessorPath(key.rootType(), key.path())
            );
            return accessor.resolvable();
        }

        @Override
        public TypedValue read(EvaluationContext context, Object target, String name) throws AccessException {
            if (!isSupportedPropertyReadTarget(target, name)) {
                throw new AccessException("unsupported target for property read: " + name);
            }
            AccessorResolution resolution = resolvePropertyWithMemo(target, name);
            if (!resolution.resolvable()) {
                throw new AccessException("property not resolvable: " + name);
            }
            return new TypedValue(resolution.value());
        }

        @Override
        public boolean canWrite(EvaluationContext context, Object target, String name) {
            return false;
        }

        @Override
        public void write(EvaluationContext context, Object target, String name, Object newValue) throws AccessException {
            throw new AccessException("memoizing accessor is read-only");
        }

        private AccessorResolution resolvePropertyWithMemo(Object target, String property) {
            PropertyReadMemoKey memoKey = new PropertyReadMemoKey(target, property);
            AccessorResolution cached = localPropertyReadMemo.get(memoKey);
            if (cached != null) {
                return cached;
            }
            AccessorPathAccessor accessor = ACCESSOR_PATH_CACHE.get(
                    new AccessorPathCacheKey(target.getClass(), property),
                    key -> compileAccessorPath(key.rootType(), key.path())
            );
            AccessorResolution resolved = accessor.resolve(target);
            localPropertyReadMemo.put(memoKey, resolved);
            return resolved;
        }
    }

    private static final class PropertyReadMemoKey {
        private final Object target;
        private final String property;
        private final int hash;

        private PropertyReadMemoKey(Object target, String property) {
            this.target = target;
            this.property = property;
            this.hash = System.identityHashCode(target) * 31 + property.hashCode();
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof PropertyReadMemoKey that)) {
                return false;
            }
            return this.target == that.target && this.property.equals(that.property);
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    /**
     * 清理表达式缓存
     */
    public static void clearCache() {
        long count = EXPRESSION_CACHE.estimatedSize();
        EXPRESSION_CACHE.invalidateAll();
        EXPRESSION_BINDING_PLAN_CACHE.invalidateAll();
        DTO_READABLE_PROPERTIES.invalidateAll();
        ACCESSOR_PATH_CACHE.invalidateAll();
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
