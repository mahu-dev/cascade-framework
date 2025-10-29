package io.github.cascade.cache.impl;

import io.github.cascade.cache.core.Cache;
import io.github.cascade.cache.core.CacheRefresher;
import io.github.cascade.cache.core.CacheSync;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * @author lionel lionelk@163.com
 * =============================
 * Date: 2025/10/29
 * Time: 22:30
 * =============================
 *
 * 分布式自动刷新缓存装饰器
 * <p>
 * 融合能力：
 * 1. ✅ 自动刷新：拦截 get() 操作，自动追踪热点 key 并定时刷新
 * 2. ✅ 分布式同步：监听 Redis Pub/Sub，同步其他节点的缓存变更
 * 3. ✅ 刷新时同步：本地刷新数据后，自动通知其他节点更新
 * 4. ✅ 多级缓存：支持 L1 + L2 多级缓存架构
 * <p>
 * 设计原则：
 * - 装饰器模式：不改变原始缓存的情况下增强功能
 * - 透明代理：所有操作对用户透明
 * - 异常隔离：同步和刷新失败不影响缓存操作本身
 * - 性能优先：异步发布同步事件
 * <p>
 * 使用场景：
 * - 分布式应用中的热点数据自动刷新
 * - 多节点环境下的缓存一致性保证
 * - 高并发场景下减少缓存穿透
 * <p>
 * 与其他装饰器的区别：
 * - SyncAwareCache：只有同步，不会自动刷新
 * - AutoRefreshCache：只有刷新，不会同步其他节点
 * - DistributedAutoRefreshCache：同时支持刷新和同步
 */
public class DistributedAutoRefreshCache<K, V> implements Cache<K, V> {

    private static final Logger LOGGER = LoggerFactory.getLogger(DistributedAutoRefreshCache.class);

    // ==================== 核心组件 ====================

    /**
     * 被装饰的缓存（通常是 FunctionalCache）
     */
    @Getter
    private final Cache<K, V> delegate;

    /**
     * 缓存刷新器
     */
    private final CacheRefresher<K, V> refresher;

    /**
     * 分布式同步器
     */
    @Getter
    private final CacheSync<K, V> cacheSync;

    /**
     * 当前节点ID
     */
    private final String nodeId;

    /**
     * 默认刷新间隔（秒）
     */
    private final long defaultRefreshIntervalSeconds;

    /**
     * 是否自动启动刷新器
     */
    private final boolean autoStart;

    // ==================== 状态管理 ====================

    /**
     * 已追踪的热点 key（避免重复添加到刷新器）
     */
    private final Set<K> trackedKeys = ConcurrentHashMap.newKeySet();

    /**
     * 每个 key 的自定义刷新间隔
     */
    private final Map<K, Long> keyRefreshIntervals = new ConcurrentHashMap<>();

    /**
     * 刷新器是否已启动
     */
    private final AtomicBoolean refresherStarted = new AtomicBoolean(false);

    /**
     * 是否已订阅同步事件
     */
    private final AtomicBoolean syncSubscribed = new AtomicBoolean(false);

    // ==================== 构造函数 ====================

    /**
     * 完整构造函数
     *
     * @param delegate                      被装饰的缓存
     * @param refresher                     刷新器
     * @param cacheSync                     同步器
     * @param nodeId                        节点ID
     * @param defaultRefreshIntervalSeconds 默认刷新间隔（秒）
     * @param autoStart                     是否自动启动刷新器
     */
    public DistributedAutoRefreshCache(Cache<K, V> delegate,
                                       CacheRefresher<K, V> refresher,
                                       CacheSync<K, V> cacheSync,
                                       String nodeId,
                                       long defaultRefreshIntervalSeconds,
                                       boolean autoStart) {
        this.delegate = delegate;
        this.refresher = refresher;
        this.cacheSync = cacheSync;
        this.nodeId = nodeId != null ? nodeId : NodeIdManager.getInstance().getNodeId();
        this.defaultRefreshIntervalSeconds = defaultRefreshIntervalSeconds;
        this.autoStart = autoStart;

        // 初始化同步监听器
        initSyncListeners();

        LOGGER.info("DistributedAutoRefreshCache 已创建: cache={}, nodeId={}, defaultRefreshInterval={}秒, autoStart={}",
                delegate.getName(), this.nodeId, defaultRefreshIntervalSeconds, autoStart);
    }

    /**
     * 便捷构造函数（使用默认配置）
     */
    public DistributedAutoRefreshCache(Cache<K, V> delegate,
                                       CacheRefresher<K, V> refresher,
                                       CacheSync<K, V> cacheSync,
                                       String nodeId) {
        this(delegate, refresher, cacheSync, nodeId, 300L, true);
    }

    /**
     * 便捷构造函数（使用NodeIdManager获取nodeId）
     */
    public DistributedAutoRefreshCache(Cache<K, V> delegate,
                                       CacheRefresher<K, V> refresher,
                                       CacheSync<K, V> cacheSync,
                                       long defaultRefreshIntervalSeconds,
                                       boolean autoStart) {
        this(delegate, refresher, cacheSync, null, defaultRefreshIntervalSeconds, autoStart);
    }

