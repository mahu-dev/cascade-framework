package cc.coderm.cascade.limiter.aspect;

import cc.coderm.cascade.limiter.annotation.RateLimit;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.lang.Nullable;

import java.lang.reflect.Method;

/**
 * 限流调用解析工具类
 * <p>
 * 负责从 AOP 连接点中提取目标方法和 {@link RateLimit} 注解。
 * 无状态工具类，所有方法均为静态方法。
 *
 * <p>功能职责：
 * <ul>
 *   <li>解析目标类（处理 AOP 代理场景）</li>
 *   <li>提取目标方法（处理桥接方法、泛擦除等）</li>
 *   <li>查找方法或类级别的 {@code @RateLimit} 注解</li>
 * </ul>
 *
 * @see RateLimit
 * @see ProceedingJoinPoint
 */
final class RateLimitInvocationResolver {

    private RateLimitInvocationResolver() {
        /* This utility class should not be instantiated */
    }

    /**
     * 解析 AOP 连接点，提取目标方法和 @RateLimit 注解
     * <p>
     * 从 AOP 连接点中解析出真实的目标方法和限流注解，
     * 处理可能的代理场景、方法优先级等复杂情况。
     * <p>
     * <b>解析流程：</b>
     * <ol>
     *   <li>获取方法签名</li>
     *   <li>解析真实的目标类（穿透代理）</li>
     *   <li>获取最具体的方法（处理桥接方法）</li>
     *   <li>查找方法或类级别的 {@code @RateLimit} 注解</li>
     * </ol>
     *
     * @param pjp AOP 连接点，包含目标对象、方法等信息
     * @return 解析结果，包含目标方法和找到的 @RateLimit 注解（可能为 null）
     */
    static ResolvedInvocation resolve(ProceedingJoinPoint pjp) {
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        Class<?> targetClass = resolveTargetClass(pjp, signature);
        Method method = AopUtils.getMostSpecificMethod(signature.getMethod(), targetClass);
        RateLimit rateLimit = resolveRateLimit(method, targetClass);
        return new ResolvedInvocation(method, rateLimit);
    }

    private static Class<?> resolveTargetClass(ProceedingJoinPoint pjp, MethodSignature signature) {
        Object target = pjp.getTarget();
        if (target == null) {
            return signature.getDeclaringType();
        }
        return AopUtils.getTargetClass(target);
    }

    @Nullable
    private static RateLimit resolveRateLimit(Method method, Class<?> targetClass) {
        RateLimit methodAnnotation = AnnotatedElementUtils.findMergedAnnotation(method, RateLimit.class);
        if (methodAnnotation != null) {
            return methodAnnotation;
        }
        return AnnotatedElementUtils.findMergedAnnotation(targetClass, RateLimit.class);
    }

    record ResolvedInvocation(Method method, @Nullable RateLimit rateLimit) {
    }
}
