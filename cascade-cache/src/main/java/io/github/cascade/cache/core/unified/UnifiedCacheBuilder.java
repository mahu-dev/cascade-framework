package io.github.cascade.cache.core.unified;

import io.github.cascade.cache.annotation.AutoConfigureLoader;
import io.github.cascade.cache.api.Cache;
import io.github.cascade.cache.api.CacheLoader;
import io.github.cascade.cache.config.CascadeCacheConfiguration;
import io.github.cascade.cache.core.CacheLoaderResolver;
import io.github.cascade.cache.core.unified.CaffeineEngine.CaffeineConfig;
import io.github.cascade.cache.core.unified.RedisEngine.RedisConfig;
import io.github.cascade.cache.protection.*;
import io.github.cascade.cache.sync.RedissonCacheSyncManager;
import io.github.cascade.cache.sync.UnifiedCacheSynchronizer;
import io.github.cascade.cache.event.UnifiedEventProcessor;
import io.github.cascade.cache.refresh.CacheRefreshScheduler;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.context.ApplicationContext;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

/**
 * 统一缓存构建器
 * 使用新的简化架构，替代原来复杂的继承体系
 *
 * @param <K> 键类型
 * @param <V> 值类型
 * @author cascade
 */
@Slf4j
public class UnifiedCacheBuilder<K, V> {

    private final String cacheName;
    private Class<K> keyType;
    private Class<V> valueType;

    // 基础配置
    private Executor executor = ForkJoinPool.commonPool();
    private CacheLoader<K, V> cacheLoader;

    // L1配置
    private final CaffeineConfig l1Config = new CaffeineConfig();
    private boolean enableL1 = true;

    // L2配置
    private final RedisConfig l2Config = new RedisConfig();
    private boolean enableL2 = false;
    private RedissonClient redissonClient;

    // 防护配置
    private boolean enableProtection = false;
    private CascadeBloomFilter bloomFilter;
    private RandomTtlProtection randomTtl;
    private RedissonLockProtection distributedLock;

    // 同步配置
    private boolean enableSync = false;
    private String syncTopic = "cascade:cache:sync";
    private Duration syncTimeout = Duration.ofSeconds(5);
    private boolean asyncSync = true;

    // 刷新配置
    private boolean enableAutoRefresh = false;
    private Duration refreshInterval = Duration.ofMinutes(10);
    private boolean refreshOnAccess = false;

    // Spring上下文（用于自动发现）
    private static ApplicationContext applicationContext;
    private static CacheLoaderResolver cacheLoaderResolver;

    // 自动发现配置
    private boolean autoDiscoverLoader = true;

    /**
     * 构造器
     */
    public UnifiedCacheBuilder(String cacheName) {
        this.cacheName = cacheName;
    }

    /**
     * 从配置创建构建器
     */
    public UnifiedCacheBuilder(CascadeCacheConfiguration config) {
        this.cacheName = config.getName();
        applyConfiguration(config);
    }


    /**
     * 创建构建器
     */
    public static <K, V> UnifiedCacheBuilder<K, V> newBuilder(String cacheName) {
        return new UnifiedCacheBuilder<>(cacheName);
    }

    /**
     * 创建类型化构建器
     */
    public static <K, V> UnifiedCacheBuilder<K, V> newBuilder(String cacheName, Class<K> keyType, Class<V> valueType) {
        return new UnifiedCacheBuilder<K, V>(cacheName).types(keyType, valueType);
    }

    /**
     * String Key快捷构建器
     */
    public static <V> UnifiedCacheBuilder<String, V> stringCache(String cacheName, Class<V> valueType) {
        return newBuilder(cacheName, String.class, valueType);
    }

    /**
     * Long Key快捷构建器
     */
    public static <V> UnifiedCacheBuilder<Long, V> longCache(String cacheName, Class<V> valueType) {
        return newBuilder(cacheName, Long.class, valueType);
    }

    // ==================== 类型配置 ====================

    /**
     * 设置键值类型
     */
    public UnifiedCacheBuilder<K, V> types(Class<K> keyType, Class<V> valueType) {
        this.keyType = keyType;
        this.valueType = valueType;
        return this;
    }

    /**
     * 设置执行器
     */
    public UnifiedCacheBuilder<K, V> executor(Executor executor) {
        this.executor = executor;
        return this;
    }

    /**
     * 设置缓存加载器
     */
    public UnifiedCacheBuilder<K, V> loader(CacheLoader<K, V> loader) {
        this.cacheLoader = loader;
        this.l1Config.cacheLoader(loader);
        this.l2Config.cacheLoader(loader);
        return this;
    }

