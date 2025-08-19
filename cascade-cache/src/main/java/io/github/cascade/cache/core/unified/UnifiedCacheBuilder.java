package io.github.cascade.cache.core.unified;

import io.github.cascade.cache.annotation.AutoConfigureLoader;
import io.github.cascade.cache.api.Cache;
import io.github.cascade.cache.api.CacheLoader;
import io.github.cascade.cache.core.loader.CacheLoaderResolver;
import io.github.cascade.cache.core.unified.CaffeineEngine.CaffeineConfig;
import io.github.cascade.cache.core.unified.RedisEngine.RedisConfig;
import io.github.cascade.cache.protection.*;
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

        log.info("Built unified cache: {}, L1={}, L2={}, protection={}",
                cacheName, enableL1, enableL2, enableProtection);

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
}