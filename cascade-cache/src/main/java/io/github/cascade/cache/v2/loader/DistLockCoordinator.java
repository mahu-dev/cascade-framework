package io.github.cascade.cache.v2.loader;

import java.util.function.Supplier;

/**
 * 分布式锁协调器。
 */
public interface DistLockCoordinator<K> {

    enum Outcome {
        ACQUIRED,
        NOT_ACQUIRED,
        ERROR
    }

    record LockResult<T>(Outcome outcome, T value, Throwable error) {
        public static <T> LockResult<T> acquired(T value) {
            return new LockResult<>(Outcome.ACQUIRED, value, null);
        }

        public static <T> LockResult<T> notAcquired() {
            return new LockResult<>(Outcome.NOT_ACQUIRED, null, null);
        }

        public static <T> LockResult<T> error(Throwable error) {
            return new LockResult<>(Outcome.ERROR, null, error);
        }
    }

    <T> LockResult<T> withLock(String cacheName,
                               K key,
                               long waitMs,
                               long leaseMs,
                               Supplier<T> supplier);

    static <K> DistLockCoordinator<K> noop() {
        return new DistLockCoordinator<>() {
            @Override
            public <T> LockResult<T> withLock(String cacheName,
                                              K key,
                                              long waitMs,
                                              long leaseMs,
                                              Supplier<T> supplier) {
                return LockResult.acquired(supplier.get());
            }
        };
    }
}
