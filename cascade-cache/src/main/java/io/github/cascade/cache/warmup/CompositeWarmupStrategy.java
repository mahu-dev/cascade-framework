package io.github.cascade.cache.warmup;

import io.github.cascade.cache.api.Cache;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Collectors;

/**
 * 复合预热策略
 * 支持组合多种预热策略，可以按顺序或并行执行
 *
 * @param <K> 键类型
 * @param <V> 值类型
 * @author Cascade Framework
 */
public class CompositeWarmupStrategy<K, V> implements CacheWarmupStrategy<K, V> {

    private final String strategyName;
    private final WarmupConfig config;
    private final List<CacheWarmupStrategy<K, V>> strategies;
    private final ExecutionMode executionMode;
    private final Executor executor;

    public CompositeWarmupStrategy(String strategyName,
                                  WarmupConfig config,
                                  List<CacheWarmupStrategy<K, V>> strategies,
                                  ExecutionMode executionMode) {
        this(strategyName, config, strategies, executionMode, ForkJoinPool.commonPool());
    }

    public CompositeWarmupStrategy(String strategyName,
                                  WarmupConfig config,
                                  List<CacheWarmupStrategy<K, V>> strategies,
                                  ExecutionMode executionMode,
                                  Executor executor) {
        this.strategyName = strategyName;
        this.config = config;
        this.strategies = new ArrayList<>(strategies);
        this.executionMode = executionMode;
        this.executor = executor;
    }

    @Override
    public WarmupResult warmup(Cache<K, V> cache) {
        switch (executionMode) {
            case SEQUENTIAL:
                return warmupSequential(cache);
            case PARALLEL:
                return warmupParallel(cache);
            case FAIL_FAST:
                return warmupFailFast(cache);
            case BEST_EFFORT:
                return warmupBestEffort(cache);
            default:
                throw new IllegalArgumentException("Unsupported execution mode: " + executionMode);
        }
    }

    @Override
    public CompletableFuture<WarmupResult> warmupAsync(Cache<K, V> cache, Executor executor) {
        return CompletableFuture.supplyAsync(() -> warmup(cache), executor);
    }

    /**
     * 顺序执行预热策略
     */
    private WarmupResult warmupSequential(Cache<K, V> cache) {
        Instant start = Instant.now();
        long totalLoaded = 0;
        long totalFailed = 0;
        List<String> messages = new ArrayList<>();
        
        for (CacheWarmupStrategy<K, V> strategy : strategies) {
            // 检查超时
            if (Duration.between(start, Instant.now()).compareTo(config.getTimeout()) > 0) {
                messages.add("Timeout reached, stopping sequential warmup");
                break;
            }

            try {
                WarmupResult result = strategy.warmup(cache);
                totalLoaded += result.getLoadedCount();
                totalFailed += result.getFailedCount();
                messages.add(String.format("%s: %s", strategy.getStrategyName(), result.getMessage()));
                
                if (!result.isSuccess() && config.isFailFast()) {
                    Duration duration = Duration.between(start, Instant.now());
                    String message = String.format("Sequential warmup failed at %s: %s", 
                        strategy.getStrategyName(), String.join("; ", messages));
                    return WarmupResult.failure(duration, message, result.getError());
                }
            } catch (Exception e) {
                totalFailed++;
                messages.add(String.format("%s: Failed with exception", strategy.getStrategyName()));
                
                if (config.isFailFast()) {
                    Duration duration = Duration.between(start, Instant.now());
                    String message = String.format("Sequential warmup failed at %s: %s", 
                        strategy.getStrategyName(), String.join("; ", messages));
                    return WarmupResult.failure(duration, message, e);
                }
            }
        }

        Duration duration = Duration.between(start, Instant.now());
        String message = String.format("Sequential warmup completed: %d loaded, %d failed. Details: %s", 
            totalLoaded, totalFailed, String.join("; ", messages));
        return WarmupResult.success(totalLoaded, totalFailed, duration, message);
    }

