package io.github.cascade.cache.simple;

import io.github.cascade.cache.config.CascadeCacheProperties;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.function.Function;

/**
 * 新的缓存管理器实现
 * 基于职责分离的架构设计，各个组件职责明确
 *
 * <p>架构特点：</p>
 * <ul>
 *   <li>CacheRegistry: 负责缓存实例的注册和查找</li>
 *   <li>CacheFactoryRegistry: 负责根据类型选择合适的工厂创建缓存</li>
 *   <li>LifecycleManager: 负责管理所有组件的生命周期</li>
 *   <li>CacheManagerImpl: 负责协调各组件并提供统一的外部接口</li>
 * </ul>
 *
 * @author cascade
 */
@Component("cacheManagerImpl")
public class CacheManagerImpl implements CacheManager {

    private static final Logger log = LoggerFactory.getLogger(CacheManagerImpl.class);

    /**
     * -- GETTER --
     * 获取缓存注册中心（用于高级操作）
     */
    // 核心组件
    @Getter
    private final CacheRegistry cacheRegistry;
    /**
     * -- GETTER --
     * 获取工厂注册中心（用于高级操作）
     */
    @Getter
    private final CacheFactoryRegistry factoryRegistry;
    /**
     * -- GETTER --
     * 获取生命周期管理器（用于高级操作）
     */
    @Getter
    private final LifecycleManager lifecycleManager;
    private final CascadeCacheProperties defaultConfig;
    /**
     * -- GETTER --
     * 获取CacheLoader解析器
     */
    @Getter
    private final CacheLoaderResolver cacheLoaderResolver;

    // 状态管理
    private volatile boolean closed = false;
    private final String nodeId;

    /**
     * 构造函数，注入所有依赖组件
     */
    public CacheManagerImpl(CacheRegistry cacheRegistry,
                            CacheFactoryRegistry factoryRegistry,
                            LifecycleManager lifecycleManager,
                            CascadeCacheProperties defaultConfig,
                            CacheLoaderResolver cacheLoaderResolver) {
        this.cacheRegistry = cacheRegistry;
        this.factoryRegistry = factoryRegistry;
        this.lifecycleManager = lifecycleManager;
        this.defaultConfig = defaultConfig != null ? defaultConfig : CascadeCacheProperties.defaults();
        this.cacheLoaderResolver = cacheLoaderResolver;
        this.nodeId = generateNodeId();

        log.info("缓存管理器初始化完成: nodeId={}, factoryCount={}",
                nodeId, factoryRegistry.getFactoryCount());
        log.debug("支持的缓存类型: {}", factoryRegistry.getRegisteredFactories().keySet());
    }

    // ==================== CacheManager 核心接口实现 ====================

    @Override
    public <K, V> Cache<K, V> getOrCreateCache(String cacheName, Class<K> keyType, Class<V> valueType) {
        return getOrCreateCache(cacheName, keyType, valueType, defaultConfig, null);
    }

    @Override
    public <K, V> Cache<K, V> getOrCreateCache(String cacheName, Class<K> keyType, Class<V> valueType,
                                               CascadeCacheProperties config) {
        return getOrCreateCache(cacheName, keyType, valueType, config, null);
    }

    @Override
    public <K, V> Cache<K, V> getOrCreateCache(String cacheName, Class<K> keyType, Class<V> valueType,
                                               Function<K, V> loader) {
        return getOrCreateCache(cacheName, keyType, valueType, defaultConfig, loader);
    }

    @Override
    public <K, V> Cache<K, V> getOrCreateCache(String cacheName, Class<K> keyType, Class<V> valueType,
                                               CascadeCacheProperties config, Function<K, V> loader) {
        checkNotClosed();

        if (cacheName == null || cacheName.trim().isEmpty()) {
            throw new IllegalArgumentException("缓存名称不能为空");
        }
        if (keyType == null || valueType == null) {
            throw new IllegalArgumentException("键类型和值类型不能为空");
        }
        final CascadeCacheProperties finalConfig = config != null ? config : defaultConfig;

        log.debug("获取或创建缓存: name={}, keyType={}, valueType={}",
                cacheName, keyType.getSimpleName(), valueType.getSimpleName());

        return cacheRegistry.computeIfAbsent(cacheName, () -> {
            // 构建缓存定义
            CacheDefinition<K, V> definition = CacheDefinition.of(cacheName, keyType, valueType)
                    .setConfig(finalConfig)
                    .setLoader(loader)
                    .setLoaderResolver(cacheLoaderResolver);

            // 使用工厂创建缓存
            Cache<K, V> cache = factoryRegistry.createCache(definition);

            // 启动相关组件（刷新器、同步器等）
            startCacheComponents(cacheName, cache, definition);

            return cache;
        });
    }

