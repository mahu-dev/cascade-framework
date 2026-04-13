package io.github.cascade.lock.aspect;

import io.github.cascade.lock.annotation.DistributedLock;
import io.github.cascade.lock.config.CascadeLockProperties;
import io.github.cascade.lock.core.LockExecutor;
import io.github.cascade.lock.enums.LockType;
import io.github.cascade.lock.exception.LockException;
import io.github.cascade.lock.key.KeyGenerator;
import io.github.cascade.lock.key.LockKeyNormalizer;
import io.github.cascade.lock.model.LockInfo;
import io.github.cascade.lock.model.LockResult;
import io.github.cascade.lock.util.SneakyThrow;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.BridgeMethodResolver;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

@Aspect
public class DistributedLockAspect {

    private static final Logger LOGGER = LoggerFactory.getLogger(DistributedLockAspect.class);

    private final LockExecutor lockExecutor;
    private final KeyGenerator keyGenerator;
    private final CascadeLockProperties properties;

    public DistributedLockAspect(LockExecutor lockExecutor, KeyGenerator keyGenerator, CascadeLockProperties properties) {
        this.lockExecutor = lockExecutor;
        this.keyGenerator = keyGenerator;
        this.properties = properties;
        LOGGER.info("DistributedLockAspect initialized with properties: {}", properties);
    }


    @Around("@annotation(io.github.cascade.lock.annotation.DistributedLock)")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {

        Method signatureMethod = ((MethodSignature) joinPoint.getSignature()).getMethod();
        Method method = resolveTargetMethod(joinPoint, signatureMethod);
        DistributedLock annotation = resolveAnnotation(method, signatureMethod);
        if (annotation == null) {
            String methodText = method.toGenericString();
            throw new LockException(
                    "切面命中但无法解析 @DistributedLock 注解，请检查代理方法映射: " + methodText,
                    methodText
            );
        }

        LockInfo lockInfo = buildLockInfo(annotation, joinPoint, method);

        LOGGER.debug("[cascade-lock] 尝试获取锁: {}", lockInfo.getDisplayKey());

        LockResult<Object> result = lockExecutor.execute(lockInfo, () -> proceed(joinPoint));
        if (shouldRejectNullResult(result, method)) {
            throw new LockException(
                    "SKIP 策略在未获取锁时会返回 null，方法返回基本类型无法接收 null: " + method.toGenericString(),
                    lockInfo.getDisplayKey()
            );
        }
        return result.getResult();
    }

    private static Method resolveTargetMethod(ProceedingJoinPoint joinPoint, Method signatureMethod) {
        Object target = joinPoint.getTarget();
        if (target == null) {
            return signatureMethod;
        }
        Class<?> targetClass = AopUtils.getTargetClass(target);
        if (targetClass == null) {
            return signatureMethod;
        }
        Method specificMethod = AopUtils.getMostSpecificMethod(signatureMethod, targetClass);
        return BridgeMethodResolver.findBridgedMethod(specificMethod);
    }

    private static DistributedLock resolveAnnotation(Method targetMethod, Method signatureMethod) {
        DistributedLock annotation = AnnotatedElementUtils.findMergedAnnotation(targetMethod, DistributedLock.class);
        if (annotation != null) {
            return annotation;
        }
        if (!targetMethod.equals(signatureMethod)) {
            return AnnotatedElementUtils.findMergedAnnotation(signatureMethod, DistributedLock.class);
        }
        return null;
    }

    private static Object proceed(ProceedingJoinPoint joinPoint) {
        try {
            return joinPoint.proceed();
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            return SneakyThrow.rethrow(e);
        }
    }

    private static boolean shouldRejectNullResult(LockResult<Object> result, Method method) {
        if (result.isAcquired() || result.getResult() != null) {
            return false;
        }
        Class<?> returnType = method.getReturnType();
        return returnType.isPrimitive() && returnType != Void.TYPE;
    }

    private LockInfo buildLockInfo(DistributedLock annotation,
                                   ProceedingJoinPoint joinPoint,
                                   Method method) {
        String[] rawKeys = annotation.keys();
        if (rawKeys.length == 0) {
            throw new LockException("@DistributedLock 必须指定至少一个 key", "");
        }

        String prefix = StringUtils.hasText(annotation.keyPrefix())
                ? annotation.keyPrefix()
                : properties.getKeyPrefix();

        List<String> resolvedKeys = Arrays.stream(rawKeys)
                .map(expr -> LockKeyNormalizer.normalizeSingleKey(expr, "@DistributedLock key 表达式"))
                .map(expr -> {
                    String parsed = keyGenerator.generate(expr, joinPoint, method);
                    String normalized = LockKeyNormalizer.normalizeSingleKey(parsed, "@DistributedLock 解析后的 key");
                    return LockKeyNormalizer.applyPrefix(prefix, normalized);
                })
                .toList();
        resolvedKeys = LockKeyNormalizer.normalizeKeyList(resolvedKeys, "@DistributedLock keys");

        if (resolvedKeys.size() > 1) {
            validateMultiKeyType(annotation.lockType(), resolvedKeys);
        }

        return LockInfo.builder()
                .keys(resolvedKeys)
                .lockType(annotation.lockType())
                .lockStrategy(annotation.strategy())
                .waitTime(resolveWaitTime(annotation))
                .leaseTime(resolveLeaseTime(annotation))
                .timeUnit(annotation.timeUnit())
                .failMessage(annotation.message())
                .build();
    }

    private static void validateMultiKeyType(LockType lockType, List<String> keys) {
        if (lockType != LockType.RED && lockType != LockType.MULTI) {
            throw new LockException(
                    "多 key 仅支持 RED / MULTI 类型，当前为 " + lockType,
                    keys.toString()
            );
        }
    }

    private long resolveWaitTime(DistributedLock annotation) {
        return annotation.waitTime() == DistributedLock.UNSET
                ? properties.getWaitTime()
                : annotation.waitTime();
    }

    private long resolveLeaseTime(DistributedLock annotation) {
        return annotation.leaseTime() == DistributedLock.UNSET
                ? properties.getLeaseTime()
                : annotation.leaseTime();
    }
}
