package io.github.cascade.cache.annotation;

import java.lang.annotation.*;

/**
 * Cascade缓存注解
 * 用于标记方法需要缓存结果
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CascadeCacheable {

    /**
     * 缓存名称
     */
    String[] value() default {};

    /**
     * 缓存名称（别名）
     */
    String[] cacheNames() default {};

    /**
     * 缓存键表达式
     */
    String key() default "";

    /**
     * 缓存键生成器
     */
    String keyGenerator() default "";

    /**
     * 缓存条件表达式
     */
    String condition() default "";

    /**
     * 排除条件表达式
     */
    String unless() default "";

    /**
     * 是否同步执行
     */
    boolean sync() default false;

    /**
     * TTL（生存时间）
     */
    String ttl() default "";

    /**
     * 是否启用L1缓存
     */
    boolean enableL1() default true;

    /**
     * 是否启用L2缓存
     */
    boolean enableL2() default true;

    /**
     * 是否启用布隆过滤器
     */
    boolean enableBloomFilter() default false;

    /**
     * 缓存加载器
     */
    String loader() default "";

    /**
     * 异步加载
     */
    boolean asyncLoad() default false;

    /**
     * 批量加载
     */
    boolean batchLoad() default false;

    /**
     * 预刷新时间比例（0.0-1.0）
     */
    double refreshAheadFactor() default 0.0;
    
    // ==================== 高级配置功能 ====================
    
    /**
     * L1缓存最大容量
     */
    long l1MaximumSize() default -1;
    
    /**
     * L1缓存初始容量
     */
    int l1InitialCapacity() default -1;
    
    /**
     * L1缓存并发级别
     */
    int l1ConcurrencyLevel() default -1;
    
    /**
     * L1缓存访问后过期时间
     */
    String l1ExpireAfterAccess() default "";
    
    /**
     * L1缓存写入后过期时间
     */
    String l1ExpireAfterWrite() default "";
    
    /**
     * L2缓存键前缀
     */
    String l2KeyPrefix() default "";
    
    /**
     * L2缓存默认TTL
     */
    String l2DefaultTtl() default "";
    
    /**
     * L2缓存操作超时时间
     */
    String l2Timeout() default "";
    
    /**
     * L2缓存序列化器类型
     */
    String l2Serializer() default "";
    
    /**
     * L2缓存是否启用批量操作
     */
    boolean l2EnableBatch() default true;
    
    /**
     * L2缓存批量操作大小
     */
    int l2BatchSize() default -1;
    
    // ==================== 防护功能 ====================
    
    /**
     * 布隆过滤器期望元素数量
     */
    long bloomExpectedElements() default -1;
    
    /**
     * 布隆过滤器误判率
     */
    double bloomFalsePositiveRate() default -1.0;
    
    /**
     * 自定义布隆过滤器Bean名称
     */
    String bloomFilterBean() default "";
    
    /**
     * 是否启用分布式锁防护
     */
    boolean enableDistributedLock() default false;
    
    /**
     * 分布式锁超时时间
     */
    String lockTimeout() default "";
    
    /**
     * 分布式锁等待超时时间
     */
    String lockWaitTimeout() default "";
    
    /**
     * 分布式锁最大重试次数
     */
    int lockMaxRetries() default -1;
    
    /**
     * 分布式锁重试延迟
     */
    String lockRetryDelay() default "";
    
    /**
     * 分布式锁键前缀
     */
    String lockKeyPrefix() default "";
    
    // ==================== 随机TTL防护 ====================
    
    /**
     * 是否启用随机TTL
     */
    boolean enableRandomTtl() default false;
    
    /**
     * 随机TTL基础时间
     */
    String randomTtlBase() default "";
    
    /**
     * 随机TTL抖动范围
     */
    String randomTtlJitterRange() default "";
    
    /**
     * 随机TTL抖动比例
     */
    double randomTtlJitterRatio() default -1.0;
    
    // ==================== 同步配置 ====================
    
    /**
     * 是否启用分布式同步
     */
    boolean enableSync() default false;
    
    /**
     * 同步主题名称
     */
    String syncTopic() default "";
    
    /**
     * 同步超时时间
     */
    String syncTimeout() default "";
    
    /**
     * 是否异步同步
     */
    boolean asyncSync() default true;
    
    // ==================== 监控配置 ====================
    
    /**
     * 是否启用监控
     */
    boolean enableMonitoring() default true;
    
    /**
     * 是否启用指标导出
     */
    boolean enableMetrics() default true;
    
    /**
     * 是否启用链路追踪
     */
    boolean enableTracing() default false;
    
    /**
     * 指标导出间隔
     */
    String metricsInterval() default "";
    
    /**
     * 自定义事件监听器Bean名称
     */
    String eventListenerBean() default "";
    
    // ==================== 刷新配置 ====================
    
    /**
     * 是否启用自动刷新
     */
    boolean enableAutoRefresh() default false;
    
    /**
     * 刷新间隔
     */
    String refreshInterval() default "";
    
    /**
     * 最小刷新间隔
     */
    String minRefreshInterval() default "";
    
    /**
     * 最大刷新间隔
     */
    String maxRefreshInterval() default "";
    
    /**
     * 刷新线程池大小
     */
    int refreshThreadPoolSize() default -1;
    
    /**
     * 刷新队列容量
     */
    int refreshQueueCapacity() default -1;
    
    /**
     * 是否允许并发刷新
     */
    boolean allowConcurrentRefresh() default false;
    
    /**
     * 刷新超时时间
     */
    String refreshTimeout() default "";
    
    /**
     * 刷新失败重试次数
     */
    int refreshMaxRetries() default -1;
    
    /**
     * 刷新重试间隔
     */
    String refreshRetryInterval() default "";
    
    /**
     * 是否启用预加载
     */
    boolean enablePreload() default false;
    
    /**
     * 预加载批处理大小
     */
    int preloadBatchSize() default -1;
    
    /**
     * 预加载并发度
     */
    int preloadConcurrency() default -1;
    
    // ==================== 执行器配置 ====================
    
    /**
     * 自定义执行器Bean名称
     */
    String executorBean() default "";
    
    /**
     * 自定义Redis客户端Bean名称
     */
    String redisClientBean() default "";
}