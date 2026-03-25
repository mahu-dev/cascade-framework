package io.github.cascade.cache.v2.sync;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cascade.cache.v2.model.CacheRecord;
import io.github.cascade.cache.v2.model.InvalidationEvent;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

/**
 * 基于 Redis Pub/Sub 的失效事件总线。
 */
public class RedisInvalidationBus<K> implements InvalidationBus<K> {

    private static final Logger LOGGER = LoggerFactory.getLogger(RedisInvalidationBus.class);

    private final RedissonClient redissonClient;
    private final String topicPrefix;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final ConcurrentMap<String, Consumer<InvalidationEvent<K>>> handlers = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, RTopic> topics = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Integer> listenerIds = new ConcurrentHashMap<>();

    private volatile boolean running = false;

    public RedisInvalidationBus(RedissonClient redissonClient, String topicPrefix) {
        this.redissonClient = redissonClient;
        this.topicPrefix = topicPrefix != null ? topicPrefix : "cascade:cache:sync:";
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
        InvalidationEvent<K> event = InvalidationEvent.update(cacheName, key, record, valueTypeName, nodeId);
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
        if (!running) {
            running = true;
            handlers.forEach(this::attachListener);
        }
    }

    @Override
    public void stop() {
        if (running) {
            running = false;
            listenerIds.forEach((cache, id) -> {
                RTopic topic = topics.get(cache);
                if (topic != null) {
                    topic.removeListener(id);
                }
            });
            listenerIds.clear();
            topics.clear();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private CompletableFuture<Void> publish(String cacheName, InvalidationEvent<K> event) {
        return CompletableFuture.runAsync(() -> {
            try {
                String payload = objectMapper.writeValueAsString(event);
                redissonClient.getTopic(topic(cacheName)).publish(payload);
            } catch (JsonProcessingException e) {
                LOGGER.warn("失效事件序列化失败: cache={}, error={}", cacheName, e.getMessage());
            } catch (Exception e) {
                LOGGER.warn("失效事件发布失败: cache={}, error={}", cacheName, e.getMessage());
            }
        });
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
                    casted.setKey((K) raw.getKey());
                    CacheRecord<Object> record = null;
                    if (raw.getRecord() != null) {
                        record = objectMapper.convertValue(raw.getRecord(), CacheRecord.class);
                    }
                    casted.setRecord(record);
                    handler.accept(casted);
                } catch (Exception e) {
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
}
