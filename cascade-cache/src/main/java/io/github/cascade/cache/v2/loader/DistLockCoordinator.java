package io.github.cascade.cache.v2.loader;

import java.util.function.Supplier;

/**
 * 分布式锁协调器。
 */
public interface DistLockCoordinator<K> {

    <T> T withLock(String cacheName,
                   K key,
                   long waitMs,
                   long leaseMs,
                   Supplier<T> supplier,
                   Runnable onDegrade);

    static <K> DistLockCoordinator<K> noop() {
        return new DistLockCoordinator<>() {
            @Override
            public <T> T withLock(String cacheName,
                                  K key,
                                  long waitMs,
                                  long leaseMs,
                                  Supplier<T> supplier,
                                  Runnable onDegrade) {
                if (onDegrade != null) {
                    onDegrade.run();
                }
                return supplier.get();
            }
        };
    }
}