    @Override
    public <K, V> Cache<K, V> getCache(String cacheName) {
        checkNotClosed();
        return cacheRegistry.get(cacheName);
    }

    // ==================== 缓存管理接口 ====================

    @Override
    public <K, V> boolean registerCache(String cacheName, Cache<K, V> cache) {
        checkNotClosed();

        boolean registered = cacheRegistry.register(cacheName, cache);
        if (registered) {
            // 将缓存也注册到生命周期管理器
            lifecycleManager.start(cacheName, cache);
        }
        return registered;
    }

    @Override
    public boolean removeCache(String cacheName) {
        checkNotClosed();

        // 先停止相关组件
        stopCacheComponents(cacheName);

        // 然后移除缓存
        Cache<?, ?> removed = cacheRegistry.remove(cacheName);
        return removed != null;
    }

    @Override
    public boolean containsCache(String cacheName) {
        checkNotClosed();
        return cacheRegistry.contains(cacheName);
    }

    @Override
    public Collection<String> getCacheNames() {
        checkNotClosed();
        return cacheRegistry.getCacheNames();
    }

    @Override
    public void clearAll() {
        checkNotClosed();

        log.info("开始清空所有缓存...");

        Collection<String> cacheNames = getCacheNames();
        for (String cacheName : cacheNames) {
            try {
                Cache<?, ?> cache = cacheRegistry.get(cacheName);
                if (cache != null) {
                    cache.clear();
                    log.debug("缓存已清空: {}", cacheName);
                }
            } catch (Exception e) {
                log.error("清空缓存失败: name={}, error={}", cacheName, e.getMessage());
            }
        }

        log.info("所有缓存清空完成，缓存数: {}", cacheNames.size());
    }

    @Override
    public int getCacheCount() {
        checkNotClosed();
        return cacheRegistry.size();
    }

    // ==================== 生命周期管理 ====================

