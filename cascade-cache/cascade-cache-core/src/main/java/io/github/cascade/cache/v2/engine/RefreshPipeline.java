package io.github.cascade.cache.v2.engine;

import io.github.cascade.cache.v2.store.model.CacheRecord;

/**
 * 刷新管线：热点追踪与刷新判定。
 */
public class RefreshPipeline {

    public boolean shouldTrack(boolean autoRefreshEnabled,
                               long accessCount,
                               int hotKeyAccessThreshold,
                               int trackedSize,
                               int maxTrackedKeys) {
        return autoRefreshEnabled
                && accessCount >= hotKeyAccessThreshold
                && trackedSize < maxTrackedKeys;
    }

    public boolean shouldRefresh(boolean autoRefreshEnabled, CacheRecord<?> record, long nowMs) {
        if (!autoRefreshEnabled || record == null) {
            return false;
        }
        long softExpireAt = record.getSoftExpireAtMs();
        return softExpireAt > 0 && nowMs >= softExpireAt;
    }
}