    // ==================== L1缓存配置 ====================

    /**
     * 启用/禁用L1缓存
     */
    public UnifiedCacheBuilder<K, V> enableL1(boolean enable) {
        this.enableL1 = enable;
        return this;
    }

    /**
     * 设置L1最大大小
     */
    public UnifiedCacheBuilder<K, V> maximumSize(long size) {
        this.l1Config.maximumSize(size);
        return this;
    }

    /**
     * 设置L1写入后过期时间
     */
    public UnifiedCacheBuilder<K, V> expireAfterWrite(Duration duration) {
        this.l1Config.expireAfterWrite(duration);
        return this;
    }

    /**
     * 设置L1访问后过期时间
     */
    public UnifiedCacheBuilder<K, V> expireAfterAccess(Duration duration) {
        this.l1Config.expireAfterAccess(duration);
        return this;
    }

    /**
     * 设置自动刷新间隔
     */
    public UnifiedCacheBuilder<K, V> refreshAfterWrite(Duration duration) {
        this.l1Config.refreshAfterWrite(duration);
        this.l2Config.refreshAfterWrite(duration);
        return this;
    }

    /**
     * 启用统计
     */
    public UnifiedCacheBuilder<K, V> recordStats(boolean record) {
        this.l1Config.recordStats(record);
        return this;
    }

    // ==================== L2缓存配置 ====================

    /**
     * 启用Redis L2缓存
     */
    public UnifiedCacheBuilder<K, V> enableL2(boolean enable) {
        this.enableL2 = enable;
        return this;
    }

    /**
     * 启用Redis L2缓存（使用指定客户端）
     */
    public UnifiedCacheBuilder<K, V> withRedis(RedissonClient client) {
        this.enableL2 = true;
        this.redissonClient = client;
        return this;
    }

    /**
     * 启用Redis L2缓存（自动发现客户端）
     */
    public UnifiedCacheBuilder<K, V> withRedis() {
        this.enableL2 = true;
        this.redissonClient = getRedissonClientFromSpring();
        if (this.redissonClient == null) {
            throw new IllegalStateException("RedissonClient not found in Spring context");
        }
        return this;
    }

    /**
     * 设置Redis键前缀
     */
    public UnifiedCacheBuilder<K, V> keyPrefix(String prefix) {
        this.l2Config.keyPrefix(prefix);
        return this;
    }

    /**
     * 设置Redis默认TTL
     */
    public UnifiedCacheBuilder<K, V> defaultTtl(Duration ttl) {
        this.l2Config.defaultTtl(ttl);
        return this;
    }

    // ==================== 防护配置 ====================

    /**
     * 启用防护机制
     */
    public UnifiedCacheBuilder<K, V> enableProtection(boolean enable) {
        this.enableProtection = enable;
        return this;
    }

    /**
     * 配置布隆过滤器
     */
    public UnifiedCacheBuilder<K, V> bloomFilter(long expectedElements, double falsePositiveRate) {
        this.enableProtection = true;
        if (redissonClient != null) {
            this.bloomFilter = RedissonBloomFilterProtection.builder(redissonClient)
                    .filterName(cacheName + "_bloom")
                    .expectedElements(expectedElements)
                    .falsePositiveRate(falsePositiveRate)
                    .build();
        }
        return this;
    }

    /**
     * 配置随机TTL防雪崩
     */
    public UnifiedCacheBuilder<K, V> randomTtl(Duration baseTtl, double jitterRatio) {
        this.enableProtection = true;
        Duration jitterRange = Duration.ofMillis((long) (baseTtl.toMillis() * jitterRatio));
        this.randomTtl = new RandomTtlProtection(baseTtl, jitterRange,
                RandomTtlProtection.JitterStrategy.UNIFORM);
        return this;
    }

    /**
     * 配置分布式锁防热点
     */
    public UnifiedCacheBuilder<K, V> distributedLock(Duration lockTimeout) {
        this.enableProtection = true;
        if (redissonClient != null) {
            this.distributedLock = new RedissonLockProtection(
                    redissonClient,
                    cacheName + "_lock:",
                    lockTimeout,
                    Duration.ofMillis(100),
                    3,
                    Duration.ofMillis(50)
            );
        }
        return this;
    }

    // ==================== 便捷配置方法 ====================

