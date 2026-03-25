package io.github.cascade.cache.v2.support;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * 本地单飞控制，避免同 key 并发重复加载。
 */
public class SingleFlight<K, V> {

    private final ConcurrentMap<K, CompletableFuture<V>> inflight = new ConcurrentHashMap<>();

    public V execute(K key, Supplier<V> supplier) {
        CompletableFuture<V> existing = inflight.get(key);
        if (existing != null) {
            return existing.join();
        }

        CompletableFuture<V> created = CompletableFuture.supplyAsync(supplier);
        CompletableFuture<V> previous = inflight.putIfAbsent(key, created);
        CompletableFuture<V> future = previous != null ? previous : created;

        try {
            return future.join();
        } finally {
            inflight.remove(key, future);
        }
    }
}

