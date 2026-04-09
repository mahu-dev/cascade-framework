package io.github.cascade.cache.v2.engine;

import io.github.cascade.cache.v2.consistency.VersionManager;
import io.github.cascade.cache.v2.store.l1.L1CacheStore;
import io.github.cascade.cache.v2.store.l2.L2CacheStore;

import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * EngineBackedCache 驱逐与清空逻辑。
 */
final class EngineBackedCacheEviction<K, V> {

    private final L1CacheStore<K, V> l1Store;
    private final L2CacheStore<K, V> l2Store;
    private final Set<K> trackedKeys;
    private final com.github.benmanes.caffeine.cache.Cache<K, AtomicLong> readCounter;
    private final com.github.benmanes.caffeine.cache.Cache<K, Long> localVersion;
    private final VersionManager<K> versionManager;
    private final AtomicLong clearVersion;

    EngineBackedCacheEviction(L1CacheStore<K, V> l1Store,
                              L2CacheStore<K, V> l2Store,
                              Set<K> trackedKeys,
                              com.github.benmanes.caffeine.cache.Cache<K, AtomicLong> readCounter,
                              com.github.benmanes.caffeine.cache.Cache<K, Long> localVersion,
                              VersionManager<K> versionManager,
                              AtomicLong clearVersion) {
        this.l1Store = l1Store;
        this.l2Store = l2Store;
        this.trackedKeys = trackedKeys;
        this.readCounter = readCounter;
        this.localVersion = localVersion;
        this.versionManager = versionManager;
        this.clearVersion = clearVersion;
    }

    long evict(K key) {
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

    long clear() {
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

    void evictLocal(K key) {
        if (l1Store != null) {
            l1Store.evict(key);
        }
        trackedKeys.remove(key);
        readCounter.invalidate(key);
        localVersion.invalidate(key);
    }

    void clearLocal() {
        if (l1Store != null) {
            l1Store.clear();
        }
        trackedKeys.clear();
        readCounter.invalidateAll();
        localVersion.invalidateAll();
    }

    long nextVersion(K key) {
        long version = versionManager.nextVersion(key);
        localVersion.put(key, version);
        return version;
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
}
