package io.github.cascade.cache.config.unified;

import io.github.cascade.cache.protection.CascadeBloomFilter;
import lombok.Data;
import lombok.experimental.Accessors;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.concurrent.Executor;

/**
 * Cascade缓存统一配置类
 * 整合所有缓存相关配置，提供统一的配置入口
 *
 * @author cascade
 */
@Data
@Accessors(chain = true)
public class CascadeCacheConfiguration {

    /**
     * 缓存名称
     */
    private String name;

    /**
     * 是否启用缓存
     */
    private boolean enabled = true;

    /**
     * 通用配置
     */
    private CommonConfig common = new CommonConfig();

    /**
     * L1缓存配置
     */
    private L1Config l1 = new L1Config();

    /**
     * L2缓存配置
     */
    private L2Config l2 = new L2Config();

    /**
     * 同步配置
     */
    private SyncConfig sync = new SyncConfig();

    /**
     * 防护配置
     */
    private ProtectionConfig protection = new ProtectionConfig();

    /**
     * 监控配置
     */
    private MonitoringConfig monitoring = new MonitoringConfig();

    /**
     * 通用配置
     */
    @Data
    @Accessors(chain = true)
    public static class CommonConfig {
        /**
         * 缓存最大容量（默认配置）
         */
        private long maximumSize = 10000;

        /**
         * 写入后过期时间
         */
        private Duration expireAfterWrite;

        /**
         * 访问后过期时间
         */
        private Duration expireAfterAccess;

        /**
         * 写入后刷新时间
         */
        private Duration refreshAfterWrite;

        /**
         * 是否记录统计信息
         */
        private boolean recordStats = false;

        /**
         * 异步操作执行器（运行时设置）
         */
        private Executor executor;
    }

    /**
     * L1缓存配置
     */
    @Data
    @Accessors(chain = true)
    public static class L1Config {
        /**
         * 是否启用L1缓存
         */
        private boolean enabled = true;

        /**
         * L1缓存最大容量
         */
        private long maximumSize = 10000;

        /**
         * 写入后过期时间（null则使用通用配置）
         */
        private Duration expireAfterWrite;

        /**
         * 访问后过期时间（null则使用通用配置）
         */
        private Duration expireAfterAccess;

        /**
         * 是否记录统计信息
         */
        private boolean recordStats = false;

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
    @Accessors(chain = true)
    public static class L2Config {
        /**
         * 是否启用L2缓存
         */
        private boolean enabled = false;

        /**
         * Redis键前缀
         */
        private String keyPrefix = "cascade:";

        /**
         * 默认TTL
         */
        private Duration defaultTtl = Duration.ofHours(1);

        /**
         * 自动刷新时间
         */
        private Duration refreshAfterWrite;

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
         * 操作超时时间
         */
        private Duration timeout = Duration.ofSeconds(5);

        /**
         * Redis客户端（运行时设置）
         */
        private RedissonClient redissonClient;

        /**
         * Redis连接配置
         */
        private RedisConfig redis = new RedisConfig();

        /**
         * Redis连接配置
         */
        @Data
        @Accessors(chain = true)
        public static class RedisConfig {
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
             * 连接超时时间
             */
            private Duration timeout = Duration.ofSeconds(5);

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
    @Accessors(chain = true)
    public static class SyncConfig {
        /**
         * 是否启用同步
         */
        private boolean enabled = false;

        /**
         * 同步主题
         */
        private String topic = "cascade:cache:sync";

        /**
         * 同步超时时间
         */
        private Duration timeout = Duration.ofSeconds(5);

        /**
         * 是否异步同步
         */
        private boolean async = true;
    }

    /**
     * 防护配置
     */
    @Data
    @Accessors(chain = true)
    public static class ProtectionConfig {
        /**
         * 是否启用防护机制
         */
        private boolean enabled = false;

        /**
         * 布隆过滤器配置
         */
        private BloomFilterConfig bloomFilter = new BloomFilterConfig();

        /**
         * 随机TTL配置
         */
        private RandomTtlConfig randomTtl = new RandomTtlConfig();

        /**
         * 分布式锁配置
         */
        private DistributedLockConfig distributedLock = new DistributedLockConfig();

        /**
         * 布隆过滤器配置
         */
        @Data
        @Accessors(chain = true)
        public static class BloomFilterConfig {
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

            /**
             * 自定义布隆过滤器实例（运行时设置）
             */
            private CascadeBloomFilter customFilter;
        }

        /**
         * 随机TTL配置
         */
        @Data
        @Accessors(chain = true)
        public static class RandomTtlConfig {
            /**
             * 是否启用随机TTL
             */
            private boolean enabled = true;

            /**
             * 基础TTL
             */
            private Duration baseTtl = Duration.ofMinutes(30);

            /**
             * 抖动范围
             */
            private Duration jitterRange = Duration.ofMinutes(5);

            /**
             * 抖动比例（相对于基础TTL）
             */
            private double jitterRatio = 0.1;
        }

        /**
         * 分布式锁配置
         */
        @Data
        @Accessors(chain = true)
        public static class DistributedLockConfig {
            /**
             * 是否启用分布式锁
             */
            private boolean enabled = true;

            /**
             * 锁超时时间
             */
            private Duration lockTimeout = Duration.ofSeconds(30);

            /**
             * 等待超时时间
             */
            private Duration waitTimeout = Duration.ofSeconds(10);

            /**
             * 最大重试次数
             */
            private int maxRetries = 3;

            /**
             * 重试延迟
             */
            private Duration retryDelay = Duration.ofMillis(100);

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
    @Accessors(chain = true)
    public static class MonitoringConfig {
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
         * 指标导出间隔
         */
        private Duration metricsInterval = Duration.ofMinutes(1);

        /**
         * 事件监听器类名
         */
        private String eventListenerClass;
    }
}