    /**
     * 一键配置基础缓存
     */
    public UnifiedCacheBuilder<K, V> basicConfig(long maxSize, Duration expireAfter) {
        return maximumSize(maxSize).expireAfterWrite(expireAfter);
    }

    /**
     * 一键配置多级缓存
     */
    public UnifiedCacheBuilder<K, V> multiTierConfig(long l1Size, Duration l1Expire, Duration l2Ttl) {
        return maximumSize(l1Size)
                .expireAfterWrite(l1Expire)
                .withRedis()
                .defaultTtl(l2Ttl);
    }

    /**
     * 一键配置防护机制
     */
    public UnifiedCacheBuilder<K, V> withProtection() {
        return enableProtection(true)
                .bloomFilter(100000, 0.01)
                .randomTtl(Duration.ofMinutes(30), 0.1)
                .distributedLock(Duration.ofSeconds(5));
    }

    // ==================== 同步配置 ====================

    /**
     * 启用分布式同步
     */
    public UnifiedCacheBuilder<K, V> enableSync(boolean enabled) {
        this.enableSync = enabled;
        return this;
    }

    /**
     * 设置同步主题
     */
    public UnifiedCacheBuilder<K, V> syncTopic(String topic) {
        this.syncTopic = topic;
        return this;
    }

    /**
     * 设置同步超时时间
     */
    public UnifiedCacheBuilder<K, V> syncTimeout(Duration timeout) {
        this.syncTimeout = timeout;
        return this;
    }

    /**
     * 设置是否异步同步
     */
    public UnifiedCacheBuilder<K, V> asyncSync(boolean async) {
        this.asyncSync = async;
        return this;
    }

    /**
     * 一键配置分布式同步
     */
    public UnifiedCacheBuilder<K, V> withSync() {
        return enableSync(true)
                .syncTopic("cascade:cache:sync:" + cacheName)
                .syncTimeout(Duration.ofSeconds(5))
                .asyncSync(true);
    }

    // ==================== 刷新配置 ====================

    /**
     * 启用自动刷新
     */
    public UnifiedCacheBuilder<K, V> enableAutoRefresh(boolean enabled) {
        this.enableAutoRefresh = enabled;
        return this;
    }

    /**
     * 设置刷新间隔
     */
    public UnifiedCacheBuilder<K, V> refreshInterval(Duration interval) {
        this.refreshInterval = interval;
        return this;
    }

    /**
     * 设置访问时是否触发刷新检查
     */
    public UnifiedCacheBuilder<K, V> refreshOnAccess(boolean enabled) {
        this.refreshOnAccess = enabled;
        return this;
    }

    /**
     * 一键配置自动刷新
     */
    public UnifiedCacheBuilder<K, V> withAutoRefresh() {
        return enableAutoRefresh(true)
                .refreshInterval(Duration.ofMinutes(10))
                .refreshOnAccess(false);
    }

    /**
     * 一键配置自动刷新（自定义间隔）
     */
    public UnifiedCacheBuilder<K, V> withAutoRefresh(Duration interval) {
        return enableAutoRefresh(true)
                .refreshInterval(interval)
                .refreshOnAccess(false);
    }

    // ==================== 构建方法 ====================