    @Override
    public void close() {
        if (closed) {
            log.debug("缓存管理器已关闭，无需重复关闭");
            return;
        }

        log.info("开始关闭缓存管理器: nodeId={}", nodeId);
        closed = true;

        try {
            // 1. 停止所有组件（刷新器、同步器等）
            lifecycleManager.close();

            // 2. 清空缓存注册中心（会自动关闭所有缓存）
            cacheRegistry.clear();

            log.info("缓存管理器关闭完成: nodeId={}", nodeId);
        } catch (Exception e) {
            log.error("关闭缓存管理器异常: nodeId={}, error={}", nodeId, e.getMessage(), e);
        }
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    // ==================== 扩展功能 ====================

    /**
     * 获取缓存管理器统计信息
     */
    public CacheManagerStats getStats() {
        checkNotClosed();

        return CacheManagerStats.builder()
                .nodeId(nodeId)
                .cacheCount(getCacheCount())
                .refresherCount(0) // 暂时固定为0
                .syncerCount(0)    // 暂时固定为0
                .closed(closed)
                .cacheNames(getCacheNames())
                .build();
    }

    // ==================== 私有方法 ====================

    /**
     * 启动缓存相关组件
     */
    private <K, V> void startCacheComponents(String cacheName, Cache<K, V> cache, CacheDefinition<K, V> definition) {
        try {
            // 启动刷新器
            if (shouldCreateRefresher(definition)) {
                CacheRefresher<K, V> refresher = createCacheRefresher(cacheName, cache, definition);
                if (refresher != null) {
                    lifecycleManager.start(cacheName + ":refresher", refresher);
                    log.debug("缓存刷新器已启动: cache={}", cacheName);
                }
            }

            // 启动同步器
            if (shouldCreateSync(definition)) {
                CacheSync<K, V> sync = createCacheSync(cacheName, cache, definition);
                if (sync != null) {
                    lifecycleManager.start(cacheName + ":sync", sync);
                    log.debug("缓存同步器已启动: cache={}", cacheName);
                }
            }

        } catch (Exception e) {
            log.error("启动缓存组件失败: cache={}, error={}", cacheName, e.getMessage(), e);
        }
    }

    /**
     * 停止缓存相关组件
     */
    private void stopCacheComponents(String cacheName) {
        try {
            // 停止刷新器
            lifecycleManager.stop(cacheName + ":refresher");

            // 停止同步器
            lifecycleManager.stop(cacheName + ":sync");

            log.debug("缓存组件已停止: cache={}", cacheName);
        } catch (Exception e) {
            log.error("停止缓存组件失败: cache={}, error={}", cacheName, e.getMessage());
        }
    }

    /**
     * 判断是否应该创建刷新器
     */
    private <K, V> boolean shouldCreateRefresher(CacheDefinition<K, V> definition) {
        return definition.getConfig().isRefreshEnabled() &&
                definition.getLoader() != null;
    }

    /**
     * 判断是否应该创建同步器
     */
    private <K, V> boolean shouldCreateSync(CacheDefinition<K, V> definition) {
        return definition.getConfig().isSyncEnabled() &&
                definition.getType() == CacheType.TIERED;
    }

    /**
     * 创建缓存刷新器
     */
    private <K, V> CacheRefresher<K, V> createCacheRefresher(String cacheName, Cache<K, V> cache,
                                                             CacheDefinition<K, V> definition) {
        return new ScheduledCacheRefresher<>(cacheName, cache, (CacheLoader<K, V>) definition.getLoader(), definition.getConfig());
    }

    /**
     * 创建缓存同步器
     */
    private <K, V> CacheSync<K, V> createCacheSync(String cacheName, Cache<K, V> cache,
                                                   CacheDefinition<K, V> definition) {
        // 同步器功能暂时跳过，需要RedissonClient依赖注入支持
        log.debug("跳过缓存同步器创建: cache={}", cacheName);
        return null;
    }

    /**
     * 生成节点ID
     */
    private String generateNodeId() {
        String appName = System.getProperty("spring.application.name", "cascade-cache");
        long timestamp = System.currentTimeMillis() % 100000;
        return String.format("%s-%d", appName, timestamp);
    }

    /**
     * 检查管理器是否未关闭
     */
    private void checkNotClosed() {
        if (closed) {
            throw new IllegalStateException("缓存管理器已关闭: " + nodeId);
        }
    }

    /**
     * 获取或创建缓存刷新器
     */
    public <K, V> CacheRefresher<K, V> getOrCreateCacheRefresher(String cacheName) {
        checkNotClosed();

        if (cacheName == null || cacheName.trim().isEmpty()) {
            throw new IllegalArgumentException("缓存名称不能为空");
        }

        // 查找现有的刷新器
        String refresherKey = cacheName + ":refresher";
        Object component = lifecycleManager.getComponent(refresherKey);

        if (component instanceof CacheRefresher) {
            return (CacheRefresher<K, V>) component;
        }

        // 获取缓存实例
        Cache<K, V> cache = getCache(cacheName);
        if (cache == null) {
            log.warn("缓存不存在，无法创建刷新器: cacheName={}", cacheName);
            return null;
        }

        // 获取缓存对应的Loader
        CacheLoader<K, V> loader = null;

        // 尝试从TieredCache获取loader
        if (cache instanceof TieredCache<?, ?>) {
            TieredCache<K, V> tieredCache = (TieredCache<K, V>) cache;
            loader = tieredCache.getLoader().orElse(null);
        }

        // 如果TieredCache没有loader，尝试通过CacheLoaderResolver获取
        if (loader == null && cacheLoaderResolver != null) {
            // 需要获取缓存的泛型类型
            if (cache instanceof TieredCache<?, ?>) {
                TieredCache<K, V> tieredCache = (TieredCache<K, V>) cache;
                Class<K> keyType = tieredCache.getKeyType();
                Class<V> valueType = tieredCache.getValueType();
                loader = cacheLoaderResolver.resolveCacheLoader(keyType, valueType);
            }
        }

        // 如果仍然没有找到loader，则无法创建刷新器
        if (loader == null) {
            log.warn("无法创建缓存刷新器，没有找到对应的CacheLoader: cacheName={}", cacheName);
            return null;
        }

        // 创建新的刷新器
        try {
            CacheRefresher<K, V> refresher = new ScheduledCacheRefresher<>(
                    cacheName,
                    cache,
                    loader, // 使用找到的loader
                    defaultConfig
            );

            // 启动并注册到生命周期管理器
            lifecycleManager.start(refresherKey, refresher);

            log.info("缓存刷新器创建成功: cacheName={}", cacheName);
            return refresher;
        } catch (Exception e) {
            log.error("创建缓存刷新器失败: cacheName={}, error={}", cacheName, e.getMessage(), e);
            return null;
        }
    }

    @Override
    public String toString() {
        return String.format("CacheManagerImpl{nodeId=%s, cacheCount=%d, closed=%s, componentCount=%d}",
                nodeId, getCacheCount(), closed, lifecycleManager.getRunningCount());
    }
}