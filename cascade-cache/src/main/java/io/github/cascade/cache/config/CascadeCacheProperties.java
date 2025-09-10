package io.github.cascade.cache.config;

import lombok.Data;
import lombok.experimental.Accessors;
import org.springframework.boot.context.properties.ConfigurationProperties;


/**
 * Cascade缓存配置属性
 * 统一配置类：既用于Spring Boot属性绑定，也提供运行时配置方法
 *
 * @author cascade
 */
@Data
@ConfigurationProperties(prefix = "cascade")
public class CascadeCacheProperties {

    /**
     * 是否启用Cascade框架
     */
    private boolean enabled = true;

    /**
     * 默认缓存名称
     */
    private String defaultCacheName = "default";

    /**
     * L1缓存配置
     */
    private L1Properties l1 = new L1Properties();

    /**
     * L2缓存配置
     */
    private L2Properties l2 = new L2Properties();

    /**
     * 同步配置
     */
    private SyncProperties sync = new SyncProperties();

    /**
     * 刷新调度器配置
     */
    private RefreshConfig refresh = new RefreshConfig();

    /**
     * CacheLoader配置
     * -- GETTER --
     * 获取CacheLoader配置
     */
    private LoaderProperties loader = new LoaderProperties();

    /**
     * L1缓存配置
     */
    @Data
    public static class L1Properties {
        /**
         * 是否启用L1缓存
         */
        private boolean enabled = true;

        /**
         * L1缓存最大容量
         */
        private long maximumSize = 10000;

        /**
         * 写入后过期时间（秒）
         */
        private long expireAfterWriteSeconds = 3600;

        /**
         * 访问后过期时间（秒）
         */
        private long expireAfterAccessSeconds = -1;

        /**
         * 是否记录统计信息
         */
        private boolean recordStats = true;

        /**
         * 初始容量
         */
        private int initialCapacity = 16;

        /**
         * 并发级别
         */
        private int concurrencyLevel = 4;
    }

    /**
     * L2缓存配置
     */
    @Data
    public static class L2Properties {
        /**
         * 是否启用L2缓存
         */
        private boolean enabled = false;

        /**
         * Redis键前缀
         */
        private String keyPrefix = "cascade:";

        /**
         * 默认TTL（秒）
         */
        private long defaultTtlSeconds = 7200;

        /**
         * 是否启用批量操作
         */
        private boolean enableBatch = true;

        /**
         * 批量操作大小
         */
        private int batchSize = 100;

        /**
         * 序列化器类型
         */
        private String serializer = "json";

        /**
         * 操作超时时间（秒）
         */
        private long timeoutSeconds = 5;
    }


    /**
     * 刷新调度器配置
     */
    @Data
    @Accessors(chain = true)
    public static class RefreshConfig {
        /**
         * 是否启用自动刷新
         */
        private boolean enabled = true;

        /**
         * 默认刷新间隔（秒）
         */
        private long defaultRefreshIntervalSeconds = 10; // 10分钟

        /**
         * 最小刷新间隔（秒）
         */
        private long minRefreshIntervalSeconds = 60; // 1分钟

        /**
         * 最大刷新间隔（秒）
         */
        private long maxRefreshIntervalSeconds = 1800; // 1小时

        /**
         * 刷新线程池大小
         */
        private int threadPoolSize = 2;

        /**
         * 刷新队列容量
         */
        private int queueCapacity = 1000;

        /**
         * 是否允许并发刷新
         */
        private boolean allowConcurrentRefresh = false;

        /**
         * 刷新超时时间（秒）
         */
        private long refreshTimeoutSeconds = 30;

        /**
         * 失败重试次数
         */
        private int maxRetries = 3;

        /**
         * 重试间隔（秒）
         */
        private long retryIntervalSeconds = 5;

        /**
         * 是否在初始化时启动调度器
         */
        private boolean startOnInit = true;

        /**
         * 调度器关闭超时时间（秒）
         */
        private long shutdownTimeoutSeconds = 10;
    }

    /**
     * CacheLoader配置
     */
    @Data
    public static class LoaderProperties {
        /**
         * 是否启用自动发现
         */
        private boolean autoDiscover = true;

        /**
         * 是否启用缓存统计
         */
        private boolean enableStats = true;

        /**
         * 加载超时时间（秒）
         */
        private long timeoutSeconds = 30;
    }

    // ==================== 运行时配置方法 ====================
    // 这些方法使CascadeCacheProperties能够替代CacheConfiguration接口

    /**
     * 是否启用L1缓存
     */
    public boolean isL1Enabled() {
        return l1.enabled;
    }

    /**
     * L1缓存最大条目数
     */
    public long getL1MaxSize() {
        return l1.maximumSize;
    }

    /**
     * L1缓存写后过期时间（秒）
     */
    public long getL1ExpireAfterWriteSeconds() {
        return l1.expireAfterWriteSeconds;
    }

    /**
     * L1缓存访问后过期时间（秒）
     */
    public long getL1ExpireAfterAccessSeconds() {
        return l1.expireAfterAccessSeconds;
    }

    /**
     * 是否启用L2缓存
     */
    public boolean isL2Enabled() {
        return l2.enabled;
    }

    /**
     * L2缓存默认TTL（秒）
     */
    public long getL2DefaultTtlSeconds() {
        return l2.defaultTtlSeconds;
    }

    /**
     * L2缓存键前缀
     */
    public String getL2KeyPrefix() {
        return l2.keyPrefix;
    }

    /**
     * 是否启用分布式同步
     */
    public boolean isSyncEnabled() {
        return sync.isEnabled();
    }

    /**
     * 获取同步配置
     */
    public SyncProperties getSyncConfig() {
        return sync;
    }

    /**
     * 同步主题名称
     */
    public String getSyncTopic() {
        return sync.getTopicPrefix();
    }

    /**
     * 是否启用定时刷新
     */
    public boolean isRefreshEnabled() {
        return refresh.enabled;
    }

    /**
     * 刷新间隔（秒）
     */
    public long getRefreshIntervalSeconds() {
        return refresh.defaultRefreshIntervalSeconds;
    }

    /**
     * 是否并行刷新
     */
    public boolean isParallelRefresh() {
        return refresh.allowConcurrentRefresh;
    }

    /**
     * 是否启用统计
     */
    public boolean isStatsEnabled() {
        return l1.recordStats;
    }

    // ==================== 便捷构建方法 ====================

    /**
     * 创建默认配置
     */
    public static CascadeCacheProperties defaults() {
        return new CascadeCacheProperties();
    }

    /**
     * 仅L1缓存配置
     */
    public static CascadeCacheProperties l1Only() {
        CascadeCacheProperties config = new CascadeCacheProperties();
        config.l2.enabled = false;
        config.sync.disable();
        return config;
    }

    /**
     * 仅L2缓存配置
     */
    public static CascadeCacheProperties l2Only() {
        CascadeCacheProperties config = new CascadeCacheProperties();
        config.l1.enabled = false;
        config.l2.enabled = true;
        config.sync.disable();
        return config;
    }

    /**
     * 多级缓存配置
     */
    public static CascadeCacheProperties tiered() {
        CascadeCacheProperties config = new CascadeCacheProperties();
        config.l1.enabled = true;
        config.l2.enabled = true;
        config.sync.enable();
        return config;
    }
}