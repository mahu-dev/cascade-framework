package io.github.cascade.lock.aspect;

import io.github.cascade.lock.annotation.DistributedLock;
import io.github.cascade.lock.config.CascadeLockProperties;
import io.github.cascade.lock.core.LockExecutor;
import io.github.cascade.lock.key.KeyGenerator;
import io.github.cascade.lock.model.LockInfo;
import io.github.cascade.lock.model.LockResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Aspect
@RequiredArgsConstructor
public class DistributedLockAspect {

    private final LockExecutor lockExecutor;
    private final KeyGenerator keyGenerator;
    private final CascadeLockProperties properties;

    @Around("@annotation(distributedLock)")
    public Object around(ProceedingJoinPoint joinPoint,
                         DistributedLock distributedLock) throws Throwable {

        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        LockInfo lockInfo = buildLockInfo(distributedLock, joinPoint, method);

        log.debug("[cascade-lock] 尝试获取锁: {}", lockInfo.getLockKey());

        LockResult<Object> result = lockExecutor.execute(lockInfo, () -> {
            try {
                return joinPoint.proceed();
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }
        });
        return result.getResult();
    }

    private LockInfo buildLockInfo(DistributedLock annotation,
                                   ProceedingJoinPoint joinPoint,
                                   Method method) {
        // 解析主 key
        String rawKey = keyGenerator.generate(annotation.key(), joinPoint, method);
        String prefix = StringUtils.hasText(annotation.keyPrefix())
                ? annotation.keyPrefix()
                : properties.getKeyPrefix();
        String finalKey = StringUtils.hasText(prefix) ? prefix + ":" + rawKey : rawKey;

        // 解析多 key（联锁/红锁）
        List<String> multiKeys = null;
        if (annotation.keys().length > 0) {
            multiKeys = Arrays.stream(annotation.keys())
                    .map(k -> {
                        String parsed = keyGenerator.generate(k, joinPoint, method);
                        return StringUtils.hasText(prefix) ? prefix + ":" + parsed : parsed;
                    })
                    .collect(Collectors.toList());
        }

        return LockInfo.builder()
                .lockKey(finalKey)
                .lockKeys(multiKeys)
                .lockType(annotation.lockType())
                .lockStrategy(annotation.strategy())
                .waitTime(annotation.waitTime())
                .leaseTime(annotation.leaseTime())
                .timeUnit(annotation.timeUnit())
                .failMessage(annotation.message())
                .build();
    }
}