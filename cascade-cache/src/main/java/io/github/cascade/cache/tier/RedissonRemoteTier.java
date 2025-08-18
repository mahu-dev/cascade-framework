package io.github.cascade.cache.tier;

import io.github.cascade.cache.api.CacheLoader;
import lombok.Getter;
import org.redisson.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;


/**
 * 基于Redisson的远程缓存层实现(L2)
 */
public class RedissonRemoteTier<K, V> extends AbstractRemoteTier<K, V> {

    private static final Logger log = LoggerFactory.getLogger(RedissonRemoteTier.class);

    /**
     * -- GETTER --
     * 获取Redisson客户端
     */
    @Getter
    private final RedissonClient redissonClient;
    private final Duration refreshAfterWrite;
    private final ScheduledExecutorService refreshExecutor;
    private final ConcurrentHashMap<K, Instant> writeTimestamps;
    private CacheLoader<K, V> cacheLoader;

    public RedissonRemoteTier(String name, RedissonClient redissonClient, RemoteTierConfig config) {
        super(name, config.getDefaultTtl());
        this.redissonClient = redissonClient;
        this.refreshAfterWrite = config.getRefreshAfterWrite();
        this.setKeyPrefix(config.getKeyPrefix() != null ? config.getKeyPrefix() : name + ":");

        // 初始化自动刷新相关组件
        if (refreshAfterWrite != null && !refreshAfterWrite.isZero()) {
            this.writeTimestamps = new ConcurrentHashMap<>();
            this.refreshExecutor = Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "cascade-l2-refresh-" + name);
                t.setDaemon(true);
                return t;
            });
            // 启动定期检查任务
            startRefreshTask();
        } else {
            this.writeTimestamps = null;
            this.refreshExecutor = null;
        }
    }

    @Override
    public V get(K key) {
        if (key == null) {
            return null;
        }

        try {
            RBucket<V> bucket = redissonClient.getBucket(buildKey(key));
            V value = bucket.get();

            if (value != null) {
                recordHit();
                return value;
            } else {
                recordMiss();
                
                // 缓存未命中，尝试使用CacheLoader加载数据
                if (cacheLoader != null) {
                    try {
                        V loadedValue = cacheLoader.load(key);
                        if (loadedValue != null) {
                            // 将加载的数据写入缓存
                            put(key, loadedValue, getDefaultTtl());
                            log.debug("Loaded and cached value for key: {}", key);
                            return loadedValue;
                        }
                    } catch (Exception loaderException) {
                        log.warn("Failed to load value for key '{}' using CacheLoader: {}", 
                                key, loaderException.getMessage());
                        // 继续返回null，不阻断程序流程
                    }
                }
                
                return null;
            }
        } catch (Exception e) {
            recordMiss();
            recordConnectionError();
            log.error("Error getting value for key: " + key, e);
            
            // Redis异常时，仍尝试使用CacheLoader作为降级方案
            if (cacheLoader != null) {
                try {
                    V fallbackValue = cacheLoader.load(key);
                    if (fallbackValue != null) {
                        log.info("Used CacheLoader as fallback for key '{}' due to Redis error", key);
                        return fallbackValue;
                    }
                } catch (Exception loaderException) {
                    log.warn("CacheLoader fallback also failed for key '{}': {}", 
                            key, loaderException.getMessage());
                }
            }
            
            return null; // 发生异常时返回null，允许应用程序继续运行
        }
    }


    @Override
    public Map<K, V> getAll(Set<K> keys) {
        if (keys == null || keys.isEmpty()) {
            return Map.of();
        }

        Map<K, V> finalResult = new HashMap<>();
        Set<K> missedKeys = new HashSet<>();

        try {
            // 构建Redis键
            String[] redisKeys = keys.stream()
                    .map(this::buildKey)
                    .toArray(String[]::new);

            RBuckets buckets = redissonClient.getBuckets();
            Map<String, V> result = buckets.get(redisKeys);

            // 转换回原始键，分离命中和未命中的键
            for (K key : keys) {
                String redisKey = buildKey(key);
                if (result.containsKey(redisKey)) {
                    finalResult.put(key, result.get(redisKey));
                    recordHit();
                } else {
                    missedKeys.add(key);
                    recordMiss();
                }
            }

        } catch (Exception e) {
            // Redis异常时，所有键都视为miss
            for (int i = 0; i < keys.size(); i++) {
                recordMiss();
            }
            recordConnectionError();
            log.error("Error getting all values for keys: " + keys, e);
            missedKeys.addAll(keys); // 所有键都需要通过CacheLoader加载
        }

        // 对于未命中的键，尝试使用CacheLoader加载
        if (!missedKeys.isEmpty() && cacheLoader != null) {
            try {
                Map<K, V> loadedValues = cacheLoader.loadAll(missedKeys);
                if (loadedValues != null && !loadedValues.isEmpty()) {
                    // 将加载的数据写入缓存
                    putAll(loadedValues, getDefaultTtl());
                    finalResult.putAll(loadedValues);
                    log.debug("Loaded and cached {} values using CacheLoader", loadedValues.size());
                }
            } catch (Exception loaderException) {
                log.warn("Failed to load values for keys '{}' using CacheLoader: {}", 
                        missedKeys, loaderException.getMessage());
                
                // 如果批量加载失败，尝试逐个加载
                for (K missedKey : missedKeys) {
                    try {
                        V loadedValue = cacheLoader.load(missedKey);
                        if (loadedValue != null) {
                            put(missedKey, loadedValue, getDefaultTtl());
                            finalResult.put(missedKey, loadedValue);
                            log.debug("Individually loaded and cached value for key: {}", missedKey);
                        }
                    } catch (Exception individualLoaderException) {
                        log.debug("Failed to individually load value for key '{}': {}", 
                                missedKey, individualLoaderException.getMessage());
                        // 继续处理其他键
                    }
                }
            }
        }

        return finalResult;
    }


    @Override
    public void put(K key, V value, Duration ttl) {
        if (key == null || value == null) {
            return;
        }

        try {
            RBucket<V> bucket = redissonClient.getBucket(buildKey(key));
            if (ttl != null && !ttl.isZero()) {
                bucket.set(value, ttl);
            } else {
                bucket.set(value);
            }

            // 记录写入时间，用于自动刷新
            recordWriteTime(key);
        } catch (Exception e) {
            recordConnectionError();
            log.error("Error putting value for key: " + key, e);
            throw new RuntimeException("Error putting value to Redis", e);
        }
    }

    @Override
    public void putAll(Map<K, V> map) {
        if (map == null || map.isEmpty()) {
            return;
        }

        try {
            RBuckets buckets = redissonClient.getBuckets();
            Map<String, V> redisMap = map.entrySet().stream()
                    .collect(Collectors.toMap(
                            entry -> buildKey(entry.getKey()),
                            Map.Entry::getValue
                    ));

            buckets.set(redisMap);

            // 设置TTL
            Duration ttl = getDefaultTtl();
            if (ttl != null && !ttl.isZero()) {
                for (K key : map.keySet()) {
                    RBucket<V> bucket = redissonClient.getBucket(buildKey(key));
                    bucket.expire(ttl);
                }
            }
        } catch (Exception e) {
            recordConnectionError();
            log.error("Error putting all values for keys: " + map.keySet(), e);
            throw new RuntimeException("Error putting values to Redis", e);
        }
    }

    @Override
    public void putAll(Map<K, V> map, Duration ttl) {
        if (map == null || map.isEmpty()) {
            return;
        }

        try {
            if (ttl != null && !ttl.isZero()) {
                // 逐个设置TTL
                for (Map.Entry<K, V> entry : map.entrySet()) {
                    put(entry.getKey(), entry.getValue(), ttl);
                }
            } else {
                putAll(map);
            }
        } catch (Exception e) {
            recordConnectionError();
            log.error("Error putting all values with TTL for keys: " + map.keySet(), e);
            throw new RuntimeException("Error putting values with TTL to Redis", e);
        }
    }

    @Override
    public boolean putIfAbsent(K key, V value, Duration ttl) {
        if (key == null || value == null) {
            return false;
        }

        try {
            RBucket<V> bucket = redissonClient.getBucket(buildKey(key));
            boolean success = bucket.trySet(value);

            if (success && ttl != null && !ttl.isZero()) {
                bucket.expire(ttl);
            }

            return success;
        } catch (Exception e) {
            recordConnectionError();
            log.error("Error putting if absent for key: " + key, e);
            return false; // 异常时返回false，表示操作失败
        }
    }

    @Override
    public void evict(K key) {
        if (key == null) {
            return;
        }

        try {
            RBucket<V> bucket = redissonClient.getBucket(buildKey(key));
            if (bucket.delete()) {
                recordEviction();
            }
        } catch (Exception e) {
            recordConnectionError();
            log.error("Error evicting key: " + key, e);
            // 记录为异常，但不抛出异常，允许程序继续
        }
    }

    @Override
    public void evictAll(Set<K> keys) {
        if (keys == null || keys.isEmpty()) {
            return;
        }

        try {
            RKeys rKeys = redissonClient.getKeys();
            String[] redisKeys = keys.stream()
                    .map(this::buildKey)
                    .toArray(String[]::new);

            long deleted = rKeys.delete(redisKeys);
            for (int i = 0; i < deleted; i++) {
                recordEviction();
            }
        } catch (Exception e) {
            recordConnectionError();
            log.error("Error evicting all keys: " + keys, e);
            // 记录为异常，但不抛出异常，允许程序继续
        }
    }

    @Override
    public void clear() {
        try {
            RKeys rKeys = redissonClient.getKeys();
            String pattern = getKeyPrefix() + "*";
            Iterable<String> keys = rKeys.getKeysByPattern(pattern);

            long deleted = 0;
            for (String key : keys) {
                if (rKeys.delete(key) > 0) {
                    deleted++;
                }
            }
            for (int i = 0; i < deleted; i++) {
                recordEviction();
            }
        } catch (Exception e) {
            recordConnectionError();
            log.error("Error clearing cache", e);
            // 记录为异常，但不抛出异常，允许程序继续
        }
    }

    @Override
    public boolean containsKey(K key) {
        if (key == null) {
            return false;
        }

        try {
            RBucket<V> bucket = redissonClient.getBucket(buildKey(key));
            return bucket.isExists();
        } catch (Exception e) {
            recordConnectionError();
            log.error("Error checking key existence: " + key, e);
            return false; // 异常时返回false
        }
    }

    @Override
    public long size() {
        try {
            RKeys rKeys = redissonClient.getKeys();
            String pattern = getKeyPrefix() + "*";
            return rKeys.count();
        } catch (Exception e) {
            recordConnectionError();
            log.error("Error getting cache size", e);
            return 0; // 异常时返回0
        }
    }

    @Override
    public void cleanUp() {
        // Redis会自动清理过期键，这里可以执行一些维护操作
    }

    // ==================== 远程缓存特有方法实现 ====================

    @Override
    public Duration getTimeToLive(K key) {
        if (key == null) {
            return Duration.ZERO;
        }

        try {
            RBucket<V> bucket = redissonClient.getBucket(buildKey(key));
            long ttl = bucket.remainTimeToLive();
            return ttl > 0 ? Duration.ofMillis(ttl) : Duration.ZERO;
        } catch (Exception e) {
            recordConnectionError();
            log.error("Error getting TTL for key: " + key, e);
            return Duration.ZERO; // 异常时返回0
        }
    }

    @Override
    public boolean expire(K key, Duration ttl) {
        if (key == null || ttl == null) {
            return false;
        }

        try {
            RBucket<V> bucket = redissonClient.getBucket(buildKey(key));
            return bucket.expire(ttl);
        } catch (Exception e) {
            recordConnectionError();
            log.error("Error setting expiration for key: " + key, e);
            return false;
        }
    }

    @Override
    public boolean persist(K key) {
        if (key == null) {
            return false;
        }

        try {
            RBucket<V> bucket = redissonClient.getBucket(buildKey(key));
            return bucket.clearExpire();
        } catch (Exception e) {
            recordConnectionError();
            log.error("Error persisting key: " + key, e);
            return false;
        }
    }

    @Override
    public Set<K> keys(String pattern) {
        try {
            RKeys rKeys = redissonClient.getKeys();
            String redisPattern = getKeyPrefix() + pattern;
            Iterable<String> keyNames = rKeys.getKeysByPattern(redisPattern);

            Set<K> result = new HashSet<>();
            String prefix = getKeyPrefix();
            for (String keyName : keyNames) {
                if (keyName.startsWith(prefix)) {
                    String originalKey = keyName.substring(prefix.length());
                    result.add(extractKey(originalKey));
                }
            }
            return result;
        } catch (Exception e) {
            recordConnectionError();
            log.error("Error getting keys by pattern: " + pattern, e);
            return new HashSet<>();
        }
    }

    @Override
    public boolean isConnected() {
        try {
            return redissonClient.getKeys().count() >= 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public Map<String, Object> getClusterInfo() {
        Map<String, Object> info = new HashMap<>();
        try {
            info.put("connected", isConnected());
            info.put("ping", ping());
            try {
                info.put("nodes", redissonClient.getConfig().getTransportMode().name());
            } catch (Exception e) {
                info.put("nodes", "unknown");
            }
        } catch (Exception e) {
            info.put("error", e.getMessage());
        }
        return info;
    }

    @Override
    public Object eval(String script, List<K> keys, Object... args) {
        try {
            List<String> keyNames = keys.stream()
                    .map(this::buildKey)
                    .collect(Collectors.toList());
            List<Object> keyObjects = keyNames.stream().map(k -> (Object) k).collect(Collectors.toList());
            return redissonClient.getScript().eval(
                    RScript.Mode.READ_WRITE,
                    script,
                    RScript.ReturnType.VALUE,
                    keyObjects,
                    args
            );
        } catch (Exception e) {
            recordConnectionError();
            log.error("Error executing script", e);
            throw new RuntimeException("Error executing Lua script", e);
        }
    }

    @Override
    protected Pipeline createPipeline() {
        return new RedissonPipeline();
    }

    @Override
    protected Transaction createTransaction() {
        return new RedissonTransaction();
    }

    @Override
    protected void doSubscribe(MessageListener listener, String... channels) {
        try {
            for (String channel : channels) {
                RTopic topic = redissonClient.getTopic(channel);
                topic.addListener(Object.class, (charSequence, msg) -> listener.onMessage(charSequence.toString(), msg));
            }
        } catch (Exception e) {
            recordConnectionError();
            log.error("Error subscribing to channels: " + Arrays.toString(channels), e);
        }
    }

    @Override
    protected void doPsubscribe(MessageListener listener, String... patterns) {
        try {
            for (String pattern : patterns) {
                RPatternTopic topic = redissonClient.getPatternTopic(pattern);
                topic.addListener(Object.class, (charSequence, charSequence2, msg) -> listener.onMessage(charSequence2.toString(), msg));
            }
        } catch (Exception e) {
            recordConnectionError();
            log.error("Error pattern subscribing to patterns: " + Arrays.toString(patterns), e);
        }
    }

    @Override
    protected void doUnsubscribe(String... channels) {
        try {
            for (String channel : channels) {
                RTopic topic = redissonClient.getTopic(channel);
                topic.removeAllListeners();
            }
        } catch (Exception e) {
            recordConnectionError();
            log.error("Error unsubscribing from channels: " + Arrays.toString(channels), e);
        }
    }

    @Override
    public long publish(String channel, Object message) {
        try {
            RTopic topic = redissonClient.getTopic(channel);
            return topic.publish(message);
        } catch (Exception e) {
            recordConnectionError();
            log.error("Error publishing to channel: " + channel, e);
            return 0;
        }
    }

    // ==================== 内部实现类 ====================

    private class RedissonPipeline implements Pipeline {
        private final RBatch batch = redissonClient.createBatch();

        @Override
        public Pipeline put(Object key, Object value) {
            @SuppressWarnings("unchecked")
            RBucketAsync<Object> bucket = batch.getBucket(buildKey((K) key));
            bucket.setAsync(value);
            return this;
        }

        @Override
        public Pipeline put(Object key, Object value, Duration ttl) {
            @SuppressWarnings("unchecked")
            RBucketAsync<Object> bucket = batch.getBucket(buildKey((K) key));
            bucket.setAsync(value, ttl);
            return this;
        }

        @Override
        public Pipeline get(Object key) {
            @SuppressWarnings("unchecked")
            RBucketAsync<Object> bucket = batch.getBucket(buildKey((K) key));
            bucket.getAsync();
            return this;
        }

        @Override
        public Pipeline evict(Object key) {
            @SuppressWarnings("unchecked")
            RBucketAsync<Object> bucket = batch.getBucket(buildKey((K) key));
            bucket.deleteAsync();
            return this;
        }

        @Override
        public Pipeline exists(Object key) {
            @SuppressWarnings("unchecked")
            RBucketAsync<Object> bucket = batch.getBucket(buildKey((K) key));
            bucket.isExistsAsync();
            return this;
        }

        @Override
        public List<Object> execute() {
            try {
                BatchResult<?> result = batch.execute();
                recordCommand(System.nanoTime());
                return new ArrayList<>(result.getResponses());
            } catch (Exception e) {
                recordConnectionError();
                log.error("Error executing pipeline", e);
                throw new RuntimeException("Error executing pipeline", e);
            }
        }

        @Override
        public void close() {
            // Redisson batch会自动清理
        }
    }

    private class RedissonTransaction implements Transaction {
        private final RTransaction transaction = redissonClient.createTransaction(org.redisson.api.TransactionOptions.defaults());

        @Override
        public Transaction put(Object key, Object value) {
            @SuppressWarnings("unchecked")
            RBucket<Object> bucket = transaction.getBucket(buildKey((K) key));
            bucket.set(value);
            return this;
        }

        @Override
        public Transaction put(Object key, Object value, Duration ttl) {
            @SuppressWarnings("unchecked")
            RBucket<Object> bucket = transaction.getBucket(buildKey((K) key));
            bucket.set(value, ttl);
            return this;
        }

        @Override
        public Transaction evict(Object key) {
            @SuppressWarnings("unchecked")
            RBucket<Object> bucket = transaction.getBucket(buildKey((K) key));
            bucket.delete();
            return this;
        }

        @Override
        public Transaction watch(Object... keys) {
            // Redisson事务不需要显式watch
            return this;
        }

        @Override
        public Transaction unwatch() {
            // Redisson事务不需要显式unwatch
            return this;
        }

        @Override
        public List<Object> exec() {
            try {
                transaction.commit();
                recordCommand(System.nanoTime());
                return new ArrayList<>(); // Redisson事务返回的结果格式不同
            } catch (Exception e) {
                recordConnectionError();
                log.error("Error executing transaction", e);
                transaction.rollback();
                return null; // 表示事务被丢弃
            }
        }

        @Override
        public void discard() {
            try {
                transaction.rollback();
            } catch (Exception e) {
                log.error("Error discarding transaction", e);
            }
        }

        @Override
        public void close() {
            try {
                // Redisson事务会自动处理状态
                transaction.rollback();
            } catch (Exception e) {
                log.error("Error closing transaction", e);
            }
        }
    }

    /**
     * 设置缓存加载器，用于自动刷新
     */
    @Override
    public void setLoader(CacheLoader<K, V> loader) {
        this.cacheLoader = loader;
    }

    /**
     * 获取缓存加载器
     */
    public CacheLoader<K, V> getLoader() {
        return cacheLoader;
    }

    /**
     * 记录键的写入时间
     */
    private void recordWriteTime(K key) {
        if (writeTimestamps != null) {
            writeTimestamps.put(key, Instant.now());
        }
    }

    /**
     * 启动自动刷新任务
     */
    private void startRefreshTask() {
        if (refreshExecutor == null || refreshAfterWrite == null) {
            return;
        }

        long refreshIntervalSeconds = Math.max(refreshAfterWrite.toSeconds() / 10, 30); // 检查间隔
        refreshExecutor.scheduleWithFixedDelay(this::checkAndRefreshExpiredEntries,
                refreshIntervalSeconds, refreshIntervalSeconds, TimeUnit.SECONDS);
    }

    /**
     * 检查并刷新过期的条目
     */
    private void checkAndRefreshExpiredEntries() {
        if (writeTimestamps == null || cacheLoader == null) {
            return;
        }

        Instant now = Instant.now();
        Iterator<Map.Entry<K, Instant>> iterator = writeTimestamps.entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<K, Instant> entry = iterator.next();
            K key = entry.getKey();
            Instant writeTime = entry.getValue();

            // 检查是否需要刷新
            if (Duration.between(writeTime, now).compareTo(refreshAfterWrite) >= 0) {
                // 异步刷新
                refreshExecutor.submit(() -> refreshKey(key));
                // 更新写入时间，避免重复刷新
                writeTimestamps.put(key, now);
            }

            // 清理太老的记录（超过2倍的刷新时间）
            Duration maxAge = refreshAfterWrite.multipliedBy(2);
            if (Duration.between(writeTime, now).compareTo(maxAge) > 0) {
                iterator.remove();
            }
        }
    }

    /**
     * 刷新单个键
     */
    private void refreshKey(K key) {
        if (cacheLoader == null) {
            return;
        }

        try {
            // 使用CacheLoader重新加载数据
            V newValue = cacheLoader.load(key);
            if (newValue != null) {
                // 使用默认TTL重新写入
                put(key, newValue, getDefaultTtl());
                log.debug("Refreshed cache entry for key: {}", key);
            }
        } catch (Exception e) {
            log.warn("Failed to refresh cache entry for key: {}", key, e);
        }
    }

    /**
     * 关闭自动刷新功能
     */
    public void shutdown() {
        if (refreshExecutor != null && !refreshExecutor.isShutdown()) {
            refreshExecutor.shutdown();
            try {
                if (!refreshExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    refreshExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                refreshExecutor.shutdownNow();
            }
        }
        if (writeTimestamps != null) {
            writeTimestamps.clear();
        }
    }

    /**
     * 远程缓存配置类
     */
    @Getter
    public static class RemoteTierConfig {
        private String keyPrefix;
        private Duration defaultTtl = Duration.ofHours(1);
        private Duration refreshAfterWrite;
        private boolean enableBatch = true;
        private int batchSize = 100;

        public RemoteTierConfig setKeyPrefix(String keyPrefix) {
            this.keyPrefix = keyPrefix;
            return this;
        }

        public RemoteTierConfig setDefaultTtl(Duration defaultTtl) {
            this.defaultTtl = defaultTtl;
            return this;
        }

        public RemoteTierConfig setRefreshAfterWrite(Duration refreshAfterWrite) {
            this.refreshAfterWrite = refreshAfterWrite;
            return this;
        }

        public RemoteTierConfig setEnableBatch(boolean enableBatch) {
            this.enableBatch = enableBatch;
            return this;
        }

        public RemoteTierConfig setBatchSize(int batchSize) {
            this.batchSize = batchSize;
            return this;
        }
    }
}