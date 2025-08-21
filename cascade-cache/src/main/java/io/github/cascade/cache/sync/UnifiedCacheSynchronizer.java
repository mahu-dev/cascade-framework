package io.github.cascade.cache.sync;

import io.github.cascade.cache.api.Cache;
import io.github.cascade.cache.event.UnifiedCacheEvent;
import io.github.cascade.cache.event.UnifiedEventProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 统一缓存同步器
 * 整合了事件处理和分布式同步功能
 *
 * @param <K> 键类型
 * @param <V> 值类型
 * @author Cascade Framework
 */
public class UnifiedCacheSynchronizer<K, V> implements CacheSynchronizer<K, V>, CacheSyncListener {

    private static final Logger log = LoggerFactory.getLogger(UnifiedCacheSynchronizer.class);

    private final String cacheId;
    private final CacheSyncManager syncManager;
    private final UnifiedEventProcessor eventProcessor;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final SyncStatsImpl stats = new SyncStatsImpl();

    private Cache<K, V> cache;

    public UnifiedCacheSynchronizer(String cacheId, CacheSyncManager syncManager,
                                    UnifiedEventProcessor eventProcessor) {
        this.cacheId = cacheId;
        this.syncManager = syncManager;
        this.eventProcessor = eventProcessor;
    }

