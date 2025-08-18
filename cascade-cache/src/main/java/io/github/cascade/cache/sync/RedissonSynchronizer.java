package io.github.cascade.cache.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 基于Redisson的缓存同步器实现
 */
public class RedissonSynchronizer implements SimpleCacheSynchronizer {
    
    private static final Logger log = LoggerFactory.getLogger(RedissonSynchronizer.class);
    
    private static final String DEFAULT_TOPIC = "cascade:cache:sync";
    
    private final RedissonClient redissonClient;
    private final String topicName;
    private final ObjectMapper objectMapper;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final SynchronizerStatsImpl stats = new SynchronizerStatsImpl();
    
    private RTopic topic;
    private Consumer<CacheSyncEvent> eventHandler;
    
    public RedissonSynchronizer(RedissonClient redissonClient) {
        this(redissonClient, DEFAULT_TOPIC);
    }
    
    public RedissonSynchronizer(RedissonClient redissonClient, String topicName) {
        this.redissonClient = redissonClient;
        this.topicName = topicName;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.findAndRegisterModules();
    }
    
    @Override
    public void start(Consumer<CacheSyncEvent> eventHandler) {
        if (running.compareAndSet(false, true)) {
            this.eventHandler = eventHandler;
            
            log.info("Starting Redisson synchronizer with topic: {}", topicName);
            
            try {
                topic = redissonClient.getTopic(topicName);
                topic.addListener(String.class, (channel, message) -> {
                    try {
                        handleMessage(message);
                    } catch (Exception e) {
                        log.error("Error handling sync message: {}", message, e);
                        stats.incrementFailed();
                    }
                });
                
                log.info("Redisson synchronizer started successfully");
            } catch (Exception e) {
                running.set(false);
                log.error("Failed to start Redisson synchronizer", e);
                throw new RuntimeException("Failed to start synchronizer", e);
            }
        }
    }
    
    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) {
            log.info("Stopping Redisson synchronizer");
            
            try {
                if (topic != null) {
                    topic.removeAllListeners();
                }
                log.info("Redisson synchronizer stopped successfully");
            } catch (Exception e) {
                log.warn("Error stopping Redisson synchronizer", e);
            } finally {
                eventHandler = null;
            }
        }
    }
    
    @Override
    public void publishEvent(CacheSyncEvent event) {
        if (!running.get()) {
            log.warn("Synchronizer is not running, cannot publish event: {}", event);
            return;
        }
        
        try {
            String message = serializeEvent(event);
            topic.publish(message);
            stats.incrementSent();
            log.debug("Published sync event: {}", event);
        } catch (Exception e) {
            stats.incrementFailed();
            log.error("Error publishing sync event: {}", event, e);
            throw new RuntimeException("Failed to publish sync event", e);
        }
    }
    
    @Override
    public String getName() {
        return "RedissonSynchronizer-" + topicName;
    }
    
    @Override
    public boolean isRunning() {
        return running.get();
    }
    
    @Override
    public SynchronizerStats getStats() {
        return stats;
    }
    
    private void handleMessage(String message) {
        try {
            CacheSyncEvent event = deserializeEvent(message);
            
            if (eventHandler != null) {
                eventHandler.accept(event);
                stats.incrementReceived();
            }
            
        } catch (Exception e) {
            log.error("Error deserializing sync message: {}", message, e);
            stats.incrementFailed();
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
     * 统计信息实现
     */
    private static class SynchronizerStatsImpl implements SynchronizerStats {
        private final AtomicLong sentEvents = new AtomicLong(0);
        private final AtomicLong receivedEvents = new AtomicLong(0);
        private final AtomicLong failedEvents = new AtomicLong(0);
        private final AtomicLong lastActivityTime = new AtomicLong(0);
        
        @Override
        public long getSentEvents() {
            return sentEvents.get();
        }
        
        @Override
        public long getReceivedEvents() {
            return receivedEvents.get();
        }
        
        @Override
        public long getFailedEvents() {
            return failedEvents.get();
        }
        
        @Override
        public long getLastActivityTime() {
            return lastActivityTime.get();
        }
        
        @Override
        public void reset() {
            sentEvents.set(0);
            receivedEvents.set(0);
            failedEvents.set(0);
            lastActivityTime.set(System.currentTimeMillis());
        }
        
        public void incrementSent() {
            sentEvents.incrementAndGet();
            lastActivityTime.set(System.currentTimeMillis());
        }
        
        public void incrementReceived() {
            receivedEvents.incrementAndGet();
            lastActivityTime.set(System.currentTimeMillis());
        }
        
        public void incrementFailed() {
            failedEvents.incrementAndGet();
            lastActivityTime.set(System.currentTimeMillis());
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
        
        public CacheSyncEventDto() {}
        
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