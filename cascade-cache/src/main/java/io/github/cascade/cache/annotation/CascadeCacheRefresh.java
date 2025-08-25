package io.github.cascade.cache.annotation;

import java.lang.annotation.*;

/**
 * Cascade缓存自动刷新注解
 * 用于启用缓存的定时自动刷新功能
 * 
 * @author cascade
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CascadeCacheRefresh {
    
    /**
     * 缓存名称
     */
    String[] value() default {};
    
    /**
     * 缓存名称（别名）
     */
    String[] cacheNames() default {};
    
    /**
     * 刷新间隔（支持Spring表达式，如 "PT10M"、"600s"、"#{config.refreshInterval}"）
     */
    String refreshInterval() default "PT10M";
    
    /**
     * 最小刷新间隔
     */
    String minRefreshInterval() default "PT1M";
    
    /**
     * 最大刷新间隔  
     */
    String maxRefreshInterval() default "PT1H";
    
    /**
     * 刷新条件表达式
     */
    String condition() default "";
    
    /**
     * 指定CacheLoader Bean名称
     */
    String loader() default "";
    
    /**
     * 是否允许并发刷新
     */
    boolean allowConcurrentRefresh() default false;
    
    /**
     * 刷新超时时间
     */
    String refreshTimeout() default "PT30S";
    
    /**
     * 失败重试次数
     */
    int maxRetries() default 3;
    
    /**
     * 重试间隔
     */
    String retryInterval() default "PT5S";
    
    /**
     * 预刷新提前时间因子（0.0-1.0）
     * 当缓存剩余生存时间低于此比例时，提前触发刷新
     */
    double preRefreshFactor() default 0.0;
    
    /**
     * 是否启用预加载
     */
    boolean enablePreload() default false;
    
    /**
     * 预加载批处理大小
     */
    int preloadBatchSize() default 50;
    
    /**
     * 预加载并发度
     */
    int preloadConcurrency() default 2;
    
    /**
     * 是否在初始化时立即启动刷新调度器
     */
    boolean startOnInit() default true;
    
    /**
     * 刷新策略
     */
    RefreshStrategy strategy() default RefreshStrategy.FIXED_RATE;
    
    /**
     * 刷新策略枚举
     */
    enum RefreshStrategy {
        /** 固定频率刷新 */
        FIXED_RATE,
        /** 固定延迟刷新 */
        FIXED_DELAY,
        /** 动态调整刷新频率 */
        ADAPTIVE,
        /** 基于访问频率的智能刷新 */
        SMART
    }
}