package io.github.cascade.cache.v2.engine;

import io.github.cascade.cache.v2.observability.CacheMetricsCollector;
import io.github.cascade.cache.v2.observability.CacheStatsSnapshot;
import io.github.cascade.cache.v2.policy.CachePolicy;
import io.github.cascade.cache.v2.policy.RefreshExecutionOptions;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * EngineBackedCache 指标与诊断快照逻辑。
 */
final class EngineBackedCacheMetrics {

    private EngineBackedCacheMetrics() {
    }

    static CacheStatsSnapshot statsSnapshot(AtomicLong l1Hit,
                                            AtomicLong l2Hit,
                                            AtomicLong miss,
                                            AtomicLong backfillL1,
                                            AtomicLong backfillL2,
                                            AtomicLong refreshSuccess,
                                            AtomicLong refreshFail,
                                            AtomicLong invalidatePublish,
                                            AtomicLong invalidateConsume,
                                            AtomicLong syncUpdateFallback,
                                            AtomicLong singleFlightJoin,
                                            AtomicLong distLockDegrade) {
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

    static <K> Map<String, Object> diagnosticsSnapshot(String cacheName,
                                                       String nodeId,
                                                       CachePolicy policy,
                                                       RefreshExecutionOptions refreshOptions,
                                                       AtomicBoolean refreshStarted,
                                                       ThreadPoolExecutor refreshExecutor,
                                                       Set<K> trackedKeys,
                                                       Set<K> refreshingKeys,
                                                       com.github.benmanes.caffeine.cache.Cache<K, Long> localVersion,
                                                       AtomicLong eventLagMs,
                                                       AtomicLong droppedEvents,
                                                       com.github.benmanes.caffeine.cache.Cache<K, AtomicLong> readCounter,
                                                       CacheStatsSnapshot stats) {
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

    static void markL1Hit(AtomicLong counter, CacheMetricsCollector collector) {
        counter.incrementAndGet();
        collector.incL1Hit();
    }

    static void markL2Hit(AtomicLong counter, CacheMetricsCollector collector) {
        counter.incrementAndGet();
        collector.incL2Hit();
    }

    static void markMiss(AtomicLong counter, CacheMetricsCollector collector) {
        counter.incrementAndGet();
        collector.incMiss();
    }

    static void markBackfillL1(AtomicLong counter, CacheMetricsCollector collector) {
        counter.incrementAndGet();
        collector.incBackfillL1();
    }

    static void markBackfillL2(AtomicLong counter, CacheMetricsCollector collector) {
        counter.incrementAndGet();
        collector.incBackfillL2();
    }

    static void markRefreshSuccess(AtomicLong counter, CacheMetricsCollector collector) {
        counter.incrementAndGet();
        collector.incRefreshSuccess();
    }

    static void markRefreshFail(AtomicLong counter, CacheMetricsCollector collector) {
        counter.incrementAndGet();
        collector.incRefreshFail();
    }

    static void markSyncPublish(AtomicLong counter, CacheMetricsCollector collector) {
        counter.incrementAndGet();
        collector.incSyncPublish();
    }

    static void markSyncConsume(AtomicLong counter, CacheMetricsCollector collector) {
        counter.incrementAndGet();
        collector.incSyncConsume();
    }

    static void markSyncUpdateFallback(AtomicLong counter, CacheMetricsCollector collector) {
        counter.incrementAndGet();
        collector.incSyncUpdateFallback();
    }

    static void markSingleFlightJoin(AtomicLong counter, CacheMetricsCollector collector) {
        counter.incrementAndGet();
        collector.incSingleFlightJoin();
    }

    static void markDistLockDegrade(AtomicLong counter, CacheMetricsCollector collector) {
        counter.incrementAndGet();
        collector.incDistLockDegrade();
    }
}
