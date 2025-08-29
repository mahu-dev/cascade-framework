package io.github.cascade.cache.simple;

import io.github.cascade.cache.config.CascadeCacheProperties;
import lombok.Getter;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

/**
 * 精简缓存管理器实现
 * <p>
 * 设计原则：
 * 1. 组件整合：集成所有缓存组件提供统一服务
 * 2. 配置驱动：根据配置自动选择缓存策略
 * 3. 线程安全：使用线程安全的数据结构
 * 4. 资源管理：正确管理组件生命周期
 * 核心功能：
 * - 多级缓存：自动组装L1+L2缓存
 * - 分布式同步：可选的Redis发布订阅同步
 * - 定时刷新：支持缓存自动刷新
 * - 生命周期管理：统一的启动和关闭流程
 *
 * @author cascade
 */
public class SimpleCacheManager implements CacheManager {

    private static final Logger log = LoggerFactory.getLogger(SimpleCacheManager.class);

    // 核心依赖
    private final RedissonClient redissonClient;
    private final CascadeCacheProperties defaultConfig;
    /**
     * -- GETTER --
     * 获取CacheLoaderResolver
     */
    @Getter
    private CacheLoaderResolver cacheLoaderResolver;

    // 缓存实例管理
    private final ConcurrentMap<String, Cache> caches = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CacheRefresher<?, ?>> refreshers = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CacheSync<?, ?>> syncers = new ConcurrentHashMap<>();

    // 状态管理
    private volatile boolean closed = false;
    private final String nodeId;

    /**
     * 构造器
     */
    public SimpleCacheManager(RedissonClient redissonClient) {
        this(redissonClient, CascadeCacheProperties.defaults());
    }

    /**
     * 构造器（带默认配置）
     */
    public SimpleCacheManager(RedissonClient redissonClient, CascadeCacheProperties defaultConfig) {
        this.redissonClient = redissonClient;
        this.defaultConfig = defaultConfig != null ? defaultConfig : CascadeCacheProperties.defaults();
        this.nodeId = generateNodeId();

        log.info("初始化缓存管理器: nodeId={}, 默认配置={}", nodeId, this.defaultConfig);
    }

    // ==================== 缓存创建与获取 ====================

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
    @SuppressWarnings("unchecked")
    public <K, V> Cache<K, V> getOrCreateCache(String cacheName, Class<K> keyType, Class<V> valueType,
                                               CascadeCacheProperties config, Function<K, V> loader) {
        checkNotClosed();
        // 获取已存在的缓存
        Cache<K, V> existingCache = caches.get(cacheName);
        if (existingCache != null) {
            log.debug("返回已存在的缓存: {}", cacheName);
            return existingCache;
        }

        // 使用双重检查锁定模式创建缓存
        return caches.computeIfAbsent(cacheName, key -> {
            try {
                log.debug("创建新缓存: name={}, config={}", cacheName, config);
                return createCache(cacheName, keyType, valueType, config, loader);
            } catch (Exception e) {
                log.error("创建缓存失败: name={}, error={}", cacheName, e.getMessage(), e);
                throw new RuntimeException("创建缓存失败: " + cacheName, e);
            }
        });
    }

    @Override
    @SuppressWarnings("unchecked")
    public <K, V> Cache<K, V> getCache(String cacheName) {
        checkNotClosed();
        Cache cache = caches.get(cacheName);
        return cache != null ? (Cache<K, V>) cache : null;
    }

    // ==================== 缓存管理 ====================

    @Override
    public <K, V> boolean registerCache(String cacheName, Cache<K, V> cache) {
        checkNotClosed();

        if (cacheName == null || cache == null) {
            throw new IllegalArgumentException("缓存名称和缓存实例不能为null");
        }

        Cache existing = caches.putIfAbsent(cacheName, cache);
        boolean registered = existing == null;

        if (registered) {
            log.info("注册缓存成功: {}", cacheName);
        } else {
            log.warn("缓存已存在，注册失败: {}", cacheName);
        }

        return registered;
    }

