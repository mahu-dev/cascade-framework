package io.github.cascade.cache.simple;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import io.github.cascade.cache.config.CascadeCacheProperties;
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

    private static final Logger log = LoggerFactory.getLogger(CaffeineL1Cache.class);

    private final String cacheName;
    private final com.github.benmanes.caffeine.cache.Cache<K, V> caffeineCache;
    private final CascadeCacheProperties config;
    private volatile boolean closed = false;

    /**
     * 构造器
     */
    public CaffeineL1Cache(String cacheName, CascadeCacheProperties config) {
        this.cacheName = cacheName;
        this.config = config;
        this.caffeineCache = buildCaffeineCache();

        log.info("创建L1缓存: {} - 配置: 最大条目={}, 写后过期={}s, 访问后过期={}s",
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
                log.debug("L1缓存项被移除: cache={}, key={}, cause={}", cacheName, key, cause));

        return builder.build();
    }

    // ==================== Cache接口实现 ====================

    @Override
    public Optional<V> get(K key) {
        checkNotClosed();
        V value = caffeineCache.getIfPresent(key);
        return Optional.ofNullable(value);
    }

    @Override
    public V getOrLoad(K key, Function<K, V> loader) {
        checkNotClosed();
        return caffeineCache.get(key, loader);
    }

    @Override
    public CompletableFuture<Optional<V>> getAsync(K key) {
        checkNotClosed();
        return CompletableFuture.supplyAsync(() -> get(key));
    }

    @Override
    public void put(K key, V value) {
        checkNotClosed();
        caffeineCache.put(key, value);
        log.debug("L1缓存存储: cache={}, key={}", cacheName, key);
    }

    @Override
    public void put(K key, V value, long ttlSeconds) {
        // Caffeine不支持单独设置TTL，使用全局配置
        put(key, value);
        log.debug("L1缓存存储(忽略TTL): cache={}, key={}, ttl={}s", cacheName, key, ttlSeconds);
    }

    @Override
    public CompletableFuture<Void> putAsync(K key, V value) {
        checkNotClosed();
        return CompletableFuture.runAsync(() -> put(key, value));
    }

    @Override
    public void evict(K key) {
        checkNotClosed();
        caffeineCache.invalidate(key);
        log.debug("L1缓存删除: cache={}, key={}", cacheName, key);
    }

    @Override
    public void clear() {
        checkNotClosed();
        long sizeBefore = caffeineCache.estimatedSize();
        caffeineCache.invalidateAll();
        log.info("L1缓存清空: cache={}, 删除条目数={}", cacheName, sizeBefore);
    }

    @Override
    public Map<K, V> getAll(Iterable<K> keys) {
        checkNotClosed();
        List<K> keyList = new ArrayList<>();
        keys.forEach(keyList::add);
        return caffeineCache.getAllPresent(keyList);
    }

    @Override
    public void putAll(Map<K, V> entries) {
        checkNotClosed();
        caffeineCache.putAll(entries);
        log.debug("L1缓存批量存储: cache={}, 条目数={}", cacheName, entries.size());
    }

    @Override
    public boolean containsKey(K key) {
        checkNotClosed();
        return caffeineCache.getIfPresent(key) != null;
    }

    @Override
    public long size() {
        checkNotClosed();
        return caffeineCache.estimatedSize();
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
            log.info("L1缓存已关闭: {}", cacheName);
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