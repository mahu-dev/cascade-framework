package io.github.cascade.cache.config.unified;

import io.github.cascade.cache.core.properties.DistributedTieredCacheProperties;

/**
 * 配置转换器
 * 用于将现有的分散配置转换为统一配置
 * 
 * @author cascade
 */
public class ConfigurationConverter {

    /**
     * 从分布式缓存配置转换为统一配置
     */
    public static CascadeCacheConfiguration fromDistributedProperties(
            String cacheName,
            DistributedTieredCacheProperties properties) {
        
        CascadeCacheConfiguration config = new CascadeCacheConfiguration()
                .setName(cacheName)
                .setEnabled(true);

        // 转换同步配置
        config.getSync()
                .setEnabled(properties.isEnableSync());

        // 转换防护配置
        config.getProtection()
                .setEnabled(properties.isEnableProtection());

        // 转换监控配置
        if (properties.getMonitoring() != null) {
            config.getMonitoring()
                    .setEnableMetrics(properties.getMonitoring().isEnableMetrics());
        }

        return config;
    }

    /**
     * 从Spring Boot配置属性转换为统一配置
     * 这是一个简化版本，主要用于autoconfigure模块
     */
    public static CascadeCacheConfiguration fromSpringBootProperties(
            String cacheName,
            Object properties) {
        
        CascadeCacheConfiguration config = new CascadeCacheConfiguration()
                .setName(cacheName)
                .setEnabled(true);

        // 设置基本配置（可根据需要扩展）
        config.getL1().setEnabled(true);
        config.getL2().setEnabled(false);
        config.getSync().setEnabled(false);
        config.getProtection().setEnabled(false);
        config.getMonitoring().setEnabled(true);

        return config;
    }

    /**
     * 创建默认配置
     */
    public static CascadeCacheConfiguration createDefault(String cacheName) {
        return new CascadeCacheConfiguration()
                .setName(cacheName)
                .setEnabled(true);
    }

    /**
     * 合并两个配置，第二个配置会覆盖第一个配置的非空值
     */
    public static CascadeCacheConfiguration merge(
            CascadeCacheConfiguration base, 
            CascadeCacheConfiguration override) {
        
        if (override == null) {
            return base;
        }

        CascadeCacheConfiguration result = new CascadeCacheConfiguration()
                .setName(override.getName() != null ? override.getName() : base.getName())
                .setEnabled(override.isEnabled());

        // 合并通用配置
        mergeCommonConfig(base.getCommon(), override.getCommon(), result.getCommon());

        // 合并L1配置
        mergeL1Config(base.getL1(), override.getL1(), result.getL1());

        // 合并L2配置
        mergeL2Config(base.getL2(), override.getL2(), result.getL2());

        // 合并同步配置
        mergeSyncConfig(base.getSync(), override.getSync(), result.getSync());

        // 合并防护配置
        mergeProtectionConfig(base.getProtection(), override.getProtection(), result.getProtection());

        // 合并监控配置
        mergeMonitoringConfig(base.getMonitoring(), override.getMonitoring(), result.getMonitoring());

        return result;
    }

    private static void mergeCommonConfig(
            CascadeCacheConfiguration.CommonConfig base,
            CascadeCacheConfiguration.CommonConfig override,
            CascadeCacheConfiguration.CommonConfig result) {
        
        result.setMaximumSize(override.getMaximumSize() > 0 ? override.getMaximumSize() : base.getMaximumSize())
              .setExpireAfterWrite(override.getExpireAfterWrite() != null ? override.getExpireAfterWrite() : base.getExpireAfterWrite())
              .setExpireAfterAccess(override.getExpireAfterAccess() != null ? override.getExpireAfterAccess() : base.getExpireAfterAccess())
              .setRefreshAfterWrite(override.getRefreshAfterWrite() != null ? override.getRefreshAfterWrite() : base.getRefreshAfterWrite())
              .setRecordStats(override.isRecordStats() || base.isRecordStats())
              .setExecutor(override.getExecutor() != null ? override.getExecutor() : base.getExecutor());
    }

    private static void mergeL1Config(
            CascadeCacheConfiguration.L1Config base,
            CascadeCacheConfiguration.L1Config override,
            CascadeCacheConfiguration.L1Config result) {
        
        result.setEnabled(override.isEnabled() || base.isEnabled())
              .setMaximumSize(override.getMaximumSize() > 0 ? override.getMaximumSize() : base.getMaximumSize())
              .setExpireAfterWrite(override.getExpireAfterWrite() != null ? override.getExpireAfterWrite() : base.getExpireAfterWrite())
              .setExpireAfterAccess(override.getExpireAfterAccess() != null ? override.getExpireAfterAccess() : base.getExpireAfterAccess())
              .setRecordStats(override.isRecordStats() || base.isRecordStats())
              .setInitialCapacity(override.getInitialCapacity() > 0 ? override.getInitialCapacity() : base.getInitialCapacity())
              .setConcurrencyLevel(override.getConcurrencyLevel() > 0 ? override.getConcurrencyLevel() : base.getConcurrencyLevel())
              .setWeakKeys(override.isWeakKeys() || base.isWeakKeys())
              .setWeakValues(override.isWeakValues() || base.isWeakValues())
              .setSoftValues(override.isSoftValues() || base.isSoftValues());
    }

