package io.github.cascade.cache.v2.sync;

import io.github.cascade.cache.v2.model.InvalidationEvent;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * 失效同步总线抽象。
 */
public interface InvalidationBus<K> {

    CompletableFuture<Void> publishInvalidation(String cacheName, K key, long version, String nodeId);

    CompletableFuture<Void> publishClear(String cacheName, long version, String nodeId);

    void subscribe(String cacheName, Consumer<InvalidationEvent<K>> handler);

    void unsubscribe(String cacheName);

    void start();

    void stop();

    boolean isRunning();

    static <K> InvalidationBus<K> noop() {
        return new NoOpInvalidationBus<>();
    }

    final class NoOpInvalidationBus<K> implements InvalidationBus<K> {
        @Override
        public CompletableFuture<Void> publishInvalidation(String cacheName, K key, long version, String nodeId) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> publishClear(String cacheName, long version, String nodeId) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void subscribe(String cacheName, Consumer<InvalidationEvent<K>> handler) {
            // no-op
        }

        @Override
        public void unsubscribe(String cacheName) {
            // no-op
        }

        @Override
        public void start() {
            // no-op
        }

        @Override
        public void stop() {
            // no-op
        }

        @Override
        public boolean isRunning() {
            return true;
        }
    }
}

