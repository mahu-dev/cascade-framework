package io.github.cascade.cache.v2.engine;

import io.github.cascade.cache.v2.consistency.InvalidationBus;
import io.github.cascade.cache.v2.policy.RefreshExecutionOptions;
import io.github.cascade.cache.v2.store.l1.L1CacheStore;
import io.github.cascade.cache.v2.store.l2.L2CacheStore;
import org.slf4j.Logger;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * EngineBackedCache 生命周期管理逻辑。
 */
final class EngineBackedCacheLifecycle<K, V> {

    private final Logger logger;
    private final String cacheName;
    private final AtomicBoolean closed;
    private final AtomicBoolean subscribed;
    private final ScheduledExecutorService refreshScheduler;
    private final ThreadPoolExecutor refreshExecutor;
    private final ExecutorService asyncExecutor;
    private final RefreshExecutionOptions refreshOptions;
    private final InvalidationBus<K> invalidationBus;
    private final L1CacheStore<K, V> l1Store;
    private final L2CacheStore<K, V> l2Store;

    EngineBackedCacheLifecycle(Logger logger,
                               String cacheName,
                               AtomicBoolean closed,
                               AtomicBoolean subscribed,
                               ScheduledExecutorService refreshScheduler,
                               ThreadPoolExecutor refreshExecutor,
                               ExecutorService asyncExecutor,
                               RefreshExecutionOptions refreshOptions,
                               InvalidationBus<K> invalidationBus,
                               L1CacheStore<K, V> l1Store,
                               L2CacheStore<K, V> l2Store) {
        this.logger = logger;
        this.cacheName = cacheName;
        this.closed = closed;
        this.subscribed = subscribed;
        this.refreshScheduler = refreshScheduler;
        this.refreshExecutor = refreshExecutor;
        this.asyncExecutor = asyncExecutor;
        this.refreshOptions = refreshOptions;
        this.invalidationBus = invalidationBus;
        this.l1Store = l1Store;
        this.l2Store = l2Store;
    }

    void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        EngineBackedCacheCore.shutdownExecutorGracefully(refreshScheduler, refreshOptions.shutdownTimeoutSeconds());
        EngineBackedCacheCore.shutdownExecutorGracefully(refreshExecutor, refreshOptions.shutdownTimeoutSeconds());
        EngineBackedCacheCore.shutdownExecutorGracefully(asyncExecutor, refreshOptions.shutdownTimeoutSeconds());

        if (subscribed.compareAndSet(true, false)) {
            closeSafely("invalidationBus.unsubscribe", () -> invalidationBus.unsubscribe(cacheName));
        }
        closeSafely("invalidationBus.stop", invalidationBus::stop);
        closeSafely("l1Store.close", () -> {
            if (l1Store != null) {
                l1Store.close();
            }
        });
        closeSafely("l2Store.close", () -> {
            if (l2Store != null) {
                l2Store.close();
            }
        });
    }

    boolean isClosed() {
        return closed.get();
    }

    void checkNotClosed() {
        if (closed.get()) {
            throw new IllegalStateException("缓存已关闭: " + cacheName);
        }
    }

    private void closeSafely(String step, Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            logger.warn("关闭缓存步骤失败: cache={}, step={}, error={}", cacheName, step, e.getMessage(), e);
        }
    }
}
