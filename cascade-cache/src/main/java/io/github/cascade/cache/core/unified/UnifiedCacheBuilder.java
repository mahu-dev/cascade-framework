package io.github.cascade.cache.core.unified;

import io.github.cascade.cache.api.Cache;
import io.github.cascade.cache.api.CacheLoader;
import io.github.cascade.cache.builder.CacheBuilderValidator;
import io.github.cascade.cache.builder.CacheConfigurationProcessor;
import io.github.cascade.cache.builder.EnhancementConfigurationStep;
import io.github.cascade.cache.builder.TierConfigurationStep;
import io.github.cascade.cache.config.CascadeCacheConfiguration;
import io.github.cascade.cache.core.unified.CaffeineEngine.CaffeineConfig;
import io.github.cascade.cache.core.unified.RedisEngine.RedisConfig;
import io.github.cascade.cache.metrics.UnifiedMonitoringManager;
import io.github.cascade.cache.protection.SimplifiedCacheProtectionManager;
import io.github.cascade.cache.refresh.CacheRefreshScheduler;
import io.github.cascade.cache.sync.UnifiedCacheSynchronizer;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;

import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

/**
 * 统一缓存构建器 - 新架构版本
 * 使用分段构建器模式，提供清晰的构建流程
 * 去除向后兼容代码，专注于新的API设计
 *
 * @author cascade
 */
@Slf4j
public class UnifiedCacheBuilder {

    // ==================== 静态工厂方法 ====================

    /**
     * 创建缓存构建器的通用入口
     */
    public static <K, V> TierConfigurationStep<K, V> forCache(String cacheName, Class<K> keyType, Class<V> valueType) {
        return new SegmentedBuilderImpl<>(cacheName, keyType, valueType);
    }

    /**
     * 创建字符串键缓存构建器
     */
    public static <V> TierConfigurationStep<String, V> stringCache(String cacheName, Class<V> valueType) {
        return forCache(cacheName, String.class, valueType);
    }

    /**
     * 创建长整型键缓存构建器
     */
    public static <V> TierConfigurationStep<Long, V> longCache(String cacheName, Class<V> valueType) {
        return forCache(cacheName, Long.class, valueType);
    }

    /**
     * 创建整型键缓存构建器
     */
    public static <V> TierConfigurationStep<Integer, V> intCache(String cacheName, Class<V> valueType) {
        return forCache(cacheName, Integer.class, valueType);
    }

    // ==================== 分段构建器实现 ====================

    /**
     * 分段构建器实现类
     * 封装了完整的缓存构建逻辑，使用新的架构设计
     */
    public static class SegmentedBuilderImpl<K, V> implements TierConfigurationStep<K, V>, EnhancementConfigurationStep<K, V> {

        // 基础信息
        private final String cacheName;
        private final Class<K> keyType;
        private final Class<V> valueType;

        // 层级配置
        private boolean enableL1 = true;
        private boolean enableL2 = false;
        private final CaffeineConfig<K, V> l1Config = new CaffeineConfig<>();
        private final RedisConfig l2Config = new RedisConfig();
        private RedissonClient redissonClient;

        // 增强功能配置
        private CascadeCacheConfiguration.ProtectionConfig protectionConfig;
        private CascadeCacheConfiguration.SyncConfig syncConfig;
        private CascadeCacheConfiguration.RefreshConfig refreshConfig;

        // 核心组件
        private CacheLoader<K, V> cacheLoader;
        private Executor executor = ForkJoinPool.commonPool();
        private UnifiedMonitoringManager monitoringManager;

        // 辅助工具
        private final CacheConfigurationProcessor<K, V> configProcessor;
        private final CacheBuilderValidator validator;

        public SegmentedBuilderImpl(String cacheName, Class<K> keyType, Class<V> valueType) {
            this.cacheName = cacheName;
            this.keyType = keyType;
            this.valueType = valueType;
            this.configProcessor = new CacheConfigurationProcessor<>(cacheName, keyType, valueType);
            this.validator = new CacheBuilderValidator(cacheName);

            // 设置默认L1配置
            this.l1Config.setMaximumSize(10000);
            this.l1Config.setRecordStats(true);
        }

        // ==================== TierConfigurationStep 实现 ====================

        @Override
        public EnhancementConfigurationStep<K, V> withL1Only(CascadeCacheConfiguration.L1Config l1Config) {
            this.enableL1 = true;
            this.enableL2 = false;
            this.configProcessor.applyL1Config(createConfigWithL1(l1Config), this.l1Config);
            return this;
        }

        @Override
        public EnhancementConfigurationStep<K, V> withL1Only() {
            this.enableL1 = true;
            this.enableL2 = false;
            // 使用默认L1配置
            return this;
        }

