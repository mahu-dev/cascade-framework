package io.github.cascade.cache.warmup;

import io.github.cascade.cache.api.Cache;
import io.github.cascade.cache.api.CacheLoader;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 数据库预热策略
 * 从数据库加载数据进行缓存预热
 *
 * @param <K> 键类型
 * @param <V> 值类型
 * @author Cascade Framework
 */
public class DatabaseWarmupStrategy<K, V> implements CacheWarmupStrategy<K, V> {

    private final String strategyName;
    private final WarmupConfig config;
    private final Supplier<Set<K>> keyProvider;
    private final CacheLoader<K, V> cacheLoader;
    private final Executor executor;

    public DatabaseWarmupStrategy(String strategyName, 
                                 WarmupConfig config,
                                 Supplier<Set<K>> keyProvider,
                                 CacheLoader<K, V> cacheLoader) {
        this(strategyName, config, keyProvider, cacheLoader, ForkJoinPool.commonPool());
    }

    public DatabaseWarmupStrategy(String strategyName, 
                                 WarmupConfig config,
                                 Supplier<Set<K>> keyProvider,
                                 CacheLoader<K, V> cacheLoader,
                                 Executor executor) {
        this.strategyName = strategyName;
        this.config = config;
        this.keyProvider = keyProvider;
        this.cacheLoader = cacheLoader;
        this.executor = executor;
    }

    @Override
    public WarmupResult warmup(Cache<K, V> cache) {
        Instant start = Instant.now();
        long loadedCount = 0;
        long failedCount = 0;
        
        try {
            // 获取需要预热的键
            Set<K> keys = keyProvider.get();
            if (keys == null || keys.isEmpty()) {
                return WarmupResult.success(0, Duration.between(start, Instant.now()));
            }

            // 分批处理
            List<Set<K>> batches = partitionKeys(keys, config.getBatchSize());
            
            for (Set<K> batch : batches) {
                try {
                    // 检查超时
                    if (Duration.between(start, Instant.now()).compareTo(config.getTimeout()) > 0) {
                        break;
                    }

                    // 批量加载数据
                    Map<K, V> batchData = loadBatchWithRetry(batch);
                    
                    // 将数据放入缓存
                    for (Map.Entry<K, V> entry : batchData.entrySet()) {
                        try {
                            cache.put(entry.getKey(), entry.getValue());
                            loadedCount++;
                        } catch (Exception e) {
                            failedCount++;
                            if (config.isFailFast()) {
                                throw e;
                            }
                        }
                    }
                    
                } catch (Exception e) {
                    failedCount += batch.size();
                    if (config.isFailFast()) {
                        Duration duration = Duration.between(start, Instant.now());
                        return WarmupResult.failure(duration, "Warmup failed on batch", e);
                    }
                }
            }

            Duration duration = Duration.between(start, Instant.now());
            String message = String.format("Database warmup completed: %d loaded, %d failed", 
                loadedCount, failedCount);
            return WarmupResult.success(loadedCount, failedCount, duration, message);
            
        } catch (Exception e) {
            Duration duration = Duration.between(start, Instant.now());
            return WarmupResult.failure(duration, "Database warmup failed", e);
        }
    }

    @Override
    public CompletableFuture<WarmupResult> warmupAsync(Cache<K, V> cache, Executor executor) {
        return CompletableFuture.supplyAsync(() -> {
            if (config.getParallelism() <= 1) {
                return warmup(cache);
            } else {
                return warmupParallel(cache);
            }
        }, executor);
    }

