package io.github.cascade.autoconfigure;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cascade缓存配置属性
 * 简化版本，用于Spring Boot自动配置
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
     * 是否启用Redis多级缓存
     */
    private boolean enableRedis = false;

    /**
     * 是否启用防护机制
     */
    private boolean enableProtection = false;

    /**
     * 是否启用同步
     */
    private boolean enableSync = false;

    /**
     * 缓存默认大小
     */
    private long defaultSize = 10000;

    /**
     * L1缓存过期时间（秒）
     */
    private long l1ExpireAfterWriteSeconds = 3600;

    /**
     * L2缓存过期时间（秒）
     */
    private long l2ExpireAfterWriteSeconds = 7200;

    /**
     * 是否启用随机TTL防雪崩
     */
    private boolean enableRandomTtl = true;

    /**
     * 随机TTL范围（秒）
     */
    private long randomTtlRangeSeconds = 300;

    /**
     * 是否启用布隆过滤器
     */
    private boolean enableBloomFilter = false;

    /**
     * 布隆过滤器预期插入数量
     */
    private long bloomFilterExpectedInsertions = 100000;

    /**
     * 布隆过滤器误判率
     */
    private double bloomFilterFpp = 0.01;
}