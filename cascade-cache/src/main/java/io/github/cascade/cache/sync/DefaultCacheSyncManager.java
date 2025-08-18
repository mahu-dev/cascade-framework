package io.github.cascade.cache.sync;

import io.github.cascade.cache.api.Cache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 默认缓存同步管理器实现
 * 集成多个同步器，提供统一的同步管理
 */
public class DefaultCacheSyncManager implements CacheSyncManager {
    
    private static final Logger log = LoggerFactory.getLogger(DefaultCacheSyncManager.class);
    
    private final String nodeId;
    private final ConcurrentMap<String, CacheSyncListener> listeners = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Cache<?, ?>> registeredCaches = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final CacheSyncStats stats = new CacheSyncStats();
    
    private SimpleCacheSynchronizer synchronizer;
    
    public DefaultCacheSyncManager(String nodeId) {
        this.nodeId = nodeId;
    }
    
    public DefaultCacheSyncManager(String nodeId, SimpleCacheSynchronizer synchronizer) {
        this.nodeId = nodeId;
        this.synchronizer = synchronizer;
    }
    
    /**
     * 设置同步器
     */
    public void setSynchronizer(SimpleCacheSynchronizer synchronizer) {
        this.synchronizer = synchronizer;
    }
    
    /**
     * 注册缓存
     */
    public void registerCache(String cacheId, Cache<?, ?> cache) {
        registeredCaches.put(cacheId, cache);
        
        // 为每个缓存创建默认监听器
        String listenerId = "cache-listener-" + cacheId;
        CacheSyncListener listener = new DefaultCacheSyncListener(cacheId, cache);
        registerListener(listener);
        
        log.info("Registered cache '{}' for synchronization", cacheId);
    }
    
    /**
     * 取消注册缓存
     */
    public void unregisterCache(String cacheId) {
        registeredCaches.remove(cacheId);
        unregisterListener("cache-listener-" + cacheId);
        log.info("Unregistered cache '{}' from synchronization", cacheId);
    }
    
    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            log.info("Starting cache sync manager for node: {}", nodeId);
            
            if (synchronizer != null) {
                try {
                    synchronizer.start(this::handleSyncEvent);
                    log.info("Cache synchronizer started successfully");
                } catch (Exception e) {
                    log.error("Failed to start cache synchronizer", e);
                    running.set(false);
                    throw new RuntimeException("Failed to start cache sync manager", e);
                }
            }
            
