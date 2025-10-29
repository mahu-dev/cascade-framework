package io.github.cascade.cache.impl;

import io.github.cascade.cache.core.Cache;
import io.github.cascade.cache.core.CacheRefresher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * @author lionel lionelk@163.com
 * =============================
 * Date: 2025/10/29
 * Time: 15:23
 * =============================
 * <p>
 * 自动刷新缓存装饰器
 * <p>
 * 功能：
 * 1. 拦截 get() 和 getOrLoad() 操作
 * 2. 自动将访问的键添加到刷新器监控列表
 * 3. 自动启动刷新器
 * 4. 对用户完全透明，无需任何额外操作
 * <p>
 * 使用场景：
 * - 热点数据自动刷新
 * - 访问频率高的缓存自动保持新鲜
 * - 减少缓存穿透风险
 */
public class AutoRefreshCache<K, V> implements Cache<K, V> {

    private static final Logger log = LoggerFactory.getLogger(AutoRefreshCache.class);

    private final Cache<K, V> delegate;
    private final CacheRefresher<K, V> refresher;
    private final long defaultRefreshIntervalSeconds;
    private final boolean autoStart;

    // 记录已添加到刷新列表的键，避免重复添加
    private final Set<K> trackedKeys = ConcurrentHashMap.newKeySet();

    // 支持为不同的键设置不同的刷新间隔
    private final Map<K, Long> keyRefreshIntervals = new ConcurrentHashMap<>();

    /**
     * 构造器
     *
     * @param delegate                      被装饰的缓存
     * @param refresher                     刷新器
     * @param defaultRefreshIntervalSeconds 默认刷新间隔（秒）
     * @param autoStart                     是否自动启动刷新器
     */
    public AutoRefreshCache(Cache<K, V> delegate,
                            CacheRefresher<K, V> refresher,
                            long defaultRefreshIntervalSeconds,
                            boolean autoStart) {
        this.delegate = delegate;
        this.refresher = refresher;
        this.defaultRefreshIntervalSeconds = defaultRefreshIntervalSeconds;
        this.autoStart = autoStart;

        log.info("AutoRefreshCache 已创建，默认刷新间隔: {}秒，自动启动: {}",
                defaultRefreshIntervalSeconds, autoStart);
    }

    /**
     * 便捷构造器，使用默认配置
     */
    public AutoRefreshCache(Cache<K, V> delegate, CacheRefresher<K, V> refresher) {
        this(delegate, refresher, 300L, true);  // 默认5分钟刷新
    }

    @Override
    public Optional<V> get(K key) {
        // 自动添加键到刷新列表
        trackKeyForRefresh(key);

        // 委托给底层缓存
        return delegate.get(key);
    }

    @Override
    public V getOrLoad(K key, Function<K, V> loader) {
        // 自动添加键到刷新列表
        trackKeyForRefresh(key);

        // 委托给底层缓存
        return delegate.getOrLoad(key, loader);
    }

    /**
     * 自动追踪键并添加到刷新列表
     */
    private void trackKeyForRefresh(K key) {
        if (key == null) {
            return;
        }

        // 如果已经追踪过，直接返回
        if (trackedKeys.contains(key)) {
            return;
        }

        try {
            // 获取该键的刷新间隔（如果有自定义的话）
            long refreshInterval = keyRefreshIntervals.getOrDefault(key, defaultRefreshIntervalSeconds);

            // 添加到刷新器监控列表
            refresher.addKey(key, refreshInterval);
            trackedKeys.add(key);

            log.debug("键 [{}] 已自动添加到刷新监控列表，刷新间隔: {}秒", key, refreshInterval);

            // 自动启动刷新器（如果还未启动）
            if (autoStart && !refresher.isRunning()) {
                refresher.start();
                log.info("刷新器已自动启动");
            }
        } catch (Exception e) {
            log.warn("自动追踪键 [{}] 失败，继续执行", key, e);
        }
    }

    /**
     * 为特定的键设置自定义刷新间隔
     *
     * @param key                    键
     * @param refreshIntervalSeconds 刷新间隔（秒）
     */
    public void setKeyRefreshInterval(K key, long refreshIntervalSeconds) {
        keyRefreshIntervals.put(key, refreshIntervalSeconds);

        // 如果该键已经被追踪，更新刷新间隔
        if (trackedKeys.contains(key)) {
            refresher.addKey(key, refreshIntervalSeconds);
            log.info("键 [{}] 的刷新间隔已更新为: {}秒", key, refreshIntervalSeconds);
        }
    }

    /**
     * 停止追踪特定的键
     */
    public void stopTrackingKey(K key) {
        if (trackedKeys.remove(key)) {
            refresher.removeKey(key);
            keyRefreshIntervals.remove(key);
            log.info("键 [{}] 已从刷新监控列表移除", key);
        }
    }

    /**
     * 获取当前追踪的键数量
     */
    public int getTrackedKeysCount() {
        return trackedKeys.size();
    }

    /**
     * 获取刷新器状态
     */
    public boolean isRefresherRunning() {
        return refresher.isRunning();
    }

    // ==================== 委托其他方法到底层缓存 ====================

    @Override
    public void put(K key, V value) {
        delegate.put(key, value);
    }

    @Override
    public void put(K key, V value, long ttlSeconds) {
        delegate.put(key, value, ttlSeconds);
    }

    @Override
    public void putAll(Map<K, V> entries) {
        delegate.putAll(entries);
    }

    @Override
    public void evict(K key) {
        // 清除缓存时，也停止追踪该键
        stopTrackingKey(key);
        delegate.evict(key);
    }

    @Override
    public void clear() {
        // 清空缓存时，停止所有追踪
        trackedKeys.forEach(refresher::removeKey);
        trackedKeys.clear();
        keyRefreshIntervals.clear();
        delegate.clear();
    }

    @Override
    public CompletableFuture<Optional<V>> getAsync(K key) {
        trackKeyForRefresh(key);
        return delegate.getAsync(key);
    }

    @Override
    public CompletableFuture<Void> putAsync(K key, V value) {
        return delegate.putAsync(key, value);
    }

    @Override
    public Map<K, V> getAll(Iterable<K> keys) {
        // 批量获取时，也追踪这些键
        keys.forEach(this::trackKeyForRefresh);
        return delegate.getAll(keys);
    }

    @Override
    public boolean containsKey(K key) {
        return delegate.containsKey(key);
    }

    @Override
    public String getName() {
        return delegate.getName();
    }

    @Override
    public long size() {
        return delegate.size();
    }

    @Override
    public boolean isClosed() {
        return delegate.isClosed();
    }

    @Override
    public void close() {
        try {
            // 停止刷新器
            if (refresher.isRunning()) {
                refresher.stop();
                log.info("刷新器已停止");
            }
        } finally {
            delegate.close();
        }
    }
}