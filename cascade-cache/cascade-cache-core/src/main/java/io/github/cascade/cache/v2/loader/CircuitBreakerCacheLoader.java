package io.github.cascade.cache.v2.loader;

import io.github.cascade.cache.v2.api.CacheLoader;
import io.github.cascade.cache.v2.common.exception.CacheLoadException;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * 熔断器缓存加载器实现
 * <p>
 * 实现熔断器模式，用于防止级联故障。当失败率过高时，
 * 会暂时阻止对失败服务的调用，给系统恢复的机会。
 *
 * @param <K> 键类型
 * @param <V> 值类型
 * @author cascade
 */
public final class CircuitBreakerCacheLoader<K, V> implements CacheLoader<K, V> {

    private final Function<K, V> loader;
    private final int failureThreshold;
    private final Duration recoveryTime;
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private volatile long lastFailureTime;
    private volatile boolean circuitOpen;

    public CircuitBreakerCacheLoader(Function<K, V> loader, int failureThreshold, Duration recoveryTime) {
        this.loader = loader;
        this.failureThreshold = failureThreshold;
        this.recoveryTime = recoveryTime;
    }

    @Override
    public V apply(K key) {
        if (circuitOpen && !shouldAttemptRecovery()) {
            throw new CacheLoadException("熔断器开启，拒绝请求: " + key);
        }

        try {
            V result = loader.apply(key);
            onSuccess();
            return result;
        } catch (RuntimeException e) {
            onFailure();
            throw e;
        }
    }

    private boolean shouldAttemptRecovery() {
        return System.currentTimeMillis() - lastFailureTime >= recoveryTime.toMillis();
    }

    private void onSuccess() {
        failureCount.set(0);
        circuitOpen = false;
    }

    private void onFailure() {
        int currentFailures = failureCount.incrementAndGet();
        lastFailureTime = System.currentTimeMillis();
        if (currentFailures >= failureThreshold) {
            circuitOpen = true;
        }
    }
}