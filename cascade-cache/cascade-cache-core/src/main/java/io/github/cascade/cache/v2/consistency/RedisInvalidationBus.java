package io.github.cascade.cache.v2.consistency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cascade.cache.v2.store.model.CacheRecord;
import io.github.cascade.cache.v2.consistency.InvalidationEvent;
import io.github.cascade.cache.v2.observability.CacheMetricsCollector;
import io.github.cascade.cache.v2.support.ObjectMapperHolder;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 基于 Redis Pub/Sub 的失效事件总线。
 */
public class RedisInvalidationBus<K> implements InvalidationBus<K> {

    private static final Logger LOGGER = LoggerFactory.getLogger(RedisInvalidationBus.class);
    private static final String UPDATE_PAYLOAD_CODEC = "jackson-json-v1";
    private static final AtomicInteger EXECUTOR_INDEX = new AtomicInteger(0);

    private final RedissonClient redissonClient;
    private final String topicPrefix;
    private final CacheMetricsCollector metricsCollector;
    private final PublishOptions publishOptions;
    private final ObjectMapper objectMapper = ObjectMapperHolder.getInstance();
    private final Object lifecycleMonitor = new Object();

    private final ConcurrentMap<String, Consumer<InvalidationEvent<K>>> handlers = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, RTopic> topics = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Integer> listenerIds = new ConcurrentHashMap<>();
    private volatile ThreadPoolExecutor publishExecutor;

    private volatile boolean running = false;

    public RedisInvalidationBus(RedissonClient redissonClient, String topicPrefix) {
        this(redissonClient, topicPrefix, CacheMetricsCollector.create("sync-bus", null), PublishOptions.defaults());
    }

    public RedisInvalidationBus(RedissonClient redissonClient,
                                String topicPrefix,
                                CacheMetricsCollector metricsCollector,
                                PublishOptions publishOptions) {
        this.redissonClient = redissonClient;
        this.topicPrefix = topicPrefix != null ? topicPrefix : "cascade:cache:sync:";
        this.metricsCollector = metricsCollector != null ? metricsCollector : CacheMetricsCollector.create("sync-bus", null);
        this.publishOptions = publishOptions != null ? publishOptions : PublishOptions.defaults();
    }

    @Override
    public CompletableFuture<Void> publishInvalidation(String cacheName, K key, long version, String nodeId) {
        if (!running) {
            return CompletableFuture.completedFuture(null);
        }
        InvalidationEvent<K> event = InvalidationEvent.invalidate(cacheName, key, version, nodeId);
        return publish(cacheName, event);
    }

    @Override
    public CompletableFuture<Void> publishUpdate(String cacheName,
                                                 K key,
                                                 CacheRecord<?> record,
                                                 String valueTypeName,
                                                 String nodeId) {
        if (!running) {
            return CompletableFuture.completedFuture(null);
        }
        if (record == null) {
            return publishInvalidation(cacheName, key, 0L, nodeId);
        }
        InvalidationEvent.UpdatePayload payload = toUpdatePayload(record, cacheName, key);
        if (payload == null) {
            return publishInvalidation(cacheName, key, record.getVersion(), nodeId);
        }
        InvalidationEvent<K> event = InvalidationEvent.update(
                cacheName, key, record.getVersion(), payload, valueTypeName, nodeId
        );
        return publish(cacheName, event);
    }

    @Override
    public CompletableFuture<Void> publishClear(String cacheName, long version, String nodeId) {
        if (!running) {
            return CompletableFuture.completedFuture(null);
        }
        InvalidationEvent<K> event = InvalidationEvent.clear(cacheName, version, nodeId);
        return publish(cacheName, event);
    }

    @Override
    public void subscribe(String cacheName, Consumer<InvalidationEvent<K>> handler) {
        handlers.put(cacheName, handler);
        if (running) {
            attachListener(cacheName, handler);
        }
    }

    @Override
    public void unsubscribe(String cacheName) {
        handlers.remove(cacheName);
        RTopic topic = topics.remove(cacheName);
        Integer listenerId = listenerIds.remove(cacheName);
        if (topic != null && listenerId != null) {
            topic.removeListener(listenerId);
        }
    }

    @Override
    public void start() {
        synchronized (lifecycleMonitor) {
            if (running) {
                return;
            }
            running = true;
            if (publishOptions.asyncPublish()) {
                ensurePublishExecutor();
            }
            handlers.forEach(this::attachListener);
        }
    }