    /**
     * 构建缓存
     */
    public Cache<K, V> build() {
        validateConfig();

        // 创建L1引擎
        CacheEngine<K, V> l1Engine = null;
        if (enableL1) {
            l1Engine = new CaffeineEngine<>(cacheName + "_l1", l1Config);
            log.debug("Created L1 engine: {}", cacheName);
        }

        // 创建L2引擎
        CacheEngine<K, V> l2Engine = null;
        if (enableL2 && redissonClient != null) {
            l2Engine = new RedisEngine<>(cacheName + "_l2", redissonClient, l2Config);
            log.debug("Created L2 engine: {}", cacheName);
        }

        // 创建统一缓存
        UnifiedCache<K, V> cache;
        if (l1Engine != null && l2Engine != null) {
            cache = new UnifiedCache<>(cacheName, l1Engine, l2Engine, executor);
        } else if (l1Engine != null) {
            cache = new UnifiedCache<>(cacheName, l1Engine);
        } else if (l2Engine != null) {
            cache = new UnifiedCache<>(cacheName, l2Engine);
        } else {
            throw new IllegalStateException("At least one cache tier must be enabled");
        }

        // 设置加载器
        if (cacheLoader != null) {
            cache.setLoader(cacheLoader);
        } else if (autoDiscoverLoader && cacheLoaderResolver != null && keyType != null && valueType != null) {
            // 使用CacheLoaderResolver自动发现
            CacheLoader<K, V> discoveredLoader = cacheLoaderResolver.resolveCacheLoader(keyType, valueType);
            if (discoveredLoader != null) {
                cache.setLoader(discoveredLoader);
                log.info("Auto-discovered CacheLoader: {} for cache: {}", discoveredLoader.getClass().getSimpleName(), cacheName);
            } else {
                log.debug("No compatible CacheLoader found for cache: {} with types <{}, {}>",
                        cacheName, keyType.getSimpleName(), valueType.getSimpleName());
            }
        }

        // 设置防护
        if (enableProtection) {
            SimplifiedCacheProtectionManager.Builder protectionBuilder =
                    SimplifiedCacheProtectionManager.builder();

            if (bloomFilter != null) {
                protectionBuilder.bloomFilter(bloomFilter);
            }
            if (randomTtl != null) {
                protectionBuilder.randomTtl(randomTtl);
            }
            if (distributedLock != null) {
                protectionBuilder.redissonLock(distributedLock);
            }

            cache.setProtectionManager(protectionBuilder.build());
            log.debug("Configured protection for cache: {}", cacheName);
        }

        // 设置同步器
        if (enableSync && redissonClient != null) {
            try {
                // 创建同步管理器
                RedissonCacheSyncManager syncManager = new RedissonCacheSyncManager(redissonClient, syncTopic);
                
                // 创建事件处理器
                UnifiedEventProcessor eventProcessor = new UnifiedEventProcessor(asyncSync);
                
                // 创建统一同步器
                UnifiedCacheSynchronizer<K, V> synchronizer = new UnifiedCacheSynchronizer<>(
                    cacheName, syncManager, eventProcessor);
                
                // 设置到缓存中
                cache.setSynchronizer(synchronizer);
                
                log.info("Configured distributed sync for cache: {}, topic: {}", cacheName, syncTopic);
            } catch (Exception e) {
                log.warn("Failed to configure distributed sync for cache: {}, error: {}", cacheName, e.getMessage());
            }
        }

        // 设置刷新调度器
        if (enableAutoRefresh && cacheLoader != null) {
            try {
                // 创建刷新回调
                CacheRefreshScheduler.RefreshCallback<K, V> refreshCallback = (key, newValue) -> {
                    cache.put(key, newValue);
                    log.debug("Auto refreshed cache key: {} with new value", key);
                };
                
                // 创建刷新调度器
                CacheRefreshScheduler<K, V> refreshScheduler = new CacheRefreshScheduler<>(
                    refreshCallback, cacheLoader, refreshInterval);
                
                // 设置到缓存中
                cache.setRefreshScheduler(refreshScheduler);
                
                log.info("Configured auto refresh for cache: {}, interval: {}", cacheName, refreshInterval);
            } catch (Exception e) {
                log.warn("Failed to configure auto refresh for cache: {}, error: {}", cacheName, e.getMessage());
            }
        }

        log.info("Built unified cache: {}, L1={}, L2={}, protection={}, sync={}, autoRefresh={}",
                cacheName, enableL1, enableL2, enableProtection, enableSync, enableAutoRefresh);

        return cache;
    }

    // ==================== 私有方法 ====================

    /**
     * 验证配置
     */
    private void validateConfig() {
        if (cacheName == null || cacheName.trim().isEmpty()) {
            throw new IllegalArgumentException("Cache name cannot be null or empty");
        }
        if (!enableL1 && !enableL2) {
            throw new IllegalStateException("At least one cache tier must be enabled");
        }
        if (enableL2 && redissonClient == null) {
            throw new IllegalStateException("RedissonClient is required for L2 cache");
        }
    }

