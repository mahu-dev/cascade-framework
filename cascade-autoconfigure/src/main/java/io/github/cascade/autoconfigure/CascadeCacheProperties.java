package io.github.cascade.autoconfigure;

import io.github.cascade.cache.config.CachePropertiesProvider;
import io.github.cascade.cache.config.unified.CascadeCacheConfiguration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Cascade缓存配置属性
 * 基于 CascadeCacheConfiguration 的扁平化版本，用于 application.yml/properties 配置
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

    /**
     * 转换为运行时配置对象
     * 统一配置转换逻辑，避免重复代码
     */
    @Override
    public CascadeCacheConfiguration toCascadeCacheConfiguration(String cacheName) {
        CascadeCacheConfiguration config = new CascadeCacheConfiguration();
        
        // 基础配置
        config.setName(cacheName != null ? cacheName : defaultCacheName)
              .setEnabled(enabled);
        
        // 通用配置
        config.getCommon()
              .setMaximumSize(defaultSize)
              .setRecordStats(true);
        
        // L1配置
        config.getL1()
              .setEnabled(true)
              .setMaximumSize(defaultSize)
              .setExpireAfterWrite(Duration.ofSeconds(l1ExpireAfterWriteSeconds))
              .setRecordStats(true);
        
        // L2配置
        if (enableRedis) {
            config.getL2()
                  .setEnabled(true)
                  .setDefaultTtl(Duration.ofSeconds(l2ExpireAfterWriteSeconds));
        }
        
        // 同步配置
        if (enableSync) {
            config.getSync().setEnabled(true);
        }
        
        // 防护配置
        if (enableProtection) {
            config.getProtection().setEnabled(true);
            
            // 布隆过滤器
            if (enableBloomFilter) {
                config.getProtection().getBloomFilter()
                      .setEnabled(true)
                      .setExpectedElements(bloomFilterExpectedInsertions)
                      .setFalsePositiveRate(bloomFilterFpp);
            }
            
            // 随机TTL
            if (enableRandomTtl) {
                config.getProtection().getRandomTtl()
                      .setEnabled(true)
                      .setJitterRange(Duration.ofSeconds(randomTtlRangeSeconds));
            }
        }
        
        return config;
    }

    /**
     * 检查是否启用
     */
    @Override
    public boolean isEnabled() {
        return this.enabled;
    }
}