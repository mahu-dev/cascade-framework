package io.github.cascade.cache.warmup;

import io.github.cascade.cache.api.Cache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * 缓存预热管理器
 * 统一管理所有缓存的预热策略和执行
 */
@Component
public class CacheWarmupManager {
    
    private static final Logger log = LoggerFactory.getLogger(CacheWarmupManager.class);
    
    private final Map<String, Cache<?, ?>> caches = new ConcurrentHashMap<>();
    private final Map<String, List<CacheWarmupStrategy<?, ?>>> strategies = new ConcurrentHashMap<>();
    private final Map<String, WarmupExecutionInfo> executions = new ConcurrentHashMap<>();
    private final ExecutorService executorService;
    
    @Autowired(required = false)
    private ApplicationEventPublisher eventPublisher;
    
    public CacheWarmupManager() {
        this.executorService = Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r, "cache-warmup-" + System.currentTimeMillis());
            thread.setDaemon(true);
            return thread;
        });
    }
    
    /**
     * 注册缓存
     */
    public void registerCache(String cacheName, Cache<?, ?> cache) {
        caches.put(cacheName, cache);
        log.info("Registered cache for warmup: {}", cacheName);
    }
    
    /**
     * 注册预热策略
     */
    public <K, V> void registerStrategy(String cacheName, CacheWarmupStrategy<K, V> strategy) {
        strategies.computeIfAbsent(cacheName, k -> new ArrayList<>()).add(strategy);
        log.info("Registered warmup strategy '{}' for cache: {}", strategy.getStrategyName(), cacheName);
    }
    
    /**
     * 执行指定缓存的预热
     */
    @SuppressWarnings("unchecked")
    public CompletableFuture<List<CacheWarmupStrategy.WarmupResult>> warmupCache(String cacheName) {
        Cache<Object, Object> cache = (Cache<Object, Object>) caches.get(cacheName);
        if (cache == null) {
            return CompletableFuture.completedFuture(
                Collections.singletonList(
                    CacheWarmupStrategy.WarmupResult.failure(
                        Duration.ZERO, 
                        "Cache not found: " + cacheName, 
                        new IllegalArgumentException("Unknown cache: " + cacheName)
                    )
                )
            );
        }
        
        List<CacheWarmupStrategy<?, ?>> cacheStrategies = strategies.get(cacheName);
        if (cacheStrategies == null || cacheStrategies.isEmpty()) {
            log.warn("No warmup strategies configured for cache: {}", cacheName);
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
        
        // 按优先级排序策略
        List<CacheWarmupStrategy<Object, Object>> sortedStrategies = cacheStrategies.stream()
            .sorted(Comparator.comparingInt(CacheWarmupStrategy::getPriority))
            .map(s -> (CacheWarmupStrategy<Object, Object>) s)
            .collect(Collectors.toList());
        
        WarmupExecutionInfo executionInfo = new WarmupExecutionInfo(cacheName, sortedStrategies.size());
        executions.put(cacheName, executionInfo);
        
        publishEvent(new CacheWarmupEvent.Started(cacheName, sortedStrategies.size()));
        
        return CompletableFuture.supplyAsync(() -> {
            List<CacheWarmupStrategy.WarmupResult> results = new ArrayList<>();
            
            for (CacheWarmupStrategy<Object, Object> strategy : sortedStrategies) {
                try {
                    log.info("Executing warmup strategy '{}' for cache: {}", strategy.getStrategyName(), cacheName);
                    Instant start = Instant.now();
                    
                    CacheWarmupStrategy.WarmupResult result = strategy.warmup(cache);
                    Duration duration = Duration.between(start, Instant.now());
                    
                    results.add(result);
                    executionInfo.completeStrategy(strategy.getStrategyName(), result);
                    
                    log.info("Completed warmup strategy '{}' for cache: {} in {}ms - {}",
                        strategy.getStrategyName(), cacheName, duration.toMillis(), result);
                    
                    publishEvent(new CacheWarmupEvent.StrategyCompleted(
                        cacheName, strategy.getStrategyName(), result));
                        
                } catch (Exception e) {
                    log.error("Failed to execute warmup strategy '{}' for cache: {}",
                        strategy.getStrategyName(), cacheName, e);
                    
                    CacheWarmupStrategy.WarmupResult errorResult = CacheWarmupStrategy.WarmupResult.failure(
                        Duration.ZERO, "Strategy execution failed", e);
                    results.add(errorResult);
                    executionInfo.completeStrategy(strategy.getStrategyName(), errorResult);
                    
                    publishEvent(new CacheWarmupEvent.StrategyFailed(
                        cacheName, strategy.getStrategyName(), e));
                }
            }
            
            executionInfo.complete();
            publishEvent(new CacheWarmupEvent.Completed(cacheName, results));
            
            return results;
        }, executorService);
    }
    
    /**
     * 执行所有缓存的预热
     */
    public CompletableFuture<Map<String, List<CacheWarmupStrategy.WarmupResult>>> warmupAllCaches() {
        Map<String, CompletableFuture<List<CacheWarmupStrategy.WarmupResult>>> futures = caches.keySet().stream()
            .collect(Collectors.toMap(
                cacheName -> cacheName,
                this::warmupCache
            ));
        
        return CompletableFuture.allOf(futures.values().toArray(new CompletableFuture[0]))
            .thenApply(v -> futures.entrySet().stream()
                .collect(Collectors.toMap(
                    Map.Entry::getKey,
                    entry -> entry.getValue().join()
                )));
    }
    
    /**
     * 获取预热执行状态
     */
    public WarmupExecutionInfo getExecutionInfo(String cacheName) {
        return executions.get(cacheName);
    }
    
    /**
     * 获取所有预热执行状态
     */
    public Map<String, WarmupExecutionInfo> getAllExecutionInfo() {
        return new HashMap<>(executions);
    }
    
    /**
     * 是否正在执行预热
     */
    public boolean isWarmingUp(String cacheName) {
        WarmupExecutionInfo info = executions.get(cacheName);
        return info != null && !info.isCompleted();
    }
    
    /**
     * 停止指定缓存的预热
     */
    public void stopWarmup(String cacheName) {
        WarmupExecutionInfo info = executions.get(cacheName);
        if (info != null) {
            info.cancel();
            publishEvent(new CacheWarmupEvent.Cancelled(cacheName));
        }
    }
    
    private void publishEvent(CacheWarmupEvent event) {
        if (eventPublisher != null) {
            eventPublisher.publishEvent(event);
        }
    }
    
    @PostConstruct
    public void initialize() {
        log.info("Cache warmup manager initialized");
    }
    
    @PreDestroy
    public void shutdown() {
        executions.values().forEach(WarmupExecutionInfo::cancel);
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(30, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("Cache warmup manager shutdown");
    }
    
    /**
     * 预热执行信息
     */
    public static class WarmupExecutionInfo {
        private final String cacheName;
        private final int totalStrategies;
        private final Instant startTime;
        private final Map<String, CacheWarmupStrategy.WarmupResult> strategyResults = new ConcurrentHashMap<>();
        private volatile boolean completed = false;
        private volatile boolean cancelled = false;
        private volatile Instant endTime;
        
        public WarmupExecutionInfo(String cacheName, int totalStrategies) {
            this.cacheName = cacheName;
            this.totalStrategies = totalStrategies;
            this.startTime = Instant.now();
        }
        
        public void completeStrategy(String strategyName, CacheWarmupStrategy.WarmupResult result) {
            strategyResults.put(strategyName, result);
        }
        
        public void complete() {
            this.completed = true;
            this.endTime = Instant.now();
        }
        
        public void cancel() {
            this.cancelled = true;
            this.endTime = Instant.now();
        }
        
        public String getCacheName() { return cacheName; }
        public int getTotalStrategies() { return totalStrategies; }
        public int getCompletedStrategies() { return strategyResults.size(); }
        public Instant getStartTime() { return startTime; }
        public Instant getEndTime() { return endTime; }
        public boolean isCompleted() { return completed; }
        public boolean isCancelled() { return cancelled; }
        public Map<String, CacheWarmupStrategy.WarmupResult> getStrategyResults() { 
            return new HashMap<>(strategyResults); 
        }
        
        public Duration getDuration() {
            Instant end = endTime != null ? endTime : Instant.now();
            return Duration.between(startTime, end);
        }
        
        public double getProgress() {
            return totalStrategies > 0 ? (double) getCompletedStrategies() / totalStrategies : 0.0;
        }
    }
}