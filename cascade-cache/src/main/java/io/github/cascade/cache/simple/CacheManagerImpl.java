package io.github.cascade.cache.simple;

import io.github.cascade.cache.config.CascadeCacheProperties;
import io.github.cascade.cache.config.SyncProperties;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;
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

    // 同步工厂列表 (Spring自动注入)
    private final List<CacheSyncFactory> cacheSyncFactories;

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
                            CacheLoaderResolver cacheLoaderResolver,
                            List<CacheSyncFactory> cacheSyncFactories) {
        this.cacheRegistry = cacheRegistry;
        this.factoryRegistry = factoryRegistry;
        this.lifecycleManager = lifecycleManager;
        this.defaultConfig = defaultConfig != null ? defaultConfig : CascadeCacheProperties.defaults();
        this.cacheLoaderResolver = cacheLoaderResolver;
        this.cacheSyncFactories = cacheSyncFactories;
        this.nodeId = generateNodeId();

        log.info("缓存管理器初始化完成: nodeId={}, factoryCount={}, syncFactoryCount={}",
                nodeId, factoryRegistry.getFactoryCount(),
                cacheSyncFactories != null ? cacheSyncFactories.size() : 0);
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
        if (loader == null) {
            // 尝试从 cacheLoaderResolver 中获取真实类型的 Loader
            loader = cacheLoaderResolver.resolveCacheLoader(keyType, valueType);
        }

        if (cacheName == null || cacheName.trim().isEmpty()) {
            throw new IllegalArgumentException("缓存名称不能为空");
        }
        if (keyType == null || valueType == null) {
            throw new IllegalArgumentException("键类型和值类型不能为空");
        }
        final CascadeCacheProperties finalConfig = config != null ? config : defaultConfig;

        log.debug("获取或创建缓存: name={}, keyType={}, valueType={}",
                cacheName, keyType.getSimpleName(), valueType.getSimpleName());

        // 首先检查缓存是否已存在
        Cache<K, V> existingCache = cacheRegistry.get(cacheName);
        if (existingCache != null) {
            return existingCache;
        }

        Function<K, V> finalLoader = loader;
        
        // 创建缓存但不在computeIfAbsent中启动组件
        Cache<K, V> cache = cacheRegistry.computeIfAbsent(cacheName, () -> {
            // 构建缓存定义
            CacheDefinition<K, V> definition = CacheDefinition.of(cacheName, keyType, valueType)
                    .setConfig(finalConfig)
                    .setLoader(finalLoader)
                    .setLoaderResolver(cacheLoaderResolver);

            // 使用工厂创建缓存
            return factoryRegistry.createCache(definition);
        });

        // 在computeIfAbsent外部启动组件（避免递归更新）
        if (cache != null) {
            CacheDefinition<K, V> definition = CacheDefinition.of(cacheName, keyType, valueType)
                    .setConfig(finalConfig)
                    .setLoader(finalLoader)
                    .setLoaderResolver(cacheLoaderResolver);
            
            // 验证定义并推断缓存类型
            definition.validate();
            
            // 启动相关组件并可能返回装饰后的缓存
            Cache<K, V> decoratedCache = startCacheComponents(cacheName, cache, definition);
            
            // 如果缓存被装饰了，需要更新注册表
            if (decoratedCache != cache) {
                // 直接使用replace方法更新，这是安全的因为已经在computeIfAbsent外部
                cacheRegistry.replace(cacheName, decoratedCache);
                cache = decoratedCache;
                log.debug("缓存注册表已更新为装饰后的缓存: {}", cacheName);
            }
        }

        return cache;
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
     * 启动缓存相关组件，返回可能装饰后的缓存实例
     */
    private <K, V> Cache<K, V> startCacheComponents(String cacheName, Cache<K, V> cache, CacheDefinition<K, V> definition) {
        try {
            Cache<K, V> resultCache = cache;
            
            // 启动刷新器
            if (shouldCreateRefresher(definition)) {
                CacheRefresher<K, V> refresher = createCacheRefresher(cacheName, cache, definition);
                lifecycleManager.start(cacheName + ":refresher", refresher);
                log.debug("缓存刷新器已启动: cache={}", cacheName);
            }

            // 启动同步器并包装缓存
            if (shouldCreateSync(definition)) {
                CacheSync<K, V> sync = createCacheSync(cacheName, cache, definition);
                if (sync != null) {
                    // 用SyncAwareCache装饰原始缓存
                    Cache<K, V> syncAwareCache = SyncAwareCache.wrapIfNeeded(cache, sync, nodeId);

                    // 如果缓存被装饰了，更新返回的缓存实例
                    if (syncAwareCache != cache) {
                        resultCache = syncAwareCache;
                        log.debug("缓存已装饰为同步感知: cache={}", cacheName);
                    }

                    lifecycleManager.start(cacheName + ":sync", sync);
                    log.debug("缓存同步器已启动: cache={}", cacheName);
                }
            }

            return resultCache;

        } catch (Exception e) {
            log.error("启动缓存组件失败: cache={}, error={}", cacheName, e.getMessage(), e);
            return cache;
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
        return definition.getConfig().isRefreshEnabled() && definition.getLoader() != null;
    }

    /**
     * 判断是否应该创建同步器
     */
    private <K, V> boolean shouldCreateSync(CacheDefinition<K, V> definition) {
        return definition.getConfig().isSyncEnabled() && definition.getType().equals(CacheType.TIERED);
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
        try {
            SyncProperties syncConfig = definition.getConfig().getSyncConfig();

            // 使用工厂创建同步器
            CacheSyncFactory factory = findCacheSyncFactory(syncConfig.getType());
            if (factory != null) {
                CacheSync<K, V> sync = factory.createCacheSync(syncConfig);

                // 为缓存订阅同步事件
                sync.subscribe(cacheName, event -> handleSyncEvent(cache, event));

                log.info("缓存同步器创建成功: cache={}, type={}", cacheName, syncConfig.getType());
                return sync;
            }

            log.warn("未找到合适的同步器工厂: cache={}, type={}", cacheName, syncConfig.getType());
            return null;

        } catch (Exception e) {
            log.error("创建缓存同步器失败: cache={}, error={}", cacheName, e.getMessage(), e);
            return null;
        }
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
        if (loader == null && cacheLoaderResolver != null && cache instanceof TieredCache<?, ?>) {
            TieredCache<K, V> tieredCache = (TieredCache<K, V>) cache;
            Class<K> keyType = tieredCache.getKeyType();
            Class<V> valueType = tieredCache.getValueType();
            loader = cacheLoaderResolver.resolveCacheLoader(keyType, valueType);
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

    // ==================== 同步器辅助方法 ====================

    /**
     * 查找合适的缓存同步工厂
     */
    private CacheSyncFactory findCacheSyncFactory(SyncProperties.SyncType syncType) {
        if (cacheSyncFactories == null || cacheSyncFactories.isEmpty()) {
            log.warn("未找到任何缓存同步工厂，请检查: 1) RedissonClient是否配置 2) RedisCacheSyncFactory是否被正确注册");
            return null;
        }

        log.debug("查找同步工厂: type={}, 可用工厂: {}", syncType, 
                  cacheSyncFactories.stream().map(f -> f.getClass().getSimpleName()).toList());

        CacheSyncFactory factory = cacheSyncFactories.stream()
                .filter(f -> f.supports(syncType))
                .findFirst()
                .orElse(null);
        
        if (factory == null) {
            log.warn("未找到支持类型{}的同步工厂，可用类型: {}", syncType,
                    cacheSyncFactories.stream()
                            .flatMap(f -> java.util.Arrays.stream(SyncProperties.SyncType.values()).filter(f::supports))
                            .toList());
        } else {
            log.debug("找到同步工厂: type={}, factory={}", syncType, factory.getClass().getSimpleName());
        }
        
        return factory;
    }

    /**
     * 处理同步事件
     */
    private <K, V> void handleSyncEvent(Cache<K, V> cache, CacheSync.SyncEvent<K, V> event) {
        try {
            switch (event.getType()) {
                case PUT -> {
                    if (event.getKey() != null && event.getValue() != null) {
                        cache.put(event.getKey(), event.getValue());
                        log.trace("同步PUT事件已处理: cache={}, key={}", cache.getName(), event.getKey());
                    }
                }
                case EVICT -> {
                    if (event.getKey() != null) {
                        cache.evict(event.getKey());
                        log.trace("同步EVICT事件已处理: cache={}, key={}", cache.getName(), event.getKey());
                    }
                }
                case CLEAR -> {
                    cache.clear();
                    log.trace("同步CLEAR事件已处理: cache={}", cache.getName());
                }
            }
        } catch (Exception e) {
            log.error("处理同步事件失败: cache={}, event={}, error={}",
                    cache.getName(), event, e.getMessage());
        }
    }

    @Override
    public String toString() {
        return String.format("CacheManagerImpl{nodeId=%s, cacheCount=%d, closed=%s, componentCount=%d}",
                nodeId, getCacheCount(), closed, lifecycleManager.getRunningCount());
    }
}