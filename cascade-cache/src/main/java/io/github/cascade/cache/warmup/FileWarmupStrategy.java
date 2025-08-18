package io.github.cascade.cache.warmup;

import io.github.cascade.cache.api.Cache;
import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * 文件预热策略
 * 从文件加载数据进行缓存预热
 *
 * @param <K> 键类型
 * @param <V> 值类型
 * @author Cascade Framework
 */
public class FileWarmupStrategy<K, V> implements CacheWarmupStrategy<K, V> {

    private final String strategyName;
    private final WarmupConfig config;
    private final Path filePath;
    private final Function<String, Map.Entry<K, V>> lineParser;
    private final Executor executor;

    public FileWarmupStrategy(String strategyName,
                             WarmupConfig config,
                             Path filePath,
                             Function<String, Map.Entry<K, V>> lineParser) {
        this(strategyName, config, filePath, lineParser, ForkJoinPool.commonPool());
    }

    public FileWarmupStrategy(String strategyName,
                             WarmupConfig config,
                             Path filePath,
                             Function<String, Map.Entry<K, V>> lineParser,
                             Executor executor) {
        this.strategyName = strategyName;
        this.config = config;
        this.filePath = filePath;
        this.lineParser = lineParser;
        this.executor = executor;
    }

    @Override
    public WarmupResult warmup(Cache<K, V> cache) {
        Instant start = Instant.now();
        long loadedCount = 0;
        long failedCount = 0;
        
        try {
            if (!Files.exists(filePath)) {
                return WarmupResult.failure(
                    Duration.between(start, Instant.now()),
                    "File not found: " + filePath,
                    new FileNotFoundException(filePath.toString())
                );
            }

            try (BufferedReader reader = Files.newBufferedReader(filePath)) {
                String line;
                List<Map.Entry<K, V>> batch = new ArrayList<>();
                
                while ((line = reader.readLine()) != null) {
                    // 检查超时
                    if (Duration.between(start, Instant.now()).compareTo(config.getTimeout()) > 0) {
                        break;
                    }

                    try {
                        // 解析行数据
                        Map.Entry<K, V> entry = lineParser.apply(line.trim());
                        if (entry != null && entry.getKey() != null && entry.getValue() != null) {
                            batch.add(entry);
                        }
                        
                        // 批量处理
                        if (batch.size() >= config.getBatchSize()) {
                            BatchResult result = processBatch(cache, batch);
                            loadedCount += result.loadedCount;
                            failedCount += result.failedCount;
                            batch.clear();
                            
                            if (config.isFailFast() && result.failedCount > 0) {
                                break;
                            }
                        }
                    } catch (Exception e) {
                        failedCount++;
                        if (config.isFailFast()) {
                            Duration duration = Duration.between(start, Instant.now());
                            return WarmupResult.failure(duration, "Failed to parse line: " + line, e);
                        }
                    }
                }
                
                // 处理剩余的批次
                if (!batch.isEmpty()) {
                    BatchResult result = processBatch(cache, batch);
                    loadedCount += result.loadedCount;
                    failedCount += result.failedCount;
                }
            }

            Duration duration = Duration.between(start, Instant.now());
            String message = String.format("File warmup completed: %d loaded, %d failed from %s", 
                loadedCount, failedCount, filePath.getFileName());
            return WarmupResult.success(loadedCount, failedCount, duration, message);
            
        } catch (Exception e) {
            Duration duration = Duration.between(start, Instant.now());
            return WarmupResult.failure(duration, "File warmup failed", e);
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
     * 并行预热（按行分块处理）
     */
    private WarmupResult warmupParallel(Cache<K, V> cache) {
        Instant start = Instant.now();
        
        try {
            if (!Files.exists(filePath)) {
                return WarmupResult.failure(
                    Duration.between(start, Instant.now()),
                    "File not found: " + filePath,
                    new FileNotFoundException(filePath.toString())
                );
            }

            // 读取所有行并分块
            List<String> lines = Files.readAllLines(filePath);
            List<List<String>> chunks = partitionLines(lines, config.getBatchSize());
            
            // 并行处理块
            List<CompletableFuture<BatchResult>> futures = chunks.stream()
                .map(chunk -> CompletableFuture.supplyAsync(() -> processChunk(cache, chunk), executor))
                .collect(java.util.stream.Collectors.toList());

            // 等待所有块完成
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
                        // 忽略单个块的异常
                    }
                }
            }

            Duration duration = Duration.between(start, Instant.now());
            String message = String.format("Parallel file warmup completed: %d loaded, %d failed from %s", 
                totalLoaded, totalFailed, filePath.getFileName());
            return WarmupResult.success(totalLoaded, totalFailed, duration, message);
            
        } catch (Exception e) {
            Duration duration = Duration.between(start, Instant.now());
            return WarmupResult.failure(duration, "Parallel file warmup failed", e);
        }
    }

    /**
     * 处理单个文件块
     */
    private BatchResult processChunk(Cache<K, V> cache, List<String> lines) {
        long loadedCount = 0;
        long failedCount = 0;
        
        List<Map.Entry<K, V>> batch = new ArrayList<>();
        
        for (String line : lines) {
            try {
                Map.Entry<K, V> entry = lineParser.apply(line.trim());
                if (entry != null && entry.getKey() != null && entry.getValue() != null) {
                    batch.add(entry);
                }
            } catch (Exception e) {
                failedCount++;
            }
        }
        
        if (!batch.isEmpty()) {
            BatchResult result = processBatch(cache, batch);
            loadedCount += result.loadedCount;
            failedCount += result.failedCount;
        }
        
        return new BatchResult(loadedCount, failedCount);
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
     * 将行列表分割成块
     */
    private List<List<String>> partitionLines(List<String> lines, int chunkSize) {
        List<List<String>> chunks = new ArrayList<>();
        
        for (int i = 0; i < lines.size(); i += chunkSize) {
            int end = Math.min(i + chunkSize, lines.size());
            chunks.add(lines.subList(i, end));
        }
        
        return chunks;
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
     * 创建文件预热策略构建器
     */
    public static <K, V> Builder<K, V> builder() {
        return new Builder<>();
    }

    /**
     * 构建器
     */
    public static class Builder<K, V> {
        private String strategyName = "FileWarmupStrategy";
        private WarmupConfig config = WarmupConfig.defaultConfig();
        private Path filePath;
        private Function<String, Map.Entry<K, V>> lineParser;
        private Executor executor = ForkJoinPool.commonPool();

        public Builder<K, V> strategyName(String strategyName) {
            this.strategyName = strategyName;
            return this;
        }

        public Builder<K, V> config(WarmupConfig config) {
            this.config = config;
            return this;
        }

        public Builder<K, V> filePath(Path filePath) {
            this.filePath = filePath;
            return this;
        }

        public Builder<K, V> filePath(String filePath) {
            this.filePath = Paths.get(filePath);
            return this;
        }

        public Builder<K, V> lineParser(Function<String, Map.Entry<K, V>> lineParser) {
            this.lineParser = lineParser;
            return this;
        }

        public Builder<K, V> executor(Executor executor) {
            this.executor = executor;
            return this;
        }

        /**
         * 设置JSON行解析器
         */
        public Builder<K, V> jsonLineParser(Function<String, K> keyExtractor, Function<String, V> valueExtractor) {
            this.lineParser = line -> {
                try {
                    K key = keyExtractor.apply(line);
                    V value = valueExtractor.apply(line);
                    return new AbstractMap.SimpleEntry<>(key, value);
                } catch (Exception e) {
                    return null;
                }
            };
            return this;
        }

        /**
         * 设置CSV行解析器
         */
        public Builder<K, V> csvLineParser(String delimiter, int keyIndex, int valueIndex, 
                                          Function<String, K> keyConverter, Function<String, V> valueConverter) {
            this.lineParser = line -> {
                try {
                    String[] parts = line.split(delimiter);
                    if (parts.length > Math.max(keyIndex, valueIndex)) {
                        K key = keyConverter.apply(parts[keyIndex]);
                        V value = valueConverter.apply(parts[valueIndex]);
                        return new AbstractMap.SimpleEntry<>(key, value);
                    }
                    return null;
                } catch (Exception e) {
                    return null;
                }
            };
            return this;
        }

        public FileWarmupStrategy<K, V> build() {
            Objects.requireNonNull(filePath, "filePath cannot be null");
            Objects.requireNonNull(lineParser, "lineParser cannot be null");
            return new FileWarmupStrategy<>(strategyName, config, filePath, lineParser, executor);
        }
    }
}