    /**
     * 并行预热
     */
    private WarmupResult warmupParallel(Cache<K, V> cache) {
        Instant start = Instant.now();
        
        try {
            Set<K> keys = keyProvider.get();
            if (keys == null || keys.isEmpty()) {
                return WarmupResult.success(0, Duration.between(start, Instant.now()));
            }

            // 分批处理
            List<Set<K>> batches = partitionKeys(keys, config.getBatchSize());
            
            // 并行处理批次
            List<CompletableFuture<BatchResult>> futures = batches.stream()
                .map(batch -> CompletableFuture.supplyAsync(() -> processBatch(cache, batch), executor))
                .collect(Collectors.toList());

            // 等待所有批次完成
            CompletableFuture<Void> allOf = CompletableFuture.allOf(
                futures.toArray(new CompletableFuture[0]));
            
            try {
                allOf.get(config.getTimeout().toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                // 取消未完成的任务
                futures.forEach(f -> f.cancel(true));
            }

            // 收集结果
            long totalLoaded = 0;
            long totalFailed = 0;
            
            for (CompletableFuture<BatchResult> future : futures) {
                if (future.isDone() && !future.isCancelled()) {
                    try {
                        BatchResult result = future.get();
                        totalLoaded += result.loadedCount;
                        totalFailed += result.failedCount;
                    } catch (Exception e) {
                        // 忽略单个批次的异常
                    }
                }
            }

            Duration duration = Duration.between(start, Instant.now());
            String message = String.format("Parallel database warmup completed: %d loaded, %d failed", 
                totalLoaded, totalFailed);
            return WarmupResult.success(totalLoaded, totalFailed, duration, message);
            
        } catch (Exception e) {
            Duration duration = Duration.between(start, Instant.now());
            return WarmupResult.failure(duration, "Parallel database warmup failed", e);
        }
    }

    /**
     * 处理单个批次
     */
    private BatchResult processBatch(Cache<K, V> cache, Set<K> batch) {
        long loadedCount = 0;
        long failedCount = 0;
        
        try {
            Map<K, V> batchData = loadBatchWithRetry(batch);
            
            for (Map.Entry<K, V> entry : batchData.entrySet()) {
                try {
                    cache.put(entry.getKey(), entry.getValue());
                    loadedCount++;
                } catch (Exception e) {
                    failedCount++;
                }
            }
        } catch (Exception e) {
            failedCount = batch.size();
        }
        
        return new BatchResult(loadedCount, failedCount);
    }

    /**
     * 带重试的批量加载
     */
    private Map<K, V> loadBatchWithRetry(Set<K> keys) throws Exception {
        Exception lastException = null;
        
        for (int attempt = 0; attempt <= config.getMaxRetries(); attempt++) {
            try {
                if (cacheLoader.supportsBatchLoading()) {
                    return cacheLoader.loadAll(keys);
                } else {
                    // 单个加载
                    Map<K, V> result = new HashMap<>();
                    for (K key : keys) {
                        try {
                            V value = cacheLoader.load(key);
                            if (value != null) {
                                result.put(key, value);
                            }
                        } catch (Exception e) {
                            // 忽略单个键的加载失败
                        }
                    }
                    return result;
                }
            } catch (Exception e) {
                lastException = e;
                if (attempt < config.getMaxRetries()) {
                    try {
                        Thread.sleep(config.getRetryDelay().toMillis());
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new Exception("Warmup interrupted", ie);
                    }
                }
            }
        }
        
        throw new Exception("Failed to load batch after " + config.getMaxRetries() + " retries", lastException);
    }

    /**
     * 将键集合分割成批次
     */
    private List<Set<K>> partitionKeys(Set<K> keys, int batchSize) {
        List<Set<K>> batches = new ArrayList<>();
        Set<K> currentBatch = new HashSet<>();
        
        for (K key : keys) {
            currentBatch.add(key);
            if (currentBatch.size() >= batchSize) {
                batches.add(new HashSet<>(currentBatch));
                currentBatch.clear();
            }
        }
        
        if (!currentBatch.isEmpty()) {
            batches.add(currentBatch);
        }
        
        return batches;
    }

    @Override
    public String getStrategyName() {
        return strategyName;
    }

    @Override
    public WarmupConfig getWarmupConfig() {
        return config;
    }

    @Override
    public boolean supportsIncrementalWarmup() {
        return true;
    }

    @Override
    public boolean supportsParallelWarmup() {
        return true;
    }

    /**
     * 批次处理结果
     */
    private static class BatchResult {
        final long loadedCount;
        final long failedCount;
        
        BatchResult(long loadedCount, long failedCount) {
            this.loadedCount = loadedCount;
            this.failedCount = failedCount;
        }
    }

    /**
     * 创建数据库预热策略构建器
     */
    public static <K, V> Builder<K, V> builder() {
        return new Builder<>();
    }

    /**
     * 构建器
     */
    public static class Builder<K, V> {
        private String strategyName = "DatabaseWarmupStrategy";
        private WarmupConfig config = WarmupConfig.defaultConfig();
        private Supplier<Set<K>> keyProvider;
        private CacheLoader<K, V> cacheLoader;
        private Executor executor = ForkJoinPool.commonPool();

        public Builder<K, V> strategyName(String strategyName) {
            this.strategyName = strategyName;
            return this;
        }

        public Builder<K, V> config(WarmupConfig config) {
            this.config = config;
            return this;
        }

        public Builder<K, V> keyProvider(Supplier<Set<K>> keyProvider) {
            this.keyProvider = keyProvider;
            return this;
        }

        public Builder<K, V> cacheLoader(CacheLoader<K, V> cacheLoader) {
            this.cacheLoader = cacheLoader;
            return this;
        }

        public Builder<K, V> executor(Executor executor) {
            this.executor = executor;
            return this;
        }

        public DatabaseWarmupStrategy<K, V> build() {
            Objects.requireNonNull(keyProvider, "keyProvider cannot be null");
            Objects.requireNonNull(cacheLoader, "cacheLoader cannot be null");
            return new DatabaseWarmupStrategy<>(strategyName, config, keyProvider, cacheLoader, executor);
        }
    }
}