            log.info("Cache sync manager started successfully");
        }
    }
    
    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) {
            log.info("Stopping cache sync manager");
            
            if (synchronizer != null) {
                try {
                    synchronizer.stop();
                    log.info("Cache synchronizer stopped");
                } catch (Exception e) {
                    log.warn("Error stopping cache synchronizer", e);
                }
            }
            
            listeners.clear();
            registeredCaches.clear();
            log.info("Cache sync manager stopped");
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
        if (!running.get()) {
            log.warn("Cache sync manager is not running, cannot publish event: {}", event);
            return;
        }
        
        if (synchronizer == null) {
            log.warn("No synchronizer configured, cannot publish event: {}", event);
            return;
        }
        
        try {
            synchronizer.publishEvent(event);
            stats.incrementPublishCount();
            log.debug("Published sync event: {}", event);
        } catch (Exception e) {
            log.error("Error publishing sync event: {}", event, e);
            stats.incrementErrorCount();
            throw new RuntimeException("Failed to publish sync event", e);
        }
    }
    
    @Override
    public void registerListener(CacheSyncListener listener) {
        if (listener != null && listener.getListenerId() != null) {
            listeners.put(listener.getListenerId(), listener);
            log.info("Registered cache sync listener: {}", listener.getListenerId());
        }
    }
    
    @Override
    public void unregisterListener(String listenerId) {
        if (listenerId != null) {
            CacheSyncListener removed = listeners.remove(listenerId);
            if (removed != null) {
                log.info("Unregistered cache sync listener: {}", listenerId);
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
    
    /**
     * 处理接收到的同步事件
     */
    private void handleSyncEvent(CacheSyncEvent event) {
        try {
            // 跳过自己发送的消息
            if (nodeId.equals(event.getSourceNodeId())) {
                return;
            }
            
            stats.incrementReceiveCount();
            log.debug("Received sync event: {}", event);
            
            // 分发给所有监听器
            for (CacheSyncListener listener : listeners.values()) {
                if (listener.isActive() && listener.shouldHandle(event)) {
                    try {
                        listener.onSyncEvent(event);
                    } catch (Exception e) {
                        log.error("Error in listener {}: {}", listener.getListenerId(), e.getMessage(), e);
                        stats.incrementErrorCount();
                    }
                }
            }
            
        } catch (Exception e) {
            log.error("Error handling sync event: {}", event, e);
            stats.incrementErrorCount();
        }
    }
    
    /**
     * 便捷方法：发布PUT事件
     */
    public void publishPutEvent(String cacheId, Object key, Object value) {
        CacheSyncEvent event = new CacheSyncEvent(cacheId, key, value, nodeId);
        publishEvent(event);
    }
    
    /**
     * 便捷方法：发布EVICT事件
     */
    public void publishEvictEvent(String cacheId, Object key) {
        CacheSyncEvent event = new CacheSyncEvent(cacheId, CacheSyncEvent.Operation.EVICT, key, nodeId);
        publishEvent(event);
    }
    
    /**
     * 便捷方法：发布CLEAR事件
     */
    public void publishClearEvent(String cacheId) {
        CacheSyncEvent event = new CacheSyncEvent(cacheId, nodeId);
        publishEvent(event);
    }
    
    /**
     * 获取已注册的缓存
     */
    public Map<String, Cache<?, ?>> getRegisteredCaches() {
        return new ConcurrentHashMap<>(registeredCaches);
    }
    
    /**
     * 检查缓存是否已注册
     */
    public boolean isCacheRegistered(String cacheId) {
        return registeredCaches.containsKey(cacheId);
    }
    
    /**
     * 默认缓存同步监听器
     * 接收同步事件并应用到本地缓存
     */
    private static class DefaultCacheSyncListener implements CacheSyncListener {
        
        private final String cacheId;
        private final Cache<Object, Object> cache;
        private final String listenerId;
        
        @SuppressWarnings("unchecked")
        public DefaultCacheSyncListener(String cacheId, Cache<?, ?> cache) {
            this.cacheId = cacheId;
            this.cache = (Cache<Object, Object>) cache;
            this.listenerId = "cache-listener-" + cacheId;
        }
        
        @Override
        public void onSyncEvent(CacheSyncEvent event) {
            // 只处理本缓存的事件
            if (!cacheId.equals(event.getCacheId())) {
                return;
            }
            
            try {
                switch (event.getOperation()) {
                    case PUT:
                        if (event.getKey() != null && event.getValue() != null) {
                            cache.put(event.getKey(), event.getValue());
                        }
                        break;
                    case EVICT:
                        if (event.getKey() != null) {
                            cache.evict(event.getKey());
                        } else if (event.getKeys() != null) {
                            cache.evictAll(event.getKeys());
                        }
                        break;
                    case CLEAR:
                        cache.clear();
                        break;
                    case REFRESH:
                        // 对于REFRESH操作，我们简单地删除缓存项，让其重新加载
                        if (event.getKey() != null) {
                            cache.evict(event.getKey());
                        } else if (event.getKeys() != null) {
                            cache.evictAll(event.getKeys());
                        }
                        break;
                }
            } catch (Exception e) {
                log.error("Error applying sync event to cache '{}': {}", cacheId, event, e);
                throw e;
            }
        }
        
        @Override
        public String getListenerId() {
            return listenerId;
        }
        
        @Override
        public boolean shouldHandle(CacheSyncEvent event) {
            return cacheId.equals(event.getCacheId());
        }
    }
}