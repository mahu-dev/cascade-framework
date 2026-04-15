package io.github.cascade.cache.v2.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cascade.cache.v2.api.Cache;
import io.github.cascade.cache.v2.consistency.InvalidationBus;
import io.github.cascade.cache.v2.consistency.VersionManager;
import io.github.cascade.cache.v2.loader.DistLockCoordinator;
import io.github.cascade.cache.v2.loader.LoaderPriority;
import io.github.cascade.cache.v2.loader.SingleFlight;
import io.github.cascade.cache.v2.observability.CacheMetricsCollector;
import io.github.cascade.cache.v2.observability.CacheStatsSnapshot;
import io.github.cascade.cache.v2.policy.CachePolicy;
import io.github.cascade.cache.v2.policy.RefreshExecutionOptions;
import io.github.cascade.cache.v2.store.l1.L1CacheStore;
import io.github.cascade.cache.v2.store.l2.L2CacheStore;
import io.github.cascade.cache.v2.store.model.CacheRecord;
import io.github.cascade.cache.v2.support.ObjectMapperHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * V2 统一缓存引擎实现。
 */
public class EngineBackedCache<K, V> implements Cache<K, V> {

    private static final Logger LOGGER = LoggerFactory.getLogger(EngineBackedCache.class);
    /**
     * 异步包装任务线程数；主链路仍同步执行，2 线程足够覆盖常见并发且避免过度切换。
     */
    private static final int ASYNC_POOL_SIZE = 2;
    /**
     * 异步有界队列上限；满载后回退同步执行，避免无界堆积。
     */
    private static final int ASYNC_QUEUE_CAPACITY = 1024;
    /**
     * 热点读计数容量系数：maxTrackedKeys * 4，覆盖短时并发尖峰。
     */
    private static final int READ_COUNTER_MAX_FACTOR = 4;
    /**
     * 热点读计数访问后过期（分钟）：平衡热点识别窗口与内存占用。
     */
    private static final long READ_COUNTER_EXPIRE_AFTER_ACCESS_MINUTES = 30L;
    /**
     * 本地版本去重容量系数：高于读计数，降低高基数场景误淘汰。
     */
    private static final int LOCAL_VERSION_MAX_FACTOR = 16;
    /**
     * 本地版本去重最小容量下限，避免低配置下频繁抖动。
     */
    private static final long LOCAL_VERSION_MIN_MAX_SIZE = 10_000L;
    /**
     * 本地版本去重访问后过期（分钟）：覆盖事件传播延迟与重试窗口。
     */
    private static final long LOCAL_VERSION_EXPIRE_AFTER_ACCESS_MINUTES = 60L;
    private static final AtomicInteger ASYNC_THREAD_COUNTER = new AtomicInteger(0);
    private static final AtomicInteger REFRESH_THREAD_COUNTER = new AtomicInteger(0);

    /**
     * 缓存名称，贯穿日志、指标、同步主题和诊断输出。
     */
    private final String cacheName;
    /**
     * 统一运行策略，决定 TTL、刷新、同步、回填和保护能力。
     */
    private final CachePolicy policy;
    /**
     * 本地一级缓存；为空表示禁用 L1。
     */
    private final L1CacheStore<K, V> l1Store;
    /**
     * 共享二级缓存；为空表示禁用 L2。
     */
    private final L2CacheStore<K, V> l2Store;
    /**
     * 指标采集器。
     */
    private final CacheMetricsCollector metricsCollector;
    /**
     * 值类型，用于同步 UPDATE 事件反序列化。
     */
    private final Class<V> valueType;
    private final ObjectMapper objectMapper = ObjectMapperHolder.getInstance();
    /**
     * 当前节点标识，用于同步消息去环与诊断。
     */
    private final String nodeId;
    /**
     * 刷新执行参数快照。
     */
    private final RefreshExecutionOptions refreshOptions;
    /**
     * 对外异步 API 的轻量线程池。
     */
    private final ExecutorService asyncExecutor;
    /**
     * 实际执行刷新任务的线程池。
     */
    private final ThreadPoolExecutor refreshExecutor;
    /**
     * 刷新定时扫描调度器。
     */
    private final ScheduledExecutorService refreshScheduler;
    /**
     * 同 key 并发加载合并器。
     */
    private final SingleFlight<K, V> singleFlight = new SingleFlight<>();
    private final ReadPipeline<K, V> readPipeline = new ReadPipeline<>();
    private final WritePipeline<V> writePipeline = new WritePipeline<>();
    private final RefreshPipeline refreshPipeline = new RefreshPipeline();
    /**
     * 已被识别为热点且需要纳入自动刷新的 key 集。
     */
    private final Set<K> trackedKeys = ConcurrentHashMap.newKeySet();
    /**
     * 正在刷新中的 key 集，避免同 key 重复提交刷新任务。
     */
    private final Set<K> refreshingKeys = ConcurrentHashMap.newKeySet();
    /**
     * 本地观察到的 key 版本，用于过滤旧同步事件。
     */
    private final com.github.benmanes.caffeine.cache.Cache<K, Long> localVersion;
    /**
     * 热点 key 访问计数。
     */
    private final com.github.benmanes.caffeine.cache.Cache<K, AtomicLong> readCounter;
    /**
     * clear 操作的逻辑版本号。
     */
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
    /**
     * 驱逐相关委托，统一处理版本推进和本地状态清理。
     */
    private final EngineBackedCacheEviction<K, V> evictionDelegate;
    /**
     * 写链路委托，统一处理回源、写透、回填和同步事件发布。
     */
    private final EngineBackedCacheWrite<K, V> writeDelegate;
    /**
     * 刷新链路委托，统一处理热点追踪和异步刷新。
     */
    private final EngineBackedCacheRefresh<K, V> refreshDelegate;
    /**
     * 同步链路委托，统一处理失效/更新事件的发布与消费。
     */
    private final EngineBackedCacheSync<K, V> syncDelegate;
    /**
     * 生命周期委托，统一处理订阅、关闭和线程池释放。
     */
    private final EngineBackedCacheLifecycle<K, V> lifecycleDelegate;

