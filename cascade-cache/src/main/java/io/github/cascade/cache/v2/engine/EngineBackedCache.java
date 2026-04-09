package io.github.cascade.cache.v2.engine;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cascade.cache.v2.api.Cache;
import io.github.cascade.cache.v2.common.exception.CacheConfigurationException;
import io.github.cascade.cache.v2.consistency.VersionManager;
import io.github.cascade.cache.v2.engine.ReadPipeline;
import io.github.cascade.cache.v2.engine.RefreshPipeline;
import io.github.cascade.cache.v2.engine.WritePipeline;
import io.github.cascade.cache.v2.loader.DistLockCoordinator;
import io.github.cascade.cache.v2.store.model.CacheRecord;
import io.github.cascade.cache.v2.consistency.InvalidationEvent;
import io.github.cascade.cache.v2.observability.CacheMetricsCollector;
import io.github.cascade.cache.v2.observability.CacheStatsSnapshot;
import io.github.cascade.cache.v2.policy.CachePolicy;
import io.github.cascade.cache.v2.policy.LockFailureStrategy;
import io.github.cascade.cache.v2.policy.RefreshExecutionOptions;
import io.github.cascade.cache.v2.policy.SyncMode;
import io.github.cascade.cache.v2.store.l1.L1CacheStore;
import io.github.cascade.cache.v2.store.l2.L2CacheStore;
import io.github.cascade.cache.v2.loader.LoaderPriority;
import io.github.cascade.cache.v2.loader.SingleFlight;
import io.github.cascade.cache.v2.consistency.InvalidationBus;
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
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * V2 统一缓存引擎实现。
 */
public class EngineBackedCache<K, V> implements Cache<K, V> {

    private static final Logger LOGGER = LoggerFactory.getLogger(EngineBackedCache.class);
    private static final String UPDATE_PAYLOAD_CODEC = "jackson-json-v1";
    private static final int ASYNC_POOL_SIZE = 2;
    private static final int ASYNC_QUEUE_CAPACITY = 1024;
    private static final int READ_COUNTER_MAX_FACTOR = 4;
    private static final long READ_COUNTER_EXPIRE_AFTER_ACCESS_MINUTES = 30L;
    private static final int LOCAL_VERSION_MAX_FACTOR = 16;
    private static final long LOCAL_VERSION_MIN_MAX_SIZE = 10_000L;
    private static final long LOCAL_VERSION_EXPIRE_AFTER_ACCESS_MINUTES = 60L;
    private static final AtomicInteger ASYNC_THREAD_COUNTER = new AtomicInteger(0);
    private static final AtomicInteger REFRESH_THREAD_COUNTER = new AtomicInteger(0);

    private final String cacheName;
    private final CachePolicy policy;
    private final L1CacheStore<K, V> l1Store;
    private final L2CacheStore<K, V> l2Store;
    private final InvalidationBus<K> invalidationBus;
    private final VersionManager<K> versionManager;
    private final DistLockCoordinator<K> lockCoordinator;
    private final CacheMetricsCollector metricsCollector;
    private final Class<V> valueType;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String nodeId;
    private final RefreshExecutionOptions refreshOptions;
    private final ExecutorService asyncExecutor;
    private final ThreadPoolExecutor refreshExecutor;
    private final ScheduledExecutorService refreshScheduler;
    private final SingleFlight<K, V> singleFlight = new SingleFlight<>();
    private final ReadPipeline<K, V> readPipeline = new ReadPipeline<>();
    private final WritePipeline<V> writePipeline = new WritePipeline<>();
    private final RefreshPipeline refreshPipeline = new RefreshPipeline();
    private final Set<K> trackedKeys = ConcurrentHashMap.newKeySet();
    private final Set<K> refreshingKeys = ConcurrentHashMap.newKeySet();
    private final com.github.benmanes.caffeine.cache.Cache<K, Long> localVersion;
    private final com.github.benmanes.caffeine.cache.Cache<K, AtomicLong> readCounter;
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
    private final AtomicLong syncUpdateFallback = new AtomicLong(0L);
    private final AtomicLong singleFlightJoin = new AtomicLong(0L);
    private final AtomicLong distLockDegrade = new AtomicLong(0L);
    private final AtomicLong eventLagMs = new AtomicLong(0L);
    private final AtomicLong droppedEvents = new AtomicLong(0L);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean subscribed = new AtomicBoolean(false);
    private final AtomicBoolean refreshStarted = new AtomicBoolean(false);

