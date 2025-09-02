package io.github.cascade.cache.simple;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cascade.cache.exception.CacheConnectionException;
import io.github.cascade.cache.exception.CacheSerializationException;
import lombok.Getter;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

/**
 * Redis发布订阅缓存同步实现
 * <p>
 * 设计原则：
 * 1. 发布订阅：基于Redis的Pub/Sub机制
 * 2. JSON序列化：使用JSON进行事件序列化
 * 3. 节点标识：避免自己发送的事件被自己处理
 * 4. 异常隔离：单个缓存的异常不影响其他缓存
 *
 * @param <K> 键类型
 * @param <V> 值类型
 * @author cascade
 */
public class RedisCacheSync<K, V> implements CacheSync<K, V> {

    private static final Logger log = LoggerFactory.getLogger(RedisCacheSync.class);

    private final RedissonClient redissonClient;
    /**
     * -- GETTER --
     * 获取主题前缀
     */
    @Getter
    private final String topicPrefix;
    /**
     * -- GETTER --
     * 获取当前节点ID
     */
    @Getter
    private final String nodeId;
    private final ObjectMapper objectMapper;
    private final ConcurrentMap<String, Consumer<SyncEvent<K, V>>> subscribers = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, RTopic> topics = new ConcurrentHashMap<>();
    private volatile boolean running = false;

    /**
     * 构造器
     */
    public RedisCacheSync(RedissonClient redissonClient, String topicPrefix) {
        this.redissonClient = redissonClient;
        this.topicPrefix = topicPrefix != null ? topicPrefix : "cascade:cache:sync:";
        this.nodeId = generateNodeId();
        this.objectMapper = new ObjectMapper();

        log.info("创建Redis缓存同步器: topicPrefix={}, nodeId={}", this.topicPrefix, this.nodeId);
    }

    /**
     * 生成节点ID
     */
    private String generateNodeId() {
        try {
            String hostName = InetAddress.getLocalHost().getHostName();
            String pid = ProcessHandle.current().pid() + "";
            long timestamp = System.currentTimeMillis();
            return hostName + "-" + pid + "-" + timestamp;
        } catch (Exception e) {
            log.warn("Failed to generate node id, using fallback", e);
            return "node-" + System.currentTimeMillis() + "-" + Thread.currentThread().getId();
        }

    }

    // ==================== CacheSync接口实现 ====================

