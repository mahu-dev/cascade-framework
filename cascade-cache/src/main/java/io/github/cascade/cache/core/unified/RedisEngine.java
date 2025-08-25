package io.github.cascade.cache.core.unified;

import io.github.cascade.cache.api.CacheLoader;
import io.github.cascade.cache.api.CacheStats;
import lombok.*;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.*;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
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

    // 基础统计信息
    private final AtomicLong hitCount = new AtomicLong(0);
    private final AtomicLong missCount = new AtomicLong(0);
    private final AtomicLong loadCount = new AtomicLong(0);
    private final AtomicLong evictionCount = new AtomicLong(0);

    // 滑动窗口统计
    private final SlidingWindowStats slidingWindowStats;

    // 刷新机制
    private final Map<K, Instant> writeTimestamps = new ConcurrentHashMap<>();
    private final ScheduledExecutorService refreshExecutor;

    public RedisEngine(String name, RedissonClient redisson, RedisConfig config) {
        this.name = name;
        this.redisson = redisson;
        this.config = config;
        String prefix = config.keyPrefix != null ? config.keyPrefix + ":" + name + ":" : name + ":";
        this.keyPrefix = prefix.replaceAll(":+", ":");

        // 初始化滑动窗口统计
        this.slidingWindowStats = new SlidingWindowStats(name);

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

        long startTime = System.nanoTime();
        try {
            String redisKey = buildRedisKey(key);
            RBucket<V> bucket = redisson.getBucket(redisKey);
            V value = bucket.get();

            long responseTime = System.nanoTime() - startTime;

            if (value != null) {
                hitCount.incrementAndGet();
                slidingWindowStats.recordHit(responseTime);
                log.debug("Redis cache hit: key={}", key);

                // 记录访问时间用于刷新
                if (config.refreshAfterWrite != null) {
                    writeTimestamps.put(key, Instant.now());
                }
            } else {
                missCount.incrementAndGet();
                slidingWindowStats.recordMiss(responseTime);
                log.debug("Redis cache miss: key={}", key);
            }

            return value;
        } catch (Exception e) {
            long responseTime = System.nanoTime() - startTime;
            slidingWindowStats.recordError(responseTime);
            log.error("Redis get operation failed: key={}", key, e);
            throw e;
        }
    }

    @Override
    public Map<K, V> getAll(Set<K> keys) {
        if (keys == null || keys.isEmpty()) return Map.of();

        Map<K, V> result = new HashMap<>();

        // 检查是否需要分批处理
        if (keys.size() <= config.batchSize) {
            // 小批量直接处理
            processBatchGet(keys, result);
        } else {
            // 大批量分批处理
            List<Set<K>> batches = partitionKeys(keys, config.batchSize);
            log.debug("Splitting {} keys into {} batches of size {}", keys.size(), batches.size(), config.batchSize);

            for (Set<K> batch : batches) {
                try {
                    processBatchGet(batch, result);
                } catch (Exception e) {
                    log.error("Failed to process batch of {} keys", batch.size(), e);
                    // 继续处理其他批次，避免全部失败
                }
            }
        }

        log.debug("Redis batch getAll: requested={}, found={}", keys.size(), result.size());
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

        // 检查是否需要分批处理
        if (map.size() <= config.batchSize) {
            // 小批量直接处理
            processBatchPut(map);
        } else {
            // 大批量分批处理
            List<Map<K, V>> batches = partitionMap(map, config.batchSize);
            log.debug("Splitting {} entries into {} batches of size {}", map.size(), batches.size(), config.batchSize);

            for (Map<K, V> batch : batches) {
                try {
                    processBatchPut(batch);
                } catch (Exception e) {
                    log.error("Failed to process batch of {} entries", batch.size(), e);
                    // 继续处理其他批次，避免全部失败
                }
            }
        }

        log.debug("Redis batch putAll completed: total entries={}", map.size());
    }

    @Override
    public boolean putIfAbsent(K key, V value) {
        if (key == null || value == null) return false;
        
        String redisKey = buildRedisKey(key);
        RBucket<V> bucket = redisson.getBucket(redisKey);
        
        // 使用Redisson的trySet提供原子操作
        boolean inserted;
        if (config.defaultTtl != null && !config.defaultTtl.isZero()) {
            inserted = bucket.trySet(value, config.defaultTtl.toMillis(), TimeUnit.MILLISECONDS);
        } else {
            inserted = bucket.trySet(value);
        }
        
        if (inserted && config.refreshAfterWrite != null) {
            writeTimestamps.put(key, Instant.now());
        }
        
        log.debug("Redis putIfAbsent: key={}, inserted={}", key, inserted);
        return inserted;
    }

    @Override
    public boolean putIfAbsent(K key, V value, Duration ttl) {
        if (key == null || value == null) return false;
        
        String redisKey = buildRedisKey(key);
        RBucket<V> bucket = redisson.getBucket(redisKey);
        
        // 使用Redisson的trySet提供原子操作（带TTL）
        boolean inserted;
        if (ttl != null && !ttl.isZero()) {
            inserted = bucket.trySet(value, ttl.toMillis(), TimeUnit.MILLISECONDS);
        } else {
            inserted = bucket.trySet(value);
        }
        
        if (inserted && config.refreshAfterWrite != null) {
            writeTimestamps.put(key, Instant.now());
        }
        
        log.debug("Redis putIfAbsent with TTL: key={}, ttl={}, inserted={}", key, ttl, inserted);
        return inserted;
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

        // 构建所有键名
        String[] redisKeys = keys.stream()
                .map(this::buildRedisKey)
                .toArray(String[]::new);

        // 批量删除
        long deleted = redisson.getKeys().delete(redisKeys);

        evictionCount.addAndGet(deleted);
        keys.forEach(writeTimestamps::remove);
        log.debug("Redis batch evictAll: requested={}, deleted={}", keys.size(), deleted);
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
     * 处理批量获取操作
     */
    private void processBatchGet(Set<K> keys, Map<K, V> result) {
        RBatch batch = redisson.createBatch();
        Map<K, RFuture<V>> futures = new HashMap<>();

        for (K key : keys) {
            String redisKey = buildRedisKey(key);
            RBucketAsync<V> bucket = batch.getBucket(redisKey);
            futures.put(key, bucket.getAsync());
        }

        // 执行批量操作
        batch.execute();

        // 收集结果
        for (Map.Entry<K, RFuture<V>> entry : futures.entrySet()) {
            try {
                V value = entry.getValue().get();
                if (value != null) {
                    result.put(entry.getKey(), value);
                    hitCount.incrementAndGet();
                } else {
                    missCount.incrementAndGet();
                }
            } catch (Exception e) {
                log.error("Failed to get key: {}", entry.getKey(), e);
                missCount.incrementAndGet();
            }
        }
    }

    /**
     * 处理批量写入操作
     */
    private void processBatchPut(Map<K, V> map) {
        RBatch batch = redisson.createBatch();

        for (Map.Entry<K, V> entry : map.entrySet()) {
            String redisKey = buildRedisKey(entry.getKey());
            RBucketAsync<V> bucket = batch.getBucket(redisKey);

            if (config.defaultTtl != null && !config.defaultTtl.isZero()) {
                bucket.setAsync(entry.getValue(), config.defaultTtl.toSeconds(), TimeUnit.SECONDS);
            } else {
                bucket.setAsync(entry.getValue());
            }

            // 记录写入时间
            if (config.refreshAfterWrite != null) {
                writeTimestamps.put(entry.getKey(), Instant.now());
            }
        }

        // 执行批量操作
        BatchResult<?> results = batch.execute();
        log.debug("Batch put completed: size={}, success={}", map.size(), results.getResponses().size());
    }

    /**
     * 将键集合分批
     */
    private List<Set<K>> partitionKeys(Set<K> keys, int batchSize) {
        List<Set<K>> batches = new ArrayList<>();
        Set<K> currentBatch = new HashSet<>(batchSize);

        for (K key : keys) {
            currentBatch.add(key);
            if (currentBatch.size() >= batchSize) {
                batches.add(currentBatch);
                currentBatch = new HashSet<>(batchSize);
            }
        }

        // 添加剩余的键
        if (!currentBatch.isEmpty()) {
            batches.add(currentBatch);
        }

        return batches;
    }

    /**
     * 将Map分批
     */
    private List<Map<K, V>> partitionMap(Map<K, V> map, int batchSize) {
        List<Map<K, V>> batches = new ArrayList<>();
        Map<K, V> currentBatch = new HashMap<>(batchSize);

        for (Map.Entry<K, V> entry : map.entrySet()) {
            currentBatch.put(entry.getKey(), entry.getValue());
            if (currentBatch.size() >= batchSize) {
                batches.add(currentBatch);
                currentBatch = new HashMap<>(batchSize);
            }
        }

        // 添加剩余的条目
        if (!currentBatch.isEmpty()) {
            batches.add(currentBatch);
        }

        return batches;
    }

    /**
     * 获取详细的性能指标
     */
    public PerformanceMetrics getPerformanceMetrics() {
        return slidingWindowStats.getPerformanceMetrics();
    }

    /**
     * Redis统计信息实现 - 增强版
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
            PerformanceMetrics metrics = slidingWindowStats.getPerformanceMetrics();
            return metrics.getAverageResponseTimeMs();
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
            PerformanceMetrics metrics = slidingWindowStats.getPerformanceMetrics();
            return metrics.getErrorCount();
        }

        @Override
        public long totalLoadTime() {
            PerformanceMetrics metrics = slidingWindowStats.getPerformanceMetrics();
            return (long) (metrics.getTotalResponseTimeMs() * 1_000_000); // 转换为纳秒
        }

        @Override
        public void reset() {
            hitCount.set(0);
            missCount.set(0);
            loadCount.set(0);
            evictionCount.set(0);
            slidingWindowStats.reset();
        }
    }

    // ==================== 滑动窗口统计实现 ====================

    /**
     * 滑动窗口统计类
     * 提供详细的性能指标统计，包括QPS、响应时间分布、错误率等
     */
    public static class SlidingWindowStats {
        private static final int WINDOW_SIZE_SECONDS = 60; // 60秒窗口
        private static final int BUCKET_COUNT = 12; // 12个桶，每个桶5秒
        private static final int BUCKET_DURATION_MS = WINDOW_SIZE_SECONDS * 1000 / BUCKET_COUNT;

        private final String name;
        private final AtomicLong[] hitCounts = new AtomicLong[BUCKET_COUNT];
        private final AtomicLong[] missCounts = new AtomicLong[BUCKET_COUNT];
        private final AtomicLong[] errorCounts = new AtomicLong[BUCKET_COUNT];
        private final AtomicLong[] responseTimes = new AtomicLong[BUCKET_COUNT]; // 总响应时间(纳秒)
        private final AtomicLong[] requestCounts = new AtomicLong[BUCKET_COUNT]; // 总请求数

        // 响应时间直方图 (微秒)
        private final AtomicLong[] responseTimeHistogram = {
                new AtomicLong(0), // < 1ms
                new AtomicLong(0), // 1-5ms
                new AtomicLong(0), // 5-10ms
                new AtomicLong(0), // 10-50ms
                new AtomicLong(0), // 50-100ms
                new AtomicLong(0), // 100-500ms
                new AtomicLong(0), // > 500ms
        };

        private volatile long lastCleanupTime = System.currentTimeMillis();

        public SlidingWindowStats(String name) {
            this.name = name;
            // 初始化所有计数器
            for (int i = 0; i < BUCKET_COUNT; i++) {
                hitCounts[i] = new AtomicLong(0);
                missCounts[i] = new AtomicLong(0);
                errorCounts[i] = new AtomicLong(0);
                responseTimes[i] = new AtomicLong(0);
                requestCounts[i] = new AtomicLong(0);
            }
        }

        /**
         * 记录命中
         */
        public void recordHit(long responseTimeNanos) {
            int bucketIndex = getCurrentBucketIndex();
            hitCounts[bucketIndex].incrementAndGet();
            responseTimes[bucketIndex].addAndGet(responseTimeNanos);
            requestCounts[bucketIndex].incrementAndGet();
            updateHistogram(responseTimeNanos);
            cleanupOldBucketsIfNeeded();
        }

        /**
         * 记录未命中
         */
        public void recordMiss(long responseTimeNanos) {
            int bucketIndex = getCurrentBucketIndex();
            missCounts[bucketIndex].incrementAndGet();
            responseTimes[bucketIndex].addAndGet(responseTimeNanos);
            requestCounts[bucketIndex].incrementAndGet();
            updateHistogram(responseTimeNanos);
            cleanupOldBucketsIfNeeded();
        }

        /**
         * 记录错误
         */
        public void recordError(long responseTimeNanos) {
            int bucketIndex = getCurrentBucketIndex();
            errorCounts[bucketIndex].incrementAndGet();
            responseTimes[bucketIndex].addAndGet(responseTimeNanos);
            requestCounts[bucketIndex].incrementAndGet();
            updateHistogram(responseTimeNanos);
            cleanupOldBucketsIfNeeded();
        }

        /**
         * 获取当前桶索引
         */
        private int getCurrentBucketIndex() {
            long currentTime = System.currentTimeMillis();
            return (int) ((currentTime / BUCKET_DURATION_MS) % BUCKET_COUNT);
        }

        /**
         * 更新响应时间直方图
         */
        private void updateHistogram(long responseTimeNanos) {
            double responseTimeMs = responseTimeNanos / 1_000_000.0;

            if (responseTimeMs < 1) {
                responseTimeHistogram[0].incrementAndGet();
            } else if (responseTimeMs < 5) {
                responseTimeHistogram[1].incrementAndGet();
            } else if (responseTimeMs < 10) {
                responseTimeHistogram[2].incrementAndGet();
            } else if (responseTimeMs < 50) {
                responseTimeHistogram[3].incrementAndGet();
            } else if (responseTimeMs < 100) {
                responseTimeHistogram[4].incrementAndGet();
            } else if (responseTimeMs < 500) {
                responseTimeHistogram[5].incrementAndGet();
            } else {
                responseTimeHistogram[6].incrementAndGet();
            }
        }

        /**
         * 定期清理过期的桶
         */
        private void cleanupOldBucketsIfNeeded() {
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastCleanupTime > BUCKET_DURATION_MS) {
                // 清理当前桶之外的旧桶
                int currentBucket = getCurrentBucketIndex();
                for (int i = 0; i < BUCKET_COUNT; i++) {
                    if (Math.abs(i - currentBucket) > 2) { // 保留当前桶及其邻近的桶
                        long timeDiff = currentTime - (i * BUCKET_DURATION_MS);
                        if (timeDiff > WINDOW_SIZE_SECONDS * 1000) {
                            hitCounts[i].set(0);
                            missCounts[i].set(0);
                            errorCounts[i].set(0);
                            responseTimes[i].set(0);
                            requestCounts[i].set(0);
                        }
                    }
                }
                lastCleanupTime = currentTime;
            }
        }

        /**
         * 获取性能指标
         */
        public PerformanceMetrics getPerformanceMetrics() {
            long totalHits = 0, totalMisses = 0, totalErrors = 0;
            long totalResponseTime = 0, totalRequests = 0;

            for (int i = 0; i < BUCKET_COUNT; i++) {
                totalHits += hitCounts[i].get();
                totalMisses += missCounts[i].get();
                totalErrors += errorCounts[i].get();
                totalResponseTime += responseTimes[i].get();
                totalRequests += requestCounts[i].get();
            }

            double avgResponseTimeMs = totalRequests > 0 ?
                    (totalResponseTime / 1_000_000.0) / totalRequests : 0.0;

            double hitRate = (totalHits + totalMisses) > 0 ?
                    (double) totalHits / (totalHits + totalMisses) : 0.0;

            double errorRate = totalRequests > 0 ?
                    (double) totalErrors / totalRequests : 0.0;

            // 计算QPS (请求每秒)
            double qps = totalRequests / (double) WINDOW_SIZE_SECONDS;

            // 复制直方图数据
            long[] histogramSnapshot = new long[responseTimeHistogram.length];
            for (int i = 0; i < responseTimeHistogram.length; i++) {
                histogramSnapshot[i] = responseTimeHistogram[i].get();
            }

            return new PerformanceMetrics(
                    name,
                    totalHits,
                    totalMisses,
                    totalErrors,
                    totalRequests,
                    avgResponseTimeMs,
                    totalResponseTime / 1_000_000.0,
                    hitRate,
                    errorRate,
                    qps,
                    histogramSnapshot
            );
        }

        /**
         * 重置统计信息
         */
        public void reset() {
            for (int i = 0; i < BUCKET_COUNT; i++) {
                hitCounts[i].set(0);
                missCounts[i].set(0);
                errorCounts[i].set(0);
                responseTimes[i].set(0);
                requestCounts[i].set(0);
            }

            for (AtomicLong counter : responseTimeHistogram) {
                counter.set(0);
            }
        }
    }

    /**
     * 性能指标数据类
     */
    public static class PerformanceMetrics {
        private final String cacheName;
        private final long hitCount;
        private final long missCount;
        private final long errorCount;
        private final long requestCount;
        private final double averageResponseTimeMs;
        private final double totalResponseTimeMs;
        private final double hitRate;
        private final double errorRate;
        private final double qps;
        private final long[] responseTimeHistogram;

        public PerformanceMetrics(String cacheName, long hitCount, long missCount, long errorCount,
                                  long requestCount, double averageResponseTimeMs, double totalResponseTimeMs,
                                  double hitRate, double errorRate, double qps, long[] responseTimeHistogram) {
            this.cacheName = cacheName;
            this.hitCount = hitCount;
            this.missCount = missCount;
            this.errorCount = errorCount;
            this.requestCount = requestCount;
            this.averageResponseTimeMs = averageResponseTimeMs;
            this.totalResponseTimeMs = totalResponseTimeMs;
            this.hitRate = hitRate;
            this.errorRate = errorRate;
            this.qps = qps;
            this.responseTimeHistogram = responseTimeHistogram.clone();
        }

        // Getters
        public String getCacheName() {
            return cacheName;
        }

        public long getHitCount() {
            return hitCount;
        }

        public long getMissCount() {
            return missCount;
        }

        public long getErrorCount() {
            return errorCount;
        }

        public long getRequestCount() {
            return requestCount;
        }

        public double getAverageResponseTimeMs() {
            return averageResponseTimeMs;
        }

        public double getTotalResponseTimeMs() {
            return totalResponseTimeMs;
        }

        public double getHitRate() {
            return hitRate;
        }

        public double getErrorRate() {
            return errorRate;
        }

        public double getQps() {
            return qps;
        }

        public long[] getResponseTimeHistogram() {
            return responseTimeHistogram.clone();
        }

        /**
         * 获取响应时间百分位数
         */
        public ResponseTimePercentiles getResponseTimePercentiles() {
            long total = 0;
            for (long count : responseTimeHistogram) {
                total += count;
            }

            if (total == 0) {
                return new ResponseTimePercentiles(0, 0, 0, 0);
            }

            long p50Target = total * 50 / 100;
            long p90Target = total * 90 / 100;
            long p95Target = total * 95 / 100;
            long p99Target = total * 99 / 100;

            double[] bucketBounds = {1, 5, 10, 50, 100, 500, Double.MAX_VALUE};

            long cumulative = 0;
            double p50 = 0, p90 = 0, p95 = 0, p99 = 0;

            for (int i = 0; i < responseTimeHistogram.length; i++) {
                cumulative += responseTimeHistogram[i];

                if (p50 == 0 && cumulative >= p50Target) {
                    p50 = bucketBounds[i];
                }
                if (p90 == 0 && cumulative >= p90Target) {
                    p90 = bucketBounds[i];
                }
                if (p95 == 0 && cumulative >= p95Target) {
                    p95 = bucketBounds[i];
                }
                if (p99 == 0 && cumulative >= p99Target) {
                    p99 = bucketBounds[i];
                }
            }

            return new ResponseTimePercentiles(p50, p90, p95, p99);
        }

        @Override
        public String toString() {
            return String.format(
                    "PerformanceMetrics{cache='%s', requests=%d, hits=%d(%.1f%%), errors=%d(%.1f%%), " +
                            "avgResponseTime=%.2fms, qps=%.2f}",
                    cacheName, requestCount, hitCount, hitRate * 100, errorCount, errorRate * 100,
                    averageResponseTimeMs, qps
            );
        }
    }

    /**
     * 响应时间百分位数
     */
    @Getter
    public static class ResponseTimePercentiles {
        private final double p50;
        private final double p90;
        private final double p95;
        private final double p99;

        public ResponseTimePercentiles(double p50, double p90, double p95, double p99) {
            this.p50 = p50;
            this.p90 = p90;
            this.p95 = p95;
            this.p99 = p99;
        }

        @Override
        public String toString() {
            return String.format("ResponseTimePercentiles{P50=%.2fms, P90=%.2fms, P95=%.2fms, P99=%.2fms}",
                    p50, p90, p95, p99);
        }
    }
}