package io.github.cascade.autoconfigure;

import io.github.cascade.cache.config.CachePropertiesProvider;
import io.github.cascade.cache.config.CascadeCacheConfiguration;
import lombok.Data;
import lombok.experimental.Accessors;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Cascade缓存配置属性
 * 分层配置版本，与 CascadeCacheConfiguration 保持结构一致
 *
 * @author cascade
 */
@Data
@ConfigurationProperties(prefix = "cascade")
public class CascadeCacheProperties implements CachePropertiesProvider {

    /**
     * 是否启用Cascade框架
     */
    private boolean enabled = true;

    /**
     * 默认缓存名称
     */
    private String defaultCacheName = "default";

    /**
     * 通用配置
     */
    private CommonProperties common = new CommonProperties();

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
     * 防护配置
     */
    private ProtectionProperties protection = new ProtectionProperties();

    /**
     * 监控配置
     */
    private MonitoringProperties monitoring = new MonitoringProperties();


    /**
     * 刷新调度器配置
     */
    private RefreshConfig refresh = new RefreshConfig();

    /**
     * 通用配置
     */
    @Data
    public static class CommonProperties {
        /**
         * 缓存最大容量（默认配置）
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
         * 写入后刷新时间（秒）
         */
        private long refreshAfterWriteSeconds = -1;

        /**
         * 是否记录统计信息
         */
        private boolean recordStats = true;
    }

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

        /**
         * 是否使用弱引用键
         */
        private boolean weakKeys = false;

        /**
         * 是否使用弱引用值
         */
        private boolean weakValues = false;

        /**
         * 是否使用软引用值
         */
        private boolean softValues = false;
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
         * 自动刷新时间（秒）
         */
        private long refreshAfterWriteSeconds = -1;

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

        /**
         * Redis连接配置
         */
        private RedisProperties redis = new RedisProperties();

        /**
         * Redis连接配置
         */
        @Data
        public static class RedisProperties {
            /**
             * Redis主机
             */
            private String host = "localhost";

            /**
             * Redis端口
             */
            private int port = 6379;

            /**
             * Redis密码
             */
            private String password;

            /**
             * Redis数据库
             */
            private int database = 0;

            /**
             * 连接超时时间（秒）
             */
            private long timeoutSeconds = 5;

            /**
             * 连接池最大连接数
             */
            private int maxTotal = 8;

            /**
             * 连接池最大空闲连接数
             */
            private int maxIdle = 8;

