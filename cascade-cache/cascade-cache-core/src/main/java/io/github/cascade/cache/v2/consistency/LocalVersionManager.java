package io.github.cascade.cache.v2.consistency;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 本地版本管理器（单节点或无 Redis 场景）。
 */
public class LocalVersionManager<K> implements VersionManager<K> {

    /**
     * 与 RedisVersionManager 保持一致：使用缓存级全局序列，避免本地按 key 维护无限增长的版本表。
     */
    private final AtomicLong keyVersionSequence = new AtomicLong(0L);
    private final AtomicLong clearVersion = new AtomicLong(0L);

    @Override
    public long nextVersion(K key) {
        return keyVersionSequence.incrementAndGet();
    }

    @Override
    public long currentVersion(K key) {
        return keyVersionSequence.get();
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
