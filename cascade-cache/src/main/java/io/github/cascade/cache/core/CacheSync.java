package io.github.cascade.cache.core;

import lombok.Getter;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * @author lionel lionelk@163.com
 * =============================
 * Date: 2025/7/10
 * Time: 13:23
 * =============================
 */

/**
 * 缓存同步接口
 * <p>
 * 设计原则：
 * 1. 事件驱动：基于发布订阅模式
 * 2. 异步处理：所有同步操作都是异步的
 * 3. 类型安全：使用泛型确保类型安全
 * 4. 可扩展：支持多种同步实现
 *
 * @param <K> 键类型
 * @param <V> 值类型
 * @author cascade
 */
public interface CacheSync<K, V> {

    // ==================== 同步事件类型 ====================

    /**
     * 缓存同步事件
     */
    @Getter
    class SyncEvent<K, V> {
        private final String cacheName;
        private final EventType type;
        private final K key;
        private final V value;
        private final String nodeId;
        private final long timestamp;

        public SyncEvent(String cacheName, EventType type, K key, V value, String nodeId) {
            this.cacheName = cacheName;
            this.type = type;
            this.key = key;
            this.value = value;
            this.nodeId = nodeId;
            this.timestamp = System.currentTimeMillis();
        }

        @Override
        public String toString() {
            return String.format("SyncEvent{cache=%s, type=%s, key=%s, node=%s, time=%d}",
                    cacheName, type, key, nodeId, timestamp);
        }
    }

    /**
     * 同步事件类型
     */
    enum EventType {
        PUT,     // 缓存写入
        EVICT,   // 缓存删除
        CLEAR    // 缓存清空
    }

    // ==================== 核心同步方法 ====================

    /**
     * 发布同步事件
     */
    CompletableFuture<Void> publishEvent(SyncEvent<K, V> event);

    /**
     * 订阅同步事件
     */
    void subscribe(String cacheName, Consumer<SyncEvent<K, V>> eventHandler);

    /**
     * 取消订阅
     */
    void unsubscribe(String cacheName);

    /**
     * 启动同步服务
     */
    void start();

    /**
     * 停止同步服务
     */
    void stop();

    /**
     * 检查是否运行中
     */
    boolean isRunning();

    // ==================== 便捷方法 ====================

    /**
     * 发布PUT事件
     */
    default CompletableFuture<Void> publishPut(String cacheName, K key, V value, String nodeId) {
        return publishEvent(new SyncEvent<>(cacheName, EventType.PUT, key, value, nodeId));
    }

    /**
     * 发布EVICT事件
     */
    default CompletableFuture<Void> publishEvict(String cacheName, K key, String nodeId) {
        return publishEvent(new SyncEvent<>(cacheName, EventType.EVICT, key, null, nodeId));
    }

    /**
     * 发布CLEAR事件
     */
    default CompletableFuture<Void> publishClear(String cacheName, String nodeId) {
        return publishEvent(new SyncEvent<>(cacheName, EventType.CLEAR, null, null, nodeId));
    }
}