    /**
     * 尝试从Spring容器获取RedissonClient
     */
    private static RedissonClient getRedissonClientFromSpring() {
        if (applicationContext == null) {
            return null;
        }
        try {
            return applicationContext.getBean(RedissonClient.class);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 尝试自动发现CacheLoader
     */
    @SuppressWarnings("unchecked")
    private CacheLoader<K, V> tryAutoDiscoverCacheLoader() {
        if (applicationContext == null || keyType == null || valueType == null) {
            return null;
        }

        try {
            // 1. 查找带@AutoConfigureLoader注解的Bean
            Map<String, Object> candidates = applicationContext.getBeansWithAnnotation(AutoConfigureLoader.class);
            for (Object bean : candidates.values()) {
                if (bean instanceof CacheLoader<?, ?>) {
                    // TODO: 实现类型匹配逻辑
                    return (CacheLoader<K, V>) bean;
                }
            }

            // 2. 按类型查找
            Map<String, CacheLoader> loaders = applicationContext.getBeansOfType(CacheLoader.class);
            if (loaders.size() == 1) {
                return (CacheLoader<K, V>) loaders.values().iterator().next();
            }

        } catch (Exception e) {
            log.debug("Failed to auto-discover CacheLoader: {}", e.getMessage());
        }

        return null;
    }

    /**
     * 设置Spring应用上下文
     */
    public static void setApplicationContext(ApplicationContext context) {
        applicationContext = context;
    }

    /**
     * 设置CacheLoader解析器
     */
    public static void setCacheLoaderResolver(CacheLoaderResolver resolver) {
        cacheLoaderResolver = resolver;
    }

    /**
     * 启用/禁用自动发现CacheLoader
     */
    public UnifiedCacheBuilder<K, V> autoDiscoverLoader(boolean enabled) {
        this.autoDiscoverLoader = enabled;
        return this;
    }

    /**
     * 从CascadeCacheConfiguration应用配置
     */
    private void applyConfiguration(CascadeCacheConfiguration config) {
        // 基础配置
        if (config.getCommon() != null) {
            var common = config.getCommon();
            maximumSize(common.getMaximumSize());
            recordStats(common.isRecordStats());
            if (common.getExpireAfterWrite() != null) {
                expireAfterWrite(common.getExpireAfterWrite());
            }
            if (common.getExpireAfterAccess() != null) {
                expireAfterAccess(common.getExpireAfterAccess());
            }
            if (common.getRefreshAfterWrite() != null) {
                refreshAfterWrite(common.getRefreshAfterWrite());
            }
            if (common.getExecutor() != null) {
                executor(common.getExecutor());
            }
        }

        // L1配置
        if (config.getL1() != null && config.getL1().isEnabled()) {
            var l1 = config.getL1();
            enableL1(true);
            maximumSize(l1.getMaximumSize());
            if (l1.getExpireAfterWrite() != null) {
                expireAfterWrite(l1.getExpireAfterWrite());
            }
            if (l1.getExpireAfterAccess() != null) {
                expireAfterAccess(l1.getExpireAfterAccess());
            }
            recordStats(l1.isRecordStats());
            // 其他L1特定配置...
        }

        // L2配置
        if (config.getL2() != null && config.getL2().isEnabled()) {
            var l2 = config.getL2();
            enableL2(true);
            keyPrefix(l2.getKeyPrefix());
            if (l2.getDefaultTtl() != null) {
                defaultTtl(l2.getDefaultTtl());
            }
            // L2序列化器和客户端配置
            if (l2.getRedissonClient() != null) {
                withRedis(l2.getRedissonClient());
            }
            // 其他L2特定配置...
        }

        // 同步配置
        if (config.getSync() != null && config.getSync().isEnabled()) {
            var sync = config.getSync();
            enableSync(true);
            syncTopic(sync.getTopic());
            if (sync.getTimeout() != null) {
                syncTimeout(sync.getTimeout());
            }
            asyncSync(sync.isAsync());
        }

        // 防护配置
        if (config.getProtection() != null && config.getProtection().isEnabled()) {
            var protection = config.getProtection();
            enableProtection(true);
            
            // 布隆过滤器配置
            var bloomFilterConfig = protection.getBloomFilter();
            if (bloomFilterConfig != null && bloomFilterConfig.isEnabled()) {
                bloomFilter(bloomFilterConfig.getExpectedElements(), bloomFilterConfig.getFalsePositiveRate());
            }
            
            // 随机TTL配置
            var randomTtlConfig = protection.getRandomTtl();
            if (randomTtlConfig != null && randomTtlConfig.isEnabled()) {
                Duration baseTtl = randomTtlConfig.getBaseTtl() != null ? 
                    randomTtlConfig.getBaseTtl() : Duration.ofMinutes(30);
                randomTtl(baseTtl, randomTtlConfig.getJitterRatio());
            }
            
            // 分布式锁配置
            var distributedLockConfig = protection.getDistributedLock();
            if (distributedLockConfig != null && distributedLockConfig.isEnabled()) {
                Duration lockTimeout = distributedLockConfig.getLockTimeout() != null ?
                    distributedLockConfig.getLockTimeout() : Duration.ofSeconds(30);
                distributedLock(lockTimeout);
            }
        }
    }
}