    /**
     * 当前生效的加载器，可由注册表或注解回放补挂。
     */
    private volatile Function<K, V> loader;
    /**
     * 当前加载器优先级，确保显式 loader 能覆盖兜底 loader。
     */
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
        this.metricsCollector = metricsCollector != null ? metricsCollector : CacheMetricsCollector.create(cacheName, null);
        this.valueType = valueType != null ? valueType : castObjectClass();
        this.nodeId = nodeId;
        this.refreshOptions = policy.getRefreshExecutionOptions() != null
                ? policy.getRefreshExecutionOptions()
                : RefreshExecutionOptions.defaults();
        this.loaderPriority = LoaderPriority.of(loader);
        this.asyncExecutor = EngineBackedCacheCore.createBoundedAsyncExecutor(
                cacheName, ASYNC_THREAD_COUNTER, ASYNC_POOL_SIZE, ASYNC_QUEUE_CAPACITY
        );
        this.refreshExecutor = EngineBackedCacheCore.createRefreshExecutor(
                cacheName, this.refreshOptions, REFRESH_THREAD_COUNTER
        );
        this.readCounter = EngineBackedCacheCore.createReadCounterCache(
                policy.getMaxTrackedKeys(), READ_COUNTER_MAX_FACTOR, READ_COUNTER_EXPIRE_AFTER_ACCESS_MINUTES
        );
        this.localVersion = EngineBackedCacheCore.createLocalVersionCache(
                policy.getMaxTrackedKeys(), LOCAL_VERSION_MIN_MAX_SIZE, LOCAL_VERSION_MAX_FACTOR,
                LOCAL_VERSION_EXPIRE_AFTER_ACCESS_MINUTES
        );
        this.refreshScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "cascade-refresh-scheduler-" + cacheName);
            thread.setDaemon(true);
            return thread;
        });
        this.clearVersion.set(versionManager.currentClearVersion());
        this.evictionDelegate = new EngineBackedCacheEviction<>(
                l1Store,
                l2Store,
                trackedKeys,
                readCounter,
                localVersion,
                versionManager,
                clearVersion
        );
        this.writeDelegate = new EngineBackedCacheWrite<>(
                LOGGER,
                cacheName,
                nodeId,
                policy,
                l2Store,
                lockCoordinator,
                singleFlight,
                writePipeline,
                () -> this.loader,
                evictionDelegate::nextVersion,
                this::readFromL2,
                this::isHardExpired,
                this::publishWriteEvent,
                this::writeBackL1,
                this::markBackfillL2,
                this::markSingleFlightJoin,
                this::markDistLockDegrade
        );
        this.refreshDelegate = new EngineBackedCacheRefresh<>(
                LOGGER,
                cacheName,
                policy,
                this.refreshOptions,
                refreshScheduler,
                refreshExecutor,
                refreshPipeline,
                l1Store,
                l2Store,
                refreshStarted,
                trackedKeys,
                refreshingKeys,
                readCounter,
                () -> this.loader,
                this::readFromL1,
                this::readFromL2,
                this::isHardExpired,
                this::pruneTrackingState,
                writeDelegate::invokeLoaderAndWriteForRefresh,
                this::markRefreshSuccess,
                this::markRefreshFail
        );
        this.syncDelegate = new EngineBackedCacheSync<>(
                LOGGER,
                cacheName,
                policy,
                invalidationBus,
                nodeId,
                this.valueType,
                objectMapper,
                l1Store,
                clearVersion,
                trackedKeys,
                readCounter,
                localVersion,
                eventLagMs,
                droppedEvents,
                this.metricsCollector,
                this::readFromL1,
                this::pruneTrackingState,
                this::markSyncPublish,
                this::markSyncConsume,
                this::markSyncUpdateFallback,
                this::markBackfillL1
        );
        this.lifecycleDelegate = new EngineBackedCacheLifecycle<>(
                LOGGER,
                cacheName,
                closed,
                subscribed,
                refreshScheduler,
                refreshExecutor,
                asyncExecutor,
                this.refreshOptions,
                invalidationBus,
                l1Store,
                l2Store
        );

        syncDelegate.startSyncIfNeeded(subscribed);
        refreshDelegate.startRefreshIfNeeded();
    }

    @SuppressWarnings("unchecked")
    private static <T> Class<T> castObjectClass() {
        return (Class<T>) Object.class;
    }

    /**
     * 在缓存已创建后按优先级补挂 loader。
     * <p>
     * 典型场景：
     * 1. 编程式先建缓存，后注册强类型 loader；
     * 2. 注解式先拿到缓存，再补上 snapshot fallback loader。
     */
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
        // 命中路径也要先做热点追踪，后续自动刷新依赖这些访问样本。
        refreshDelegate.trackKey(key);
        Optional<V> cached = readCachedValue(key);
        if (cached.isPresent()) {
            return cached;
        }

        markMiss();
        V loaded = writeDelegate.loadAndWriteBack(key, policy.getHardTtlSeconds());
        return Optional.ofNullable(loaded);
    }

    @Override
    public Optional<V> getIfPresent(K key) {
        checkNotClosed();
        if (key == null) {
            return Optional.empty();
        }
        Optional<V> cached = readCachedValue(key);
        if (cached.isPresent()) {
            refreshDelegate.trackKey(key);
        }
        return cached;
    }

    @Override
    public V getOrLoad(K key, Function<K, V> fallbackLoader) {
        checkNotClosed();
        if (key == null) {
            return null;
        }
        refreshDelegate.trackKey(key);
        Optional<V> cached = readCachedValue(key);
        if (cached.isPresent()) {
            return cached.get();
        }

        markMiss();
        if (fallbackLoader == null) {
            return writeDelegate.loadAndWriteBack(key, policy.getHardTtlSeconds());
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
        return EngineBackedCacheCore.supplyAsyncSafely(asyncExecutor, LOGGER, cacheName, () -> get(key));
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
        writeDelegate.writeThrough(key, value, ttlSeconds);
    }

    @Override
    public CompletableFuture<Void> putAsync(K key, V value) {
        checkNotClosed();
        if (key == null) {
            return CompletableFuture.completedFuture(null);
        }
        return EngineBackedCacheCore.runAsyncSafely(asyncExecutor, LOGGER, cacheName, () -> put(key, value));
    }

    @Override
    public void evict(K key) {
        checkNotClosed();
        if (key == null) {
            return;
        }
        long version = evictionDelegate.evict(key);
        publishInvalidation(key, version);
    }

    @Override
    public void clear() {
        checkNotClosed();
        long version = evictionDelegate.clear();
        publishClear(version);
    }

    /**
     * 仅执行本地驱逐（L1 + 本地追踪状态），不发布失效同步事件。
     * <p>
     * 注意：不会触发 L2（共享存储）删除，避免在 sync=false 语义下产生跨节点副作用。
     */
    public void evictWithoutSync(K key) {
        checkNotClosed();
        if (key == null) {
            return;
        }
        evictionDelegate.evictLocal(key);
    }

    /**
     * 仅执行本地清空（L1 + 本地追踪状态），不发布清空同步事件。
     * <p>
     * 注意：不会触发 L2（共享存储）清空，避免在 sync=false 语义下产生跨节点副作用。
     */
    public void clearWithoutSync() {
        checkNotClosed();
        evictionDelegate.clearLocal();
    }

    @Override
    public Map<K, V> getAll(Iterable<K> keys) {
        checkNotClosed();
        EngineBackedCacheRead.BatchReadContext<K, V> context = EngineBackedCacheRead.BatchReadContext.<K, V>builder()
                .trackKey(refreshDelegate::trackKey)
                .readFromL1(this::readFromL1)
                .evictL1(l1Store != null ? l1Store::evict : ignored -> {
                })
                .l2Store(l2Store)
                .isHardExpired(this::isHardExpired)
                .markL1Hit(this::markL1Hit)
                .markL2Hit(this::markL2Hit)
                .markMiss(this::markMiss)
                .writeBackL1(this::writeBackL1)
                .triggerRefreshIfSoftExpired(refreshDelegate::triggerRefreshIfSoftExpired)
                .loadOnMiss(key -> writeDelegate.loadAndWriteBack(key, policy.getHardTtlSeconds()))
                .build();
        return EngineBackedCacheRead.getAll(keys, context);
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
        lifecycleDelegate.close();
    }

    @Override
    public boolean isClosed() {
        return lifecycleDelegate.isClosed();
    }

    private Optional<V> readCachedValue(K key) {
        // 统一读链路：先判定 L1/L2 命中，再处理硬过期淘汰、L2->L1 回填和软过期刷新。
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
        refreshDelegate.triggerRefreshIfSoftExpired(key, record);
        return Optional.ofNullable(record.getValue());
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
        // 回填只写本地 L1，不回写 L2；这样可以把共享层命中的结果快速带回本节点。
        if (l1Store != null) {
            l1Store.put(key, record);
            markBackfillL1();
        }
    }

    private boolean isHardExpired(CacheRecord<V> record) {
        long hardExpire = record.getHardExpireAtMs();
        return hardExpire > 0 && hardExpire != Long.MAX_VALUE && System.currentTimeMillis() >= hardExpire;
    }

    private void publishInvalidation(K key, long version) {
        syncDelegate.publishInvalidation(key, version);
    }

    private void publishWriteEvent(K key, CacheRecord<V> record) {
        syncDelegate.publishWriteEvent(key, record);
    }

    private void publishClear(long version) {
        syncDelegate.publishClear(version);
    }

    public CacheStatsSnapshot statsSnapshot() {
        return EngineBackedCacheMetrics.statsSnapshot(
                l1Hit,
                l2Hit,
                miss,
                backfillL1,
                backfillL2,
                refreshSuccess,
                refreshFail,
                invalidatePublish,
                invalidateConsume,
                syncUpdateFallback,
                singleFlightJoin,
                distLockDegrade
        );
    }

    public Map<String, Object> diagnosticsSnapshot() {
        return EngineBackedCacheMetrics.diagnosticsSnapshot(
                cacheName,
                nodeId,
                policy,
                refreshOptions,
                refreshStarted,
                refreshExecutor,
                trackedKeys,
                refreshingKeys,
                localVersion,
                eventLagMs,
                droppedEvents,
                readCounter,
                statsSnapshot()
        );
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
        EngineBackedCacheMetrics.markL1Hit(l1Hit, metricsCollector);
    }

    private void markL2Hit() {
        EngineBackedCacheMetrics.markL2Hit(l2Hit, metricsCollector);
    }

    private void markMiss() {
        EngineBackedCacheMetrics.markMiss(miss, metricsCollector);
    }

    private void markBackfillL1() {
        EngineBackedCacheMetrics.markBackfillL1(backfillL1, metricsCollector);
    }

    private void markBackfillL2() {
        EngineBackedCacheMetrics.markBackfillL2(backfillL2, metricsCollector);
    }

    private void markRefreshSuccess() {
        EngineBackedCacheMetrics.markRefreshSuccess(refreshSuccess, metricsCollector);
    }

    private void markRefreshFail() {
        EngineBackedCacheMetrics.markRefreshFail(refreshFail, metricsCollector);
    }

    private void markSyncPublish() {
        EngineBackedCacheMetrics.markSyncPublish(invalidatePublish, metricsCollector);
    }

    private void markSyncConsume() {
        EngineBackedCacheMetrics.markSyncConsume(invalidateConsume, metricsCollector);
    }

    private void markSyncUpdateFallback() {
        EngineBackedCacheMetrics.markSyncUpdateFallback(syncUpdateFallback, metricsCollector);
    }

    private void markSingleFlightJoin() {
        EngineBackedCacheMetrics.markSingleFlightJoin(singleFlightJoin, metricsCollector);
    }

    private void markDistLockDegrade() {
        EngineBackedCacheMetrics.markDistLockDegrade(distLockDegrade, metricsCollector);
    }

    private void checkNotClosed() {
        lifecycleDelegate.checkNotClosed();
    }
}
