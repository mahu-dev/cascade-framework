package io.github.cascade.cache.core.unified;

import io.github.cascade.cache.api.CacheLoader;
import io.github.cascade.cache.api.CacheStats;
import lombok.*;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RKeys;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 基于Redisson的Redis缓存引擎实现
 * 支持TTL、自动刷新等高级功能
 *
 * @author cascade
 */
@Slf4j
@ToString
public class RedisEngine<K, V> implements CacheEngine<K, V> {

    private final RedissonClient redisson;
    private final String name;
    private final String keyPrefix;
    private final RedisConfig config;

    // 统计信息
    private final AtomicLong hitCount = new AtomicLong(0);
    private final AtomicLong missCount = new AtomicLong(0);
    private final AtomicLong loadCount = new AtomicLong(0);
    private final AtomicLong evictionCount = new AtomicLong(0);

    // 刷新机制
    private final Map<K, Instant> writeTimestamps = new ConcurrentHashMap<>();
    private final ScheduledExecutorService refreshExecutor;

    public RedisEngine(String name, RedissonClient redisson, RedisConfig config) {
        this.name = name;
        this.redisson = redisson;
        this.config = config;
        String prefix = config.keyPrefix != null ? config.keyPrefix + ":" + name + ":" : name + ":";
        this.keyPrefix = prefix.replaceAll(":+", ":");

        // 启动刷新任务
        if (config.refreshAfterWrite != null && config.cacheLoader != null) {
            this.refreshExecutor = Executors.newScheduledThreadPool(2);
            startRefreshTask();
        } else {
            this.refreshExecutor = null;
        }

        log.debug("Created Redis engine: {}, prefix: {}", name, keyPrefix);
    }

    @Override
    public V get(K key) {
        if (key == null) return null;

        String redisKey = buildRedisKey(key);
        RBucket<V> bucket = redisson.getBucket(redisKey);
        V value = bucket.get();

        if (value != null) {
            hitCount.incrementAndGet();
            log.debug("Redis cache hit: key={}", key);

            // 记录访问时间用于刷新
            if (config.refreshAfterWrite != null) {
                writeTimestamps.put(key, Instant.now());
            }
        } else {
            missCount.incrementAndGet();
            log.debug("Redis cache miss: key={}", key);
        }

        return value;
    }

    @Override
    public Map<K, V> getAll(Set<K> keys) {
        if (keys == null || keys.isEmpty()) return Map.of();

        Map<K, V> result = new HashMap<>();
        for (K key : keys) {
            V value = get(key);
            if (value != null) {
                result.put(key, value);
            }
        }

        log.debug("Redis getAll: requested={}, found={}", keys.size(), result.size());
        return result;
    }

    @Override
    public void put(K key, V value) {
        put(key, value, config.defaultTtl);
    }

    @Override
    public void put(K key, V value, Duration ttl) {
        if (key == null || value == null) return;

        String redisKey = buildRedisKey(key);
        RBucket<V> bucket = redisson.getBucket(redisKey);

        if (ttl != null && !ttl.isZero()) {
            bucket.set(value, ttl.toMillis(), TimeUnit.MILLISECONDS);
        } else {
            bucket.set(value);
        }

        // 记录写入时间用于刷新
        if (config.refreshAfterWrite != null) {
            writeTimestamps.put(key, Instant.now());
        }

        log.debug("Redis put: key={}, ttl={}", key, ttl.toSeconds());
    }

    @Override
    public void putAll(Map<K, V> map) {
        if (map == null || map.isEmpty()) return;

        for (Map.Entry<K, V> entry : map.entrySet()) {
            put(entry.getKey(), entry.getValue());
        }

        log.debug("Redis putAll: size={}", map.size());
    }

    @Override
    public void evict(K key) {
        if (key == null) return;

        String redisKey = buildRedisKey(key);
        boolean deleted = redisson.getBucket(redisKey).delete();

        if (deleted) {
            evictionCount.incrementAndGet();
            writeTimestamps.remove(key);
            log.debug("Redis evict: key={}", key);
        }
    }

    @Override
    public void evictAll(Set<K> keys) {
        if (keys == null || keys.isEmpty()) return;

        for (K key : keys) {
            evict(key);
        }

        log.debug("Redis evictAll: size={}", keys.size());
    }

    @Override
    public void clear() {
        RKeys keys = redisson.getKeys();
        String pattern = keyPrefix + "*";
        long deleted = keys.deleteByPattern(pattern);

        evictionCount.addAndGet(deleted);
        writeTimestamps.clear();

        log.debug("Redis cleared: {} keys, pattern: {}", deleted, pattern);
    }

    @Override
    public boolean containsKey(K key) {
        if (key == null) return false;

        String redisKey = buildRedisKey(key);
        return redisson.getBucket(redisKey).isExists();
    }

