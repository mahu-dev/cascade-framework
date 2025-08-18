package io.github.cascade.cache.unified;

import io.github.cascade.cache.api.Cache;
import io.github.cascade.cache.api.CacheManager;
import io.github.cascade.cache.builder.CascadeCacheBuilder;
import io.github.cascade.cache.config.unified.CascadeCacheConfiguration;
import io.github.cascade.cache.config.unified.ConfigurationConverter;
import io.github.cascade.cache.manager.SpringBootCacheManager;
import org.springframework.context.ApplicationContext;

/**
 * 统一的Cascade门面类
 * 提供统一、简化的缓存API入口
 *
 * @author cascade
 */
public class UnifiedCascade {

    private static CacheManager defaultCacheManager;
    private static ApplicationContext applicationContext;

    /**
     * 设置默认缓存管理器
     */
    public static void setDefaultCacheManager(CacheManager cacheManager) {
        defaultCacheManager = cacheManager;
    }

    /**
     * 设置Spring应用上下文
     */
    public static void setApplicationContext(ApplicationContext context) {
        applicationContext = context;
        CascadeCacheBuilder.setApplicationContext(context);
    }

    /**
     * 获取默认缓存管理器
     */
    public static CacheManager getDefaultCacheManager() {
        if (defaultCacheManager == null) {
            defaultCacheManager = new SpringBootCacheManager();
        }
        return defaultCacheManager;
    }

    /**
     * 创建缓存构建器 - 使用默认配置
     */
    public static <K, V> CascadeCacheBuilder<K, V> cache(String cacheName) {
        CascadeCacheConfiguration config = ConfigurationConverter.createDefault(cacheName);
        return new CascadeCacheBuilder<>(config, getDefaultCacheManager());
    }

    /**
     * 创建缓存构建器 - 使用自定义配置
     */
    public static <K, V> CascadeCacheBuilder<K, V> cache(CascadeCacheConfiguration config) {
        return new CascadeCacheBuilder<>(config, getDefaultCacheManager());
    }

    /**
     * 创建缓存构建器 - 使用自定义管理器
     */
    public static <K, V> CascadeCacheBuilder<K, V> cache(String cacheName, CacheManager cacheManager) {
        CascadeCacheConfiguration config = ConfigurationConverter.createDefault(cacheName);
        return new CascadeCacheBuilder<>(config, cacheManager);
    }

    /**
     * 获取已存在的缓存
     */
    @SuppressWarnings("unchecked")
    public static <K, V> Cache<K, V> getCache(String cacheName) {
        return (Cache<K, V>) getDefaultCacheManager().getCache(cacheName);
    }

    /**
     * 获取已存在的缓存 - 使用自定义管理器
     */
    @SuppressWarnings("unchecked")
    public static <K, V> Cache<K, V> getCache(String cacheName, CacheManager cacheManager) {
        return (Cache<K, V>) cacheManager.getCache(cacheName);
    }

    /**
     * 创建配置构建器
     */
    public static ConfigurationBuilder config(String cacheName) {
        return new ConfigurationBuilder(cacheName);
    }

    /**
     * 配置构建器
     * 提供流式API创建统一配置
     */
    public static class ConfigurationBuilder {
        private final CascadeCacheConfiguration config;

        public ConfigurationBuilder(String cacheName) {
            this.config = ConfigurationConverter.createDefault(cacheName);
        }

        /**
         * 启用L2缓存
         */
        public ConfigurationBuilder enableL2() {
            config.setL2(config.getL2().setEnabled(true));
            return this;
        }

        /**
         * 配置L2缓存键前缀
         */
        public ConfigurationBuilder l2KeyPrefix(String prefix) {
            config.setL2(config.getL2().setKeyPrefix(prefix));
            return this;
        }

        /**
         * 启用防护机制
         */
        public ConfigurationBuilder enableProtection() {
            config.setProtection(config.getProtection().setEnabled(true));
            return this;
        }

        /**
         * 启用同步
         */
        public ConfigurationBuilder enableSync() {
            config.setSync(config.getSync().setEnabled(true));
            return this;
        }

        /**
         * 配置缓存大小
         */
        public ConfigurationBuilder size(long size) {
            config.setCommon(config.getCommon().setMaximumSize(size));
            config.setL1(config.getL1().setMaximumSize(size));
            return this;
        }

        /**
         * 配置过期时间
         */
        public ConfigurationBuilder expireAfterWrite(java.time.Duration duration) {
            config.setCommon(config.getCommon().setExpireAfterWrite(duration));
            return this;
        }

        /**
         * 配置刷新时间
         */
        public ConfigurationBuilder refreshAfterWrite(java.time.Duration duration) {
            config.setCommon(config.getCommon().setRefreshAfterWrite(duration));
            return this;
        }

        /**
         * 启用统计
         */
        public ConfigurationBuilder recordStats() {
            config.setCommon(config.getCommon().setRecordStats(true));
            return this;
        }

        /**
         * 配置布隆过滤器
         */
        public ConfigurationBuilder bloomFilter(long expectedElements, double falsePositiveRate) {
            config.getProtection().setBloomFilter(
                    config.getProtection().getBloomFilter()
                            .setEnabled(true)
                            .setExpectedElements(expectedElements)
                            .setFalsePositiveRate(falsePositiveRate)
            );
            return this;
        }

        /**
         * 构建配置
         */
        public CascadeCacheConfiguration build() {
            return config;
        }

        /**
         * 构建缓存构建器
         */
        public <K, V> CascadeCacheBuilder<K, V> buildCacheBuilder() {
            return new CascadeCacheBuilder<>(config, getDefaultCacheManager());
        }

        /**
         * 构建缓存构建器 - 使用自定义管理器
         */
        public <K, V> CascadeCacheBuilder<K, V> buildCacheBuilder(CacheManager cacheManager) {
            return new CascadeCacheBuilder<>(config, cacheManager);
        }
    }

    /**
     * 工具方法：创建配置的副本
     */
    public static CascadeCacheConfiguration copyConfig(CascadeCacheConfiguration source) {
        return ConfigurationConverter.merge(ConfigurationConverter.createDefault(source.getName()), source);
    }

    /**
     * 工具方法：合并配置
     */
    public static CascadeCacheConfiguration mergeConfig(CascadeCacheConfiguration base, CascadeCacheConfiguration override) {
        return ConfigurationConverter.merge(base, override);
    }
}