    private static void mergeL2Config(
            CascadeCacheConfiguration.L2Config base,
            CascadeCacheConfiguration.L2Config override,
            CascadeCacheConfiguration.L2Config result) {
        
        result.setEnabled(override.isEnabled() || base.isEnabled())
              .setKeyPrefix(override.getKeyPrefix() != null ? override.getKeyPrefix() : base.getKeyPrefix())
              .setDefaultTtl(override.getDefaultTtl() != null ? override.getDefaultTtl() : base.getDefaultTtl())
              .setRefreshAfterWrite(override.getRefreshAfterWrite() != null ? override.getRefreshAfterWrite() : base.getRefreshAfterWrite())
              .setEnableBatch(override.isEnableBatch() || base.isEnableBatch())
              .setBatchSize(override.getBatchSize() > 0 ? override.getBatchSize() : base.getBatchSize())
              .setSerializer(override.getSerializer() != null ? override.getSerializer() : base.getSerializer())
              .setTimeout(override.getTimeout() != null ? override.getTimeout() : base.getTimeout())
              .setRedissonClient(override.getRedissonClient() != null ? override.getRedissonClient() : base.getRedissonClient());
    }

    private static void mergeSyncConfig(
            CascadeCacheConfiguration.SyncConfig base,
            CascadeCacheConfiguration.SyncConfig override,
            CascadeCacheConfiguration.SyncConfig result) {
        
        result.setEnabled(override.isEnabled() || base.isEnabled())
              .setTopic(override.getTopic() != null ? override.getTopic() : base.getTopic())
              .setTimeout(override.getTimeout() != null ? override.getTimeout() : base.getTimeout())
              .setAsync(override.isAsync() || base.isAsync());
    }

    private static void mergeProtectionConfig(
            CascadeCacheConfiguration.ProtectionConfig base,
            CascadeCacheConfiguration.ProtectionConfig override,
            CascadeCacheConfiguration.ProtectionConfig result) {
        
        result.setEnabled(override.isEnabled() || base.isEnabled());
        
        // 合并布隆过滤器配置
        result.getBloomFilter()
              .setEnabled(override.getBloomFilter().isEnabled() || base.getBloomFilter().isEnabled())
              .setExpectedElements(override.getBloomFilter().getExpectedElements() > 0 ? 
                      override.getBloomFilter().getExpectedElements() : base.getBloomFilter().getExpectedElements())
              .setFalsePositiveRate(override.getBloomFilter().getFalsePositiveRate() > 0 ? 
                      override.getBloomFilter().getFalsePositiveRate() : base.getBloomFilter().getFalsePositiveRate())
              .setCustomFilter(override.getBloomFilter().getCustomFilter() != null ? 
                      override.getBloomFilter().getCustomFilter() : base.getBloomFilter().getCustomFilter());

        // 合并随机TTL配置
        result.getRandomTtl()
              .setEnabled(override.getRandomTtl().isEnabled() || base.getRandomTtl().isEnabled())
              .setBaseTtl(override.getRandomTtl().getBaseTtl() != null ? 
                      override.getRandomTtl().getBaseTtl() : base.getRandomTtl().getBaseTtl())
              .setJitterRange(override.getRandomTtl().getJitterRange() != null ? 
                      override.getRandomTtl().getJitterRange() : base.getRandomTtl().getJitterRange())
              .setJitterRatio(override.getRandomTtl().getJitterRatio() > 0 ? 
                      override.getRandomTtl().getJitterRatio() : base.getRandomTtl().getJitterRatio());

        // 合并分布式锁配置
        result.getDistributedLock()
              .setEnabled(override.getDistributedLock().isEnabled() || base.getDistributedLock().isEnabled())
              .setLockTimeout(override.getDistributedLock().getLockTimeout() != null ? 
                      override.getDistributedLock().getLockTimeout() : base.getDistributedLock().getLockTimeout())
              .setWaitTimeout(override.getDistributedLock().getWaitTimeout() != null ? 
                      override.getDistributedLock().getWaitTimeout() : base.getDistributedLock().getWaitTimeout())
              .setMaxRetries(override.getDistributedLock().getMaxRetries() > 0 ? 
                      override.getDistributedLock().getMaxRetries() : base.getDistributedLock().getMaxRetries())
              .setRetryDelay(override.getDistributedLock().getRetryDelay() != null ? 
                      override.getDistributedLock().getRetryDelay() : base.getDistributedLock().getRetryDelay())
              .setKeyPrefix(override.getDistributedLock().getKeyPrefix() != null ? 
                      override.getDistributedLock().getKeyPrefix() : base.getDistributedLock().getKeyPrefix());
    }

    private static void mergeMonitoringConfig(
            CascadeCacheConfiguration.MonitoringConfig base,
            CascadeCacheConfiguration.MonitoringConfig override,
            CascadeCacheConfiguration.MonitoringConfig result) {
        
        result.setEnabled(override.isEnabled() || base.isEnabled())
              .setEnableMetrics(override.isEnableMetrics() || base.isEnableMetrics())
              .setEnableTracing(override.isEnableTracing() || base.isEnableTracing())
              .setMetricsInterval(override.getMetricsInterval() != null ? 
                      override.getMetricsInterval() : base.getMetricsInterval())
              .setEventListenerClass(override.getEventListenerClass() != null ? 
                      override.getEventListenerClass() : base.getEventListenerClass());
    }
}