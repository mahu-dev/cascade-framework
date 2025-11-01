package io.github.cascade.cache.core.distributed;

import io.github.cascade.cache.configuration.CascadeCacheProperties;
import io.github.cascade.cache.api.Cache;
import io.github.cascade.cache.common.exception.CacheConnectionException;
import io.github.cascade.cache.common.exception.CacheTimeoutException;
import org.redisson.api.RBatch;
import org.redisson.api.RBucket;
import org.redisson.api.RKeys;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisConnectionException;
import org.redisson.client.RedisTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * L2缓存实现 - 基于Redisson (String存储格式)
 * <p>
 * 设计原则：
 * 1. 分布式：基于Redis提供分布式缓存能力
 * 2. 高可用：支持Redis集群和哨兵模式
 * 3. 批量优化：提供批量操作减少网络开销
 * 4. TTL支持：支持灵活的过期时间设置
 * 5. String格式：每个缓存项对应独立的Redis key，便于调试和管理
 *
 * @param <K> 键类型
 * @param <V> 值类型
 * @author cascade
 */
public class RedissonL2Cache<K, V> implements Cache<K, V> {

    private static final Logger LOGGER = LoggerFactory.getLogger(RedissonL2Cache.class);

    private final String cacheName;
    private final RedissonClient redissonClient;
    private final String keyPrefix;
    private final CascadeCacheProperties config;
    private volatile boolean closed = false;

    /**
     * 构造器
     */
    public RedissonL2Cache(String cacheName, RedissonClient redissonClient, CascadeCacheProperties config) {
        this.cacheName = cacheName;
        this.redissonClient = redissonClient;
        this.config = config;

        // 构建Redis key前缀
        this.keyPrefix = buildKeyPrefix(cacheName, config.getL2KeyPrefix());

        LOGGER.info("创建L2缓存: {} -> Redis前缀: {} - 默认TTL={}s",
                cacheName, keyPrefix, config.getL2DefaultTtlSeconds());
    }

    /**
     * 构建Redis key前缀
     */
    private static String buildKeyPrefix(String cacheName, String keyPrefix) {
        String prefix = (keyPrefix == null || keyPrefix.isEmpty()) ? "" : keyPrefix;
        if (!prefix.isEmpty() && !prefix.endsWith(":")) {
            prefix += ":";
        }
        return prefix + cacheName + ":";
    }

    /**
     * 构建完整的Redis key
     */
    private String buildRedisKey(K key) {
        return keyPrefix + key.toString();
    }

    // ==================== Cache接口实现 ====================

    @Override
    public Optional<V> get(K key) {
        checkNotClosed();
        try {
            String redisKey = buildRedisKey(key);
            RBucket<V> bucket = redissonClient.getBucket(redisKey);
            V value = bucket.get();
            if (LOGGER.isTraceEnabled()) {
                LOGGER.trace("L2缓存获取: cache={}, key={}, found={}", cacheName, key, value != null);
            }
            return Optional.ofNullable(value);
        } catch (RedisTimeoutException e) {
            LOGGER.error("L2缓存获取超时: cache={}, key={}, error={}", cacheName, key, e.getMessage(), e);
            throw new CacheTimeoutException(cacheName, "GET", "Redis操作超时");
        } catch (RedisConnectionException e) {
            LOGGER.error("L2缓存连接失败: cache={}, key={}, error={}", cacheName, key, e.getMessage(), e);
            throw new CacheConnectionException(cacheName, "Redis连接失败", e);
        } catch (RuntimeException e) {
            LOGGER.error("L2缓存获取失败: cache={}, key={}, error={}", cacheName, key, e.getMessage(), e);
            return Optional.empty();
        }
    }

    @Override
    public V getOrLoad(K key, Function<K, V> loader) {
        checkNotClosed();
        Optional<V> cached = get(key);
        if (cached.isPresent()) {
            return cached.get();
        }

        // 加载并缓存
        V loaded = loader.apply(key);
        if (loaded != null) {
            put(key, loaded);
        }
        return loaded;
    }

    @Override
    public CompletableFuture<Optional<V>> getAsync(K key) {
        checkNotClosed();
        try {
            String redisKey = buildRedisKey(key);
            RBucket<V> bucket = redissonClient.getBucket(redisKey);
            return bucket.getAsync()
                    .thenApply(Optional::ofNullable)
                    .exceptionally(throwable -> {
                        LOGGER.error("L2缓存异步获取失败: cache={}, key={}, redisKey={}, error={}",
                                cacheName, key, redisKey, throwable.getMessage());
                        return Optional.empty();
                    })
                    .toCompletableFuture();
        } catch (RuntimeException e) {
            LOGGER.error("L2缓存异步获取失败: cache={}, key={}, error={}", cacheName, key, e.getMessage());
            return CompletableFuture.completedFuture(Optional.empty());
        }
    }

    @Override
    public void put(K key, V value) {
        put(key, value, config.getL2DefaultTtlSeconds());
    }