        @Override
        public EnhancementConfigurationStep<K, V> withL1AndL2(CascadeCacheConfiguration.L1Config l1Config,
                                                              CascadeCacheConfiguration.L2Config l2Config,
                                                              RedissonClient redissonClient) {
            this.enableL1 = true;
            this.enableL2 = true;
            this.redissonClient = redissonClient;

            this.configProcessor.applyL1Config(createConfigWithL1(l1Config), this.l1Config);
            this.configProcessor.applyL2Config(createConfigWithL2(l2Config), this.l2Config);
            return this;
        }

        @Override
        public EnhancementConfigurationStep<K, V> withL1AndL2(CascadeCacheConfiguration.L2Config l2Config,
                                                              RedissonClient redissonClient) {
            this.enableL1 = true;
            this.enableL2 = true;
            this.redissonClient = redissonClient;

            this.configProcessor.applyL2Config(createConfigWithL2(l2Config), this.l2Config);
            return this;
        }

        @Override
        public EnhancementConfigurationStep<K, V> withL1AndL2(CascadeCacheConfiguration.L1Config l1Config,
                                                              CascadeCacheConfiguration.L2Config l2Config) {
            this.enableL1 = true;
            this.enableL2 = true;
            this.redissonClient = CacheConfigurationProcessor.getRedissonClientFromSpring();

            this.configProcessor.applyL1Config(createConfigWithL1(l1Config), this.l1Config);
            this.configProcessor.applyL2Config(createConfigWithL2(l2Config), this.l2Config);
            return this;
        }

        @Override
        public EnhancementConfigurationStep<K, V> withL1AndL2() {
            this.enableL1 = true;
            this.enableL2 = true;
            this.redissonClient = CacheConfigurationProcessor.getRedissonClientFromSpring();

            // 设置默认L2配置
            this.l2Config.setKeyPrefix("cascade:cache:" + cacheName + ":");
            return this;
        }

        // ==================== EnhancementConfigurationStep 实现 ====================

        @Override
        public EnhancementConfigurationStep<K, V> enableProtection(CascadeCacheConfiguration.ProtectionConfig protectionConfig) {
            this.protectionConfig = protectionConfig;
            return this;
        }

        @Override
        public EnhancementConfigurationStep<K, V> enableProtection() {
            // 创建默认防护配置
            this.protectionConfig = createDefaultProtectionConfig();
            return this;
        }

        @Override
        public EnhancementConfigurationStep<K, V> enableAutoRefresh(CascadeCacheConfiguration.RefreshConfig refreshConfig) {
            this.refreshConfig = refreshConfig;
            return this;
        }

        @Override
        public EnhancementConfigurationStep<K, V> enableAutoRefresh() {
            // 创建默认刷新配置
            this.refreshConfig = createDefaultRefreshConfig();
            return this;
        }

        @Override
        public EnhancementConfigurationStep<K, V> enableCacheSync(CascadeCacheConfiguration.SyncConfig syncConfig) {
            this.syncConfig = syncConfig;
            return this;
        }

        @Override
        public EnhancementConfigurationStep<K, V> enableCacheSync() {
            // 创建默认同步配置
            this.syncConfig = createDefaultSyncConfig();
            return this;
        }

        @Override
        public EnhancementConfigurationStep<K, V> withCacheLoader(CacheLoader<K, V> cacheLoader) {
            this.cacheLoader = cacheLoader;
            this.l1Config.setCacheLoader(cacheLoader);
            this.l2Config.setCacheLoader(cacheLoader);
            return this;
        }

        @Override
        public EnhancementConfigurationStep<K, V> withExecutor(Executor executor) {
            this.executor = executor;
            return this;
        }

        @Override
        public Cache<K, V> build() {
            CascadeCacheConfiguration defaultConfig = new CascadeCacheConfiguration();
            defaultConfig.setName(cacheName);
            return build(defaultConfig);
        }

        @Override
        public Cache<K, V> build(CascadeCacheConfiguration cacheConfig) {
            long startTime = System.currentTimeMillis();

            try {
                log.debug("正在使用分段构建器构建缓存 '{}'", cacheName);

                // 验证配置
                validateConfiguration();

                // 创建缓存引擎
                CacheEngine<K, V> l1Engine = enableL1 ? new CaffeineEngine<>(cacheName, l1Config) : null;
                CacheEngine<K, V> l2Engine = enableL2 ? new RedisEngine<>(cacheName, redissonClient, l2Config) : null;

                // 创建SmartCache
                SmartCache<K, V> cache = new SmartCache<>(cacheName, l1Engine, l2Engine, executor, monitoringManager);
                cache.setCacheConfiguration(cacheConfig);

                // 配置组件
                if (cacheConfig.getProtection() != null) {
                    this.protectionConfig = cacheConfig.getProtection();
                }
                if (cacheConfig.getSync() != null) {
                    this.syncConfig = cacheConfig.getSync();
                }
                if (cacheConfig.getRefresh() != null) {
                    this.refreshConfig = cacheConfig.getRefresh();
                }
                configureComponents(cache);

                long buildTime = System.currentTimeMillis() - startTime;
                log.info("已成功构建缓存 '{}'，耗时 {}ms - L1={}, L2={}, 功能=[{}]",
                        cacheName, buildTime, enableL1, enableL2, getEnabledFeatures());

                return cache;

            } catch (Exception e) {
                long buildTime = System.currentTimeMillis() - startTime;
                log.error("构建缓存 '{}' 失败，耗时 {}ms: {}", cacheName, buildTime, e.getMessage(), e);
                throw new RuntimeException("缓存构建失败: " + cacheName, e);
            }
        }

