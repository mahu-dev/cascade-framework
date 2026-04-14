package cc.coderm.cascade.limiter.aspect;

import cc.coderm.cascade.limiter.model.RateLimitResult;
import org.aspectj.lang.ProceedingJoinPoint;
import org.springframework.aop.support.AopUtils;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * 限流降级处理器调用工具类
 * <p>
 * 提供静态方法调用本地 fallback 方法，用于限流拒绝时的降级处理。
 * 从 {@link RateLimitAspect} 中提取为独立工具类，提高代码的可测试性和可维护性。
 *
 * <p>功能特性：
 * <ul>
 *   <li>支持两种 fallback 方法签名（带/不带 RateLimitResult 参数）</li>
 *   <li>处理 AOP 代理场景，正确解析目标类</li>
 *   <li>支持私有方法调用（通过 makeAccessible）</li>
 *   <li>清晰的异常处理（解包 InvocationTargetException）</li>
 * </ul>
 *
 * @see RateLimitAspect
 * @see RateLimitResult
 */
final class RateLimitFallbackInvoker {
    private RateLimitFallbackInvoker() {
        /* This utility class should not be instantiated */
    }


    /**
     * 调用本地 fallback 方法处理限流拒绝
     * <p>
     * 当限流触发时，尝试调用目标类中定义的 fallback 方法进行降级处理。
     * 支持两种方法签名，按优先级依次尝试：
     * <ol>
     *   <li><b>带 RateLimitResult 参数</b>：{@code fallbackMethodName(原参数..., RateLimitResult)}</li>
     *   <li><b>不带 RateLimitResult 参数</b>：{@code fallbackMethodName(原参数...)}</li>
     * </ol>
     * <p>
     * <b>方法查找策略：</b>
     * <ul>
     *   <li>优先查找带 {@code RateLimitResult} 参数的方法，提供更丰富的降级上下文</li>
     *   <li>如果未找到，降级查找不带 {@code RateLimitResult} 参数的方法</li>
     *   <li>方法必须在目标类或其父类中声明</li>
     *   <li>私有方法会通过 {@code ReflectionUtils.makeAccessible} 强制可访问</li>
     * </ul>
     * <p>
     * <b>使用示例：</b>
     * <pre>
     * {@code
     * @RateLimit(key = "api", fallbackMethod = "rateLimitFallback")
     * public ResponseEntity<?> getData(String id) {
     *     return service.getData(id);
     * }
     *
     * // 不带 RateLimitResult 的 fallback
     * private ResponseEntity<?> rateLimitFallback(String id) {
     *     return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
     * }
     *
     * // 带 RateLimitResult 的 fallback（推荐）
     * private ResponseEntity<?> rateLimitFallback(String id, RateLimitResult result) {
     *     return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
     *             .header("Retry-After", result.getWaitMillis() / 1000)
     *             .build();
     * }
     * }
     * </pre>
     *
     * @param pjp                AOP 连接点，包含目标对象和方法参数
     * @param originalMethod     原始的被限流拦截的方法
     * @param fallbackMethodName fallback 方法名称
     * @param result             限流结果，包含拒绝原因和等待时间信息
     * @return fallback 方法的返回值，将作为 AOP 拦截器的最终返回值
     * @throws Throwable 如果 fallback 方法调用失败或方法不存在
     */
    static Object invoke(ProceedingJoinPoint pjp, Method originalMethod, String fallbackMethodName, RateLimitResult result)
            throws Throwable {
        // 解析真实的目标类（处理 AOP 代理场景）
        Class<?> targetClass = AopUtils.getTargetClass(pjp.getTarget());
        if (targetClass == null) {
            // 边界情况：无法解析目标类，回退到 getTarget().getClass()
            targetClass = pjp.getTarget().getClass();
        }
        Object[] originalArgs = pjp.getArgs();

        // 构建带 RateLimitResult 参数的方法签名
        // 优先级高于不带 RateLimitResult 的方法，因为提供更多上下文信息
        Class<?>[] paramTypesWithResult = new Class[originalMethod.getParameterTypes().length + 1];
        System.arraycopy(originalMethod.getParameterTypes(), 0, paramTypesWithResult, 0,
                originalMethod.getParameterTypes().length);
        paramTypesWithResult[originalMethod.getParameterTypes().length] = RateLimitResult.class;

        // 尝试查找带 RateLimitResult 参数的 fallback 方法
        Method withResult = ReflectionUtils.findMethod(targetClass, fallbackMethodName, paramTypesWithResult);
        if (withResult != null) {
            // 找到带 RateLimitResult 参数的方法，直接调用
            ReflectionUtils.makeAccessible(withResult);
            Object[] argsWithResult = new Object[originalArgs.length + 1];
            System.arraycopy(originalArgs, 0, argsWithResult, 0, originalArgs.length);
            argsWithResult[originalArgs.length] = result;
            return invokeReflectively(pjp.getTarget(), withResult, argsWithResult);
        }

        // 降级到不带 RateLimitResult 的方法
        Method fallback = findFallbackMethod(targetClass, fallbackMethodName, originalMethod.getParameterTypes());
        return invokeReflectively(pjp.getTarget(), fallback, originalArgs);
    }

