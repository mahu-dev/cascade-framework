package cc.coderm.cascade.idempotent.aspect;

import cc.coderm.cascade.idempotent.annotation.Idempotent;
import cc.coderm.cascade.idempotent.config.CascadeIdempotentProperties;
import cc.coderm.cascade.idempotent.exception.IdempotentConflictException;
import cc.coderm.cascade.idempotent.executor.IdempotentExecutor;
import cc.coderm.cascade.idempotent.key.IdempotentKeyHasher;
import cc.coderm.cascade.idempotent.model.ConflictStrategy;
import cc.coderm.cascade.idempotent.model.IdempotentContext;
import cc.coderm.cascade.idempotent.model.IdempotentRecord;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.context.expression.BeanFactoryResolver;
import org.springframework.core.BridgeMethodResolver;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * {@link Idempotent} 注解的 AOP 拦截切面。
 */
@Aspect
public class IdempotentAspect {

    private static final String IDEMPOTENT_HEADER_VARIABLE = "idempotentHeader";

    private final IdempotentExecutor executor;
    private final CascadeIdempotentProperties properties;
    private final BeanFactory beanFactory;
    private final IdempotentKeyHasher idempotentKeyHasher;
    private final IdempotentHeaderResolver idempotentHeaderResolver;

    private final ExpressionParser spelParser = new SpelExpressionParser();
    private final DefaultParameterNameDiscoverer nameDiscoverer = new DefaultParameterNameDiscoverer();
    private final ConcurrentMap<String, Expression> keyExpressionCache = new ConcurrentHashMap<>();

    public IdempotentAspect(IdempotentExecutor executor,
                            CascadeIdempotentProperties properties,
                            BeanFactory beanFactory,
                            IdempotentKeyHasher idempotentKeyHasher) {
        this(executor, properties, beanFactory, idempotentKeyHasher, IdempotentHeaderResolver.noop());
    }

    public IdempotentAspect(IdempotentExecutor executor,
                            CascadeIdempotentProperties properties,
                            BeanFactory beanFactory,
                            IdempotentKeyHasher idempotentKeyHasher,
                            IdempotentHeaderResolver idempotentHeaderResolver) {
        this.executor = executor;
        this.properties = properties;
        this.beanFactory = beanFactory;
        this.idempotentKeyHasher = idempotentKeyHasher;
        this.idempotentHeaderResolver = Objects.requireNonNull(idempotentHeaderResolver,
                "idempotentHeaderResolver");
    }

    @Around("@annotation(idempotent)")
    public Object around(ProceedingJoinPoint pjp, Idempotent idempotent) throws Throwable {
        MethodSignature sig = (MethodSignature) pjp.getSignature();
        Method method = resolveMostSpecificMethod(sig.getMethod(), pjp.getTarget());
        validateFallbackConfiguration(idempotent, method);

        // 1. 解析幂等 key
        String resolvedKey = resolveKey(idempotent, method, pjp.getArgs(), pjp.getTarget());

        // 2. 构建上下文
        long ttlMs = idempotent.ttlUnit().toMillis(idempotent.ttl());
        IdempotentContext context = IdempotentContext.builder()
                .idempotentKey(resolvedKey)
                .ttlMs(ttlMs)
                .scene(idempotent.scene())
                .deleteOnSuccess(idempotent.deleteOnSuccess())
                .deleteOnFailure(idempotent.deleteOnFailure())
                .conflictStrategy(idempotent.conflictStrategy())
                .waitTimeoutMs(idempotent.waitTimeoutMs())
                .targetMethod(method)
                .returnType(method.getGenericReturnType())
                .build();

        // 3. 执行（业务逻辑封装为 Callable）
        try {
            return executor.execute(context, pjp::proceed);

        } catch (IdempotentConflictException ex) {
            if (idempotent.conflictStrategy() == ConflictStrategy.FALLBACK) {
                return invokeFallback(pjp, method, idempotent.fallbackMethod(), ex.getRecord());
            }
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting idempotent completion.", ex);
        }
    }

    private void validateFallbackConfiguration(Idempotent idempotent, Method method) {
        boolean fallbackMethodConfigured = StringUtils.hasText(idempotent.fallbackMethod());

        if (idempotent.conflictStrategy() == ConflictStrategy.FALLBACK && !fallbackMethodConfigured) {
            throw new IllegalStateException(
                    "Invalid @Idempotent configuration on method '"
                            + method.toGenericString()
                            + "': conflictStrategy=FALLBACK requires fallbackMethod.");
        }

        if (idempotent.conflictStrategy() != ConflictStrategy.FALLBACK && fallbackMethodConfigured) {
            throw new IllegalStateException(
                    "Invalid @Idempotent configuration on method '"
                            + method.toGenericString()
                            + "': fallbackMethod is only allowed when conflictStrategy=FALLBACK.");
        }
    }

    // ─────────────── helpers ───────────────

