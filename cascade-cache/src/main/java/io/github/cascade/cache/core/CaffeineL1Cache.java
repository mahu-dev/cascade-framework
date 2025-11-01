package io.github.cascade.cache.core;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import io.github.cascade.cache.configuration.CascadeCacheProperties;
import io.github.cascade.cache.api.Cache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * L1缓存实现 - 基于Caffeine
 * <p>
 * 设计原则：
 * 1. 高性能：使用Caffeine提供极致性能
 * 2. 配置灵活：支持多种过期策略
 * 3. 统计完善：提供详细的缓存统计
 * 4. 线程安全：所有操作都是线程安全的
 *
 * @param <K> 键类型
 * @param <V> 值类型
 * @author cascade
 */
public class CaffeineL1Cache<K, V> implements Cache<K, V> {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaffeineL1Cache.class);

    private final String cacheName;
    private final com.github.benmanes.caffeine.cache.Cache<K, V> caffeineCache;
    private final CascadeCacheProperties config;
    private volatile boolean closed;

    /**
     * 构造器
     */
    public CaffeineL1Cache(String cacheName, CascadeCacheProperties config) {
        this.cacheName = cacheName;
        this.config = config;
        this.caffeineCache = buildCaffeineCache();

        LOGGER.info("创建L1缓存: {} - 配置: 最大条目={}, 写后过期={}s, 访问后过期={}s",
                cacheName, config.getL1MaxSize(),
                config.getL1ExpireAfterWriteSeconds(),
                config.getL1ExpireAfterAccessSeconds());
    }

    /**
     * 构建Caffeine缓存
     */
    private com.github.benmanes.caffeine.cache.Cache<K, V> buildCaffeineCache() {
        Caffeine builder = Caffeine.newBuilder()
                .maximumSize(config.getL1MaxSize());

        // 配置写后过期
        if (config.getL1ExpireAfterWriteSeconds() > 0) {
            builder.expireAfterWrite(Duration.ofSeconds(config.getL1ExpireAfterWriteSeconds()));
        }

        // 配置访问后过期
        if (config.getL1ExpireAfterAccessSeconds() > 0) {
            builder.expireAfterAccess(Duration.ofSeconds(config.getL1ExpireAfterAccessSeconds()));
        }

        // 启用统计
        if (config.isStatsEnabled()) {
            builder.recordStats();
        }

        // 添加移除监听器用于调试
        builder.removalListener((Object key, Object value, RemovalCause cause) ->
                LOGGER.debug("L1缓存项被移除: cache={}, key={}, cause={}", cacheName, key, cause));

        return builder.build();
    }

    // ==================== Cache接口实现 ====================

    @Override
    public Optional<V> get(K key) {
        checkNotClosed();
        try {
            V value = caffeineCache.getIfPresent(key);
            return Optional.ofNullable(value);
        } catch (RuntimeException e) {
            LOGGER.error("L1缓存获取失败: cache={}, key={}, error={}", cacheName, key, e.getMessage(), e);
            return Optional.empty();
        }
    }

    @Override
    public V getOrLoad(K key, Function<K, V> loader) {
        checkNotClosed();
        try {
            return caffeineCache.get(key, loader);
        } catch (RuntimeException e) {
            LOGGER.error("L1缓存加载失败: cache={}, key={}, error={}", cacheName, key, e.getMessage(), e);
            // 尝试直接调用loader作为fallback
            try {
                return loader.apply(key);
            } catch (RuntimeException loaderException) {
                LOGGER.error("Loader fallback失败: cache={}, key={}, error={}",
                        cacheName, key, loaderException.getMessage());
                return null;
            }
        }
    }

    @Override
    public CompletableFuture<Optional<V>> getAsync(K key) {
        checkNotClosed();
        return CompletableFuture.supplyAsync(() -> get(key));
    }

    @Override
    public void put(K key, V value) {
        checkNotClosed();
        try {
            caffeineCache.put(key, value);
            if (LOGGER.isTraceEnabled()) {
                LOGGER.trace("L1缓存存储: cache={}, key={}", cacheName, key);
            }
        } catch (RuntimeException e) {
            LOGGER.error("L1缓存存储失败: cache={}, key={}, error={}", cacheName, key, e.getMessage(), e);
        }
    }

    @Override
    public void put(K key, V value, long ttlSeconds) {
        // Caffeine不支持单独设置TTL，使用全局配置
        put(key, value);
        LOGGER.debug("L1缓存存储(忽略TTL): cache={}, key={}, ttl={}s", cacheName, key, ttlSeconds);
    }

    @Override
    public CompletableFuture<Void> putAsync(K key, V value) {
        checkNotClosed();
        return CompletableFuture.runAsync(() -> put(key, value))
                .exceptionally((Throwable throwable) -> {
                    LOGGER.error("L1缓存异步存储失败: cache={}, key={}, error={}",
                            cacheName, key, throwable.getMessage());
                    return null;
                });
    }

    @Override
    public void evict(K key) {
        checkNotClosed();
        try {
            caffeineCache.invalidate(key);
            if (LOGGER.isTraceEnabled()) {
                LOGGER.trace("L1缓存删除: cache={}, key={}", cacheName, key);
            }
        } catch (RuntimeException e) {
            LOGGER.error("L1缓存删除失败: cache={}, key={}, error={}", cacheName, key, e.getMessage(), e);
        }
    }

    @Override
    public void clear() {
        checkNotClosed();
        try {
            long sizeBefore = caffeineCache.estimatedSize();
            caffeineCache.invalidateAll();
            LOGGER.info("L1缓存清空: cache={}, 删除条目数={}", cacheName, sizeBefore);
        } catch (RuntimeException e) {
            LOGGER.error("L1缓存清空失败: cache={}, error={}", cacheName, e.getMessage(), e);
        }
    }

    @Override
    public Map<K, V> getAll(Iterable<K> keys) {
        checkNotClosed();
        try {
            List<K> keyList = new ArrayList<>();
            keys.forEach(keyList::add);
            Map<K, V> result = caffeineCache.getAllPresent(keyList);
            if (LOGGER.isTraceEnabled()) {
                LOGGER.trace("L1缓存批量获取: cache={}, 请求数={}, 返回数={}",
                        cacheName, keyList.size(), result.size());
            }
            return result;
        } catch (RuntimeException e) {
            LOGGER.error("L1缓存批量获取失败: cache={}, error={}", cacheName, e.getMessage(), e);
            return Map.of(); // 返回空Map而不是null
        }
    }

    @Override
    public void putAll(Map<K, V> entries) {
        checkNotClosed();
        if (entries == null || entries.isEmpty()) {
            return;
        }
        try {
            caffeineCache.putAll(entries);
            if (LOGGER.isTraceEnabled()) {
                LOGGER.trace("L1缓存批量存储: cache={}, 条目数={}", cacheName, entries.size());
            }
        } catch (RuntimeException e) {
            LOGGER.error("L1缓存批量存储失败: cache={}, 条目数={}, error={}",
                    cacheName, entries.size(), e.getMessage(), e);
        }
    }

    @Override
    public boolean containsKey(K key) {
        checkNotClosed();
        try {
            return caffeineCache.getIfPresent(key) != null;
        } catch (RuntimeException e) {
            LOGGER.error("L1缓存检查键失败: cache={}, key={}, error={}", cacheName, key, e.getMessage(), e);
            return false;
        }
    }

    @Override
    public long size() {
        checkNotClosed();
        try {
            return caffeineCache.estimatedSize();
        } catch (RuntimeException e) {
            LOGGER.error("L1缓存获取大小失败: cache={}, error={}", cacheName, e.getMessage(), e);
            return 0;
        }
    }

    @Override
    public String getName() {
        return cacheName;
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            caffeineCache.invalidateAll();
            caffeineCache.cleanUp();
            LOGGER.info("L1缓存已关闭: {}", cacheName);
        }
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    // ==================== 扩展方法 ====================

    /**
     * 获取缓存统计信息
     */
    public com.github.benmanes.caffeine.cache.stats.CacheStats getStats() {
        return caffeineCache.stats();
    }

    /**
     * 手动清理过期条目
     */
    public void cleanUp() {
        caffeineCache.cleanUp();
    }

    /**
     * 获取底层Caffeine缓存（用于高级操作）
     */
    public com.github.benmanes.caffeine.cache.Cache<K, V> getUnderlyingCache() {
        return caffeineCache;
    }

    /**
     * 获取缓存内容快照（用于调试）
     */
    public Map<K, V> asMap() {
        return caffeineCache.asMap();
    }

    // ==================== 私有方法 ====================

    private void checkNotClosed() {
        if (closed) {
            throw new IllegalStateException("缓存已关闭: " + cacheName);
        }
    }

    @Override
    public String toString() {
        return String.format("CaffeineL1Cache{name=%s, size=%d, closed=%s}",
                cacheName, size(), closed);
    }
}