    private volatile Function<K, V> loader;
    private volatile int loaderPriority = LoaderPriority.DEFAULT;

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
                null, CacheMetricsCollector.create(cacheName, null));
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
        this(cacheName, policy, l1Store, l2Store, loader, invalidationBus, versionManager, lockCoordinator, nodeId,
                null, metricsCollector);
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
                             Class<V> valueType,
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
        this.valueType = valueType != null ? valueType : castObjectClass();
        this.nodeId = nodeId;
        this.refreshOptions = policy.getRefreshExecutionOptions() != null
                ? policy.getRefreshExecutionOptions()
                : RefreshExecutionOptions.defaults();
        this.loaderPriority = LoaderPriority.of(loader);
        this.asyncExecutor = createBoundedAsyncExecutor(cacheName);
        this.refreshExecutor = createRefreshExecutor(cacheName, this.refreshOptions);
        this.readCounter = createReadCounterCache(policy.getMaxTrackedKeys());
        this.localVersion = createLocalVersionCache(policy.getMaxTrackedKeys());
        this.refreshScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "cascade-refresh-scheduler-" + cacheName);
            thread.setDaemon(true);
            return thread;
        });
        this.clearVersion.set(versionManager.currentClearVersion());

        startSyncIfNeeded();
        startRefreshIfNeeded();
    }

    @SuppressWarnings("unchecked")
    private static <T> Class<T> castObjectClass() {
        return (Class<T>) Object.class;
    }

    public void setLoaderIfAbsent(Function<K, V> candidate) {
        if (candidate == null) {
            return;
        }
        int candidatePriority = LoaderPriority.of(candidate);
        synchronized (this) {
            if (this.loader == null) {
                this.loader = candidate;
                this.loaderPriority = candidatePriority;
                LOGGER.info("为缓存设置延迟加载器: cache={}, priority={}", cacheName, candidatePriority);
                return;
            }
            if (candidatePriority > this.loaderPriority) {
                int previousPriority = this.loaderPriority;
                this.loader = candidate;
                this.loaderPriority = candidatePriority;
                LOGGER.info("为缓存升级加载器: cache={}, oldPriority={}, newPriority={}",
                        cacheName, previousPriority, candidatePriority);
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
        Optional<V> cached = readCachedValue(key);
        if (cached.isPresent()) {
            return cached;
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
        trackKey(key);
        Optional<V> cached = readCachedValue(key);
        if (cached.isPresent()) {
            return cached.get();
        }

        markMiss();
        if (fallbackLoader == null) {
            return loadAndWriteBack(key, policy.getHardTtlSeconds());
        }

        V loaded = fallbackLoader.apply(key);
        if (loaded != null) {
            put(key, loaded);
        }
        return loaded;
    }

    @Override
    public CompletableFuture<Optional<V>> getAsync(K key) {
        checkNotClosed();
        return supplyAsyncSafely(() -> get(key));
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
        return runAsyncSafely(() -> put(key, value));
    }

    @Override
    public void evict(K key) {
        checkNotClosed();
        if (key == null) {
            return;
        }
        long version = evictInternal(key);
        publishInvalidation(key, version);
    }

    @Override
    public void clear() {
        checkNotClosed();
        long version = clearInternal();
        publishClear(version);
    }

    /**
     * 仅执行本地驱逐，不发布失效同步事件。
     */
    public void evictWithoutSync(K key) {
        checkNotClosed();
        if (key == null) {
            return;
        }
        evictInternal(key);
    }

    /**
     * 仅执行本地清空，不发布清空同步事件。
     */
    public void clearWithoutSync() {
        checkNotClosed();
        clearInternal();
    }

    private long evictInternal(K key) {
        long version = nextVersion(key);
        if (l2Store != null) {
            l2Store.evict(key);
        }
        if (l1Store != null) {
            l1Store.evict(key);
        }
        trackedKeys.remove(key);
        readCounter.invalidate(key);
        localVersion.put(key, version);
        return version;
    }

    private long clearInternal() {
        if (l2Store != null) {
            l2Store.clear();
        }
        if (l1Store != null) {
            l1Store.clear();
        }
        trackedKeys.clear();
        readCounter.invalidateAll();
        localVersion.invalidateAll();

        long version = resolveClearVersionAfterStoreClear();
        clearVersion.set(version);
        return version;
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

        shutdownExecutorGracefully(refreshScheduler, refreshOptions.shutdownTimeoutSeconds());
        shutdownExecutorGracefully(refreshExecutor, refreshOptions.shutdownTimeoutSeconds());
        shutdownExecutorGracefully(asyncExecutor, refreshOptions.shutdownTimeoutSeconds());

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

    private Optional<V> readCachedValue(K key) {
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
        if (!readResult.hit()) {
            return Optional.empty();
        }

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

    private V loadWithProtection(K key, long ttlSeconds) {
        if (!policy.isDistributedLockEnabled()) {
            return invokeLoaderAndWrite(key, ttlSeconds);
        }
        DistLockCoordinator.LockResult<V> result = lockCoordinator.withLock(
                cacheName,
                key,
                policy.getDistributedLockWaitMs(),
                policy.getDistributedLockLeaseMs(),
                () -> loadWithL2RecheckThenSource(key, ttlSeconds)
        );
        if (result.outcome() == DistLockCoordinator.Outcome.ACQUIRED) {
            return result.value();
        }

        markDistLockDegrade();
        if (policy.getLockFailureStrategy() == LockFailureStrategy.STRICT) {
            if (result.error() != null) {
                LOGGER.warn("分布式锁异常且采用STRICT策略，放弃加载: cache={}, key={}, error={}",
                        cacheName, key, result.error().getMessage());
            }
            return null;
        }
        return loadWithL2RecheckThenSource(key, ttlSeconds);
    }

    private V loadWithL2RecheckThenSource(K key, long ttlSeconds) {
        Optional<CacheRecord<V>> latest = readFromL2(key);
        if (latest.isPresent() && !isHardExpired(latest.get())) {
            writeBackL1(key, latest.get());
            return latest.get().getValue();
        }
        return invokeLoaderAndWrite(key, ttlSeconds);
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
        } catch (CacheConfigurationException e) {
            throw e;
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
        if (!refreshOptions.startOnInit()) {
            return;
        }
        ensureRefreshSchedulerStarted();
    }

    private void ensureRefreshSchedulerStarted() {
        if (!policy.isAutoRefreshEnabled()) {
            return;
        }
        if (!refreshStarted.compareAndSet(false, true)) {
            return;
        }
        long interval = Math.max(1L, policy.getRefreshIntervalSeconds());
        try {
            refreshScheduler.scheduleWithFixedDelay(this::refreshTrackedKeysSafely, interval, interval, TimeUnit.SECONDS);
        } catch (RejectedExecutionException e) {
            refreshStarted.set(false);
            LOGGER.warn("刷新调度启动失败: cache={}, error={}", cacheName, e.getMessage());
        }
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
        if (!policy.isAutoRefreshEnabled() || trackedKeys.isEmpty()) {
            return;
        }

        for (K key : new ArrayList<>(trackedKeys)) {
            Optional<CacheRecord<V>> record = readFromL1(key);
            if (record.isEmpty()) {
                record = readFromL2(key);
            }
            if (record.isEmpty()) {
                pruneTrackingState(key, true);
                continue;
            }
            CacheRecord<V> resolved = record.get();
            if (isHardExpired(resolved)) {
                if (l1Store != null) {
                    l1Store.evict(key);
                }
                if (l2Store != null) {
                    l2Store.evict(key);
                }
                pruneTrackingState(key, true);
                continue;
            }
            if (loader != null
                    && refreshPipeline.shouldRefresh(policy.isAutoRefreshEnabled(), resolved, System.currentTimeMillis())) {
                triggerRefresh(key);
            }
        }
    }

    private void triggerRefreshIfSoftExpired(K key, CacheRecord<V> record) {
        ensureRefreshSchedulerStarted();
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
        runRefreshWithRetry(key, 0)
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

    private CompletableFuture<V> runRefreshWithRetry(K key, int attempt) {
        CompletableFuture<V> refreshAttempt;
        try {
            refreshAttempt = CompletableFuture.supplyAsync(
                    () -> loadAndWriteBack(key, policy.getHardTtlSeconds()),
                    refreshExecutor
            );
        } catch (RejectedExecutionException e) {
            LOGGER.debug("刷新任务提交被拒绝: cache={}, key={}, attempt={}, error={}",
                    cacheName, key, attempt + 1, e.getMessage());
            return retryLaterOrComplete(key, attempt);
        }

        CompletableFuture<V> timeoutGuard = refreshAttempt.orTimeout(refreshOptions.refreshTimeoutSeconds(), TimeUnit.SECONDS);
        return timeoutGuard.handle((value, throwable) -> {
            if (throwable == null && value != null) {
                return CompletableFuture.completedFuture(value);
            }
            if (isTimeout(throwable)) {
                refreshAttempt.cancel(true);
                LOGGER.debug("刷新任务超时: cache={}, key={}, attempt={}, timeoutSeconds={}",
                        cacheName, key, attempt + 1, refreshOptions.refreshTimeoutSeconds());
            } else if (throwable != null) {
                LOGGER.debug("刷新任务失败: cache={}, key={}, attempt={}, error={}",
                        cacheName, key, attempt + 1, rootCauseMessage(throwable));
            } else {
                LOGGER.debug("刷新任务返回空值: cache={}, key={}, attempt={}", cacheName, key, attempt + 1);
            }
            return retryLaterOrComplete(key, attempt);
        }).thenCompose(Function.identity());
    }

    private CompletableFuture<V> retryLaterOrComplete(K key, int attempt) {
        if (attempt >= refreshOptions.maxRetries()) {
            return CompletableFuture.completedFuture(null);
        }
        int nextAttempt = attempt + 1;
        long delaySeconds = Math.max(0L, refreshOptions.retryIntervalSeconds());
        if (delaySeconds == 0L) {
            return runRefreshWithRetry(key, nextAttempt);
        }

        CompletableFuture<V> chained = new CompletableFuture<>();
        try {
            refreshScheduler.schedule(
                    () -> runRefreshWithRetry(key, nextAttempt)
                            .whenComplete((value, throwable) -> completeFuture(chained, value, throwable)),
                    delaySeconds,
                    TimeUnit.SECONDS
            );
        } catch (RejectedExecutionException e) {
            chained.complete(null);
        }
        return chained;
    }

    private static <T> void completeFuture(CompletableFuture<T> target, T value, Throwable throwable) {
        if (throwable == null) {
            target.complete(value);
        } else {
            target.completeExceptionally(throwable);
        }
    }

    private static boolean isTimeout(Throwable throwable) {
        Throwable root = rootCause(throwable);
        return root instanceof TimeoutException;
    }

    private static String rootCauseMessage(Throwable throwable) {
        Throwable root = rootCause(throwable);
        return root == null ? "unknown" : root.getMessage();
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current != null && current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private void trackKey(K key) {
        if (policy.isAutoRefreshEnabled() && key != null) {
            ensureRefreshSchedulerStarted();
            long accessCount = readCounter.asMap().computeIfAbsent(key, ignored -> new AtomicLong(0L)).incrementAndGet();
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

    private static ExecutorService createBoundedAsyncExecutor(String cacheName) {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                ASYNC_POOL_SIZE,
                ASYNC_POOL_SIZE,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(ASYNC_QUEUE_CAPACITY),
                runnable -> {
                    Thread thread = new Thread(runnable, "cascade-async-" + cacheName + "-" + ASYNC_THREAD_COUNTER.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
        executor.prestartAllCoreThreads();
        return executor;
    }

    private static ThreadPoolExecutor createRefreshExecutor(String cacheName, RefreshExecutionOptions options) {
        int poolSize = options.effectiveThreadPoolSize();
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                poolSize,
                poolSize,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(options.queueCapacity()),
                runnable -> {
                    Thread thread = new Thread(
                            runnable,
                            "cascade-refresh-worker-" + cacheName + "-" + REFRESH_THREAD_COUNTER.incrementAndGet()
                    );
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
        executor.prestartAllCoreThreads();
        return executor;
    }

    private static <K> com.github.benmanes.caffeine.cache.Cache<K, AtomicLong> createReadCounterCache(int maxTrackedKeys) {
        long maxSize = Math.max(1_000L, (long) Math.max(1, maxTrackedKeys) * READ_COUNTER_MAX_FACTOR);
        return Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterAccess(READ_COUNTER_EXPIRE_AFTER_ACCESS_MINUTES, TimeUnit.MINUTES)
                .build();
    }

    private static <K> com.github.benmanes.caffeine.cache.Cache<K, Long> createLocalVersionCache(int maxTrackedKeys) {
        long maxSize = Math.max(
                LOCAL_VERSION_MIN_MAX_SIZE,
                (long) Math.max(1, maxTrackedKeys) * LOCAL_VERSION_MAX_FACTOR
        );
        // 版本仅用于本地事件去重，不需要无限保留；通过容量+过期限制高基数场景的内存占用。
        return Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterAccess(LOCAL_VERSION_EXPIRE_AFTER_ACCESS_MINUTES, TimeUnit.MINUTES)
                .build();
    }

    private <T> CompletableFuture<T> supplyAsyncSafely(Supplier<T> supplier) {
        try {
            return CompletableFuture.supplyAsync(supplier, asyncExecutor);
        } catch (RejectedExecutionException e) {
            LOGGER.debug("异步任务提交被拒绝，降级为同步执行: cache={}, error={}", cacheName, e.getMessage());
            try {
                return CompletableFuture.completedFuture(supplier.get());
            } catch (Exception ex) {
                return CompletableFuture.failedFuture(ex);
            }
        }
    }

    private CompletableFuture<Void> runAsyncSafely(Runnable runnable) {
        return supplyAsyncSafely(() -> {
            runnable.run();
            return null;
        });
    }

    private static void shutdownExecutorGracefully(ExecutorService executor, long timeoutSeconds) {
        if (executor == null) {
            return;
        }
        long waitSeconds = Math.max(0L, timeoutSeconds);
        try {
            executor.shutdown();
            if (waitSeconds == 0L || !executor.awaitTermination(waitSeconds, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        } catch (Exception ignored) {
            executor.shutdownNow();
        }
    }

    private long resolveClearVersionAfterStoreClear() {
        if (l2Store == null) {
            return versionManager.nextClearVersion();
        }
        long current = versionManager.currentClearVersion();
        if (current > 0) {
            return current;
        }
        return versionManager.nextClearVersion();
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
        invalidationBus.publishUpdate(cacheName, key, record, valueType.getName(), nodeId)
                .exceptionally(throwable -> {
                    LOGGER.debug("发布更新事件失败: cache={}, key={}, error={}", cacheName, key, throwable.getMessage());
                    return null;
                });
    }

    private void publishWriteEvent(K key, CacheRecord<V> record) {
        if (shouldPublishUpdate(record)) {
            publishUpdate(key, record);
        } else {
            publishInvalidation(key, record != null ? record.getVersion() : 0L);
        }
    }

    private boolean shouldPublishUpdate(CacheRecord<V> record) {
        if (policy.getSyncMode() != SyncMode.UPDATE || !policy.isSyncUpdateEnabled() || record == null) {
            return false;
        }
        Object value = record.getValue();
        if (value == null) {
            markSyncUpdateFallback();
            return false;
        }
        try {
            int payloadBytes = objectMapper.writeValueAsBytes(value).length;
            if (payloadBytes > policy.getSyncUpdateMaxPayloadBytes()) {
                markSyncUpdateFallback();
                LOGGER.debug("UPDATE事件payload超限，降级为INVALIDATE: cache={}, keyPayloadBytes={}, limit={}",
                        cacheName, payloadBytes, policy.getSyncUpdateMaxPayloadBytes());
                return false;
            }
            return true;
        } catch (Exception e) {
            markSyncUpdateFallback();
            LOGGER.debug("UPDATE事件payload序列化失败，降级为INVALIDATE: cache={}, error={}", cacheName, e.getMessage());
            return false;
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
            readCounter.invalidateAll();
            localVersion.invalidateAll();
            return;
        }
        K key = event.getKey();
        if (key == null) {
            return;
        }

        long incoming = event.getVersion();
        long current = readFromL1(key).map(CacheRecord::getVersion)
                .orElseGet(() -> Optional.ofNullable(localVersion.getIfPresent(key)).orElse(0L));
        if (incoming <= current) {
            droppedEvents.incrementAndGet();
            return;
        }

        localVersion.put(key, incoming);
        if (event.getOperation() == InvalidationEvent.Operation.UPDATE) {
            applyUpdateOrInvalidate(key, event);
            return;
        }

        if (l1Store != null) {
            l1Store.evict(key);
        }
        pruneTrackingState(key, false);
    }

    private void applyUpdateOrInvalidate(K key, InvalidationEvent<K> event) {
        InvalidationEvent.UpdatePayload payload = event.getUpdatePayload();
        if (payload == null || l1Store == null) {
            fallbackInvalidateOnUpdate(key, "payload为空或L1不可用");
            return;
        }
        if (payload.getCodec() == null || !UPDATE_PAYLOAD_CODEC.equals(payload.getCodec())) {
            fallbackInvalidateOnUpdate(key, "payload codec不支持: " + payload.getCodec());
            return;
        }
        String eventType = event.getValueTypeName();
        if (eventType == null || eventType.isBlank()) {
            fallbackInvalidateOnUpdate(key, "valueType缺失");
            return;
        }
        if (valueType != Object.class
                && !valueType.getName().equals(eventType)) {
            fallbackInvalidateOnUpdate(key, "valueType不匹配: event=" + eventType + ", local=" + valueType.getName());
            return;
        }
        try {
            String valuePayload = payload.getValuePayload();
            if (valuePayload == null || valuePayload.isBlank()) {
                fallbackInvalidateOnUpdate(key, "payload.value为空");
                return;
            }
            V typedValue = decodeUpdateValue(valuePayload, eventType);
            if (typedValue == null) {
                fallbackInvalidateOnUpdate(key, "payload.value为空");
                return;
            }
            CacheRecord<V> casted = new CacheRecord<>(
                    typedValue,
                    event.getVersion(),
                    payload.getWriteTimeMs(),
                    payload.getSoftExpireAtMs(),
                    payload.getHardExpireAtMs(),
                    payload.getSourceNodeId()
            );
            l1Store.put(key, casted);
            markBackfillL1();
        } catch (Exception e) {
            fallbackInvalidateOnUpdate(key, "payload转换失败: " + e.getMessage());
        }
    }

    private void fallbackInvalidateOnUpdate(K key, String reason) {
        markSyncUpdateFallback();
        if (l1Store != null) {
            l1Store.evict(key);
        }
        LOGGER.debug("UPDATE事件降级为INVALIDATE: cache={}, key={}, reason={}", cacheName, key, reason);
    }

    @SuppressWarnings("unchecked")
    private V decodeUpdateValue(String valuePayload, String eventTypeName) throws Exception {
        if (valueType == Object.class) {
            if (eventTypeName != null && !eventTypeName.isBlank()) {
                try {
                    Class<?> declaredType = Class.forName(eventTypeName);
                    Object value = objectMapper.readValue(valuePayload, declaredType);
                    return (V) value;
                } catch (ClassNotFoundException ignored) {
                    // 保底按Object反序列化，避免类型缺失导致整个更新失败
                }
            }
            return (V) objectMapper.readValue(valuePayload, Object.class);
        }
        Object raw = objectMapper.readValue(valuePayload, Object.class);
        if (valueType.isInstance(raw)) {
            return valueType.cast(raw);
        }
        return objectMapper.convertValue(raw, valueType);
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
                syncUpdateFallback.get(),
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
        snapshot.put("policy.syncUpdateEnabled", policy.isSyncUpdateEnabled());
        snapshot.put("policy.syncUpdateMaxPayloadBytes", policy.getSyncUpdateMaxPayloadBytes());
        snapshot.put("policy.autoRefresh", policy.isAutoRefreshEnabled());
        snapshot.put("policy.refresh.startOnInit", refreshOptions.startOnInit());
        snapshot.put("policy.refresh.allowConcurrentRefresh", refreshOptions.allowConcurrentRefresh());
        snapshot.put("policy.refresh.threadPoolSize", refreshOptions.threadPoolSize());
        snapshot.put("policy.refresh.effectiveThreadPoolSize", refreshOptions.effectiveThreadPoolSize());
        snapshot.put("policy.refresh.queueCapacity", refreshOptions.queueCapacity());
        snapshot.put("policy.refresh.timeoutSeconds", refreshOptions.refreshTimeoutSeconds());
        snapshot.put("policy.refresh.maxRetries", refreshOptions.maxRetries());
        snapshot.put("policy.refresh.retryIntervalSeconds", refreshOptions.retryIntervalSeconds());
        snapshot.put("policy.refresh.shutdownTimeoutSeconds", refreshOptions.shutdownTimeoutSeconds());
        snapshot.put("policy.hardTtlSeconds", policy.getHardTtlSeconds());
        snapshot.put("policy.softTtlSeconds", policy.getSoftTtlSeconds());
        snapshot.put("policy.singleFlight", policy.isSingleFlightEnabled());
        snapshot.put("policy.distributedLock", policy.isDistributedLockEnabled());
        snapshot.put("policy.lockFailureStrategy", policy.getLockFailureStrategy().name());
        snapshot.put("refreshSchedulerStarted", refreshStarted.get());
        snapshot.put("refreshExecutor.activeCount", refreshExecutor.getActiveCount());
        snapshot.put("refreshExecutor.poolSize", refreshExecutor.getPoolSize());
        snapshot.put("refreshExecutor.queueSize", refreshExecutor.getQueue().size());
        snapshot.put("trackedKeys", trackedKeys.size());
        snapshot.put("refreshingKeys", refreshingKeys.size());
        snapshot.put("localVersionKeys", localVersion.estimatedSize());
        snapshot.put("eventLagMs", eventLagMs.get());
        snapshot.put("droppedEvents", droppedEvents.get());
        snapshot.put("hotKeysTopN", readCounter.asMap().entrySet().stream()
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
        snapshot.put("stats.syncUpdateFallback", stats.syncUpdateFallback());
        snapshot.put("stats.singleFlightJoin", stats.singleFlightJoin());
        snapshot.put("stats.distLockDegrade", stats.distLockDegrade());
        return snapshot;
    }

    private void pruneTrackingState(K key, boolean clearVersionState) {
        if (key == null) {
            return;
        }
        trackedKeys.remove(key);
        refreshingKeys.remove(key);
        readCounter.invalidate(key);
        if (clearVersionState) {
            localVersion.invalidate(key);
        }
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

    private void markSyncUpdateFallback() {
        syncUpdateFallback.incrementAndGet();
        metricsCollector.incSyncUpdateFallback();
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
