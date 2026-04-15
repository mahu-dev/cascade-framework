package io.github.cascade.cache.v2.consistency;

import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;

/**
 * 基于 Redis 的版本管理器。
 */
public class RedisVersionManager<K> implements VersionManager<K> {

    private final RedissonClient redissonClient;
    private final String namespaceVersionKey;
    /**
     * key 级版本采用“缓存级全局序列”，避免为每个业务 key 额外创建 Redis 计数键导致高基数膨胀。
     */
    private final String keyVersionSequenceKey;

    public RedisVersionManager(String cacheName, RedissonClient redissonClient, String prefix) {
        this.redissonClient = redissonClient;
        String finalPrefix = prefix == null ? "" : prefix;
        if (!finalPrefix.isEmpty() && !finalPrefix.endsWith(":")) {
            finalPrefix += ":";
        }
        String keyPrefix = finalPrefix + cacheName + ":";
        this.namespaceVersionKey = keyPrefix + "ns:version";
        this.keyVersionSequenceKey = keyPrefix + "ver:sequence";
    }

    @Override
    public long nextVersion(K key) {
        return redissonClient.getAtomicLong(keyVersionSequenceKey).incrementAndGet();
    }

    @Override
    public long currentVersion(K key) {
        return redissonClient.getAtomicLong(keyVersionSequenceKey).get();
    }

    @Override
    public long nextClearVersion() {
        return redissonClient.getAtomicLong(namespaceVersionKey).incrementAndGet();
    }

    @Override
    public long currentClearVersion() {
        return redissonClient.getAtomicLong(namespaceVersionKey).get();
    }
}