        // ==================== 私有辅助方法 ====================

        private void validateConfiguration() {
            validator.validateConfiguration(
                    enableL1, enableL2, l1Config, l2Config, redissonClient,
                    protectionConfig, syncConfig, refreshConfig, cacheLoader, true
            );
        }

        private void configureComponents(SmartCache<K, V> cache) {
            // 配置缓存加载器
            configProcessor.configureCacheLoader(cache, cacheLoader, true);

            // 配置防护机制
            if (protectionConfig != null && protectionConfig.isEnabled()) {
                SimplifiedCacheProtectionManager protectionManager =
                        configProcessor.configureProtection(protectionConfig, redissonClient);
                if (protectionManager != null) {
                    cache.setProtectionManager(protectionManager);
                }
            }

            // 配置同步器
            if (syncConfig != null && syncConfig.isEnabled()) {
                UnifiedCacheSynchronizer<K, V> synchronizer =
                        configProcessor.configureSynchronizer(syncConfig, redissonClient);
                if (synchronizer != null) {
                    cache.setSynchronizer(synchronizer);
                }
            }

            // 配置刷新调度器 - 修复：使用已设置的 CacheLoader
            if (refreshConfig != null && refreshConfig.isEnabled()) {
                // 获取已经设置到缓存中的 CacheLoader（可能是自动发现的）
                CacheLoader<K, V> actualCacheLoader = cache.getLoader();
                CacheRefreshScheduler<K, V> refreshScheduler =
                        configProcessor.configureRefreshScheduler(refreshConfig, actualCacheLoader, cache);
                if (refreshScheduler != null) {
                    cache.setRefreshScheduler(refreshScheduler);
                    log.debug("已为缓存 '{}' 成功配置刷新调度器", cacheName);
                } else {
                    log.warn("为缓存 '{}' 配置刷新调度器失败 - 缓存加载器不可用", cacheName);
                }
            }
        }

        private String getEnabledFeatures() {
            StringBuilder features = new StringBuilder();
            if (protectionConfig != null && protectionConfig.isEnabled()) {
                features.append("protection,");
            }
            if (syncConfig != null && syncConfig.isEnabled()) {
                features.append("sync,");
            }
            if (refreshConfig != null && refreshConfig.isEnabled()) {
                features.append("refresh,");
            }
            String result = features.toString();
            return result.isEmpty() ? "none" : result.substring(0, result.length() - 1);
        }

        // ==================== 配置创建方法 ====================

        private CascadeCacheConfiguration createConfigWithL1(CascadeCacheConfiguration.L1Config l1Config) {
            CascadeCacheConfiguration config = new CascadeCacheConfiguration();
            config.setL1(l1Config);
            return config;
        }

        private CascadeCacheConfiguration createConfigWithL2(CascadeCacheConfiguration.L2Config l2Config) {
            CascadeCacheConfiguration config = new CascadeCacheConfiguration();
            config.setL2(l2Config);
            return config;
        }

        private CascadeCacheConfiguration.ProtectionConfig createDefaultProtectionConfig() {
            log.debug("正在为缓存 '{}' 创建默认防护配置", cacheName);
            CascadeCacheConfiguration.ProtectionConfig config = new CascadeCacheConfiguration.ProtectionConfig();
            config.setEnabled(true);
            return config;
        }

        private CascadeCacheConfiguration.RefreshConfig createDefaultRefreshConfig() {
            log.debug("正在为缓存 '{}' 创建默认刷新配置", cacheName);
            CascadeCacheConfiguration.RefreshConfig config = new CascadeCacheConfiguration.RefreshConfig();
            config.setEnabled(true);
            return config;
        }

        private CascadeCacheConfiguration.SyncConfig createDefaultSyncConfig() {
            log.debug("正在为缓存 '{}' 创建默认同步配置", cacheName);
            CascadeCacheConfiguration.SyncConfig config = new CascadeCacheConfiguration.SyncConfig();
            config.setEnabled(true).setTopic("cascade:cache:sync:" + cacheName);
            return config;
        }
    }

    // ==================== 静态配置方法 ====================

    /**
     * 设置Spring应用上下文（用于自动发现）
     */
    public static void setApplicationContext(org.springframework.context.ApplicationContext applicationContext) {
        CacheConfigurationProcessor.setApplicationContext(applicationContext);
    }

    /**
     * 设置CacheLoader解析器
     */
    public static void setCacheLoaderResolver(io.github.cascade.cache.core.CacheLoaderResolver cacheLoaderResolver) {
        CacheConfigurationProcessor.setCacheLoaderResolver(cacheLoaderResolver);
    }
}