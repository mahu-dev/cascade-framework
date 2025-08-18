package io.github.cascade.cache.core.management;

import io.github.cascade.cache.api.CacheTier;
import io.github.cascade.cache.core.operations.TieredCacheOperations;
import io.github.cascade.cache.sync.CacheSyncEvent;
import io.github.cascade.cache.sync.CacheSyncListener;
import io.github.cascade.cache.sync.CacheSyncManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

/**
 * 缓存同步协调器
 * 专注于处理跨节点缓存同步逻辑
 *
 * @author cascade
 */
public class CacheSyncCoordinator {

    private static final Logger log = LoggerFactory.getLogger(CacheSyncCoordinator.class);

    private final String cacheName;
    private final CacheSyncManager syncManager;
    private final boolean enableSync;

    public CacheSyncCoordinator(String cacheName, CacheSyncManager syncManager, boolean enableSync) {
        this.cacheName = cacheName;
        this.syncManager = syncManager;
        this.enableSync = enableSync;
    }

    /**
     * 初始化同步协调器
     */
    public void initialize(CacheSyncListener listener) {
        if (!enableSync || syncManager == null) {
            return;
        }

        try {
            syncManager.registerListener(listener);
            if (!syncManager.isRunning()) {
                syncManager.start();
            }
            log.info("Cache sync coordinator initialized for cache: {}", cacheName);
        } catch (Exception e) {
            log.error("Failed to initialize sync coordinator for cache: {}", cacheName, e);
            throw new RuntimeException("Sync coordinator initialization failed", e);
        }
    }

    /**
     * 关闭同步协调器
     */
    public void shutdown() {
        if (!enableSync || syncManager == null) {
            return;
        }

        try {
            syncManager.unregisterListener("enhanced-cache-" + cacheName);
            log.info("Cache sync coordinator shutdown for cache: {}", cacheName);
        } catch (Exception e) {
            log.error("Failed to shutdown sync coordinator for cache: {}", cacheName, e);
        }
    }

    /**
     * 发布PUT事件
     */
    public <K, V> void publishPutEvent(K key, V value) {
        if (!enableSync || syncManager == null) {
            return;
        }

        try {
            CacheSyncEvent event = new CacheSyncEvent(
                    cacheName, key, value, getCurrentNodeId()
            );
            syncManager.publishEvent(event);
        } catch (Exception e) {
            log.warn("Failed to publish PUT sync event for key: {}", key, e);
        }
    }

    /**
     * 发布EVICT事件
     */
    public <K> void publishEvictEvent(K key) {
        if (!enableSync || syncManager == null) {
            return;
        }

        try {
            CacheSyncEvent event = new CacheSyncEvent(
                    cacheName, CacheSyncEvent.Operation.EVICT, key, getCurrentNodeId()
            );
            syncManager.publishEvent(event);
        } catch (Exception e) {
            log.warn("Failed to publish EVICT sync event for key: {}", key, e);
        }
    }

    /**
     * 发布批量EVICT事件
     */
    public <K> void publishEvictAllEvent(Set<K> keys) {
        if (!enableSync || syncManager == null || keys.isEmpty()) {
            return;
        }

        try {
            Set<Object> objectKeys = Set.copyOf(keys);
            CacheSyncEvent event = new CacheSyncEvent(
                    cacheName, CacheSyncEvent.Operation.EVICT, objectKeys, getCurrentNodeId()
            );
            syncManager.publishEvent(event);
        } catch (Exception e) {
            log.warn("Failed to publish EVICT_ALL sync event for keys: {}", keys, e);
        }
    }

    /**
     * 发布CLEAR事件
     */
    public void publishClearEvent() {
        if (!enableSync || syncManager == null) {
            return;
        }

        try {
            CacheSyncEvent event = new CacheSyncEvent(cacheName, getCurrentNodeId());
            syncManager.publishEvent(event);
        } catch (Exception e) {
            log.warn("Failed to publish CLEAR sync event", e);
        }
    }

    /**
     * 发布REFRESH事件
     */
    public <K> void publishRefreshEvent(K key) {
        if (!enableSync || syncManager == null) {
            return;
        }

        try {
            CacheSyncEvent event = new CacheSyncEvent(
                    cacheName, CacheSyncEvent.Operation.REFRESH, key, getCurrentNodeId()
            );
            syncManager.publishEvent(event);
        } catch (Exception e) {
            log.warn("Failed to publish REFRESH sync event for key: {}", key, e);
        }
    }

    /**
     * 处理同步事件
     */
    public <K, V> void handleSyncEvent(CacheSyncEvent event, TieredCacheOperations<K, V> cacheOperations) {
        try {
            switch (event.getOperation()) {
                case PUT -> handlePutSync(event, cacheOperations);
                case EVICT -> handleEvictSync(event, cacheOperations);
                case CLEAR -> handleClearSync(event, cacheOperations);
                case REFRESH -> handleRefreshSync(event, cacheOperations);
            }
        } catch (Exception e) {
            log.error("Error handling sync event: {}", event, e);
        }
    }

    @SuppressWarnings("unchecked")
    private <K, V> void handlePutSync(CacheSyncEvent event, TieredCacheOperations<K, V> cacheOperations) {
        K key = (K) event.getKey();
        V value = (V) event.getValue();
        if (key != null && value != null) {
            // 只同步到L1缓存，避免L2的重复写入
            cacheOperations.put(key, value, CacheTier.L1);
        }
    }

    @SuppressWarnings("unchecked")
    private <K, V> void handleEvictSync(CacheSyncEvent event, TieredCacheOperations<K, V> cacheOperations) {
        if (event.getKeys() != null && !event.getKeys().isEmpty()) {
            Set<K> keys = (Set<K>) event.getKeys();
            keys.forEach(key -> cacheOperations.evict(key, CacheTier.L1));
        } else if (event.getKey() != null) {
            K key = (K) event.getKey();
            cacheOperations.evict(key, CacheTier.L1);
        }
    }

    private <K, V> void handleClearSync(CacheSyncEvent event, TieredCacheOperations<K, V> cacheOperations) {
        cacheOperations.clear(CacheTier.L1);
    }

    @SuppressWarnings("unchecked")
    private <K, V> void handleRefreshSync(CacheSyncEvent event, TieredCacheOperations<K, V> cacheOperations) {
        if (event.getKeys() != null) {
            Set<K> keys = (Set<K>) event.getKeys();
            keys.forEach(key -> cacheOperations.evict(key, CacheTier.L1));
        } else if (event.getKey() != null) {
            K key = (K) event.getKey();
            cacheOperations.evict(key, CacheTier.L1);
        }
    }

    /**
     * 获取当前节点ID
     */
    private String getCurrentNodeId() {
        return syncManager != null ? syncManager.getCurrentNodeId() : "unknown";
    }
}