    @Override
    public void put(K key, V value, long ttlSeconds) {
        checkNotClosed();
        try {
            String redisKey = buildRedisKey(key);
            RBucket<V> bucket = redissonClient.getBucket(redisKey);

            if (ttlSeconds > 0) {
                bucket.set(value, Duration.ofSeconds(ttlSeconds));
            } else {
                bucket.set(value);
            }
            if (LOGGER.isTraceEnabled()) {
                LOGGER.trace("L2缓存存储: cache={}, key={}, ttl={}s", cacheName, key, ttlSeconds);
            }
        } catch (RuntimeException e) {
            LOGGER.error("L2缓存存储失败: cache={}, key={}, error={}", cacheName, key, e.getMessage());
        }
    }

    @Override
    public CompletableFuture<Void> putAsync(K key, V value) {
        checkNotClosed();
        try {
            long ttl = config.getL2DefaultTtlSeconds();
            String redisKey = buildRedisKey(key);
            RBucket<V> bucket = redissonClient.getBucket(redisKey);

            return (ttl > 0 ?
                    bucket.setAsync(value, ttl, TimeUnit.SECONDS) :
                    bucket.setAsync(value))
                    .<Void>thenApply(prev -> null)
                    .exceptionally(throwable -> {
                        LOGGER.error("L2缓存异步存储失败: cache={}, key={}, redisKey={}, error={}",
                                cacheName, key, redisKey, throwable.getMessage());
                        return null;
                    })
                    .toCompletableFuture();
        } catch (RuntimeException e) {
            LOGGER.error("L2缓存异步存储失败: cache={}, key={}, error={}", cacheName, key, e.getMessage());
            return CompletableFuture.completedFuture(null);
        }
    }

    @Override
    public void evict(K key) {
        checkNotClosed();
        try {
            String redisKey = buildRedisKey(key);
            RBucket<V> bucket = redissonClient.getBucket(redisKey);
            boolean existed = bucket.delete();
            if (LOGGER.isTraceEnabled() && existed) {
                LOGGER.trace("L2缓存删除: cache={}, key={}", cacheName, key);
            }
        } catch (RuntimeException e) {
            LOGGER.error("L2缓存删除失败: cache={}, key={}, error={}", cacheName, key, e.getMessage());
        }
    }

    @Override
    public void clear() {
        checkNotClosed();
        try {
            // 使用批量删除优化性能
            String pattern = keyPrefix + "*";
            RKeys keys = redissonClient.getKeys();

            // 分批删除，避免一次性处理大量键影响Redis性能
            final int batchSize = 1000;
            List<String> batch = new ArrayList<>(batchSize);
            int totalDeleted = 0;

            for (String key : keys.getKeysByPattern(pattern)) {
                batch.add(key);
                if (batch.size() >= batchSize) {
                    totalDeleted += deleteBatch(keys, batch);
                    batch.clear();
                }
            }

            // 处理剩余的键
            if (!batch.isEmpty()) {
                totalDeleted += deleteBatch(keys, batch);
            }

            LOGGER.info("L2缓存清空: cache={}, pattern={}, 删除条目数={}", cacheName, pattern, totalDeleted);
        } catch (RuntimeException e) {
            LOGGER.error("L2缓存清空失败: cache={}, error={}", cacheName, e.getMessage(), e);
        }
    }

    /**
     * 批量删除键
     */
    private int deleteBatch(RKeys keys, List<String> keyList) {
        try {
            // 使用Redis的批量删除命令
            long deleted = keys.delete(keyList.toArray(new String[0]));
            return (int) deleted;
        } catch (RuntimeException e) {
            // 如果批量删除失败，fallback到逐个删除
            LOGGER.warn("批量删除失败，使用逐个删除: batch size={}, error={}", keyList.size(), e.getMessage());
            int count = 0;
            for (String key : keyList) {
                try {
                    if (redissonClient.getBucket(key).delete()) {
                        count++;
                    }
                } catch (RuntimeException ex) {
                    LOGGER.debug("删除键失败: key={}", key);
                }
            }
            return count;
        }
    }

    @Override
    public Map<K, V> getAll(Iterable<K> keys) {
        checkNotClosed();
        try {
            Map<K, V> result = new HashMap<>();

            // 构建Redis键列表
            List<String> redisKeys = new ArrayList<>();
            List<K> keyList = new ArrayList<>();
            for (K key : keys) {
                redisKeys.add(buildRedisKey(key));
                keyList.add(key);
            }

            if (redisKeys.isEmpty()) {
                return Collections.emptyMap();
            }

            // 批量获取
            Map<String, V> buckets = redissonClient.getBuckets().get(redisKeys.toArray(new String[0]));

            // 映射回原始key
            for (int i = 0; i < keyList.size(); i++) {
                K originalKey = keyList.get(i);
                String redisKey = redisKeys.get(i);
                V value = buckets.get(redisKey);
                if (value != null) {
                    result.put(originalKey, value);
                }
            }

            LOGGER.debug("L2缓存批量获取: cache={}, 请求数={}, 返回数={}",
                    cacheName, keyList.size(), result.size());
            return result;
        } catch (RuntimeException e) {
            LOGGER.error("L2缓存批量获取失败: cache={}, error={}", cacheName, e.getMessage());
            return Collections.emptyMap();
        }
    }

