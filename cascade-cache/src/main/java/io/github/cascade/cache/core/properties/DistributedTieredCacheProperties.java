package io.github.cascade.cache.core.properties;

import lombok.Data;

import java.time.Duration;

/**
 * 分布式分层缓存配置属性
 *
 * @author cascade
 */
@Data
public class DistributedTieredCacheProperties {

    /**
     * 是否启用同步
     */
    private boolean enableSync = true;

    /**
     * 是否启用防护机制
     */
    private boolean enableProtection = true;

    /**
     * L2缓存刷新间隔
     */
    private Duration l2RefreshInterval;

    /**
     * 同步配置
     */
    private SyncConfig sync = new SyncConfig();

    /**
     * 防护配置
     */
    private ProtectionConfig protection = new ProtectionConfig();

    /**
     * 加载配置
     */
    private LoadingConfig loading = new LoadingConfig();

    /**
     * 监控配置
     */
    private MonitoringConfig monitoring = new MonitoringConfig();


    /**
     * 同步配置
     */
    @Data
    public static class SyncConfig {
        private String nodeId;
        private String topic = "cascade:cache:sync";
        private Duration timeout = Duration.ofSeconds(5);

    }

    /**
     * 防护配置
     */
    @Data
    public static class ProtectionConfig {
        private boolean enableBloomFilter = true;
        private boolean enableRandomTtl = true;
        private boolean enableDistributedLock = true;
        private long bloomFilterExpectedElements = 100000;
        private double bloomFilterFalsePositiveRate = 0.01;
        private double randomTtlJitterRatio = 0.1;

    }


    /**
     * 加载配置
     * LoadingConfig类用于配置加载相关的参数设置
     * 该类包含了超时时间、批量加载开关、最大重试次数和重试延迟等配置项
     */
    @Data
    public static class LoadingConfig {
        /**
         * 超时时间配置，默认30秒
         * 用于控制操作的最大等待时间
         */
        private Duration timeout = Duration.ofSeconds(30);

        /**
         * 批量加载开关，默认开启
         * 控制是否启用批量数据加载功能
         */
        private boolean enableBatchLoading = true;

        /**
         * 最大重试次数，默认3次
         * 设置操作失败时的最大重试次数
         */
        private int maxRetries = 3;

        /**
         * 重试延迟时间，默认100毫秒
         * 设置每次重试之间的等待时间间隔
         */
        private Duration retryDelay = Duration.ofMillis(100);

    }


    /**
     * 监控配置
     */
    @Data
    public static class MonitoringConfig {
        private boolean enableMetrics = true;
        private boolean enableTracing = false;
        private Duration metricsInterval = Duration.ofSeconds(60);

    }
}