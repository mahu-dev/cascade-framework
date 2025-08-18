package io.github.cascade.cache.warmup;

import io.github.cascade.cache.api.Cache;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 内存预热策略
 * 从内存数据源加载数据进行缓存预热
 *
 * @param <K> 键类型
 * @param <V> 值类型
 * @author Cascade Framework
 */
public class MemoryWarmupStrategy<K, V> implements CacheWarmupStrategy<K, V> {

    private final String strategyName;
    private final WarmupConfig config;
    private final Supplier<Map<K, V>> dataSupplier;
    private final Executor executor;

    public MemoryWarmupStrategy(String strategyName,
                               WarmupConfig config,
                               Supplier<Map<K, V>> dataSupplier) {
        this(strategyName, config, dataSupplier, ForkJoinPool.commonPool());
    }

    public MemoryWarmupStrategy(String strategyName,
                               WarmupConfig config,
                               Supplier<Map<K, V>> dataSupplier,
                               Executor executor) {
        this.strategyName = strategyName;
        this.config = config;
        this.dataSupplier = dataSupplier;
        this.executor = executor;
    }

    @Override
    public WarmupResult warmup(Cache<K, V> cache) {
        Instant start = Instant.now();
        long loadedCount = 0;
        long failedCount = 0;
        
        try {
            // 获取数据
            Map<K, V> data = dataSupplier.get();
            if (data == null || data.isEmpty()) {
                Duration duration = Duration.between(start, Instant.now());
                return WarmupResult.success(0, 0, duration, "No data to warmup");
            }

            // 分批处理
            List<Map.Entry<K, V>> entries = new ArrayList<>(data.entrySet());
            List<List<Map.Entry<K, V>>> batches = partitionEntries(entries, config.getBatchSize());
            
            for (List<Map.Entry<K, V>> batch : batches) {
                // 检查超时
                if (Duration.between(start, Instant.now()).compareTo(config.getTimeout()) > 0) {
                    break;
                }

                BatchResult result = processBatch(cache, batch);
                loadedCount += result.loadedCount;
                failedCount += result.failedCount;
                
                if (config.isFailFast() && result.failedCount > 0) {
                    break;
                }
            }

            Duration duration = Duration.between(start, Instant.now());
            String message = String.format("Memory warmup completed: %d loaded, %d failed", 
                loadedCount, failedCount);
            return WarmupResult.success(loadedCount, failedCount, duration, message);
            
        } catch (Exception e) {
            Duration duration = Duration.between(start, Instant.now());
            return WarmupResult.failure(duration, "Memory warmup failed", e);
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
            // 获取数据
            Map<K, V> data = dataSupplier.get();
            if (data == null || data.isEmpty()) {
                Duration duration = Duration.between(start, Instant.now());
                return WarmupResult.success(0, 0, duration, "No data to warmup");
            }

            // 分批处理
            List<Map.Entry<K, V>> entries = new ArrayList<>(data.entrySet());
            List<List<Map.Entry<K, V>>> batches = partitionEntries(entries, config.getBatchSize());
            
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
            String message = String.format("Parallel memory warmup completed: %d loaded, %d failed", 
                totalLoaded, totalFailed);
            return WarmupResult.success(totalLoaded, totalFailed, duration, message);
            
        } catch (Exception e) {
            Duration duration = Duration.between(start, Instant.now());
            return WarmupResult.failure(duration, "Parallel memory warmup failed", e);
        }
    }

    /**
     * 处理单个批次
     */
    private BatchResult processBatch(Cache<K, V> cache, List<Map.Entry<K, V>> batch) {
        long loadedCount = 0;
        long failedCount = 0;
        
        for (Map.Entry<K, V> entry : batch) {
            try {
                cache.put(entry.getKey(), entry.getValue());
                loadedCount++;
            } catch (Exception e) {
                failedCount++;
            }
        }
        
        return new BatchResult(loadedCount, failedCount);
    }

    /**
     * 将条目列表分割成批次
     */
    private List<List<Map.Entry<K, V>>> partitionEntries(List<Map.Entry<K, V>> entries, int batchSize) {
        List<List<Map.Entry<K, V>>> batches = new ArrayList<>();
        
        for (int i = 0; i < entries.size(); i += batchSize) {
            int end = Math.min(i + batchSize, entries.size());
            batches.add(entries.subList(i, end));
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
     * 创建内存预热策略构建器
     */
    public static <K, V> Builder<K, V> builder() {
        return new Builder<>();
    }

    /**
     * 构建器
     */
    public static class Builder<K, V> {
        private String strategyName = "MemoryWarmupStrategy";
        private WarmupConfig config = WarmupConfig.defaultConfig();
        private Supplier<Map<K, V>> dataSupplier;
        private Executor executor = ForkJoinPool.commonPool();

        public Builder<K, V> strategyName(String strategyName) {
            this.strategyName = strategyName;
            return this;
        }

        public Builder<K, V> config(WarmupConfig config) {
            this.config = config;
            return this;
        }

        public Builder<K, V> dataSupplier(Supplier<Map<K, V>> dataSupplier) {
            this.dataSupplier = dataSupplier;
            return this;
        }

        public Builder<K, V> data(Map<K, V> data) {
            this.dataSupplier = () -> data;
            return this;
        }

        public Builder<K, V> executor(Executor executor) {
            this.executor = executor;
            return this;
        }

        /**
         * 从集合创建数据供应器
         */
        public Builder<K, V> fromCollection(Collection<V> values, java.util.function.Function<V, K> keyExtractor) {
            this.dataSupplier = () -> {
                Map<K, V> map = new HashMap<>();
                for (V value : values) {
                    K key = keyExtractor.apply(value);
                    if (key != null) {
                        map.put(key, value);
                    }
                }
                return map;
            };
            return this;
        }

        /**
         * 从数组创建数据供应器
         */
        @SafeVarargs
        public final Builder<K, V> fromEntries(Map.Entry<K, V>... entries) {
            this.dataSupplier = () -> {
                Map<K, V> map = new HashMap<>();
                for (Map.Entry<K, V> entry : entries) {
                    if (entry != null && entry.getKey() != null && entry.getValue() != null) {
                        map.put(entry.getKey(), entry.getValue());
                    }
                }
                return map;
            };
            return this;
        }

        /**
         * 从键值对创建数据供应器
         */
        public Builder<K, V> fromKeyValues(K key1, V value1) {
            this.dataSupplier = () -> {
                Map<K, V> map = new HashMap<>();
                map.put(key1, value1);
                return map;
            };
            return this;
        }

        public Builder<K, V> fromKeyValues(K key1, V value1, K key2, V value2) {
            this.dataSupplier = () -> {
                Map<K, V> map = new HashMap<>();
                map.put(key1, value1);
                map.put(key2, value2);
                return map;
            };
            return this;
        }

        public Builder<K, V> fromKeyValues(K key1, V value1, K key2, V value2, K key3, V value3) {
            this.dataSupplier = () -> {
                Map<K, V> map = new HashMap<>();
                map.put(key1, value1);
                map.put(key2, value2);
                map.put(key3, value3);
                return map;
            };
            return this;
        }

        public MemoryWarmupStrategy<K, V> build() {
            Objects.requireNonNull(dataSupplier, "dataSupplier cannot be null");
            return new MemoryWarmupStrategy<>(strategyName, config, dataSupplier, executor);
        }
    }
}