    @Override
    public boolean removeCache(String cacheName) {
        checkNotClosed();

        // 移除缓存实例
        Cache removedCache = caches.remove(cacheName);

        // 停止相关服务
        stopCacheServices(cacheName);

        boolean removed = removedCache != null;
        if (removed) {
            log.info("移除缓存成功: {}", cacheName);
        } else {
            log.debug("缓存不存在，移除失败: {}", cacheName);
        }

        return removed;
    }

    @Override
    public boolean containsCache(String cacheName) {
        checkNotClosed();
        return caches.containsKey(cacheName);
    }

    @Override
    public Collection<String> getCacheNames() {
        checkNotClosed();
        return caches.keySet();
    }

    @Override
    public void clearAll() {
        checkNotClosed();

        log.info("清空所有缓存...");
        caches.values().parallelStream().forEach(cache -> {
            try {
                cache.clear();
            } catch (Exception e) {
                log.error("清空缓存失败: cache={}, error={}", cache.getName(), e.getMessage());
            }
        });

        log.info("所有缓存已清空");
    }

    @Override
    public int getCacheCount() {
        checkNotClosed();
        return caches.size();
    }

    // ==================== 生命周期管理 ====================

    @Override
    public void close() {
        if (!closed) {
            closed = true;

            log.info("关闭缓存管理器...");

            // 停止所有刷新器
            refreshers.values().parallelStream().forEach(refresher -> {
                try {
                    refresher.stop();
                } catch (Exception e) {
                    log.error("停止刷新器失败: error={}", e.getMessage());
                }
            });
            refreshers.clear();

            // 停止所有同步器
            syncers.values().parallelStream().forEach(syncer -> {
                try {
                    syncer.stop();
                } catch (Exception e) {
                    log.error("停止同步器失败: error={}", e.getMessage());
                }
            });
            syncers.clear();

            // 清空所有缓存
            caches.clear();

            log.info("缓存管理器已关闭");
        }
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    // ==================== 私有方法 ====================

    /**
     * 创建缓存实例
     */
    private <K, V> Cache<K, V> createCache(String cacheName, Class<K> keyType, Class<V> valueType,
                                           CascadeCacheProperties config, Function<K, V> loader) {

        // 创建L1缓存（如果启用）
        Cache<K, V> l1Cache = null;
        if (config.isL1Enabled()) {
            l1Cache = createL1Cache(cacheName, config);
            log.debug("创建L1缓存: {}", cacheName);
        }

        // 创建L2缓存（如果启用）
        Cache<K, V> l2Cache = null;
        if (config.isL2Enabled() && redissonClient != null) {
            l2Cache = createL2Cache(cacheName, keyType, valueType, config);
            log.debug("创建L2缓存: {}", cacheName);
        }

        // 创建CacheLoader
        CacheLoader<K, V> cacheLoader = resolveCacheLoader(keyType, valueType, loader, config);

        // 创建TieredCache
        Cache<K, V> cache = new TieredCache<>(cacheName, config, l1Cache, l2Cache, cacheLoader,
                cacheLoaderResolver, keyType, valueType);

        // 创建分布式同步（如果启用）
        if (config.isSyncEnabled() && redissonClient != null) {
            CacheSync<K, V> sync = createCacheSync(cacheName, cache, keyType, valueType);
            syncers.put(cacheName, sync);
            sync.start();
            log.debug("启动分布式同步: {}", cacheName);
        }

        // 创建定时刷新（如果启用）
        if (config.isRefreshEnabled() && cacheLoader != null) {
            CacheRefresher<K, V> refresher = createCacheRefresher(cacheName, cache, cacheLoader, config);
            refreshers.put(cacheName, refresher);
            refresher.start();
            log.debug("启动定时刷新: {}, refresher = {}", cacheName, refresher);
        }

        return cache;
    }

    /**
     * 创建L1缓存（Caffeine）
     */
    @SuppressWarnings("unchecked")
    private <K, V> Cache<K, V> createL1Cache(String cacheName, CascadeCacheProperties config) {
        return new CaffeineL1Cache<>(cacheName, config);
    }

    /**
     * 创建L2缓存（Redisson）
     */
    private <K, V> Cache<K, V> createL2Cache(String cacheName, Class<K> keyType, Class<V> valueType,
                                             CascadeCacheProperties config) {
        return new RedissonL2Cache<>(cacheName, redissonClient, config);
    }

    /**
     * 创建缓存同步器
     */
    private <K, V> CacheSync<K, V> createCacheSync(String cacheName, Cache<K, V> cache,
                                                   Class<K> keyType, Class<V> valueType) {
        return new RedisCacheSync<>(redissonClient, "cascade:cache:sync:");
    }

    /**
     * 创建缓存刷新器
     */
    private <K, V> CacheRefresher<K, V> createCacheRefresher(String cacheName, Cache<K, V> cache,
                                                             CacheLoader<K, V> loader, CascadeCacheProperties config) {
        return new ScheduledCacheRefresher<>(cacheName, cache, loader, config);
    }

    /**
     * 停止缓存相关服务
     */
    private void stopCacheServices(String cacheName) {
        // 停止刷新器
        CacheRefresher<?, ?> refresher = refreshers.remove(cacheName);
        if (refresher != null) {
            refresher.stop();
            log.debug("停止缓存刷新器: {}", cacheName);
        }

        // 停止同步器
        CacheSync<?, ?> syncer = syncers.remove(cacheName);
        if (syncer != null) {
            syncer.stop();
            log.debug("停止缓存同步器: {}", cacheName);
        }
    }

    /**
     * 生成节点ID
     */
    private String generateNodeId() {
        return System.getProperty("cache.nodeId",
                System.getProperty("spring.application.name", "cache-node") + "-" +
                        System.currentTimeMillis() % 100000);
    }

    /**
     * 检查管理器状态
     */
    private void checkNotClosed() {
        if (closed) {
            throw new IllegalStateException("缓存管理器已关闭");
        }
    }

    // ==================== 扩展方法 ====================

    /**
     * 获取统计信息
     */
    public CacheManagerStats getStats() {
        checkNotClosed();

        return CacheManagerStats.builder()
                .nodeId(nodeId)
                .cacheCount(caches.size())
                .refresherCount(refreshers.size())
                .syncerCount(syncers.size())
                .closed(closed)
                .defaultConfig(defaultConfig)
                .cacheNames(getCacheNames())
                .build();
    }

    /**
     * 获取缓存刷新器
     */
    @SuppressWarnings("unchecked")
    public <K, V> CacheRefresher<K, V> getCacheRefresher(String cacheName) {
        checkNotClosed();
        return (CacheRefresher<K, V>) refreshers.get(cacheName);
    }

    /**
     * 获取或按需创建缓存刷新器
     * 支持注解级别覆盖全局配置
     */
    @SuppressWarnings("unchecked")
    public <K, V> CacheRefresher<K, V> getOrCreateCacheRefresher(String cacheName) {
        checkNotClosed();

        log.info("尝试获取或创建缓存刷新器: cache={}", cacheName);

        // 如果已存在，直接返回
        @SuppressWarnings("unchecked")
        CacheRefresher<K, V> existingRefresher = (CacheRefresher<K, V>) refreshers.get(cacheName);
        if (existingRefresher != null) {
            log.info("返回已存在的缓存刷新器: cache={}", cacheName);
            return existingRefresher;
        }

        // 按需创建刷新器
        Cache<K, V> cache = getCache(cacheName);
        if (cache == null) {
            log.warn("无法为不存在的缓存创建刷新器: cache={}", cacheName);
            return null;
        }

        log.info("开始按需创建缓存刷新器: cache={}", cacheName);

        // 尝试通过CacheLoaderResolver重新解析CacheLoader
        CacheLoader<K, V> cacheLoader = null;
        if (cacheLoaderResolver != null && cache instanceof TieredCache<K, V> tieredCache) {
            try {
                // 从TieredCache获取实际的类型信息
                Class<?> keyType = tieredCache.getKeyType();
                Class<?> valueType = tieredCache.getValueType();
                log.debug("从TieredCache获取类型信息: keyType={}, valueType={}", keyType.getSimpleName(), valueType.getSimpleName());

                // 使用正确的类型解析CacheLoader（需要做类型转换）
                cacheLoader = (CacheLoader<K, V>) cacheLoaderResolver.resolveCacheLoader(keyType, valueType);
                if (cacheLoader != null) {
                    log.info("通过CacheLoaderResolver重新解析到CacheLoader: loader={}, keyType={}, valueType={}",
                            cacheLoader.getClass().getSimpleName(), keyType.getSimpleName(), valueType.getSimpleName());
                } else {
                    log.debug("未找到匹配的CacheLoader: keyType={}, valueType={}", keyType.getSimpleName(), valueType.getSimpleName());
                }
            } catch (Exception e) {
                log.warn("重新解析CacheLoader失败: cache={}, error={}", cacheName, e.getMessage());
            }
        }

        if (cacheLoader == null) {
            log.warn("无法为缓存创建刷新器，缺少CacheLoader: cache={}", cacheName);
            return null;
        }

        // 创建并启动刷新器
        CacheRefresher<K, V> refresher = createCacheRefresher(cacheName, cache, cacheLoader, defaultConfig);
        refreshers.put(cacheName, refresher);
        refresher.start();

        log.info("按需创建缓存刷新器: cache={}, refresher={}", cacheName, refresher);
        return refresher;
    }

    /**
     * 获取缓存同步器
     */
    @SuppressWarnings("unchecked")
    public <K, V> CacheSync<K, V> getCacheSync(String cacheName) {
        checkNotClosed();
        return (CacheSync<K, V>) syncers.get(cacheName);
    }

    // ==================== CacheLoader自动发现相关方法 ====================

    /**
     * 设置CacheLoaderResolver
     */
    public <K, V> void setCacheLoaderResolver(CacheLoaderResolver<K, V> cacheLoaderResolver) {
        this.cacheLoaderResolver = cacheLoaderResolver;
        log.info("设置CacheLoaderResolver: {}", cacheLoaderResolver != null ? "已设置" : "已清空");
    }

    /**
     * 解析CacheLoader
     * 优先级：显式提供的loader > 自动发现的loader > null
     */
    private <K, V> CacheLoader<K, V> resolveCacheLoader(Class<K> keyType, Class<V> valueType,
                                                        Function<K, V> explicitLoader, CascadeCacheProperties config) {
        // 1. 如果显式提供了loader，直接使用
        if (explicitLoader != null) {
            log.debug("使用显式提供的CacheLoader: keyType={}, valueType={}",
                    keyType.getSimpleName(), valueType.getSimpleName());
            return explicitLoader::apply;
        }

        // 2. 尝试自动发现CacheLoader
        if (cacheLoaderResolver != null) {
            try {
                CacheLoader<K, V> autoDiscoveredLoader = cacheLoaderResolver.resolveCacheLoader(keyType, valueType);
                if (autoDiscoveredLoader != null) {
                    log.info("自动发现CacheLoader: keyType={}, valueType={}, loader={}",
                            keyType.getSimpleName(), valueType.getSimpleName(),
                            autoDiscoveredLoader.getClass().getSimpleName());
                    return autoDiscoveredLoader;
                }
            } catch (Exception e) {
                log.warn("自动发现CacheLoader失败: keyType={}, valueType={}, error={}",
                        keyType.getSimpleName(), valueType.getSimpleName(), e.getMessage());
            }
        }

        // 3. 未找到合适的CacheLoader
        log.debug("未找到CacheLoader: keyType={}, valueType={}", keyType.getSimpleName(), valueType.getSimpleName());
        return null;
    }

    @Override
    public String toString() {
        return String.format("SimpleCacheManager{nodeId=%s, caches=%d, closed=%s, resolverSet=%s}",
                nodeId, caches.size(), closed, cacheLoaderResolver != null);
    }
}