    @Override
    public void initialize(Cache<K, V> cache) {
        this.cache = cache;
        log.debug("Initialized synchronizer for cache: {}", cacheId);
    }

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            syncManager.registerListener(this);
            log.info("Started cache synchronizer for: {}", cacheId);
        }
    }

    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) {
            syncManager.unregisterListener(getListenerId());
            log.info("Stopped cache synchronizer for: {}", cacheId);
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public String getName() {
        return "UnifiedCacheSynchronizer-" + cacheId;
    }

    @Override
    public SyncStats getStats() {
        return stats;
    }

    @Override
    public void notifyPut(K key, V value) {
        if (!running.get()) return;

        publishSyncEvent(key, value, UnifiedCacheEvent.Type.SYNC_PUT);

        CacheSyncEvent syncEvent = new CacheSyncEvent(cacheId, key, value, syncManager.getCurrentNodeId());
        publishToSyncManager(syncEvent);
    }

    @Override
    public void notifyEvict(K key) {
        if (!running.get()) return;

        publishSyncEvent(key, null, UnifiedCacheEvent.Type.SYNC_EVICT);

        CacheSyncEvent syncEvent = new CacheSyncEvent(cacheId, CacheSyncEvent.Operation.EVICT, key, syncManager.getCurrentNodeId());
        publishToSyncManager(syncEvent);
    }

    @Override
    public void notifyClear() {
        if (!running.get()) return;

        publishSyncEvent(null, null, UnifiedCacheEvent.Type.SYNC_CLEAR);

        CacheSyncEvent syncEvent = new CacheSyncEvent(cacheId, syncManager.getCurrentNodeId());
        publishToSyncManager(syncEvent);
    }

    @Override
    public void notifyRefresh(K key) {
        if (!running.get()) return;

        publishSyncEvent(key, null, UnifiedCacheEvent.Type.SYNC_REFRESH);

        CacheSyncEvent syncEvent = new CacheSyncEvent(cacheId, CacheSyncEvent.Operation.REFRESH, key, syncManager.getCurrentNodeId());
        publishToSyncManager(syncEvent);
    }

    @Override
    public void notifyPutAll(Set<K> keys) {
        if (!running.get() || keys.isEmpty()) return;

        // 对于批量操作，我们可以选择单独处理每个键或者批量处理
        keys.forEach(key -> {
            V value = cache.get(key);
            if (value != null) {
                notifyPut(key, value);
            }
        });
    }

    @Override
    public void notifyEvictAll(Set<K> keys) {
        if (!running.get() || keys.isEmpty()) return;

        keys.forEach(this::notifyEvict);
    }

    @Override
    public void sync() {
        // 执行一次性同步操作
        log.debug("Performing manual sync for cache: {}", cacheId);
        stats.recordSync();
    }

    // CacheSyncListener 实现

    @Override
    public String getListenerId() {
        return "unified-sync-" + cacheId;
    }

    @Override
    public boolean isActive() {
        return running.get();
    }

    @Override
    public boolean shouldHandle(CacheSyncEvent event) {
        return cacheId.equals(event.getCacheId()) && cache != null;
    }

    @Override
    public void onSyncEvent(CacheSyncEvent event) {
        if (!shouldHandle(event)) return;

        stats.recordReceived();

        try {
            handleSyncEvent(event);

            // 发布统一事件
            UnifiedCacheEvent unifiedEvent = convertToUnifiedEvent(event);
            if (unifiedEvent != null) {
                eventProcessor.publishEvent(unifiedEvent);
            }

        } catch (Exception e) {
            log.error("Error handling sync event: {}", event, e);
            stats.recordError();
        }
    }

    /**
     * 处理同步事件
     */
    @SuppressWarnings("unchecked")
    private void handleSyncEvent(CacheSyncEvent event) {
        switch (event.getOperation()) {
            case PUT:
                if (event.getKey() != null && event.getValue() != null) {
                    cache.put((K) event.getKey(), (V) event.getValue());
                }
                break;
            case EVICT:
                if (event.getKey() != null) {
                    cache.evict((K) event.getKey());
                } else if (event.getKeys() != null) {
                    for (Object key : event.getKeys()) {
                        cache.evict((K) key);
                    }
                }
                break;
            case CLEAR:
                cache.clear();
                break;
            case REFRESH:
                if (event.getKey() != null) {
                    cache.refresh((K) event.getKey());
                }
                break;
        }
    }

    /**
     * 转换为统一事件
     */
    private UnifiedCacheEvent convertToUnifiedEvent(CacheSyncEvent syncEvent) {
        UnifiedCacheEvent.Type type;
        switch (syncEvent.getOperation()) {
            case PUT:
                type = UnifiedCacheEvent.Type.SYNC_PUT;
                break;
            case EVICT:
                type = UnifiedCacheEvent.Type.SYNC_EVICT;
                break;
            case CLEAR:
                type = UnifiedCacheEvent.Type.SYNC_CLEAR;
                break;
            case REFRESH:
                type = UnifiedCacheEvent.Type.SYNC_REFRESH;
                break;
            default:
                return null;
        }

        return UnifiedCacheEvent.builder(cacheId, type)
                .sourceNodeId(syncEvent.getSourceNodeId())
                .key(syncEvent.getKey())
                .value(syncEvent.getValue())
                .keys(syncEvent.getKeys())
                .timestamp(syncEvent.getTimestamp())
                .level(UnifiedCacheEvent.Level.DEBUG)
                .build();
    }

    /**
     * 发布同步事件到事件处理器
     */
    private void publishSyncEvent(K key, V value, UnifiedCacheEvent.Type type) {
        log.debug("发布同步事件 key = {},value = {},type = {}", key, value, type);
        UnifiedCacheEvent event = UnifiedCacheEvent.builder(cacheId, type)
                .sourceNodeId(syncManager.getCurrentNodeId())
                .key(key)
                .value(value)
                .level(UnifiedCacheEvent.Level.DEBUG)
                .build();

        eventProcessor.publishEvent(event);
    }

    /**
     * 发布到同步管理器
     */
    private void publishToSyncManager(CacheSyncEvent event) {
        log.debug("publishToSyncManager 发布同步事件: {}", event);
        long startTime = System.nanoTime();
        try {
            syncManager.publishEvent(event);
            stats.recordSent(System.nanoTime() - startTime);
        } catch (Exception e) {
            stats.recordError();
            log.error("Failed to publish sync event: {}", event, e);
        }
    }

    /**
     * 同步统计实现
     */
    private static class SyncStatsImpl implements SyncStats {
        private final LongAdder sentCount = new LongAdder();
        private final LongAdder receivedCount = new LongAdder();
        private final LongAdder errorCount = new LongAdder();
        private final LongAdder syncCount = new LongAdder();
        private final LongAdder totalSendTime = new LongAdder();
        private final AtomicLong lastSyncTime = new AtomicLong(0);

        @Override
        public long getSentMessageCount() {
            return sentCount.sum();
        }

        @Override
        public long getReceivedMessageCount() {
            return receivedCount.sum();
        }

        @Override
        public long getFailedMessageCount() {
            return errorCount.sum();
        }

        @Override
        public double getAverageSendTime() {
            long sent = sentCount.sum();
            return sent > 0 ? (double) totalSendTime.sum() / sent : 0.0;
        }

        @Override
        public long getLastSyncTime() {
            return lastSyncTime.get();
        }

        @Override
        public void reset() {
            sentCount.reset();
            receivedCount.reset();
            errorCount.reset();
            syncCount.reset();
            totalSendTime.reset();
            lastSyncTime.set(0);
        }

        public void recordSent(long sendTimeNanos) {
            sentCount.increment();
            totalSendTime.add(sendTimeNanos);
        }

        public void recordReceived() {
            receivedCount.increment();
        }

        public void recordError() {
            errorCount.increment();
        }

        public void recordSync() {
            syncCount.increment();
            lastSyncTime.set(System.currentTimeMillis());
        }

        @Override
        public String toString() {
            return String.format("SyncStats{sent=%d, received=%d, errors=%d, avgSendTime=%.2fms}",
                    getSentMessageCount(), getReceivedMessageCount(), getFailedMessageCount(),
                    getAverageSendTime() / 1_000_000.0);
        }
    }
}