    @Override
    public void putAll(Map<K, V> entries) {
        checkNotClosed();
        if (entries == null || entries.isEmpty()) {
            return;
        }

        try {
            long ttl = config.getL2DefaultTtlSeconds();

            if (ttl > 0) {
                // 使用批量操作优化性能
                RBatch batch = redissonClient.createBatch();
                entries.forEach((key, value) -> {
                    String redisKey = buildRedisKey(key);
                    batch.getBucket(redisKey).setAsync(value, ttl, TimeUnit.SECONDS);
                });
                batch.execute();
            } else {
                // 使用Buckets批量设置
                Map<String, V> redisBuckets = new HashMap<>();
                entries.forEach((key, value) -> {
                    String redisKey = buildRedisKey(key);
                    redisBuckets.put(redisKey, value);
                });
                redissonClient.getBuckets().set(redisBuckets);
            }

            LOGGER.debug("L2缓存批量存储: cache={}, 条目数={}, ttl={}s", cacheName, entries.size(), ttl);
        } catch (RuntimeException e) {
            LOGGER.error("L2缓存批量存储失败: cache={}, error={}", cacheName, e.getMessage());
        }
    }

    @Override
    public boolean containsKey(K key) {
        checkNotClosed();
        try {
            String redisKey = buildRedisKey(key);
            RBucket<V> bucket = redissonClient.getBucket(redisKey);
            return bucket.isExists();
        } catch (RuntimeException e) {
            LOGGER.error("L2缓存检查键失败: cache={}, key={}, error={}", cacheName, key, e.getMessage());
            return false;
        }
    }

    @Override
    public long size() {
        checkNotClosed();
        try {
            String pattern = keyPrefix + "*";
            RKeys keys = redissonClient.getKeys();
            return keys.countExists(pattern);
        } catch (RuntimeException e) {
            LOGGER.error("L2缓存获取大小失败: cache={}, error={}", cacheName, e.getMessage());
            return 0;
        }
    }

    @Override
    public String getName() {
        return cacheName;
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            LOGGER.info("L2缓存已关闭: {}", cacheName);
            // 注意：不要关闭redissonClient，因为它可能被其他缓存使用
        }
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    // ==================== 扩展方法 ====================

    /**
     * 获取键的剩余TTL（秒）
     */
    public long getRemainingTimeToLive(K key) {
        checkNotClosed();
        try {
            String redisKey = buildRedisKey(key);
            RBucket<V> bucket = redissonClient.getBucket(redisKey);
            return bucket.remainTimeToLive() / 1000; // 转换为秒
        } catch (RuntimeException e) {
            LOGGER.error("获取TTL失败: cache={}, key={}, error={}", cacheName, key, e.getMessage());
            return -1;
        }
    }

    /**
     * 设置键的过期时间
     */
    public boolean expire(K key, long ttlSeconds) {
        checkNotClosed();
        try {
            String redisKey = buildRedisKey(key);
            RBucket<V> bucket = redissonClient.getBucket(redisKey);
            return bucket.expire(Duration.ofSeconds(ttlSeconds));
        } catch (RuntimeException e) {
            LOGGER.error("设置过期时间失败: cache={}, key={}, ttl={}, error={}",
                    cacheName, key, ttlSeconds, e.getMessage());
            return false;
        }
    }

    /**
     * 获取Redis键集合
     */
    public Set<String> keySet() {
        checkNotClosed();
        try {
            String pattern = keyPrefix + "*";
            RKeys keys = redissonClient.getKeys();
            Iterable<String> matchingKeys = keys.getKeysByPattern(pattern);
            Set<String> result = new HashSet<>();
            matchingKeys.forEach(result::add);
            return result;
        } catch (RuntimeException e) {
            LOGGER.error("获取键集合失败: cache={}, error={}", cacheName, e.getMessage());
            return Collections.emptySet();
        }
    }

    /**
     * 获取实际的缓存键集合（去掉前缀）
     */
    public Set<K> getCacheKeySet() {
        checkNotClosed();
        try {
            String pattern = keyPrefix + "*";
            RKeys keys = redissonClient.getKeys();
            Iterable<String> matchingKeys = keys.getKeysByPattern(pattern);
            Set<K> result = new HashSet<>();

            for (String redisKey : matchingKeys) {
                if (redisKey.startsWith(keyPrefix)) {
                    String cacheKey = redisKey.substring(keyPrefix.length());
                    // 注意：这里简化处理，假设K可以从String转换
                    result.add((K) cacheKey);
                }
            }
            return result;
        } catch (RuntimeException e) {
            LOGGER.error("获取缓存键集合失败: cache={}, error={}", cacheName, e.getMessage());
            return Collections.emptySet();
        }
    }

    // ==================== 私有方法 ====================

    private void checkNotClosed() {
        if (closed) {
            throw new IllegalStateException("缓存已关闭: " + cacheName);
        }
    }

    @Override
    public String toString() {
        return String.format("RedissonL2Cache{name=%s, keyPrefix=%s, size=%d, closed=%s}",
                cacheName, keyPrefix, size(), closed);
    }
}