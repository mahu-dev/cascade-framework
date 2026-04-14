package io.github.cascade.cache.v2.consistency;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 本地版本管理器（单节点或无 Redis 场景）。
 */
public class LocalVersionManager<K> implements VersionManager<K> {

    private final ConcurrentMap<K, AtomicLong> keyVersions = new ConcurrentHashMap<>();
    private final AtomicLong clearVersion = new AtomicLong(0L);

    @Override
    public long nextVersion(K key) {
        return keyVersions.computeIfAbsent(key, ignored -> new AtomicLong(0L)).incrementAndGet();
    }

    @Override
    public long currentVersion(K key) {
        AtomicLong version = keyVersions.get(key);
        return version == null ? 0L : version.get();
    }

    @Override
    public long nextClearVersion() {
        return clearVersion.incrementAndGet();
    }

    @Override
    public long currentClearVersion() {
        return clearVersion.get();
    }
}
