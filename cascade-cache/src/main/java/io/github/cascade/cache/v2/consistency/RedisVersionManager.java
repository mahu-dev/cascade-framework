package io.github.cascade.cache.v2.consistency;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cascade.cache.v2.support.CacheKeyEncoder;
import io.github.cascade.cache.v2.support.ObjectMapperHolder;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;

/**
 * 基于 Redis 的版本管理器。
 */
public class RedisVersionManager<K> implements VersionManager<K> {

    private final RedissonClient redissonClient;
    private final String keyPrefix;
    private final String namespaceVersionKey;
    private final ObjectMapper objectMapper = ObjectMapperHolder.getInstance();

    public RedisVersionManager(String cacheName, RedissonClient redissonClient, String prefix) {
        this.redissonClient = redissonClient;
        String finalPrefix = prefix == null ? "" : prefix;
        if (!finalPrefix.isEmpty() && !finalPrefix.endsWith(":")) {
            finalPrefix += ":";
        }
        this.keyPrefix = finalPrefix + cacheName + ":";
        this.namespaceVersionKey = this.keyPrefix + "ns:version";
    }

    @Override
    public long nextVersion(K key) {
        return redissonClient.getAtomicLong(versionKey(key)).incrementAndGet();
    }

    @Override
    public long currentVersion(K key) {
        return redissonClient.getAtomicLong(versionKey(key)).get();
    }

    @Override
    public long nextClearVersion() {
        return redissonClient.getAtomicLong(namespaceVersionKey).incrementAndGet();
    }

    @Override
    public long currentClearVersion() {
        return redissonClient.getAtomicLong(namespaceVersionKey).get();
    }

    private String versionKey(K key) {
        return keyPrefix + "ver:" + encodeKey(key);
    }

    private String encodeKey(K key) {
        return CacheKeyEncoder.encodeKey(objectMapper, key);
    }
}
