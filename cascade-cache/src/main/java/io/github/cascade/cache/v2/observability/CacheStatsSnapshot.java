package io.github.cascade.cache.v2.observability;

/**
 * 缓存运行时统计快照。
 */
public record CacheStatsSnapshot(
        long l1Hit,
        long l2Hit,
        long miss,
        long backfillL1,
        long backfillL2,
        long refreshSuccess,
        long refreshFail,
        long invalidatePublish,
        long invalidateConsume,
        long singleFlightJoin,
        long distLockDegrade
) {
}
