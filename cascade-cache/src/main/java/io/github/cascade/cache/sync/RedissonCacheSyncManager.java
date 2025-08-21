package io.github.cascade.cache.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 基于Redisson的缓存同步管理器实现
 */
public class RedissonCacheSyncManager implements CacheSyncManager {

    private static final Logger logger = LoggerFactory.getLogger(RedissonCacheSyncManager.class);

    private static final String DEFAULT_TOPIC = "cascade:cache:sync";

    private final RedissonClient redissonClient;
    private final String topicName;
    private final String nodeId;
    private final ObjectMapper objectMapper;
    private final ConcurrentMap<String, CacheSyncListener> listeners = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final CacheSyncStats stats = new CacheSyncStats();

    private RTopic topic;

    public RedissonCacheSyncManager(RedissonClient redissonClient) {
        this(redissonClient, DEFAULT_TOPIC);
    }

    public RedissonCacheSyncManager(RedissonClient redissonClient, String topicName) {
        this.redissonClient = redissonClient;
        this.topicName = topicName;
        this.nodeId = generateNodeId();
        this.objectMapper = new ObjectMapper();
        this.objectMapper.findAndRegisterModules();
    }

    private String generateNodeId() {
        try {
            String hostName = InetAddress.getLocalHost().getHostName();
            String pid = ProcessHandle.current().pid() + "";
            long timestamp = System.currentTimeMillis();
            return hostName + "-" + pid + "-" + timestamp;
        } catch (Exception e) {
            logger.warn("Failed to generate node id, using fallback", e);
            return "node-" + System.currentTimeMillis() + "-" + Thread.currentThread().getId();
        }
    }

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            logger.info("Starting cache sync manager with topic: {}, nodeId: {}", topicName, nodeId);

            topic = redissonClient.getTopic(topicName);
            topic.addListener(String.class, (channel, message) -> {
                try {
                    handleSyncMessage(message);
                } catch (Exception e) {
                    logger.error("Error handling sync message: {}", message, e);
                    stats.incrementErrorCount();
                }
            });

            logger.info("Cache sync manager started successfully");
        }
    }

    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) {
            logger.info("Stopping cache sync manager");

            if (topic != null) {
                topic.removeAllListeners();
            }

            listeners.clear();
            logger.info("Cache sync manager stopped");
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public String getCurrentNodeId() {
        return nodeId;
    }

    @Override
    public void publishEvent(CacheSyncEvent event) {
        logger.debug("Publishing sync event: {}", event);
        if (!running.get()) {
            logger.warn("Cache sync manager is not running, cannot publish event: {}", event);
            return;
        }

        if (event.getSourceNodeId().equals(nodeId)) {
            try {
                String message = serializeEvent(event);
                topic.publish(message);
                stats.incrementPublishCount();
                logger.debug("Published sync event: {}", event);
            } catch (Exception e) {
                logger.error("Error publishing sync event: {}", event, e);
                stats.incrementErrorCount();
                throw new RuntimeException("Failed to publish sync event", e);
            }
        }
    }

    @Override
    public void registerListener(CacheSyncListener listener) {
        if (listener != null && listener.getListenerId() != null) {
            listeners.put(listener.getListenerId(), listener);
            logger.info("Registered cache sync listener: {}", listener.getListenerId());
        }
    }

    @Override
    public void unregisterListener(String listenerId) {
        if (listenerId != null) {
            CacheSyncListener removed = listeners.remove(listenerId);
            if (removed != null) {
                logger.info("Unregistered cache sync listener: {}", listenerId);
            }
        }
    }

    @Override
    public int getListenerCount() {
        return listeners.size();
    }

    @Override
    public CacheSyncStats getStats() {
        return stats;
    }

    private void handleSyncMessage(String message) {
        try {
            CacheSyncEvent event = deserializeEvent(message);

            // 跳过自己发送的消息
            if (nodeId.equals(event.getSourceNodeId())) {
                return;
            }

            stats.incrementReceiveCount();
            logger.debug("Received sync event: {}", event);

            // 分发给所有监听器
            for (CacheSyncListener listener : listeners.values()) {
                if (listener.isActive() && listener.shouldHandle(event)) {
                    try {
                        listener.onSyncEvent(event);
                    } catch (Exception e) {
                        logger.error("Error in listener {}: {}", listener.getListenerId(), e.getMessage(), e);
                        stats.incrementErrorCount();
                    }
                }
            }

        } catch (Exception e) {
            logger.error("Error deserializing sync message: {}", message, e);
            stats.incrementErrorCount();
        }
    }

    private String serializeEvent(CacheSyncEvent event) {
        try {
            return objectMapper.writeValueAsString(new CacheSyncEventDto(event));
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize sync event", e);
        }
    }

    private CacheSyncEvent deserializeEvent(String message) {
        try {
            CacheSyncEventDto dto = objectMapper.readValue(message, CacheSyncEventDto.class);
            return dto.toEvent();
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize sync event", e);
        }
    }

    /**
     * 缓存同步事件DTO，用于序列化
     */
    private static class CacheSyncEventDto {
        public String cacheId;
        public String operation;
        public Object key;
        public Object value;
        public Object[] keys;
        public String sourceNodeId;
        public long timestamp;

        public CacheSyncEventDto() {
        }

        public CacheSyncEventDto(CacheSyncEvent event) {
            this.cacheId = event.getCacheId();
            this.operation = event.getOperation().name();
            this.key = event.getKey();
            this.value = event.getValue();
            this.keys = event.getKeys() != null ? event.getKeys().toArray() : null;
            this.sourceNodeId = event.getSourceNodeId();
            this.timestamp = event.getTimestamp().toEpochMilli();
        }

        public CacheSyncEvent toEvent() {
            CacheSyncEvent.Operation op = CacheSyncEvent.Operation.valueOf(operation);

            switch (op) {
                case PUT:
                    return new CacheSyncEvent(cacheId, key, value, sourceNodeId);
                case EVICT:
                case REFRESH:
                    if (keys != null && keys.length > 0) {
                        return new CacheSyncEvent(cacheId, op, java.util.Set.of(keys), sourceNodeId);
                    } else {
                        return new CacheSyncEvent(cacheId, op, key, sourceNodeId);
                    }
                case CLEAR:
                    return new CacheSyncEvent(cacheId, sourceNodeId);
                default:
                    throw new IllegalArgumentException("Unknown operation: " + operation);
            }
        }
    }
}