    private String resolveKey(Idempotent idempotent, Method method, Object[] args, Object target) {
        String prefix = properties.getRedisKeyPrefix()
                + idempotent.scene() + ":";

        String keyExpr = idempotent.key();
        if (keyExpr.isEmpty()) {
            // 默认 key：类全限定名#方法签名:参数（稳定 JSON）MD5
            String argsHash = idempotentKeyHasher.hash(method, args);
            return prefix + defaultMethodNamespace(method) + ":" + argsHash;
        }

        MethodBasedEvaluationContext ctx = new LazyHeaderEvaluationContext(
                target,
                method,
                args,
                nameDiscoverer,
                properties.getIdempotentHeaderName(),
                idempotentHeaderResolver);
        ctx.setBeanResolver(new BeanFactoryResolver(beanFactory));
        Expression expression = keyExpressionCache.computeIfAbsent(keyExpr, spelParser::parseExpression);
        String evaluated = expression.getValue(ctx, String.class);
        if (!StringUtils.hasText(evaluated)) {
            throw new IllegalArgumentException("Idempotent key expression evaluated to blank");
        }
        return prefix + evaluated.trim();
    }

    private static Method resolveMostSpecificMethod(Method method, Object target) {
        Class<?> targetType = target != null ? target.getClass() : method.getDeclaringClass();
        Method specificMethod = AopUtils.getMostSpecificMethod(method, targetType);
        return BridgeMethodResolver.findBridgedMethod(specificMethod);
    }

    private static String defaultMethodNamespace(Method method) {
        StringBuilder builder = new StringBuilder(128);
        builder.append(method.getDeclaringClass().getName())
                .append('#')
                .append(method.getName())
                .append('(');
        Class<?>[] parameterTypes = method.getParameterTypes();
        for (int i = 0; i < parameterTypes.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(parameterTypes[i].getTypeName());
        }
        return builder.append(')').toString();
    }

    private static Object invokeFallback(ProceedingJoinPoint pjp, Method original,
                                         String fallbackName, IdempotentRecord conflictRecord) throws Throwable {
        Class<?> targetClass = pjp.getTarget().getClass();
        Object[] originalArgs = pjp.getArgs();

        // 先尝试带 IdempotentRecord 参数的签名
        if (conflictRecord != null) {
            Class<?>[] types = appendType(original.getParameterTypes(), IdempotentRecord.class);
            Object[] args = appendArg(originalArgs, conflictRecord);
            Method fb = findFallbackMethod(targetClass, fallbackName, types);
            if (fb != null) {
                return invokeFallbackMethod(pjp.getTarget(), fb, args);
            }
        }

        Method fb = findFallbackMethod(targetClass, fallbackName, original.getParameterTypes());
        if (fb == null) {
            throw new IllegalStateException(
                    "Fallback method '" + fallbackName + "' not found on type '"
                            + targetClass.getName()
                            + "' with signature " + buildFallbackSignature(fallbackName, original.getParameterTypes())
                            + " (or same signature plus IdempotentRecord).");
        }
        return invokeFallbackMethod(pjp.getTarget(), fb, originalArgs);
    }

    private static Method findFallbackMethod(Class<?> targetClass, String fallbackName, Class<?>[] parameterTypes) {
        Method method = ReflectionUtils.findMethod(targetClass, fallbackName, parameterTypes);
        if (method != null) {
            ReflectionUtils.makeAccessible(method);
        }
        return method;
    }

    private static Object invokeFallbackMethod(Object target, Method fallbackMethod, Object[] args) throws Throwable {
        try {
            return fallbackMethod.invoke(target, args);
        } catch (InvocationTargetException ex) {
            throw ex.getTargetException();
        } catch (IllegalAccessException ex) {
            throw new IllegalStateException(
                    "Cannot access fallback method '" + fallbackMethod.toGenericString() + "'.", ex);
        }
    }

    private static String buildFallbackSignature(String methodName, Class<?>[] parameterTypes) {
        StringBuilder builder = new StringBuilder(methodName).append('(');
        for (int i = 0; i < parameterTypes.length; i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(parameterTypes[i].getSimpleName());
        }
        return builder.append(')').toString();
    }

    private static Class<?>[] appendType(Class<?>[] types, Class<?> extra) {
        Class<?>[] result = new Class<?>[types.length + 1];
        System.arraycopy(types, 0, result, 0, types.length);
        result[types.length] = extra;
        return result;
    }

    private static Object[] appendArg(Object[] args, Object extra) {
        Object[] result = new Object[args.length + 1];
        System.arraycopy(args, 0, result, 0, args.length);
        result[args.length] = extra;
        return result;
    }

    private static final class LazyHeaderEvaluationContext extends MethodBasedEvaluationContext {
        private final String headerName;
        private final IdempotentHeaderResolver headerResolver;
        private boolean headerResolved;
        private Object headerValue;

        private LazyHeaderEvaluationContext(Object target,
                                            Method method,
                                            Object[] args,
                                            DefaultParameterNameDiscoverer nameDiscoverer,
                                            String headerName,
                                            IdempotentHeaderResolver headerResolver) {
            super(target, method, args, nameDiscoverer);
            this.headerName = headerName;
            this.headerResolver = headerResolver;
        }

        @Override
        public Object lookupVariable(String name) {
            if (!IDEMPOTENT_HEADER_VARIABLE.equals(name)) {
                return super.lookupVariable(name);
            }
            if (!headerResolved) {
                headerValue = headerResolver.resolve(headerName);
                headerResolved = true;
                super.setVariable(IDEMPOTENT_HEADER_VARIABLE, headerValue);
            }
            return headerValue;
        }
    }
}