    /**
     * 通过反射调用方法，并正确处理异常
     * <p>
     * 使用反射 API 调用目标方法，并解包反射调用过程中的检查异常。
     * 特别是处理 {@link InvocationTargetException}，抛出其目标异常而非包装异常。
     * <p>
     * <b>异常处理策略：</b>
     * <ul>
     *   <li>反射成功：返回方法调用的结果</li>
     *   <li>InvocationTargetException：提取并抛出目标异常（实际业务异常）</li>
     *   <li>目标异常为 null：抛出原始的 InvocationTargetException</li>
     * </ul>
     * <p>
     * <b>为什么要解包异常：</b>
     * <pre>
     * // 如果不解包：
     * try { fallback(); } catch (InvocationTargetException e) {
     *   // 需要处理 e.getCause() 才能拿到真正的异常
     * }
     *
     * // 解包后：
     * try { fallback(); } catch (BusinessException e) {
     *   // 直接处理真正的业务异常
     * }
     * </pre>
     *
     * @param target 目标对象，非 null
     * @param method 要调用的方法，非 null
     * @param args   方法参数，可能为空数组
     * @return 方法调用的返回值，可能为 null
     * @throws Throwable 如果方法调用抛出异常，或反射过程失败
     */
    private static Object invokeReflectively(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException ex) {
            // 解包反射异常，抛出真正的目标异常（业务异常）
            // 如果目标异常为 null（罕见情况），抛出原始异常
            throw ex.getCause() == null ? ex : ex.getCause();
        }
    }

    /**
     * 查找 fallback 方法
     * <p>
     * 在目标类及其父类中查找指定名称和参数类型的方法。
     * 如果找到，会通过 {@code ReflectionUtils.makeAccessible} 使其可访问
     * （支持调用私有方法）。
     * <p>
     * <b>异常说明：</b>如果方法不存在，抛出 {@code IllegalStateException}。
     * 这通常表示配置错误（fallback 方法名拼写错误或参数类型不匹配），
     * 应该在应用启动时或首次调用时快速失败。
     * <p>
     * <b>查找范围：</b>
     * <ul>
     *   <li>目标类本身（包括私有方法）</li>
     *   <li>目标类的父类（public/protected 方法）</li>
     *   <li>实现的接口（default 方法）</li>
     * </ul>
     *
     * @param targetClass        目标类，在其中查找方法
     * @param fallbackMethodName fallback 方法名称
     * @param paramTypes         方法参数类型数组
     * @return 找到的 Method 对象
     * @throws IllegalStateException 如果方法不存在
     */
    private static Method findFallbackMethod(Class<?> targetClass, String fallbackMethodName, Class<?>[] paramTypes) {
        // 使用 Spring 的 ReflectionUtils 查找方法（支持父类和接口查找）
        Method method = ReflectionUtils.findMethod(targetClass, fallbackMethodName, paramTypes);
        if (method == null) {
            // 方法不存在：构建友好的错误消息，包含方法签名信息
            String signature = Arrays.stream(paramTypes)
                    .map(Class::getSimpleName)
                    .collect(Collectors.joining(", "));
            throw new IllegalStateException(
                    "No fallback method '" + fallbackMethodName + "(" + signature + ")' found in class "
                            + targetClass.getName() + " or its superclasses");
        }
        // 使私有方法可访问（用户可以在目标类中定义私有 fallback 方法）
        ReflectionUtils.makeAccessible(method);
        return method;
    }
}
