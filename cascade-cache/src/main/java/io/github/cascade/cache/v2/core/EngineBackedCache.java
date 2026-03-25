package io.github.cascade.cache.v2.core;

import io.github.cascade.cache.api.Cache;
import io.github.cascade.cache.v2.consistency.VersionManager;
import io.github.cascade.cache.v2.engine.ReadPipeline;
import io.github.cascade.cache.v2.engine.RefreshPipeline;
import io.github.cascade.cache.v2.engine.WritePipeline;
import io.github.cascade.cache.v2.loader.DistLockCoordinator;
import io.github.cascade.cache.v2.model.CacheRecord;
import io.github.cascade.cache.v2.model.InvalidationEvent;
import io.github.cascade.cache.v2.observability.CacheMetricsCollector;
import io.github.cascade.cache.v2.observability.CacheStatsSnapshot;
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
    private final VersionManager<K> versionManager;
    private final DistLockCoordinator<K> lockCoordinator;
    private final CacheMetricsCollector metricsCollector;
    private final String nodeId;
    private final ExecutorService asyncExecutor;
    private final ScheduledExecutorService refreshScheduler;
    private final SingleFlight<K, V> singleFlight = new SingleFlight<>();
    private final ReadPipeline<K, V> readPipeline = new ReadPipeline<>();
    private final WritePipeline<V> writePipeline = new WritePipeline<>();
    private final RefreshPipeline refreshPipeline = new RefreshPipeline();
    private final Set<K> trackedKeys = ConcurrentHashMap.newKeySet();
    private final Set<K> refreshingKeys = ConcurrentHashMap.newKeySet();
    private final Map<K, Long> localVersion = new ConcurrentHashMap<>();
    private final Map<K, AtomicLong> readCounter = new ConcurrentHashMap<>();
    private final AtomicLong clearVersion = new AtomicLong(0L);
    private final AtomicLong l1Hit = new AtomicLong(0L);
    private final AtomicLong l2Hit = new AtomicLong(0L);
    private final AtomicLong miss = new AtomicLong(0L);
    private final AtomicLong backfillL1 = new AtomicLong(0L);
    private final AtomicLong backfillL2 = new AtomicLong(0L);
    private final AtomicLong refreshSuccess = new AtomicLong(0L);
    private final AtomicLong refreshFail = new AtomicLong(0L);
    private final AtomicLong invalidatePublish = new AtomicLong(0L);
    private final AtomicLong invalidateConsume = new AtomicLong(0L);
    private final AtomicLong singleFlightJoin = new AtomicLong(0L);
    private final AtomicLong distLockDegrade = new AtomicLong(0L);
    private final AtomicLong eventLagMs = new AtomicLong(0L);
    private final AtomicLong droppedEvents = new AtomicLong(0L);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean subscribed = new AtomicBoolean(false);

    private volatile Function<K, V> loader;

    public EngineBackedCache(String cacheName,
                             CachePolicy policy,
                             L1CacheStore<K, V> l1Store,
                             L2CacheStore<K, V> l2Store,
                             Function<K, V> loader,
                             InvalidationBus<K> invalidationBus,
                             VersionManager<K> versionManager,
                             DistLockCoordinator<K> lockCoordinator,
                             String nodeId) {
        this(cacheName, policy, l1Store, l2Store, loader, invalidationBus, versionManager, lockCoordinator, nodeId,
                CacheMetricsCollector.create(cacheName, null));
    }

    public EngineBackedCache(String cacheName,
                             CachePolicy policy,
                             L1CacheStore<K, V> l1Store,
                             L2CacheStore<K, V> l2Store,
                             Function<K, V> loader,
                             InvalidationBus<K> invalidationBus,
                             VersionManager<K> versionManager,
                             DistLockCoordinator<K> lockCoordinator,
                             String nodeId,
                             CacheMetricsCollector metricsCollector) {
        this.cacheName = cacheName;
        this.policy = policy;
        this.l1Store = l1Store;
        this.l2Store = l2Store;
        this.loader = loader;
        this.invalidationBus = invalidationBus;
        this.versionManager = versionManager;
        this.lockCoordinator = lockCoordinator;
        this.metricsCollector = metricsCollector != null ? metricsCollector : CacheMetricsCollector.create(cacheName, null);
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
        this.clearVersion.set(versionManager.currentClearVersion());

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

        ReadPipeline.ReadResult<V> readResult = readPipeline.read(
                key,
                this::readFromL1,
                this::readFromL2,
                this::isHardExpired
        );
        if (readResult.l1HardExpired() && l1Store != null) {
            l1Store.evict(key);
        }
        if (readResult.l2HardExpired() && l2Store != null) {
            l2Store.evict(key);
        }
        if (readResult.hit()) {
            CacheRecord<V> record = readResult.record();
            if (readResult.level() == ReadPipeline.HitLevel.L2) {
                markL2Hit();
                writeBackL1(key, record);
            } else {
                markL1Hit();
            }
            triggerRefreshIfSoftExpired(key, record);
            return Optional.ofNullable(record.getValue());
        }

        markMiss();
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
        readCounter.remove(key);
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
        readCounter.clear();
        localVersion.clear();

        long version = versionManager.nextClearVersion();
        clearVersion.set(version);
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
        long version = nextVersion(key);

        CacheRecord<V> record = writePipeline.createRecord(value, version, now, hardTtl, softTtl, nodeId);

        if (l2Store != null) {
            l2Store.put(key, record, hardTtl);
            markBackfillL2();
        }
        localVersion.put(key, version);
        publishWriteEvent(key, record);
        writeBackL1(key, record);
    }

    private V loadAndWriteBack(K key, long ttlSeconds) {
        if (loader == null) {
            return null;
        }

        if (policy.isSingleFlightEnabled()) {
            return singleFlight.execute(key, () -> {
                markSingleFlightJoin();
                return loadWithProtection(key, ttlSeconds);
            });
        }
        return loadWithProtection(key, ttlSeconds);
    }

    private V loadWithProtection(K key, long ttlSeconds) {
        if (!policy.isDistributedLockEnabled()) {
            return invokeLoaderAndWrite(key, ttlSeconds);
        }
        return lockCoordinator.withLock(
                cacheName,
                key,
                policy.getDistributedLockWaitMs(),
                policy.getDistributedLockLeaseMs(),
                () -> {
                    Optional<CacheRecord<V>> latest = readFromL2(key);
                    if (latest.isPresent() && !isHardExpired(latest.get())) {
                        writeBackL1(key, latest.get());
                        return latest.get().getValue();
                    }
                    return invokeLoaderAndWrite(key, ttlSeconds);
                },
                this::markDistLockDegrade
        );
    }

    private V invokeLoaderAndWrite(K key, long ttlSeconds) {
        Function<K, V> loadFunction = this.loader;
        if (loadFunction == null) {
            return null;
        }
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
            if (record.isPresent()
                    && refreshPipeline.shouldRefresh(policy.isAutoRefreshEnabled(), record.get(), System.currentTimeMillis())) {
                triggerRefresh(key);
            }
        }
    }

    private void triggerRefreshIfSoftExpired(K key, CacheRecord<V> record) {
        if (refreshPipeline.shouldRefresh(policy.isAutoRefreshEnabled(), record, System.currentTimeMillis())) {
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
        long startNanos = System.nanoTime();
        CompletableFuture.supplyAsync(() -> loadAndWriteBack(key, policy.getHardTtlSeconds()), asyncExecutor)
                .whenComplete((value, throwable) -> {
                    long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
                    metricsCollector.recordRefreshLatency(latencyMs);
                    if (throwable != null || value == null) {
                        markRefreshFail();
                    } else {
                        markRefreshSuccess();
                    }
                    refreshingKeys.remove(key);
                });
    }

    private void trackKey(K key) {
        if (policy.isAutoRefreshEnabled() && key != null) {
            long accessCount = readCounter.computeIfAbsent(key, ignored -> new AtomicLong(0L)).incrementAndGet();
            if (refreshPipeline.shouldTrack(
                    policy.isAutoRefreshEnabled(),
                    accessCount,
                    policy.getHotKeyAccessThreshold(),
                    trackedKeys.size(),
                    policy.getMaxTrackedKeys())) {
                trackedKeys.add(key);
            }
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
        if (key == null || record == null) {
            return;
        }
        localVersion.put(key, record.getVersion());
        if (l1Store != null) {
            l1Store.put(key, record);
            markBackfillL1();
        }
    }

    private boolean isHardExpired(CacheRecord<V> record) {
        long hardExpire = record.getHardExpireAtMs();
        return hardExpire > 0 && hardExpire != Long.MAX_VALUE && System.currentTimeMillis() >= hardExpire;
    }

    private long nextVersion(K key) {
        long version = versionManager.nextVersion(key);
        localVersion.put(key, version);
        return version;
    }

    private void publishInvalidation(K key, long version) {
        if (policy.getSyncMode() == SyncMode.NONE) {
            return;
        }
        markSyncPublish();
        invalidationBus.publishInvalidation(cacheName, key, version, nodeId)
                .exceptionally(throwable -> {
                    LOGGER.debug("发布失效事件失败: cache={}, key={}, error={}", cacheName, key, throwable.getMessage());
                    return null;
                });
    }

    private void publishUpdate(K key, CacheRecord<V> record) {
        if (policy.getSyncMode() == SyncMode.NONE || record == null) {
            return;
        }
        markSyncPublish();
        invalidationBus.publishUpdate(cacheName, key, record, nodeId)
                .exceptionally(throwable -> {
                    LOGGER.debug("发布更新事件失败: cache={}, key={}, error={}", cacheName, key, throwable.getMessage());
                    return null;
                });
    }

    private void publishWriteEvent(K key, CacheRecord<V> record) {
        if (policy.getSyncMode() == SyncMode.UPDATE) {
            publishUpdate(key, record);
        } else {
            publishInvalidation(key, record != null ? record.getVersion() : 0L);
        }
    }

    private void publishClear(long version) {
        if (policy.getSyncMode() == SyncMode.NONE) {
            return;
        }
        markSyncPublish();
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
        markSyncConsume();
        if (event.getTimestamp() > 0) {
            long lag = Math.max(0L, System.currentTimeMillis() - event.getTimestamp());
            eventLagMs.set(lag);
            metricsCollector.recordEventLag(lag);
        }
        if (event.getOperation() == InvalidationEvent.Operation.CLEAR) {
            long incomingClearVersion = event.getVersion();
            long currentClearVersion = clearVersion.get();
            if (incomingClearVersion <= currentClearVersion) {
                droppedEvents.incrementAndGet();
                return;
            }
            clearVersion.set(incomingClearVersion);
            if (l1Store != null) {
                l1Store.clear();
            }
            trackedKeys.clear();
            readCounter.clear();
            localVersion.clear();
            return;
        }
        K key = event.getKey();
        if (key == null) {
            return;
        }

        long incoming = event.getVersion();
        long current = readFromL1(key).map(CacheRecord::getVersion)
                .orElseGet(() -> localVersion.getOrDefault(key, 0L));
        if (incoming <= current) {
            droppedEvents.incrementAndGet();
            return;
        }

        localVersion.put(key, incoming);
        if (event.getOperation() == InvalidationEvent.Operation.UPDATE) {
            CacheRecord<Object> payload = event.getRecord();
            if (payload == null || l1Store == null) {
                return;
            }
            @SuppressWarnings("unchecked")
            CacheRecord<V> casted = new CacheRecord<>(
                    (V) payload.getValue(),
                    payload.getVersion(),
                    payload.getWriteTimeMs(),
                    payload.getSoftExpireAtMs(),
                    payload.getHardExpireAtMs(),
                    payload.getSourceNodeId()
            );
            l1Store.put(key, casted);
            markBackfillL1();
            return;
        }

        if (l1Store != null) {
            l1Store.evict(key);
        }
    }

    public CacheStatsSnapshot statsSnapshot() {
        return new CacheStatsSnapshot(
                l1Hit.get(),
                l2Hit.get(),
                miss.get(),
                backfillL1.get(),
                backfillL2.get(),
                refreshSuccess.get(),
                refreshFail.get(),
                invalidatePublish.get(),
                invalidateConsume.get(),
                singleFlightJoin.get(),
                distLockDegrade.get()
        );
    }

    public Map<String, Object> diagnosticsSnapshot() {
        CacheStatsSnapshot stats = statsSnapshot();
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("cacheName", cacheName);
        snapshot.put("nodeId", nodeId);
        snapshot.put("policy.syncMode", policy.getSyncMode().name());
        snapshot.put("policy.autoRefresh", policy.isAutoRefreshEnabled());
        snapshot.put("policy.hardTtlSeconds", policy.getHardTtlSeconds());
        snapshot.put("policy.softTtlSeconds", policy.getSoftTtlSeconds());
        snapshot.put("policy.singleFlight", policy.isSingleFlightEnabled());
        snapshot.put("policy.distributedLock", policy.isDistributedLockEnabled());
        snapshot.put("trackedKeys", trackedKeys.size());
        snapshot.put("refreshingKeys", refreshingKeys.size());
        snapshot.put("localVersionKeys", localVersion.size());
        snapshot.put("eventLagMs", eventLagMs.get());
        snapshot.put("droppedEvents", droppedEvents.get());
        snapshot.put("hotKeysTopN", readCounter.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().get(), a.getValue().get()))
                .limit(10)
                .map(entry -> Map.of("key", String.valueOf(entry.getKey()), "count", entry.getValue().get()))
                .toList());
        snapshot.put("stats.l1Hit", stats.l1Hit());
        snapshot.put("stats.l2Hit", stats.l2Hit());
        snapshot.put("stats.miss", stats.miss());
        snapshot.put("stats.backfillL1", stats.backfillL1());
        snapshot.put("stats.backfillL2", stats.backfillL2());
        snapshot.put("stats.refreshSuccess", stats.refreshSuccess());
        snapshot.put("stats.refreshFail", stats.refreshFail());
        snapshot.put("stats.syncPublish", stats.invalidatePublish());
        snapshot.put("stats.syncConsume", stats.invalidateConsume());
        snapshot.put("stats.singleFlightJoin", stats.singleFlightJoin());
        snapshot.put("stats.distLockDegrade", stats.distLockDegrade());
        return snapshot;
    }

    private void markL1Hit() {
        l1Hit.incrementAndGet();
        metricsCollector.incL1Hit();
    }

    private void markL2Hit() {
        l2Hit.incrementAndGet();
        metricsCollector.incL2Hit();
    }

    private void markMiss() {
        miss.incrementAndGet();
        metricsCollector.incMiss();
    }

    private void markBackfillL1() {
        backfillL1.incrementAndGet();
        metricsCollector.incBackfillL1();
    }

    private void markBackfillL2() {
        backfillL2.incrementAndGet();
        metricsCollector.incBackfillL2();
    }

    private void markRefreshSuccess() {
        refreshSuccess.incrementAndGet();
        metricsCollector.incRefreshSuccess();
    }

    private void markRefreshFail() {
        refreshFail.incrementAndGet();
        metricsCollector.incRefreshFail();
    }

    private void markSyncPublish() {
        invalidatePublish.incrementAndGet();
        metricsCollector.incSyncPublish();
    }

    private void markSyncConsume() {
        invalidateConsume.incrementAndGet();
        metricsCollector.incSyncConsume();
    }

    private void markSingleFlightJoin() {
        singleFlightJoin.incrementAndGet();
        metricsCollector.incSingleFlightJoin();
    }

    private void markDistLockDegrade() {
        distLockDegrade.incrementAndGet();
        metricsCollector.incDistLockDegrade();
    }

    private void checkNotClosed() {
        if (closed.get()) {
            throw new IllegalStateException("缓存已关闭: " + cacheName);
        }
    }
}
