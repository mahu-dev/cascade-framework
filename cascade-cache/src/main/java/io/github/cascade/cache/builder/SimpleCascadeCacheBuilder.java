package io.github.cascade.cache.builder;

import io.github.cascade.cache.api.Cache;
import io.github.cascade.cache.api.CacheLoader;
import io.github.cascade.cache.core.impl.SimpleCascadeCache;

import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

/**
 * 简化版Cascade缓存构建器
 * 提供流式API来配置和构建缓存实例
 */
public class SimpleCascadeCacheBuilder {

    // 基础配置
    private String cacheName;
    private long maximumSize = -1;
    private Duration expireAfterWrite;
    private Duration expireAfterAccess;
    private Duration refreshAfterWrite;
    private boolean recordStats = false;
    private CacheLoader<?, ?> cacheLoader;
    private Executor executor;

    private SimpleCascadeCacheBuilder() {
        // 私有构造函数，使用静态工厂方法创建
    }

    /**
     * 创建新的构建器实例
     */
    public static SimpleCascadeCacheBuilder newBuilder() {
        return new SimpleCascadeCacheBuilder();
    }

    /**
     * 从现有构建器复制创建新实例
     */
    public static SimpleCascadeCacheBuilder from(SimpleCascadeCacheBuilder builder) {
        SimpleCascadeCacheBuilder newBuilder = new SimpleCascadeCacheBuilder();
        newBuilder.cacheName = builder.cacheName;
        newBuilder.maximumSize = builder.maximumSize;
        newBuilder.expireAfterWrite = builder.expireAfterWrite;
        newBuilder.expireAfterAccess = builder.expireAfterAccess;
        newBuilder.refreshAfterWrite = builder.refreshAfterWrite;
        newBuilder.recordStats = builder.recordStats;
        newBuilder.cacheLoader = builder.cacheLoader;
        newBuilder.executor = builder.executor;
        return newBuilder;
    }

    // 基础配置方法
    public SimpleCascadeCacheBuilder cacheName(String cacheName) {
        this.cacheName = cacheName;
        return this;
    }

    public SimpleCascadeCacheBuilder maximumSize(long maximumSize) {
        if (maximumSize < 0) {
            throw new IllegalArgumentException("maximum size must not be negative");
        }
        this.maximumSize = maximumSize;
        return this;
    }

    public SimpleCascadeCacheBuilder expireAfterWrite(Duration duration) {
        this.expireAfterWrite = duration;
        return this;
    }

    public SimpleCascadeCacheBuilder expireAfterAccess(Duration duration) {
        this.expireAfterAccess = duration;
        return this;
    }

    public SimpleCascadeCacheBuilder refreshAfterWrite(Duration duration) {
        this.refreshAfterWrite = duration;
        return this;
    }

    public SimpleCascadeCacheBuilder recordStats() {
        this.recordStats = true;
        return this;
    }

    public SimpleCascadeCacheBuilder recordStats(boolean recordStats) {
        this.recordStats = recordStats;
        return this;
    }

    @SuppressWarnings("unchecked")
    public <K, V> SimpleCascadeCacheBuilder cacheLoader(CacheLoader<K, V> cacheLoader) {
        this.cacheLoader = (CacheLoader<?, ?>) cacheLoader;
        return this;
    }

    public SimpleCascadeCacheBuilder executor(Executor executor) {
        this.executor = executor;
        return this;
    }

    // 构建方法
    public <K, V> Cache<K, V> build() {
        validateConfiguration();

        String actualCacheName = cacheName != null ? cacheName : "cache-" + System.nanoTime();
        SimpleCascadeCache<K, V> cache = new SimpleCascadeCache<>(actualCacheName);

        return cache;
    }

    public <K, V> Cache<K, V> build(CacheLoader<K, V> loader) {
        this.cacheLoader = loader;
        return build();
    }

    // 内部方法
    private void validateConfiguration() {
        // 基本验证
        if (maximumSize == 0) {
            throw new IllegalStateException("maximum size cannot be zero");
        }
    }

    private Executor getExecutorInternal() {
        return executor != null ? executor : ForkJoinPool.commonPool();
    }

    // Getter方法
    public String getCacheName() {
        return cacheName;
    }

    public long getMaximumSize() {
        return maximumSize;
    }

    public Duration getExpireAfterWrite() {
        return expireAfterWrite;
    }

    public Duration getExpireAfterAccess() {
        return expireAfterAccess;
    }

    public Duration getRefreshAfterWrite() {
        return refreshAfterWrite;
    }

    public boolean isRecordStats() {
        return recordStats;
    }

    public CacheLoader<?, ?> getCacheLoader() {
        return cacheLoader;
    }

    public Executor getExecutor() {
        return executor;
    }
}