    /**
     * 便捷构造函数（使用NodeIdManager获取nodeId和默认配置）
     */
    public DistributedAutoRefreshCache(Cache<K, V> delegate,
                                       CacheRefresher<K, V> refresher,
                                       CacheSync<K, V> cacheSync) {
        this(delegate, refresher, cacheSync, null, 300L, true);
    }

    // ==================== 初始化 ====================

    /**
     * 初始化同步监听器
     */
    private void initSyncListeners() {
        if (syncSubscribed.compareAndSet(false, true)) {
            // 订阅同步事件
            cacheSync.subscribe(delegate.getName(), event -> {
                // 忽略自己发布的事件
                if (nodeId.equals(event.getNodeId())) {
                    return;
                }

                LOGGER.debug("收到同步事件: type={}, key={}, sourceNode={}",
                        event.getType(), event.getKey(), event.getNodeId());

                switch (event.getType()) {
                    case PUT -> {
                        delegate.put(event.getKey(), event.getValue());
                        LOGGER.debug("已同步 PUT 事件: key={}", event.getKey());
                    }
                    case EVICT -> {
                        delegate.evict(event.getKey());
                        removeKeyFromRefresher(event.getKey());
                        LOGGER.debug("已同步 EVICT 事件: key={}", event.getKey());
                    }
                    case CLEAR -> {
                        delegate.clear();
                        trackedKeys.clear();
                        keyRefreshIntervals.clear();
                        LOGGER.debug("已同步 CLEAR 事件");
                    }
                }
            });

            // 启动同步服务
            if (!cacheSync.isRunning()) {
                cacheSync.start();
            }

            LOGGER.info("同步监听器已初始化: cache={}, nodeId={}", delegate.getName(), nodeId);
        }
    }

    // ==================== Cache 接口实现（读操作 + 自动刷新追踪） ====================

    @Override
    public Optional<V> get(K key) {
        // 自动追踪热点 key
        trackKeyForRefresh(key);

        // 委托给底层缓存
        return delegate.get(key);
    }

    @Override
    public V getOrLoad(K key, Function<K, V> loader) {
        // 自动追踪热点 key
        trackKeyForRefresh(key);

        // 委托给底层缓存
        return delegate.getOrLoad(key, loader);
    }

    @Override
    public CompletableFuture<Optional<V>> getAsync(K key) {
        // 自动追踪热点 key
        trackKeyForRefresh(key);

        // 委托给底层缓存
        return delegate.getAsync(key);
    }

    @Override
    public Map<K, V> getAll(Iterable<K> keys) {
        // 批量追踪
        keys.forEach(this::trackKeyForRefresh);

        // 委托给底层缓存
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

        // 自动追踪（写入的数据也可能是热点）
        trackKeyForRefresh(key);
    }

    @Override
    public void put(K key, V value, long ttlSeconds) {
        // 先执行缓存操作
        delegate.put(key, value, ttlSeconds);

        // 异步发布同步事件
        publishSyncEvent(key, value, CacheSync.EventType.PUT);

        // 自动追踪
        trackKeyForRefresh(key);
    }

    @Override
    public CompletableFuture<Void> putAsync(K key, V value) {
        return delegate.putAsync(key, value).thenRun(() -> {
            // 异步发布同步事件
            publishSyncEvent(key, value, CacheSync.EventType.PUT);

            // 自动追踪
            trackKeyForRefresh(key);
        });
    }

    @Override
    public void putAll(Map<K, V> entries) {
        // 先执行缓存操作
        delegate.putAll(entries);

        // 批量发布同步事件
        entries.forEach((key, value) -> {
            publishSyncEvent(key, value, CacheSync.EventType.PUT);
            trackKeyForRefresh(key);
        });
    }

    @Override
    public void evict(K key) {
        // 先执行缓存操作
        delegate.evict(key);

        // 异步发布同步事件
        publishSyncEvent(key, null, CacheSync.EventType.EVICT);

        // 从刷新列表中移除
        removeKeyFromRefresher(key);
    }

    @Override
    public void clear() {
        // 先执行缓存操作
        delegate.clear();

        // 异步发布同步事件
        publishSyncEvent(null, null, CacheSync.EventType.CLEAR);

        // 清空刷新列表
        trackedKeys.clear();
        keyRefreshIntervals.clear();
    }

    @Override
    public void close() {
        // 停止刷新器
        if (refresher != null) {
            try {
                refresher.stop();
                LOGGER.info("刷新器已停止: cache={}", delegate.getName());
            } catch (Exception e) {
                LOGGER.error("停止刷新器失败: cache={}, error={}", delegate.getName(), e.getMessage());
            }
        }

        // 关闭底层缓存
        delegate.close();
    }

    // ==================== 自动刷新追踪 ====================