    /**
     * 并行执行预热策略
     */
    private WarmupResult warmupParallel(Cache<K, V> cache) {
        Instant start = Instant.now();
        
        // 并行执行所有策略
        List<CompletableFuture<WarmupResult>> futures = strategies.stream()
            .map(strategy -> CompletableFuture.supplyAsync(() -> strategy.warmup(cache), executor))
            .collect(Collectors.toList());

        // 等待所有策略完成
        CompletableFuture<Void> allOf = CompletableFuture.allOf(
            futures.toArray(new CompletableFuture[0]));
        
        try {
            allOf.get(config.getTimeout().toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            // 取消未完成的任务
            futures.forEach(f -> f.cancel(true));
        } catch (Exception e) {
            // 处理其他异常
        }

        // 收集结果
        long totalLoaded = 0;
        long totalFailed = 0;
        List<String> messages = new ArrayList<>();
        boolean hasFailure = false;
        Throwable lastException = null;
        
        for (int i = 0; i < futures.size(); i++) {
            CompletableFuture<WarmupResult> future = futures.get(i);
            CacheWarmupStrategy<K, V> strategy = strategies.get(i);
            
            if (future.isDone() && !future.isCancelled()) {
                try {
                    WarmupResult result = future.get();
                    totalLoaded += result.getLoadedCount();
                    totalFailed += result.getFailedCount();
                    messages.add(String.format("%s: %s", strategy.getStrategyName(), result.getMessage()));
                    
                    if (!result.isSuccess()) {
                        hasFailure = true;
                        lastException = result.getError();
                    }
                } catch (Exception e) {
                    totalFailed++;
                    hasFailure = true;
                    lastException = e;
                    messages.add(String.format("%s: Failed with exception", strategy.getStrategyName()));
                }
            } else {
                messages.add(String.format("%s: Cancelled or timeout", strategy.getStrategyName()));
            }
        }

        Duration duration = Duration.between(start, Instant.now());
        String message = String.format("Parallel warmup completed: %d loaded, %d failed. Details: %s", 
            totalLoaded, totalFailed, String.join("; ", messages));
        
        if (hasFailure && config.isFailFast()) {
            return WarmupResult.failure(duration, message, lastException);
        } else {
            return WarmupResult.success(totalLoaded, totalFailed, duration, message);
        }
    }

    /**
     * 快速失败模式：任何策略失败立即停止
     */
    private WarmupResult warmupFailFast(Cache<K, V> cache) {
        Instant start = Instant.now();
        long totalLoaded = 0;
        long totalFailed = 0;
        List<String> messages = new ArrayList<>();
        
        for (CacheWarmupStrategy<K, V> strategy : strategies) {
            try {
                WarmupResult result = strategy.warmup(cache);
                totalLoaded += result.getLoadedCount();
                totalFailed += result.getFailedCount();
                messages.add(String.format("%s: %s", strategy.getStrategyName(), result.getMessage()));
                
                if (!result.isSuccess()) {
                    Duration duration = Duration.between(start, Instant.now());
                    String message = String.format("Fail-fast warmup stopped at %s: %s", 
                        strategy.getStrategyName(), String.join("; ", messages));
                    return WarmupResult.failure(duration, message, result.getError());
                }
            } catch (Exception e) {
                Duration duration = Duration.between(start, Instant.now());
                String message = String.format("Fail-fast warmup failed at %s: %s", 
                    strategy.getStrategyName(), String.join("; ", messages));
                return WarmupResult.failure(duration, message, e);
            }
        }

        Duration duration = Duration.between(start, Instant.now());
        String message = String.format("Fail-fast warmup completed: %d loaded, %d failed. Details: %s", 
            totalLoaded, totalFailed, String.join("; ", messages));
        return WarmupResult.success(totalLoaded, totalFailed, duration, message);
    }

    /**
     * 尽力而为模式：忽略所有失败，继续执行
     */
    private WarmupResult warmupBestEffort(Cache<K, V> cache) {
        Instant start = Instant.now();
        long totalLoaded = 0;
        long totalFailed = 0;
        List<String> messages = new ArrayList<>();
        
        for (CacheWarmupStrategy<K, V> strategy : strategies) {
            try {
                WarmupResult result = strategy.warmup(cache);
                totalLoaded += result.getLoadedCount();
                totalFailed += result.getFailedCount();
                messages.add(String.format("%s: %s", strategy.getStrategyName(), result.getMessage()));
            } catch (Exception e) {
                totalFailed++;
                messages.add(String.format("%s: Failed with exception", strategy.getStrategyName()));
            }
        }

        Duration duration = Duration.between(start, Instant.now());
        String message = String.format("Best-effort warmup completed: %d loaded, %d failed. Details: %s", 
            totalLoaded, totalFailed, String.join("; ", messages));
        return WarmupResult.success(totalLoaded, totalFailed, duration, message);
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
        return strategies.stream().allMatch(CacheWarmupStrategy::supportsIncrementalWarmup);
    }

    @Override
    public boolean supportsParallelWarmup() {
        return true;
    }

    /**
     * 执行模式
     */
    public enum ExecutionMode {
        /** 顺序执行 */
        SEQUENTIAL,
        /** 并行执行 */
        PARALLEL,
        /** 快速失败：任何策略失败立即停止 */
        FAIL_FAST,
        /** 尽力而为：忽略所有失败，继续执行 */
        BEST_EFFORT
    }

    /**
     * 创建复合预热策略构建器
     */
    public static <K, V> Builder<K, V> builder() {
        return new Builder<>();
    }

    /**
     * 构建器
     */
    public static class Builder<K, V> {
        private String strategyName = "CompositeWarmupStrategy";
        private WarmupConfig config = WarmupConfig.defaultConfig();
        private List<CacheWarmupStrategy<K, V>> strategies = new ArrayList<>();
        private ExecutionMode executionMode = ExecutionMode.SEQUENTIAL;
        private Executor executor = ForkJoinPool.commonPool();

        public Builder<K, V> strategyName(String strategyName) {
            this.strategyName = strategyName;
            return this;
        }

        public Builder<K, V> config(WarmupConfig config) {
            this.config = config;
            return this;
        }

        public Builder<K, V> addStrategy(CacheWarmupStrategy<K, V> strategy) {
            this.strategies.add(strategy);
            return this;
        }

        public Builder<K, V> addStrategies(CacheWarmupStrategy<K, V>... strategies) {
            this.strategies.addAll(Arrays.asList(strategies));
            return this;
        }

        public Builder<K, V> addStrategies(Collection<CacheWarmupStrategy<K, V>> strategies) {
            this.strategies.addAll(strategies);
            return this;
        }

        public Builder<K, V> executionMode(ExecutionMode executionMode) {
            this.executionMode = executionMode;
            return this;
        }

        public Builder<K, V> sequential() {
            this.executionMode = ExecutionMode.SEQUENTIAL;
            return this;
        }

        public Builder<K, V> parallel() {
            this.executionMode = ExecutionMode.PARALLEL;
            return this;
        }

        public Builder<K, V> failFast() {
            this.executionMode = ExecutionMode.FAIL_FAST;
            return this;
        }

        public Builder<K, V> bestEffort() {
            this.executionMode = ExecutionMode.BEST_EFFORT;
            return this;
        }

        public Builder<K, V> executor(Executor executor) {
            this.executor = executor;
            return this;
        }

        public CompositeWarmupStrategy<K, V> build() {
            if (strategies.isEmpty()) {
                throw new IllegalArgumentException("At least one strategy must be provided");
            }
            return new CompositeWarmupStrategy<>(strategyName, config, strategies, executionMode, executor);
        }
    }
}