    @Override
    public CompletableFuture<Void> publishEvent(SyncEvent<K, V> event) {
        if (!running) {
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.runAsync(() -> {
            try {
                String topicName = buildTopicName(event.getCacheName());
                RTopic topic = redissonClient.getTopic(topicName);

                // 序列化事件为JSON
                SerializableEvent serializableEvent = new SerializableEvent(event);
                String jsonData = objectMapper.writeValueAsString(serializableEvent);

                // 发布事件
                topic.publish(jsonData);

                if (log.isTraceEnabled()) {
                    log.trace("发布同步事件: topic={}, event={}", topicName, event);
                }

            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                log.error("发布同步事件失败: event={}, error={}", event, e.getMessage(), e);
                throw new CacheSerializationException("RedisCacheSync", "序列化事件失败", e);
            } catch (Exception e) {
                log.error("发布同步事件失败: event={}, error={}", event, e.getMessage(), e);
                throw new CacheConnectionException("RedisCacheSync", "发布事件失败", e);
            }
        });
    }

    @Override
    public void subscribe(String cacheName, Consumer<SyncEvent<K, V>> eventHandler) {
        if (eventHandler == null) {
            throw new IllegalArgumentException("事件处理器不能为null");
        }

        subscribers.put(cacheName, eventHandler);

        if (running) {
            doSubscribe(cacheName, eventHandler);
        }

        log.info("订阅缓存同步事件: cacheName={}", cacheName);
    }

    @Override
    public void unsubscribe(String cacheName) {
        subscribers.remove(cacheName);

        RTopic topic = topics.remove(cacheName);
        if (topic != null) {
            topic.removeAllListeners();
        }

        log.info("取消订阅缓存同步事件: cacheName={}", cacheName);
    }

    @Override
    public void start() {
        if (!running) {
            running = true;

            // 为所有已订阅的缓存创建监听器
            subscribers.forEach(this::doSubscribe);

            log.info("Redis缓存同步器已启动: nodeId={}, 订阅数={}", nodeId, subscribers.size());
        }
    }

    @Override
    public void stop() {
        if (running) {
            running = false;

            // 移除所有监听器
            topics.values().forEach(RTopic::removeAllListeners);
            topics.clear();

            log.info("Redis缓存同步器已停止: nodeId={}", nodeId);
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    // ==================== 私有方法 ====================

    /**
     * 执行订阅操作
     */
    private void doSubscribe(String cacheName, Consumer<SyncEvent<K, V>> eventHandler) {
        try {
            String topicName = buildTopicName(cacheName);
            RTopic topic = redissonClient.getTopic(topicName);

            // 添加消息监听器
            topic.addListener(String.class, (channel, jsonData) -> {
                try {
                    // 反序列化事件
                    SerializableEvent serializableEvent = objectMapper.readValue(jsonData, SerializableEvent.class);

                    // 忽略自己发送的事件
                    if (nodeId.equals(serializableEvent.getNodeId())) {
                        log.info("忽略自己发送的事件: topic={}, event={}", topicName, serializableEvent);
                        return;
                    }

                    // 构造同步事件
                    SyncEvent<K, V> syncEvent = new SyncEvent<>(
                            serializableEvent.getCacheName(),
                            EventType.valueOf(serializableEvent.getType()),
                            (K) serializableEvent.getKey(),
                            (V) serializableEvent.getValue(),
                            serializableEvent.getNodeId()
                    );

                    // 处理事件
                    eventHandler.accept(syncEvent);

                    if (log.isTraceEnabled()) {
                        log.trace("处理同步事件: topic={}, event={}", topicName, syncEvent);
                    }

                } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                    log.error("同步事件反序列化失败: topic={}, data={}, error={}", topicName, jsonData, e.getMessage());
                } catch (Exception e) {
                    log.error("处理同步事件失败: topic={}, data={}, error={}", topicName, jsonData, e.getMessage(), e);
                }
            });

            topics.put(cacheName, topic);

        } catch (Exception e) {
            log.error("订阅同步事件失败: cacheName={}, error={}", cacheName, e.getMessage());
        }
    }

    /**
     * 构建主题名称
     */
    private String buildTopicName(String cacheName) {
        return topicPrefix.endsWith(":") ? topicPrefix + cacheName : topicPrefix + ":" + cacheName;
    }

    // ==================== 可序列化事件类 ====================

    /**
     * 可序列化的事件类（用于JSON序列化）
     */
    public static class SerializableEvent {
        private String cacheName;
        private String type;
        private Object key;
        private Object value;
        private String nodeId;
        private long timestamp;

        // 默认构造器（JSON反序列化需要）
        public SerializableEvent() {
        }

        public SerializableEvent(SyncEvent event) {
            this.cacheName = event.getCacheName();
            this.type = event.getType().name();
            this.key = event.getKey();
            this.value = event.getValue();
            this.nodeId = event.getNodeId();
            this.timestamp = event.getTimestamp();
        }

        // Getter/Setter methods for Jackson serialization
        public String getCacheName() {
            return cacheName;
        }

        public void setCacheName(String cacheName) {
            this.cacheName = cacheName;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public Object getKey() {
            return key;
        }

        public void setKey(Object key) {
            this.key = key;
        }

        public Object getValue() {
            return value;
        }

        public void setValue(Object value) {
            this.value = value;
        }

        public String getNodeId() {
            return nodeId;
        }

        public void setNodeId(String nodeId) {
            this.nodeId = nodeId;
        }

        public long getTimestamp() {
            return timestamp;
        }

        public void setTimestamp(long timestamp) {
            this.timestamp = timestamp;
        }
    }

    // ==================== 扩展方法 ====================

    /**
     * 获取订阅数量
     */
    public int getSubscriberCount() {
        return subscribers.size();
    }

    @Override
    public String toString() {
        return String.format("RedisCacheSync{nodeId=%s, topicPrefix=%s, running=%s, subscribers=%d}",
                nodeId, topicPrefix, running, subscribers.size());
    }
}