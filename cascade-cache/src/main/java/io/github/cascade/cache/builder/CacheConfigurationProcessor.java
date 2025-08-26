package io.github.cascade.cache.builder;

import io.github.cascade.cache.api.CacheLoader;
import io.github.cascade.cache.config.CascadeCacheConfiguration;
import io.github.cascade.cache.core.CacheLoaderResolver;
import io.github.cascade.cache.core.unified.CaffeineEngine.CaffeineConfig;
import io.github.cascade.cache.core.unified.RedisEngine.RedisConfig;
import io.github.cascade.cache.core.unified.SmartCache;
import io.github.cascade.cache.event.UnifiedEventProcessor;
import io.github.cascade.cache.protection.*;
import io.github.cascade.cache.refresh.CacheRefreshScheduler;
import io.github.cascade.cache.sync.RedissonCacheSyncManager;
import io.github.cascade.cache.sync.UnifiedCacheSynchronizer;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.context.ApplicationContext;

import java.time.Duration;

/**
 * 缓存配置处理器
 * 负责处理缓存构建过程中的各种配置逻辑
 *
 * @author cascade
 */
@Slf4j
public class CacheConfigurationProcessor<K, V> {

    private final String cacheName;
    private final Class<K> keyType;
    private final Class<V> valueType;

    // Spring上下文和解析器
    private static ApplicationContext applicationContext;
    private static CacheLoaderResolver cacheLoaderResolver;

    public CacheConfigurationProcessor(String cacheName, Class<K> keyType, Class<V> valueType) {
        this.cacheName = cacheName;
        this.keyType = keyType;
        this.valueType = valueType;
    }

    // ==================== 配置应用方法 ====================

    /**
     * 从CascadeCacheConfiguration应用L1配置
     */
    public void applyL1Config(CascadeCacheConfiguration config, CaffeineConfig<K, V> l1Config) {
        if (config.getL1() == null || !config.getL1().isEnabled()) return;

        var l1 = config.getL1();
        if (l1.getMaximumSize() != 0) {
            l1Config.setMaximumSize(l1.getMaximumSize());
        }
        l1Config.setRecordStats(l1.isRecordStats());

        if (l1.getExpireAfterWrite() != null) {
            l1Config.setExpireAfterWrite(l1.getExpireAfterWrite());
        }
        if (l1.getExpireAfterAccess() != null) {
            l1Config.setExpireAfterAccess(l1.getExpireAfterAccess());
        }
        if (l1.getInitialCapacity() != 0) {
            l1Config.setInitialCapacity(l1.getInitialCapacity());
        }

        log.debug("Applied L1 configuration for cache: {}", cacheName);
    }

    /**
     * 从CascadeCacheConfiguration应用L2配置
     */
    public void applyL2Config(CascadeCacheConfiguration config, RedisConfig l2Config) {
        if (config.getL2() == null || !config.getL2().isEnabled()) return;

        var l2 = config.getL2();
        if (l2.getKeyPrefix() != null) {
            l2Config.setKeyPrefix(l2.getKeyPrefix());
        }
        if (l2.getDefaultTtl() != null) {
            l2Config.setDefaultTtl(l2.getDefaultTtl());
        }
        if (l2.getRefreshAfterWrite() != null) {
            l2Config.setRefreshAfterWrite(l2.getRefreshAfterWrite());
        }
        l2Config.setEnableBatch(l2.isEnableBatch());
        if (l2.getBatchSize() != 0) {
            l2Config.setBatchSize(l2.getBatchSize());
        }

        log.debug("Applied L2 configuration for cache: {}", cacheName);
    }

    /**
     * 配置防护机制
     */
    public SimplifiedCacheProtectionManager configureProtection(CascadeCacheConfiguration.ProtectionConfig protectionConfig,
                                                                RedissonClient redissonClient) {
        if (protectionConfig == null || !protectionConfig.isEnabled()) {
            return null;
        }

        log.debug("Configuring protection mechanisms for cache: {}", cacheName);
        SimplifiedCacheProtectionManager.Builder builder = SimplifiedCacheProtectionManager.builder();

        // 配置布隆过滤器
        if (protectionConfig.getBloomFilter() != null && protectionConfig.getBloomFilter().isEnabled()) {
            var bloomConfig = protectionConfig.getBloomFilter();
            if (redissonClient != null) {
                CascadeBloomFilter bloomFilter = RedissonBloomFilterProtection.builder(redissonClient)
                        .filterName(cacheName + "_bloom")
                        .expectedElements(bloomConfig.getExpectedElements())
                        .falsePositiveRate(bloomConfig.getFalsePositiveRate())
                        .build();
                builder.bloomFilter(bloomFilter);
                log.debug("Added bloom filter protection for cache: {}", cacheName);
            }
        }

        // 配置随机TTL
        if (protectionConfig.getRandomTtl() != null && protectionConfig.getRandomTtl().isEnabled()) {
            var randomTtlConfig = protectionConfig.getRandomTtl();
            Duration baseTtl = randomTtlConfig.getBaseTtl() != null ?
                    randomTtlConfig.getBaseTtl() : Duration.ofMinutes(30);
            Duration jitterRange = Duration.ofMillis((long) (baseTtl.toMillis() * randomTtlConfig.getJitterRatio()));
            RandomTtlProtection randomTtl = new RandomTtlProtection(baseTtl, jitterRange,
                    RandomTtlProtection.JitterStrategy.UNIFORM);
            builder.randomTtl(randomTtl);
            log.debug("Added random TTL protection for cache: {}", cacheName);
        }

        // 配置分布式锁
        if (protectionConfig.getDistributedLock() != null && protectionConfig.getDistributedLock().isEnabled()) {
            var lockConfig = protectionConfig.getDistributedLock();
            if (redissonClient != null) {
                RedissonLockProtection distributedLock = new RedissonLockProtection(
                        redissonClient,
                        cacheName + "_lock:",
                        lockConfig.getLockTimeout(),
                        lockConfig.getWaitTimeout(),
                        lockConfig.getMaxRetries(),
                        lockConfig.getRetryDelay()
                );
                builder.redissonLock(distributedLock);
                log.debug("Added distributed lock protection for cache: {}", cacheName);
            }
        }

        return builder.build();
    }