    /**
     * 自动追踪 key 并添加到刷新列表
     */
    private void trackKeyForRefresh(K key) {
        if (key == null) {
            return;
        }

        // 如果是首次访问该 key
        if (trackedKeys.add(key)) {
            // 获取刷新间隔
            long refreshInterval = keyRefreshIntervals.getOrDefault(key, defaultRefreshIntervalSeconds);

            // 添加到刷新器
            try {
                refresher.addKey(key, refreshInterval);
                LOGGER.debug("热点 key 已添加到刷新列表: key={}, refreshInterval={}秒", key, refreshInterval);

                // 自动启动刷新器
                startRefresherIfNeeded();
            } catch (Exception e) {
                LOGGER.error("添加 key 到刷新器失败: key={}, error={}", key, e.getMessage());
            }
        }
    }

    /**
     * 从刷新器中移除 key
     */
    private void removeKeyFromRefresher(K key) {
        if (key == null) {
            return;
        }

        trackedKeys.remove(key);
        keyRefreshIntervals.remove(key);

        try {
            refresher.removeKey(key);
            LOGGER.debug("key 已从刷新列表移除: key={}", key);
        } catch (Exception e) {
            LOGGER.error("从刷新器移除 key 失败: key={}, error={}", key, e.getMessage());
        }
    }

    /**
     * 启动刷新器（如果需要）
     */
    private void startRefresherIfNeeded() {
        if (autoStart && refresherStarted.compareAndSet(false, true)) {
            try {
                refresher.start();
                LOGGER.info("刷新器已自动启动: cache={}", delegate.getName());
            } catch (Exception e) {
                LOGGER.error("启动刷新器失败: cache={}, error={}", delegate.getName(), e.getMessage());
                refresherStarted.set(false);
            }
        }
    }

    // ==================== 分布式同步 ====================

    /**
     * 发布同步事件
     */
    private void publishSyncEvent(K key, V value, CacheSync.EventType eventType) {
        CompletableFuture.runAsync(() -> {
            try {
                String cacheName = delegate.getName();
                switch (eventType) {
                    case PUT -> cacheSync.publishPut(cacheName, key, value, nodeId);
                    case EVICT -> cacheSync.publishEvict(cacheName, key, nodeId);
                    case CLEAR -> cacheSync.publishClear(cacheName, nodeId);
                }
                LOGGER.debug("同步事件已发布: eventType={}, key={}, nodeId={}", eventType, key, nodeId);
            } catch (Exception e) {
                LOGGER.error("发布同步事件失败: eventType={}, key={}, error={}",
                        eventType, key, e.getMessage());
            }
        });
    }

    // ==================== 高级功能 ====================

    /**
     * 设置指定 key 的刷新间隔
     *
     * @param key              键
     * @param intervalSeconds  刷新间隔（秒）
     */
    public void setRefreshInterval(K key, long intervalSeconds) {
        keyRefreshIntervals.put(key, intervalSeconds);

        // 如果已经在追踪，更新刷新器中的间隔
        if (trackedKeys.contains(key)) {
            try {
                refresher.addKey(key, intervalSeconds);
                LOGGER.info("更新 key 的刷新间隔: key={}, newInterval={}秒", key, intervalSeconds);
            } catch (Exception e) {
                LOGGER.error("更新刷新间隔失败: key={}, error={}", key, e.getMessage());
            }
        }
    }

    /**
     * 手动触发指定 key 的刷新
     *
     * @param key 键
     */
    public void manualRefresh(K key) {
        try {
            refresher.refresh(key);
            LOGGER.info("手动刷新成功: key={}", key);

            // 刷新后发布同步事件
            delegate.get(key).ifPresent(value ->
                    publishSyncEvent(key, value, CacheSync.EventType.PUT));
        } catch (Exception e) {
            LOGGER.error("手动刷新失败: key={}, error={}", key, e.getMessage());
        }
    }

    /**
     * 获取已追踪的热点 key 数量
     */
    public int getTrackedKeyCount() {
        return trackedKeys.size();
    }

    /**
     * 获取所有已追踪的热点 key
     */
    public Set<K> getTrackedKeys() {
        return Set.copyOf(trackedKeys);
    }

    /**
     * 静态工厂方法：包装现有缓存
     */
    public static <K, V> DistributedAutoRefreshCache<K, V> wrap(
            Cache<K, V> cache,
            CacheRefresher<K, V> refresher,
            CacheSync<K, V> cacheSync,
            String nodeId,
            long defaultRefreshIntervalSeconds) {
        return new DistributedAutoRefreshCache<>(
                cache, refresher, cacheSync, nodeId, defaultRefreshIntervalSeconds, true);
    }

    /**
     * 静态工厂方法：包装现有缓存（使用默认配置）
     */
    public static <K, V> DistributedAutoRefreshCache<K, V> wrap(
            Cache<K, V> cache,
            CacheRefresher<K, V> refresher,
            CacheSync<K, V> cacheSync,
            String nodeId) {
        return new DistributedAutoRefreshCache<>(cache, refresher, cacheSync, nodeId);
    }

    @Override
    public String toString() {
        return String.format("DistributedAutoRefreshCache{name=%s, nodeId=%s, trackedKeys=%d, refreshInterval=%ds}",
                delegate.getName(), nodeId, trackedKeys.size(), defaultRefreshIntervalSeconds);
    }
}