package io.github.cascade.cache.v2.core;

import io.github.cascade.cache.api.Cache;
import io.github.cascade.cache.v2.model.CacheRecord;
import io.github.cascade.cache.v2.model.InvalidationEvent;
import io.github.cascade.cache.v2.policy.CachePolicy;
import io.github.cascade.cache.v2.policy.SyncMode;
import io.github.cascade.cache.v2.store.L1CacheStore;
import io.github.cascade.cache.v2.store.L2CacheStore;
import io.github.cascade.cache.v2.support.SingleFlight;
import io.github.cascade.cache.v2.sync.InvalidationBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * V2 统一缓存引擎实现。
 */
public class EngineBackedCache<K, V> implements Cache<K, V> {

    private static final Logger LOGGER = LoggerFactory.getLogger(EngineBackedCache.class);

    private final String cacheName;
    private final CachePolicy policy;
    private final L1CacheStore<K, V> l1Store;
    private final L2CacheStore<K, V> l2Store;
    private final InvalidationBus<K> invalidationBus;
    private final String nodeId;
    private final ExecutorService asyncExecutor;
    private final ScheduledExecutorService refreshScheduler;
    private final SingleFlight<K, V> singleFlight = new SingleFlight<>();
    private final Set<K> trackedKeys = ConcurrentHashMap.newKeySet();
    private final Set<K> refreshingKeys = ConcurrentHashMap.newKeySet();
    private final Map<K, Long> localVersion = new ConcurrentHashMap<>();
    private final AtomicLong clearVersion = new AtomicLong(0L);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean subscribed = new AtomicBoolean(false);

    private volatile Function<K, V> loader;