    /**
     * 配置缓存加载器
     */
    public void configureCacheLoader(SmartCache<K, V> cache, CacheLoader<K, V> cacheLoader, boolean autoDiscoverLoader) {
        if (cacheLoader != null) {
            cache.setLoader(cacheLoader);
            log.debug("Set explicit CacheLoader for cache: {}", cacheName);
            return;
        }

        if (autoDiscoverLoader && cacheLoaderResolver != null && keyType != null && valueType != null) {
            try {
                CacheLoader<K, V> discoveredLoader = cacheLoaderResolver.resolveCacheLoader(keyType, valueType);
                if (discoveredLoader != null) {
                    cache.setLoader(discoveredLoader);
                    log.info("Auto-discovered CacheLoader: {} for cache: {}",
                            discoveredLoader.getClass().getSimpleName(), cacheName);
                } else {
                    log.debug("No compatible CacheLoader found for cache: {} with types <{}, {}>",
                            cacheName, keyType.getSimpleName(),
                            valueType.getSimpleName());
                }
            } catch (Exception e) {
                log.warn("Failed to discover CacheLoader for cache: {}: {}", cacheName, e.getMessage());
            }
        }
    }

    /**
     * 配置同步器
     */
    public UnifiedCacheSynchronizer<K, V> configureSynchronizer(CascadeCacheConfiguration.SyncConfig syncConfig,
                                                                RedissonClient redissonClient) {
        if (syncConfig == null || !syncConfig.isEnabled() || redissonClient == null) {
            return null;
        }

        try {
            log.debug("Configuring synchronizer for cache: {}", cacheName);

            String topic = syncConfig.getTopic() != null ? syncConfig.getTopic() : "cascade:cache:sync:" + cacheName;
            RedissonCacheSyncManager syncManager = new RedissonCacheSyncManager(redissonClient, topic);
            UnifiedEventProcessor eventProcessor = new UnifiedEventProcessor(syncConfig.isAsync());

            return new UnifiedCacheSynchronizer<>(cacheName, syncManager, eventProcessor);
        } catch (Exception e) {
            log.error("Failed to configure distributed sync for cache: {}: {}", cacheName, e.getMessage(), e);
            return null;
        }
    }

    /**
     * 配置刷新调度器
     */
    public CacheRefreshScheduler<K, V> configureRefreshScheduler(CascadeCacheConfiguration.RefreshConfig refreshConfig,
                                                                 CacheLoader<K, V> cacheLoader,
                                                                 SmartCache<K, V> cache) {
        if (refreshConfig == null || !refreshConfig.isEnabled()) {
            log.debug("Refresh scheduler not configured: refreshConfig disabled for cache: {}", cacheName);
            return null;
        }

        // 如果传入的 cacheLoader 为 null，尝试从 cache 实例获取
        if (cacheLoader == null && cache != null) {
            cacheLoader = cache.getLoader();
        }

        if (cacheLoader == null) {
            log.warn("Cannot configure refresh scheduler for cache: {} - no CacheLoader available", cacheName);
            return null;
        }

        try {
            log.debug("Configuring refresh scheduler for cache: {} with CacheLoader: {}",
                    cacheName, cacheLoader.getClass().getSimpleName());

            // 使用配置中的刷新间隔，如果没有配置则使用默认值
            Duration interval = refreshConfig.getDefaultRefreshInterval() != null ?
                    refreshConfig.getDefaultRefreshInterval() : Duration.ofSeconds(30);

            CacheRefreshScheduler.RefreshCallback<K, V> refreshCallback = (key, newValue) -> {
                cache.put(key, newValue);
                log.debug("Auto refreshed cache key: '{}' for cache: '{}'", key, cacheName);
            };

            return new CacheRefreshScheduler<>(refreshCallback, cacheLoader, interval);
        } catch (Exception e) {
            log.error("Failed to configure auto refresh for cache: {}: {}", cacheName, e.getMessage(), e);
            return null;
        }
    }

    // ==================== Spring集成方法 ====================

    /**
     * 尝试从Spring容器获取RedissonClient
     */
    public static RedissonClient getRedissonClientFromSpring() {
        if (applicationContext == null) {
            return null;
        }
        try {
            return applicationContext.getBean(RedissonClient.class);
        } catch (Exception e) {
            log.debug("RedissonClient not found in Spring context: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 设置Spring应用上下文
     */
    public static void setApplicationContext(ApplicationContext applicationContext) {
        CacheConfigurationProcessor.applicationContext = applicationContext;
    }

    /**
     * 设置CacheLoader解析器
     */
    public static void setCacheLoaderResolver(CacheLoaderResolver cacheLoaderResolver) {
        CacheConfigurationProcessor.cacheLoaderResolver = cacheLoaderResolver;
    }
}