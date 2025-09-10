package io.github.cascade.cache.simple;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * 同步感知缓存装饰器
 * <p>
 * 设计原则：
 * 1. 装饰器模式：在不改变原始缓存的情况下添加同步功能
 * 2. 透明代理：所有操作都会触发相应的同步事件
 * 3. 异常隔离：同步失败不影响缓存操作本身
 * 4. 性能优先：异步发布同步事件
 *
 * @param <K> 键类型
 * @param <V> 值类型
 * @author cascade
 */
@RequiredArgsConstructor
public class SyncAwareCache<K, V> implements Cache<K, V> {

    private static final Logger LOGGER = LoggerFactory.getLogger(SyncAwareCache.class);

    /**
     * -- GETTER --
     * 获取底层缓存（用于高级操作）
     */
    @Getter
    private final Cache<K, V> delegate;
    /**
     * -- GETTER --
     * 获取同步器（用于高级操作）
     */
    @Getter
    private final CacheSync<K, V> cacheSync;
    private final String nodeId;

    // ==================== Cache接口实现（只读操作直接代理） ====================

    @Override
    public Optional<V> get(K key) {
        return delegate.get(key);
    }

    @Override
    public V getOrLoad(K key, Function<K, V> loader) {
        return delegate.getOrLoad(key, loader);
    }

    @Override
    public CompletableFuture<Optional<V>> getAsync(K key) {
        return delegate.getAsync(key);
    }

    @Override
    public Map<K, V> getAll(Iterable<K> keys) {
        return delegate.getAll(keys);
    }

    @Override
    public boolean containsKey(K key) {
        return delegate.containsKey(key);
    }

    @Override
    public long size() {
        return delegate.size();
    }

    @Override
    public String getName() {
        return delegate.getName();
    }

    @Override
    public boolean isClosed() {
        return delegate.isClosed();
    }

    // ==================== 写操作（带同步事件发布） ====================

    @Override
    public void put(K key, V value) {
        // 先执行缓存操作
        delegate.put(key, value);

        // 异步发布同步事件
        publishSyncEvent(key, value, CacheSync.EventType.PUT);
    }

    @Override
    public void put(K key, V value, long ttlSeconds) {
        // 先执行缓存操作
        delegate.put(key, value, ttlSeconds);

        // 异步发布同步事件
        publishSyncEvent(key, value, CacheSync.EventType.PUT);
    }

    @Override
    public CompletableFuture<Void> putAsync(K key, V value) {
        return delegate.putAsync(key, value)
                .thenRunAsync(() -> publishSyncEvent(key, value, CacheSync.EventType.PUT));
    }

    @Override
    public void putAll(Map<K, V> entries) {
        // 先执行缓存操作
        delegate.putAll(entries);

        // 批量发布同步事件
        entries.forEach((key, value) -> publishSyncEvent(key, value, CacheSync.EventType.PUT));
    }

    @Override
    public void evict(K key) {
        // 先执行缓存操作
        delegate.evict(key);

        // 异步发布同步事件
        publishSyncEvent(key, null, CacheSync.EventType.EVICT);
    }

    @Override
    public void clear() {
        // 先执行缓存操作
        delegate.clear();

        // 异步发布同步事件
        publishSyncEvent(null, null, CacheSync.EventType.CLEAR);
    }

    @Override
    public void close() {
        delegate.close();
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 异步发布同步事件
     */
    private void publishSyncEvent(K key, V value, CacheSync.EventType eventType) {
        if (cacheSync == null || !cacheSync.isRunning()) {
            return;
        }

        CompletableFuture.runAsync(() -> {
            try {
                CacheSync.SyncEvent<K, V> event = new CacheSync.SyncEvent<>(
                        getName(), eventType, key, value, nodeId
                );

                cacheSync.publishEvent(event)
                        .exceptionally(throwable -> {
                            LOGGER.warn("发布同步事件失败: cache={}, event={}, error={}",
                                    getName(), event, throwable.getMessage());
                            return null;
                        });

            } catch (Exception e) {
                LOGGER.warn("创建同步事件失败: cache={}, eventType={}, error={}",
                        getName(), eventType, e.getMessage());
            }
        });
    }

    // ==================== 工厂方法 ====================

    /**
     * 创建同步感知缓存装饰器
     *
     * @param cache     原始缓存
     * @param cacheSync 缓存同步器
     * @param nodeId    节点ID
     * @return 装饰后的缓存
     */
    public static <K, V> SyncAwareCache<K, V> wrap(Cache<K, V> cache, CacheSync<K, V> cacheSync, String nodeId) {
        if (cache == null) {
            throw new IllegalArgumentException("缓存不能为null");
        }
        if (cacheSync == null) {
            LOGGER.warn("同步器为null，将创建无同步功能的装饰器: cache={}", cache.getName());
        }

        return new SyncAwareCache<>(cache, cacheSync, nodeId);
    }

    /**
     * 安全包装：如果缓存已经是同步感知的，直接返回
     */
    public static <K, V> Cache<K, V> wrapIfNeeded(Cache<K, V> cache, CacheSync<K, V> cacheSync, String nodeId) {
        if (cache instanceof SyncAwareCache) {
            LOGGER.debug("缓存已经是同步感知的，跳过包装: cache={}", cache.getName());
            return cache;
        }

        return wrap(cache, cacheSync, nodeId);
    }

    @Override
    public String toString() {
        return String.format("SyncAwareCache{delegate=%s, sync=%s, nodeId=%s}",
                delegate, cacheSync != null, nodeId);
    }
}