            /**
             * 连接池最小空闲连接数
             */
            private int minIdle = 0;
        }
    }

    /**
     * 同步配置
     */
    @Data
    public static class SyncProperties {
        /**
         * 是否启用同步
         */
        private boolean enabled = false;

        /**
         * 同步主题
         */
        private String topic = "cascade:cache:sync";

        /**
         * 同步超时时间（秒）
         */
        private long timeoutSeconds = 5;

        /**
         * 是否异步同步
         */
        private boolean async = true;
    }

    /**
     * 防护配置
     */
    @Data
    public static class ProtectionProperties {
        /**
         * 是否启用防护机制
         */
        private boolean enabled = false;

        /**
         * 布隆过滤器配置
         */
        private BloomFilterProperties bloomFilter = new BloomFilterProperties();

        /**
         * 随机TTL配置
         */
        private RandomTtlProperties randomTtl = new RandomTtlProperties();

        /**
         * 分布式锁配置
         */
        private DistributedLockProperties distributedLock = new DistributedLockProperties();

        /**
         * 布隆过滤器配置
         */
        @Data
        public static class BloomFilterProperties {
            /**
             * 是否启用布隆过滤器
             */
            private boolean enabled = true;

            /**
             * 预期元素数量
             */
            private long expectedElements = 100000;

            /**
             * 误判率
             */
            private double falsePositiveRate = 0.01;
        }

        /**
         * 随机TTL配置
         */
        @Data
        public static class RandomTtlProperties {
            /**
             * 是否启用随机TTL
             */
            private boolean enabled = true;

            /**
             * 基础TTL（秒）
             */
            private long baseTtlSeconds = 1800;

            /**
             * 抖动范围（秒）
             */
            private long jitterRangeSeconds = 300;

            /**
             * 抖动比例（相对于基础TTL）
             */
            private double jitterRatio = 0.1;
        }

        /**
         * 分布式锁配置
         */
        @Data
        public static class DistributedLockProperties {
            /**
             * 是否启用分布式锁
             */
            private boolean enabled = true;

            /**
             * 锁超时时间（秒）
             */
            private long lockTimeoutSeconds = 30;

            /**
             * 等待超时时间（秒）
             */
            private long waitTimeoutSeconds = 10;

            /**
             * 最大重试次数
             */
            private int maxRetries = 3;

            /**
             * 重试延迟（毫秒）
             */
            private long retryDelayMillis = 100;

            /**
             * 锁键前缀
             */
            private String keyPrefix = "cascade:lock:";
        }
    }

    /**
     * 监控配置
     */
    @Data
    public static class MonitoringProperties {
        /**
         * 是否启用监控
         */
        private boolean enabled = true;

        /**
         * 是否启用指标导出
         */
        private boolean enableMetrics = true;

        /**
         * 是否启用链路追踪
         */
        private boolean enableTracing = false;

        /**
         * 指标导出间隔（秒）
         */
        private long metricsIntervalSeconds = 60;

        /**
         * 事件监听器类名
         */
        private String eventListenerClass;
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
        private boolean enabled = false;

        /**
         * 默认刷新间隔
         */
        private Duration defaultRefreshInterval = Duration.ofMinutes(10);

        /**
         * 最小刷新间隔
         */
        private Duration minRefreshInterval = Duration.ofMinutes(1);

        /**
         * 最大刷新间隔
         */
        private Duration maxRefreshInterval = Duration.ofHours(1);

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
         * 刷新超时时间
         */
        private Duration refreshTimeout = Duration.ofSeconds(30);

        /**
         * 失败重试次数
         */
        private int maxRetries = 3;

        /**
         * 重试间隔
         */
        private Duration retryInterval = Duration.ofSeconds(5);

        /**
         * 是否在初始化时启动调度器
         */
        private boolean startOnInit = true;

        /**
         * 调度器关闭超时时间
         */
        private Duration shutdownTimeout = Duration.ofSeconds(10);

        /**
         * 预加载配置
         */
        private PreloadConfig preload = new PreloadConfig();

        /**
         * 预加载配置
         */
        @Data
        @Accessors(chain = true)
        public static class PreloadConfig {
            /**
             * 是否启用预加载
             */
            private boolean enabled = false;

            /**
             * 预加载触发阈值（秒数）
             */
            private long preloadThresholdSeconds = 60;

            /**
             * 预加载批处理大小
             */
            private int batchSize = 50;

            /**
             * 预加载线程数
             */
            private int concurrency = 2;
        }
    }

    /**
     * 转换为运行时配置对象
     * 统一配置转换逻辑，支持完整的分层配置转换
     */
    @Override
    public CascadeCacheConfiguration toCascadeCacheConfiguration(String cacheName) {
        CascadeCacheConfiguration config = new CascadeCacheConfiguration();

        // 基础配置
        config.setName(cacheName != null ? cacheName : defaultCacheName)
                .setEnabled(enabled);

        // 转换通用配置
        convertCommonConfig(config.getCommon());

        // 转换L1配置
        convertL1Config(config.getL1());

        // 转换L2配置
        convertL2Config(config.getL2());

        // 转换同步配置
        convertSyncConfig(config.getSync());

        // 转换防护配置
        convertProtectionConfig(config.getProtection());

        // 转换监控配置
        convertMonitoringConfig(config.getMonitoring());

        // 转换刷新配置
        convertRefreshConfig(config.getRefresh());

        return config;
    }

    /**
     * 转换刷新配置
     */
    private void convertRefreshConfig(CascadeCacheConfiguration.RefreshConfig target) {
        // 基本配置
        target.setEnabled(refresh.enabled)
                .setDefaultRefreshInterval(refresh.defaultRefreshInterval)
                .setMinRefreshInterval(refresh.minRefreshInterval)
                .setMaxRefreshInterval(refresh.maxRefreshInterval)
                .setThreadPoolSize(refresh.threadPoolSize)
                .setQueueCapacity(refresh.queueCapacity)
                .setAllowConcurrentRefresh(refresh.allowConcurrentRefresh)
                .setRefreshTimeout(refresh.refreshTimeout)
                .setMaxRetries(refresh.maxRetries)
                .setRetryInterval(refresh.retryInterval)
                .setStartOnInit(refresh.startOnInit)
                .setShutdownTimeout(refresh.shutdownTimeout);

        // 转换预加载配置
        convertPreloadConfig(target.getPreload());
    }

    /**
     * 转换预加载配置
     */
    private void convertPreloadConfig(CascadeCacheConfiguration.RefreshConfig.PreloadConfig target) {
        target.setEnabled(refresh.preload.enabled)
                .setPreloadThresholdSeconds(refresh.preload.preloadThresholdSeconds)
                .setBatchSize(refresh.preload.batchSize)
                .setConcurrency(refresh.preload.concurrency);
    }

    /**
     * 转换通用配置
     */
    private void convertCommonConfig(CascadeCacheConfiguration.CommonConfig target) {
        target.setMaximumSize(common.maximumSize)
                .setRecordStats(common.recordStats);

        if (common.expireAfterWriteSeconds > 0) {
            target.setExpireAfterWrite(Duration.ofSeconds(common.expireAfterWriteSeconds));
        }
        if (common.expireAfterAccessSeconds > 0) {
            target.setExpireAfterAccess(Duration.ofSeconds(common.expireAfterAccessSeconds));
        }
        if (common.refreshAfterWriteSeconds > 0) {
            target.setRefreshAfterWrite(Duration.ofSeconds(common.refreshAfterWriteSeconds));
        }
        target.setRecordStats(common.recordStats);
    }

    /**
     * 转换L1配置
     */
    private void convertL1Config(CascadeCacheConfiguration.L1Config target) {
        target.setEnabled(l1.enabled)
                .setMaximumSize(l1.maximumSize)
                .setRecordStats(l1.recordStats)
                .setInitialCapacity(l1.initialCapacity)
                .setConcurrencyLevel(l1.concurrencyLevel)
                .setWeakKeys(l1.weakKeys)
                .setWeakValues(l1.weakValues)
                .setSoftValues(l1.softValues);

        if (l1.expireAfterWriteSeconds > 0) {
            target.setExpireAfterWrite(Duration.ofSeconds(l1.expireAfterWriteSeconds));
        }
        if (l1.expireAfterAccessSeconds > 0) {
            target.setExpireAfterAccess(Duration.ofSeconds(l1.expireAfterAccessSeconds));
        }
    }

    /**
     * 转换L2配置
     */
    private void convertL2Config(CascadeCacheConfiguration.L2Config target) {
        target.setEnabled(l2.enabled)
                .setKeyPrefix(l2.keyPrefix)
                .setEnableBatch(l2.enableBatch)
                .setBatchSize(l2.batchSize)
                .setSerializer(l2.serializer);

        if (l2.defaultTtlSeconds > 0) {
            target.setDefaultTtl(Duration.ofSeconds(l2.defaultTtlSeconds));
        }
        if (l2.refreshAfterWriteSeconds > 0) {
            target.setRefreshAfterWrite(Duration.ofSeconds(l2.refreshAfterWriteSeconds));
        }
        if (l2.timeoutSeconds > 0) {
            target.setTimeout(Duration.ofSeconds(l2.timeoutSeconds));
        }

        // 转换Redis配置
        convertRedisConfig(target.getRedis());
    }

    /**
     * 转换Redis配置
     */
    private void convertRedisConfig(CascadeCacheConfiguration.L2Config.RedisConfig target) {
        target.setHost(l2.redis.host)
                .setPort(l2.redis.port)
                .setPassword(l2.redis.password)
                .setDatabase(l2.redis.database)
                .setMaxTotal(l2.redis.maxTotal)
                .setMaxIdle(l2.redis.maxIdle)
                .setMinIdle(l2.redis.minIdle);

        if (l2.redis.timeoutSeconds > 0) {
            target.setTimeout(Duration.ofSeconds(l2.redis.timeoutSeconds));
        }
    }

    /**
     * 转换同步配置
     */
    private void convertSyncConfig(CascadeCacheConfiguration.SyncConfig target) {
        target.setEnabled(sync.enabled)
                .setTopic(sync.topic)
                .setAsync(sync.async);

        if (sync.timeoutSeconds > 0) {
            target.setTimeout(Duration.ofSeconds(sync.timeoutSeconds));
        }
    }

    /**
     * 转换防护配置
     */
    private void convertProtectionConfig(CascadeCacheConfiguration.ProtectionConfig target) {
        target.setEnabled(protection.enabled);

        // 布隆过滤器配置
        target.getBloomFilter()
                .setEnabled(protection.bloomFilter.enabled)
                .setExpectedElements(protection.bloomFilter.expectedElements)
                .setFalsePositiveRate(protection.bloomFilter.falsePositiveRate);

        // 随机TTL配置
        target.getRandomTtl()
                .setEnabled(protection.randomTtl.enabled)
                .setJitterRatio(protection.randomTtl.jitterRatio);

        if (protection.randomTtl.baseTtlSeconds > 0) {
            target.getRandomTtl().setBaseTtl(Duration.ofSeconds(protection.randomTtl.baseTtlSeconds));
        }
        if (protection.randomTtl.jitterRangeSeconds > 0) {
            target.getRandomTtl().setJitterRange(Duration.ofSeconds(protection.randomTtl.jitterRangeSeconds));
        }

        // 分布式锁配置
        target.getDistributedLock()
                .setEnabled(protection.distributedLock.enabled)
                .setMaxRetries(protection.distributedLock.maxRetries)
                .setKeyPrefix(protection.distributedLock.keyPrefix);

        if (protection.distributedLock.lockTimeoutSeconds > 0) {
            target.getDistributedLock().setLockTimeout(Duration.ofSeconds(protection.distributedLock.lockTimeoutSeconds));
        }
        if (protection.distributedLock.waitTimeoutSeconds > 0) {
            target.getDistributedLock().setWaitTimeout(Duration.ofSeconds(protection.distributedLock.waitTimeoutSeconds));
        }
        if (protection.distributedLock.retryDelayMillis > 0) {
            target.getDistributedLock().setRetryDelay(Duration.ofMillis(protection.distributedLock.retryDelayMillis));
        }
    }

    /**
     * 转换监控配置
     */
    private void convertMonitoringConfig(CascadeCacheConfiguration.MonitoringConfig target) {
        target.setEnabled(monitoring.enabled)
                .setEnableMetrics(monitoring.enableMetrics)
                .setEnableTracing(monitoring.enableTracing)
                .setEventListenerClass(monitoring.eventListenerClass);

        if (monitoring.metricsIntervalSeconds > 0) {
            target.setMetricsInterval(Duration.ofSeconds(monitoring.metricsIntervalSeconds));
        }
    }

    /**
     * 检查是否启用
     */
    @Override
    public boolean isEnabled() {
        return this.enabled;
    }
}