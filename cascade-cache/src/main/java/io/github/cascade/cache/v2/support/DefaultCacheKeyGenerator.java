package io.github.cascade.cache.v2.support;

import org.aspectj.lang.JoinPoint;

import java.util.Arrays;

/**
 * 注解路径默认 key 生成器。
 */
public final class DefaultCacheKeyGenerator {

    private DefaultCacheKeyGenerator() {
    }

    public static String generate(JoinPoint joinPoint) {
        String signature = joinPoint.getSignature().toLongString();
        int argsHash = Arrays.deepHashCode(joinPoint.getArgs());
        return signature + "#" + argsHash;
    }
}