    @Override
    public long size() {
        RKeys keys = redisson.getKeys();
        String pattern = keyPrefix + "*";
        return keys.getKeysStreamByPattern(pattern).count();
//        return keys.countExists(pattern);
    }

    @Override
    public CacheStats getStats() {
        return new RedisStatsImpl();
    }

    @Override
    public void cleanUp() {
        // Redis自动清理过期数据，这里可以执行一些维护操作
        if (writeTimestamps.size() > 10000) {
            // 清理过期的时间戳记录
            Instant cutoff = Instant.now().minus(config.refreshAfterWrite.multipliedBy(2));
            writeTimestamps.entrySet().removeIf(entry -> entry.getValue().isBefore(cutoff));
        }
    }

    @Override
    public void close() {
        if (refreshExecutor != null && !refreshExecutor.isShutdown()) {
            refreshExecutor.shutdown();
        }
        writeTimestamps.clear();
        log.debug("Redis engine closed: {}", name);
    }

    /**
     * 构建Redis键名
     */
    private String buildRedisKey(K key) {
        return keyPrefix + key.toString();
    }

    /**
     * 启动刷新任务
     */
    private void startRefreshTask() {
        if (refreshExecutor == null || config.refreshAfterWrite == null || config.cacheLoader == null) {
            return;
        }

        long refreshIntervalSeconds = Math.max(config.refreshAfterWrite.toSeconds() / 10, 30);
        refreshExecutor.scheduleWithFixedDelay(this::checkAndRefreshExpiredEntries,
                refreshIntervalSeconds, refreshIntervalSeconds, TimeUnit.SECONDS);

        log.debug("Started refresh task: interval={}s", refreshIntervalSeconds);
    }

    /**
     * 检查并刷新过期的条目
     */
    private void checkAndRefreshExpiredEntries() {
        if (writeTimestamps.isEmpty()) return;

        Instant now = Instant.now();
        writeTimestamps.entrySet().removeIf(entry -> {
            K key = entry.getKey();
            Instant writeTime = entry.getValue();

            // 检查是否需要刷新
            if (Duration.between(writeTime, now).compareTo(config.refreshAfterWrite) >= 0) {
                // 异步刷新
                refreshExecutor.submit(() -> refreshKey(key));
                return false; // 保留记录，更新写入时间
            }

            // 清理太老的记录
            Duration maxAge = config.refreshAfterWrite.multipliedBy(3);
            return Duration.between(writeTime, now).compareTo(maxAge) > 0;
        });
    }

    /**
     * 刷新单个键
     */
    private void refreshKey(K key) {
        if (config.cacheLoader == null) return;

        try {
            V newValue = config.<K, V>cacheLoader().load(key);
            if (newValue != null) {
                put(key, newValue, config.defaultTtl);
                loadCount.incrementAndGet();
                log.debug("Refreshed key: {}", key);
            }
        } catch (Exception e) {
            log.warn("Failed to refresh key: {}, error: {}", key, e.getMessage());
        }
    }

    /**
     * 配置类
     */
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    @Accessors(chain = true)
    @Builder
    public static class RedisConfig {
        private String keyPrefix;
        private Duration defaultTtl = Duration.ofHours(1);
        private Duration refreshAfterWrite;
        private CacheLoader<?, ?> cacheLoader;
        private boolean enableBatch = true;
        private int batchSize = 100;

        public <K, V> CacheLoader<K, V> cacheLoader() {
            return (CacheLoader<K, V>) cacheLoader;
        }

    }

    /**
     * Redis统计信息实现
     */
    private class RedisStatsImpl implements CacheStats {

        @Override
        public long hitCount() {
            return hitCount.get();
        }

        @Override
        public long missCount() {
            return missCount.get();
        }

        @Override
        public double hitRate() {
            long hits = hitCount.get();
            long total = hits + missCount.get();
            return total == 0 ? 0.0 : (double) hits / total;
        }

        @Override
        public double missRate() {
            return 1.0 - hitRate();
        }

        @Override
        public long loadCount() {
            return loadCount.get();
        }

        @Override
        public double averageLoadPenalty() {
            return 0; // Redis不支持此统计
        }

        @Override
        public long evictionCount() {
            return evictionCount.get();
        }

        @Override
        public long evictionWeight() {
            return evictionCount.get();
        }

        @Override
        public long requestCount() {
            return hitCount.get() + missCount.get();
        }

        @Override
        public long loadExceptionCount() {
            return 0; // Redis不统计加载异常
        }

        @Override
        public long totalLoadTime() {
            return 0; // Redis不统计加载时间
        }

        @Override
        public void reset() {
            hitCount.set(0);
            missCount.set(0);
            loadCount.set(0);
            evictionCount.set(0);
        }
    }
}