package io.github.cascade.cache.v2.store.l2;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cascade.cache.v2.support.CacheKeyEncoder;
import io.github.cascade.cache.v2.support.ObjectMapperHolder;
import io.github.cascade.cache.v2.store.model.CacheRecord;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RBucket;
import org.redisson.api.RBuckets;
import org.redisson.api.RKeys;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.Duration;
import java.util.Optional;

/**
 * 基于 Redis 的 L2 实现。
 */
public class RedissonL2Store<K, V> implements L2CacheStore<K, V> {

    private static final Logger LOGGER = LoggerFactory.getLogger(RedissonL2Store.class);

    private final RedissonClient redissonClient;
    private final String keyPrefix;
    private final String namespaceVersionKey;
    private final ObjectMapper objectMapper = ObjectMapperHolder.getInstance();
    private volatile long cachedNamespaceVersion = -1L;

    public RedissonL2Store(String cacheName, RedissonClient redissonClient, String prefix) {
        this.redissonClient = redissonClient;
        String finalPrefix = prefix == null ? "" : prefix;
        if (!finalPrefix.isEmpty() && !finalPrefix.endsWith(":")) {
            finalPrefix += ":";
        }
        this.keyPrefix = finalPrefix + cacheName + ":";
        this.namespaceVersionKey = this.keyPrefix + "ns:version";
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<CacheRecord<V>> get(K key) {
        RBucket<CacheRecord<V>> bucket = redissonClient.getBucket(dataKey(key, currentNamespaceVersion()));
        return Optional.ofNullable(bucket.get());
    }

    @Override
    public Map<K, CacheRecord<V>> getAll(Iterable<K> keys) {
        Map<K, CacheRecord<V>> result = new LinkedHashMap<>();
        if (keys == null) {
            return result;
        }
        long namespaceVersion = currentNamespaceVersion();
        List<K> keyList = new ArrayList<>();
        List<String> redisKeys = new ArrayList<>();
        for (K key : keys) {
            keyList.add(key);
            redisKeys.add(dataKey(key, namespaceVersion));
        }
        if (redisKeys.isEmpty()) {
            return result;
        }

        RBuckets buckets = redissonClient.getBuckets();
        Map<String, CacheRecord<V>> batchValues = buckets.get(redisKeys.toArray(new String[0]));
        for (int i = 0; i < keyList.size(); i++) {
            CacheRecord<V> record = batchValues.get(redisKeys.get(i));
            if (record != null) {
                result.put(keyList.get(i), record);
            }
        }
        return result;
    }

    @Override
    public void put(K key, CacheRecord<V> record, long ttlSeconds) {
        RBucket<CacheRecord<V>> bucket = redissonClient.getBucket(dataKey(key, currentNamespaceVersion()));
        if (ttlSeconds > 0) {
            bucket.set(record, Duration.ofSeconds(ttlSeconds));
        } else {
            bucket.set(record);
        }
    }

    @Override
    public void evict(K key) {
        redissonClient.getBucket(dataKey(key, currentNamespaceVersion())).delete();
    }

    @Override
    public void clear() {
        long previousNamespaceVersion = currentNamespaceVersion();
        long version = redissonClient.getAtomicLong(namespaceVersionKey).incrementAndGet();
        cachedNamespaceVersion = Math.max(1L, version);
        reclaimNamespaceData(previousNamespaceVersion);
    }

    @Override
    public long size() {
        long namespace = currentNamespaceVersion();
        long count = 0;
        for (String ignored : redissonClient.getKeys().getKeysByPattern(dataKeyPattern(namespace))) {
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

    private String dataKey(K key, long namespaceVersion) {
        return keyPrefix + "ns:" + namespaceVersion + ":data:" + encodeKey(key);
    }

    private String dataKeyPattern(long namespaceVersion) {
        return keyPrefix + "ns:" + namespaceVersion + ":data:*";
    }

    private String versionKey(K key) {
        return keyPrefix + "ver:" + encodeKey(key);
    }

    private String encodeKey(K key) {
        return CacheKeyEncoder.encodeKey(objectMapper, key);
    }

    private long currentNamespaceVersion() {
        RAtomicLong counter = redissonClient.getAtomicLong(namespaceVersionKey);
        long remote = counter.get();
        if (remote <= 0) {
            boolean initialized = counter.compareAndSet(0L, 1L);
            remote = initialized ? 1L : Math.max(1L, counter.get());
        }
        cachedNamespaceVersion = Math.max(cachedNamespaceVersion, remote);
        return cachedNamespaceVersion;
    }

    private void reclaimNamespaceData(long namespaceVersion) {
        if (namespaceVersion <= 0) {
            return;
        }
        String pattern = dataKeyPattern(namespaceVersion);
        RKeys keys = redissonClient.getKeys();
        try {
            if (tryUnlinkByPattern(keys, pattern)) {
                return;
            }
            try {
                keys.deleteByPattern(pattern);
            } catch (RuntimeException deleteError) {
                LOGGER.warn("清理旧命名空间数据失败: pattern={}, error={}", pattern, deleteError.getMessage());
            }
        } catch (RuntimeException cleanupError) {
            LOGGER.warn("清理旧命名空间数据失败: pattern={}, error={}", pattern, cleanupError.getMessage());
        }
    }

    private static boolean tryUnlinkByPattern(RKeys keys, String pattern) {
        try {
            Method method = keys.getClass().getMethod("unlinkByPattern", String.class);
            method.invoke(keys, pattern);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        } catch (IllegalAccessException | InvocationTargetException e) {
            return false;
        }
    }
}
