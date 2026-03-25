package io.github.cascade.cache.v2.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cascade.cache.v2.model.CacheRecord;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RBucket;
import org.redisson.api.RKeys;
import org.redisson.api.RedissonClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;

/**
 * 基于 Redis 的 L2 实现。
 */
public class RedissonL2Store<K, V> implements L2CacheStore<K, V> {

    private final RedissonClient redissonClient;
    private final String keyPrefix;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RedissonL2Store(String cacheName, RedissonClient redissonClient, String prefix) {
        this.redissonClient = redissonClient;
        String finalPrefix = prefix == null ? "" : prefix;
        if (!finalPrefix.isEmpty() && !finalPrefix.endsWith(":")) {
            finalPrefix += ":";
        }
        this.keyPrefix = finalPrefix + cacheName + ":";
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<CacheRecord<V>> get(K key) {
        RBucket<CacheRecord<V>> bucket = redissonClient.getBucket(dataKey(key));
        return Optional.ofNullable(bucket.get());
    }

    @Override
    public void put(K key, CacheRecord<V> record, long ttlSeconds) {
        RBucket<CacheRecord<V>> bucket = redissonClient.getBucket(dataKey(key));
        if (ttlSeconds > 0) {
            bucket.set(record, Duration.ofSeconds(ttlSeconds));
        } else {
            bucket.set(record);
        }
    }

    @Override
    public void evict(K key) {
        redissonClient.getBucket(dataKey(key)).delete();
    }

    @Override
    public void clear() {
        RKeys keys = redissonClient.getKeys();
        for (String key : keys.getKeysByPattern(keyPrefix + "*")) {
            keys.delete(key);
        }
    }

    @Override
    public long size() {
        long count = 0;
        for (String ignored : redissonClient.getKeys().getKeysByPattern(keyPrefix + "data:*")) {
            count++;
        }
        return count;
    }

    @Override
    public long nextVersion(K key) {
        RAtomicLong counter = redissonClient.getAtomicLong(versionKey(key));
        return counter.incrementAndGet();
    }

    @Override
    public void close() {
        // redissonClient 生命周期由外部管理
    }

    private String dataKey(K key) {
        return keyPrefix + "data:" + encodeKey(key);
    }

    private String versionKey(K key) {
        return keyPrefix + "ver:" + encodeKey(key);
    }

    private String encodeKey(K key) {
        if (key == null) {
            return "null";
        }
        try {
            String json = objectMapper.writeValueAsString(key);
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(json.getBytes(StandardCharsets.UTF_8));
        } catch (JsonProcessingException e) {
            return String.valueOf(key);
        }
    }
}