    @Override
    public void stop() {
        synchronized (lifecycleMonitor) {
            if (!running) {
                return;
            }
            running = false;
            listenerIds.forEach((cache, id) -> {
                RTopic topic = topics.get(cache);
                if (topic != null) {
                    topic.removeListener(id);
                }
            });
            listenerIds.clear();
            topics.clear();
            shutdownPublishExecutor();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private CompletableFuture<Void> publish(String cacheName, InvalidationEvent<K> event) {
        final String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            metricsCollector.incSyncPublishFail();
            markDeadLetter(cacheName, event.getOperation(), e);
            LOGGER.warn("失效事件序列化失败: cache={}, op={}, error={}",
                    cacheName, event.getOperation(), e.getMessage());
            return CompletableFuture.failedFuture(e);
        }

        if (!publishOptions.asyncPublish()) {
            try {
                publishWithRetry(cacheName, event.getOperation(), payload);
                return CompletableFuture.completedFuture(null);
            } catch (Exception e) {
                return CompletableFuture.failedFuture(e);
            }
        }

        ThreadPoolExecutor executor = ensurePublishExecutor();
        CompletableFuture<Void> future = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try {
                    publishWithRetry(cacheName, event.getOperation(), payload);
                    future.complete(null);
                } catch (Throwable throwable) {
                    future.completeExceptionally(throwable);
                }
            });
        } catch (RejectedExecutionException e) {
            metricsCollector.incSyncPublishReject();
            markDeadLetter(cacheName, event.getOperation(), e);
            LOGGER.warn("失效事件发布被拒绝（线程池已满）: cache={}, op={}, error={}",
                    cacheName, event.getOperation(), e.getMessage());
            future.completeExceptionally(e);
        }
        return future;
    }

    @SuppressWarnings("unchecked")
    private void attachListener(String cacheName, Consumer<InvalidationEvent<K>> handler) {
        try {
            RTopic topic = redissonClient.getTopic(topic(cacheName));
            Integer oldId = listenerIds.remove(cacheName);
            if (oldId != null) {
                topic.removeListener(oldId);
            }
            int listenerId = topic.addListener(String.class, (channel, msg) -> {
                try {
                    InvalidationEvent<?> raw = objectMapper.readValue(msg, InvalidationEvent.class);
                    InvalidationEvent<K> casted = new InvalidationEvent<>();
                    casted.setCacheName(raw.getCacheName());
                    casted.setOperation(raw.getOperation());
                    casted.setVersion(raw.getVersion());
                    casted.setNodeId(raw.getNodeId());
                    casted.setTimestamp(raw.getTimestamp());
                    casted.setValueTypeName(raw.getValueTypeName());
                    casted.setKeyTypeName(raw.getKeyTypeName());
                    casted.setKey(decodeEventKey(raw, cacheName));
                    casted.setUpdatePayload(raw.getUpdatePayload());
                    handler.accept(casted);
                } catch (Exception e) {
                    metricsCollector.incSyncConsumeFail();
                    LOGGER.warn("失效事件消费失败: cache={}, error={}", cacheName, e.getMessage());
                }
            });
            topics.put(cacheName, topic);
            listenerIds.put(cacheName, listenerId);
        } catch (Exception e) {
            LOGGER.warn("失效事件订阅失败: cache={}, error={}", cacheName, e.getMessage());
        }
    }

    private String topic(String cacheName) {
        return topicPrefix.endsWith(":") ? topicPrefix + cacheName : topicPrefix + ":" + cacheName;
    }

    private InvalidationEvent.UpdatePayload toUpdatePayload(CacheRecord<?> record, String cacheName, K key) {
        try {
            String valuePayload = objectMapper.writeValueAsString(record.getValue());
            return new InvalidationEvent.UpdatePayload(
                    UPDATE_PAYLOAD_CODEC,
                    valuePayload,
                    record.getWriteTimeMs(),
                    record.getSoftExpireAtMs(),
                    record.getHardExpireAtMs(),
                    record.getSourceNodeId()
            );
        } catch (Exception e) {
            LOGGER.warn("UPDATE事件编码失败，降级为INVALIDATE: cache={}, key={}, error={}",
                    cacheName, key, e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private K decodeEventKey(InvalidationEvent<?> raw, String cacheName) {
        Object rawKey = raw.getKey();
        if (rawKey == null) {
            return null;
        }
        String keyTypeName = raw.getKeyTypeName();
        if (keyTypeName == null || keyTypeName.isBlank()) {
            return (K) rawKey;
        }
        try {
            Class<?> keyType = Class.forName(keyTypeName);
            Object typedKey = keyType.isInstance(rawKey)
                    ? rawKey
                    : objectMapper.convertValue(rawKey, keyType);
            return (K) typedKey;
        } catch (Exception e) {
            metricsCollector.incSyncConsumeFail();
            LOGGER.warn("失效事件key转换失败: cache={}, keyType={}, error={}",
                    cacheName, keyTypeName, e.getMessage());
            return null;
        }
    }

    private void publishWithRetry(String cacheName, InvalidationEvent.Operation operation, String payload) {
        Exception lastError = null;
        for (int attempt = 1; attempt <= publishOptions.maxRetries(); attempt++) {
            try {
                redissonClient.getTopic(topic(cacheName)).publish(payload);
                return;
            } catch (Exception e) {
                lastError = e;
                if (attempt < publishOptions.maxRetries()) {
                    metricsCollector.incSyncPublishRetry();
                    backoff(attempt);
                    continue;
                }
                metricsCollector.incSyncPublishFail();
                markDeadLetter(cacheName, operation, e);
                LOGGER.warn("失效事件发布失败: cache={}, op={}, attempts={}, error={}",
                        cacheName, operation, attempt, e.getMessage());
            }
        }
        throw new IllegalStateException("失效事件发布失败: cache=" + cacheName + ", op=" + operation, lastError);
    }

    private void backoff(int attempt) {
        long base = publishOptions.retryBackoffMs();
        if (base <= 0) {
            return;
        }
        long factor = 1L << Math.min(10, Math.max(0, attempt - 1));
        long delayMs = Math.min(2_000L, base * factor);
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void markDeadLetter(String cacheName, InvalidationEvent.Operation operation, Exception error) {
        metricsCollector.incSyncDeadLetter();
        LOGGER.warn("失效事件进入死信: cache={}, op={}, error={}", cacheName, operation, error.getMessage());
    }

    private ThreadPoolExecutor ensurePublishExecutor() {
        synchronized (lifecycleMonitor) {
            ThreadPoolExecutor current = this.publishExecutor;
            if (current != null && !current.isShutdown() && !current.isTerminated()) {
                return current;
            }
            String namePrefix = "cascade-sync-publish-" + EXECUTOR_INDEX.incrementAndGet();
            ThreadPoolExecutor created = new ThreadPoolExecutor(
                    publishOptions.threadPoolSize(),
                    publishOptions.threadPoolSize(),
                    0L,
                    TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(publishOptions.queueCapacity()),
                    runnable -> {
                        Thread thread = new Thread(runnable, namePrefix);
                        thread.setDaemon(true);
                        return thread;
                    },
                    new ThreadPoolExecutor.AbortPolicy()
            );
            created.prestartAllCoreThreads();
            this.publishExecutor = created;
            return created;
        }
    }

    private void shutdownPublishExecutor() {
        ThreadPoolExecutor current = this.publishExecutor;
        this.publishExecutor = null;
        if (current == null) {
            return;
        }
        current.shutdown();
        try {
            if (!current.awaitTermination(500, TimeUnit.MILLISECONDS)) {
                current.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            current.shutdownNow();
        }
    }

    public static final class PublishOptions {
        private final boolean asyncPublish;
        private final int threadPoolSize;
        private final int queueCapacity;
        private final int maxRetries;
        private final long retryBackoffMs;

        public PublishOptions(boolean asyncPublish,
                              int threadPoolSize,
                              int queueCapacity,
                              int maxRetries,
                              long retryBackoffMs) {
            this.asyncPublish = asyncPublish;
            this.threadPoolSize = Math.max(1, threadPoolSize);
            this.queueCapacity = Math.max(1, queueCapacity);
            this.maxRetries = Math.max(1, maxRetries);
            this.retryBackoffMs = Math.max(0L, retryBackoffMs);
        }

        public static PublishOptions defaults() {
            return new PublishOptions(true, 2, 1024, 3, 50L);
        }

        public boolean asyncPublish() {
            return asyncPublish;
        }

        public int threadPoolSize() {
            return threadPoolSize;
        }

        public int queueCapacity() {
            return queueCapacity;
        }

        public int maxRetries() {
            return maxRetries;
        }

        public long retryBackoffMs() {
            return retryBackoffMs;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof PublishOptions that)) {
                return false;
            }
            return asyncPublish == that.asyncPublish
                    && threadPoolSize == that.threadPoolSize
                    && queueCapacity == that.queueCapacity
                    && maxRetries == that.maxRetries
                    && retryBackoffMs == that.retryBackoffMs;
        }

        @Override
        public int hashCode() {
            return Objects.hash(asyncPublish, threadPoolSize, queueCapacity, maxRetries, retryBackoffMs);
        }
    }
}