    public EngineBackedCache(String cacheName,
                             CachePolicy policy,
                             L1CacheStore<K, V> l1Store,
                             L2CacheStore<K, V> l2Store,
                             Function<K, V> loader,
                             InvalidationBus<K> invalidationBus,
                             String nodeId) {
        this.cacheName = cacheName;
        this.policy = policy;
        this.l1Store = l1Store;
        this.l2Store = l2Store;
        this.loader = loader;
        this.invalidationBus = invalidationBus;
        this.nodeId = nodeId;
        this.asyncExecutor = Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r, "cascade-async-" + cacheName);
            thread.setDaemon(true);
            return thread;
        });
        this.refreshScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "cascade-refresh-" + cacheName);
            thread.setDaemon(true);
            return thread;
        });

        startSyncIfNeeded();
        startRefreshIfNeeded();
    }

    public void setLoaderIfAbsent(Function<K, V> loader) {
        if (loader != null && this.loader == null) {
            synchronized (this) {
                if (this.loader == null) {
                    this.loader = loader;
                    LOGGER.info("为缓存设置延迟加载器: cache={}", cacheName);
                }
            }
        }
    }

    @Override
    public Optional<V> get(K key) {
        checkNotClosed();
        if (key == null) {
            return Optional.empty();
        }
        trackKey(key);

        Optional<CacheRecord<V>> l1Hit = readFromL1(key);
        if (l1Hit.isPresent()) {
            CacheRecord<V> record = l1Hit.get();
            if (isHardExpired(record)) {
                l1Store.evict(key);
            } else {
                triggerRefreshIfSoftExpired(key, record);
                return Optional.ofNullable(record.getValue());
            }
        }

        Optional<CacheRecord<V>> l2Hit = readFromL2(key);
        if (l2Hit.isPresent()) {
            CacheRecord<V> record = l2Hit.get();
            if (isHardExpired(record)) {
                if (l2Store != null) {
                    l2Store.evict(key);
                }
            } else {
                writeBackL1(key, record);
                triggerRefreshIfSoftExpired(key, record);
                return Optional.ofNullable(record.getValue());
            }
        }

        V loaded = loadAndWriteBack(key, policy.getHardTtlSeconds());
        return Optional.ofNullable(loaded);
    }

    @Override
    public V getOrLoad(K key, Function<K, V> fallbackLoader) {
        checkNotClosed();
        if (key == null) {
            return null;
        }
        Optional<V> cached = get(key);
        if (cached.isPresent()) {
            return cached.get();
        }
        Function<K, V> loadFunction = fallbackLoader != null ? fallbackLoader : loader;
        if (loadFunction == null) {
            return null;
        }
        V loaded = loadFunction.apply(key);
        if (loaded != null) {
            put(key, loaded);
        }
        return loaded;
    }

    @Override
    public CompletableFuture<Optional<V>> getAsync(K key) {
        checkNotClosed();
        return CompletableFuture.supplyAsync(() -> get(key), asyncExecutor);
    }

    @Override
    public void put(K key, V value) {
        put(key, value, policy.getHardTtlSeconds());
    }

    @Override
    public void put(K key, V value, long ttlSeconds) {
        checkNotClosed();
        if (key == null) {
            return;
        }
        if (value == null) {
            evict(key);
            return;
        }
        writeThrough(key, value, ttlSeconds);
    }

    @Override
    public CompletableFuture<Void> putAsync(K key, V value) {
        checkNotClosed();
        if (key == null) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.runAsync(() -> put(key, value), asyncExecutor);
    }

    @Override
    public void evict(K key) {
        checkNotClosed();
        if (key == null) {
            return;
        }

        long version = nextVersion(key);
        if (l2Store != null) {
            l2Store.evict(key);
        }
        if (l1Store != null) {
            l1Store.evict(key);
        }
        trackedKeys.remove(key);
        localVersion.put(key, version);
        publishInvalidation(key, version);
    }

    @Override
    public void clear() {
        checkNotClosed();
        if (l2Store != null) {
            l2Store.clear();
        }
        if (l1Store != null) {
            l1Store.clear();
        }
        trackedKeys.clear();
        localVersion.clear();

        long version = clearVersion.incrementAndGet();
        publishClear(version);
    }

    @Override
    public Map<K, V> getAll(Iterable<K> keys) {
        checkNotClosed();
        Map<K, V> result = new LinkedHashMap<>();
        for (K key : keys) {
            get(key).ifPresent(value -> result.put(key, value));
        }
        return result;
    }

    @Override
    public void putAll(Map<K, V> entries) {
        checkNotClosed();
        if (entries == null || entries.isEmpty()) {
            return;
        }
        for (Map.Entry<K, V> entry : entries.entrySet()) {
            put(entry.getKey(), entry.getValue());
        }
    }

    @Override
    public boolean containsKey(K key) {
        checkNotClosed();
        if (key == null) {
            return false;
        }
        Optional<CacheRecord<V>> l1 = readFromL1(key);
        if (l1.isPresent() && !isHardExpired(l1.get())) {
            return true;
        }
        Optional<CacheRecord<V>> l2 = readFromL2(key);
        return l2.isPresent() && !isHardExpired(l2.get());
    }

    @Override
    public long size() {
        checkNotClosed();
        if (l2Store != null) {
            return l2Store.size();
        }
        return l1Store != null ? l1Store.size() : 0L;
    }

    @Override
    public String getName() {
        return cacheName;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        try {
            refreshScheduler.shutdownNow();
        } catch (Exception ignored) {
        }

        try {
            asyncExecutor.shutdownNow();
        } catch (Exception ignored) {
        }

        try {
            if (subscribed.compareAndSet(true, false)) {
                invalidationBus.unsubscribe(cacheName);
            }
            invalidationBus.stop();
        } catch (Exception ignored) {
        }

        try {
            if (l1Store != null) {
                l1Store.close();
            }
        } catch (Exception ignored) {
        }

        try {
            if (l2Store != null) {
                l2Store.close();
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public boolean isClosed() {
        return closed.get();
    }

    private void writeThrough(K key, V value, long ttlSeconds) {
        long now = System.currentTimeMillis();
        long hardTtl = ttlSeconds > 0 ? ttlSeconds : policy.getHardTtlSeconds();
        long softTtl = policy.getSoftTtlSeconds() > 0 ? policy.getSoftTtlSeconds() : Math.max(1, hardTtl / 3);
        if (hardTtl > 0 && softTtl > hardTtl) {
            softTtl = hardTtl;
        }

        long hardExpireAt = hardTtl > 0 ? now + hardTtl * 1000 : Long.MAX_VALUE;
        long softExpireAt = softTtl > 0 ? now + softTtl * 1000 : hardExpireAt;
        long version = nextVersion(key);

        CacheRecord<V> record = new CacheRecord<>(value, version, now, softExpireAt, hardExpireAt, nodeId);

        if (l2Store != null) {
            l2Store.put(key, record, hardTtl);
        }
        writeBackL1(key, record);
        localVersion.put(key, version);
        publishInvalidation(key, version);
    }

    private V loadAndWriteBack(K key, long ttlSeconds) {
        Function<K, V> loadFunction = this.loader;
        if (loadFunction == null) {
            return null;
        }

        return singleFlight.execute(key, () -> {
            try {
                V loaded = loadFunction.apply(key);
                if (loaded != null) {
                    writeThrough(key, loaded, ttlSeconds);
                }
                return loaded;
            } catch (Exception e) {
                LOGGER.warn("加载器执行失败: cache={}, key={}, error={}", cacheName, key, e.getMessage());
                return null;
            }
        });
    }

    private void startSyncIfNeeded() {
        if (policy.getSyncMode() == SyncMode.NONE) {
            return;
        }
        invalidationBus.subscribe(cacheName, this::handleInvalidationEvent);
        invalidationBus.start();
        subscribed.set(true);
    }

    private void startRefreshIfNeeded() {
        if (!policy.isAutoRefreshEnabled()) {
            return;
        }
        long interval = Math.max(1L, policy.getRefreshIntervalSeconds());
        refreshScheduler.scheduleWithFixedDelay(this::refreshTrackedKeysSafely, interval, interval, TimeUnit.SECONDS);
    }

    private void refreshTrackedKeysSafely() {
        if (closed.get()) {
            return;
        }
        try {
            refreshTrackedKeys();
        } catch (Exception e) {
            LOGGER.debug("刷新任务执行失败: cache={}, error={}", cacheName, e.getMessage());
        }
    }

    private void refreshTrackedKeys() {
        if (!policy.isAutoRefreshEnabled() || loader == null || trackedKeys.isEmpty()) {
            return;
        }

        for (K key : new ArrayList<>(trackedKeys)) {
            Optional<CacheRecord<V>> record = readFromL1(key);
            if (record.isEmpty()) {
                record = readFromL2(key);
            }
            if (record.isPresent() && isSoftExpired(record.get())) {
                triggerRefresh(key);
            }
        }
    }

    private void triggerRefreshIfSoftExpired(K key, CacheRecord<V> record) {
        if (policy.isAutoRefreshEnabled() && isSoftExpired(record)) {
            triggerRefresh(key);
        }
    }

    private void triggerRefresh(K key) {
        if (loader == null || key == null) {
            return;
        }
        if (!refreshingKeys.add(key)) {
            return;
        }
        CompletableFuture.runAsync(() -> loadAndWriteBack(key, policy.getHardTtlSeconds()), asyncExecutor)
                .whenComplete((unused, throwable) -> refreshingKeys.remove(key));
    }

    private void trackKey(K key) {
        if (policy.isAutoRefreshEnabled() && key != null) {
            trackedKeys.add(key);
        }
    }

    private Optional<CacheRecord<V>> readFromL1(K key) {
        if (l1Store == null || key == null) {
            return Optional.empty();
        }
        return l1Store.get(key);
    }

    private Optional<CacheRecord<V>> readFromL2(K key) {
        if (l2Store == null || key == null) {
            return Optional.empty();
        }
        return l2Store.get(key);
    }

    private void writeBackL1(K key, CacheRecord<V> record) {
        if (l1Store != null && key != null && record != null) {
            l1Store.put(key, record);
        }
    }

    private boolean isSoftExpired(CacheRecord<V> record) {
        long softExpire = record.getSoftExpireAtMs();
        return softExpire > 0 && System.currentTimeMillis() >= softExpire;
    }

    private boolean isHardExpired(CacheRecord<V> record) {
        long hardExpire = record.getHardExpireAtMs();
        return hardExpire > 0 && hardExpire != Long.MAX_VALUE && System.currentTimeMillis() >= hardExpire;
    }

    private long nextVersion(K key) {
        if (l2Store != null) {
            return l2Store.nextVersion(key);
        }
        return localVersion.merge(key, 1L, Long::sum);
    }

    private void publishInvalidation(K key, long version) {
        if (policy.getSyncMode() == SyncMode.NONE) {
            return;
        }
        invalidationBus.publishInvalidation(cacheName, key, version, nodeId)
                .exceptionally(throwable -> {
                    LOGGER.debug("发布失效事件失败: cache={}, key={}, error={}", cacheName, key, throwable.getMessage());
                    return null;
                });
    }

    private void publishClear(long version) {
        if (policy.getSyncMode() == SyncMode.NONE) {
            return;
        }
        invalidationBus.publishClear(cacheName, version, nodeId)
                .exceptionally(throwable -> {
                    LOGGER.debug("发布清空事件失败: cache={}, error={}", cacheName, throwable.getMessage());
                    return null;
                });
    }

    private void handleInvalidationEvent(InvalidationEvent<K> event) {
        if (event == null || event.getNodeId() == null || event.getNodeId().equals(nodeId)) {
            return;
        }
        if (event.getOperation() == InvalidationEvent.Operation.CLEAR) {
            if (l1Store != null) {
                l1Store.clear();
            }
            trackedKeys.clear();
            return;
        }
        K key = event.getKey();
        if (key == null || l1Store == null) {
            return;
        }

        long incoming = event.getVersion();
        long current = readFromL1(key).map(CacheRecord::getVersion)
                .orElseGet(() -> localVersion.getOrDefault(key, 0L));
        if (incoming > current) {
            l1Store.evict(key);
            localVersion.put(key, incoming);
        }
    }

    private void checkNotClosed() {
        if (closed.get()) {
            throw new IllegalStateException("缓存已关闭: " + cacheName);
        }
    }
}
