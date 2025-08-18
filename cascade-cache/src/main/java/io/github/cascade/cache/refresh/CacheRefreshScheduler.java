package io.github.cascade.cache.refresh;

import io.github.cascade.cache.api.CacheLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 缓存刷新调度器
 * 负责管理L2缓存的定时刷新任务
 */
public class CacheRefreshScheduler<K, V> {
    
    private static final Logger log = LoggerFactory.getLogger(CacheRefreshScheduler.class);
    
    private final ScheduledExecutorService scheduler;
    private final ConcurrentHashMap<K, ScheduledFuture<?>> scheduledRefreshes = new ConcurrentHashMap<>();
    private final RefreshCallback<K, V> refreshCallback;
    private final CacheLoader<K, V> cacheLoader;
    private final Duration refreshInterval;
    private final AtomicInteger refreshCounter = new AtomicInteger(0);
    
    /**
     * 刷新回调接口
     */
    @FunctionalInterface
    public interface RefreshCallback<K, V> {
        void refresh(K key, V newValue);
    }
    
    public CacheRefreshScheduler(RefreshCallback<K, V> refreshCallback, 
                               CacheLoader<K, V> cacheLoader,
                               Duration refreshInterval) {
        this.refreshCallback = refreshCallback;
        this.cacheLoader = cacheLoader;
        this.refreshInterval = refreshInterval;
        this.scheduler = Executors.newScheduledThreadPool(
            Math.min(4, Runtime.getRuntime().availableProcessors()),
            r -> {
                Thread t = new Thread(r, "CacheRefresh-" + refreshCounter.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        );
    }
    
    /**
     * 调度键的刷新任务
     */
    public void scheduleRefresh(K key) {
        if (key == null || refreshInterval == null || cacheLoader == null) {
            return;
        }
        
        // 取消现有的刷新任务
        ScheduledFuture<?> existing = scheduledRefreshes.get(key);
        if (existing != null && !existing.isDone()) {
            existing.cancel(false);
        }
        
        // 创建新的刷新任务
        ScheduledFuture<?> future = scheduler.schedule(
            () -> performRefresh(key),
            refreshInterval.toMillis(),
            TimeUnit.MILLISECONDS
        );
        
        scheduledRefreshes.put(key, future);
        
        log.debug("Scheduled refresh for key {} in {}", key, refreshInterval);
    }
    
    /**
     * 取消键的刷新任务
     */
    public void cancelRefresh(K key) {
        ScheduledFuture<?> future = scheduledRefreshes.remove(key);
        if (future != null && !future.isDone()) {
            future.cancel(false);
            log.debug("Cancelled refresh for key {}", key);
        }
    }
    
    /**
     * 执行刷新操作
     */
    private void performRefresh(K key) {
        try {
            log.debug("Performing scheduled refresh for key {}", key);
            
            // 从数据源加载新值
            V newValue = cacheLoader.load(key);
            
            if (newValue != null) {
                // 回调更新缓存
                refreshCallback.refresh(key, newValue);
                
                // 重新调度下次刷新
                scheduleRefresh(key);
                
                log.debug("Successfully refreshed key {} with new value", key);
            } else {
                log.debug("Refresh returned null for key {}, removing from cache", key);
                // 数据不存在时，不需要重新调度
                scheduledRefreshes.remove(key);
            }
            
        } catch (Exception e) {
            log.warn("Failed to refresh key {}: {}", key, e.getMessage());
            
            // 刷新失败时，延长间隔重试
            ScheduledFuture<?> retryFuture = scheduler.schedule(
                () -> performRefresh(key),
                Math.min(refreshInterval.toMillis() * 2, Duration.ofMinutes(30).toMillis()),
                TimeUnit.MILLISECONDS
            );
            
            scheduledRefreshes.put(key, retryFuture);
        }
    }
    
    /**
     * 获取活跃刷新任务数量
     */
    public int getActiveRefreshCount() {
        return (int) scheduledRefreshes.values().stream()
            .filter(future -> !future.isDone())
            .count();
    }
    
    /**
     * 关闭调度器
     */
    public void shutdown() {
        log.info("Shutting down cache refresh scheduler");
        
        // 取消所有任务
        scheduledRefreshes.values().forEach(future -> {
            if (!future.isDone()) {
                future.cancel(false);
            }
        });
        
        scheduledRefreshes.clear();
        
        // 关闭调度器
        scheduler.shutdown();
        
        try {
            if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * 获取刷新统计信息
     */
    public RefreshStats getStats() {
        return new RefreshStats(
            scheduledRefreshes.size(),
            getActiveRefreshCount(),
            refreshInterval
        );
    }
    
    /**
     * 刷新统计信息
     */
    public static class RefreshStats {
        private final int totalScheduled;
        private final int activeRefreshes;
        private final Duration refreshInterval;
        
        public RefreshStats(int totalScheduled, int activeRefreshes, Duration refreshInterval) {
            this.totalScheduled = totalScheduled;
            this.activeRefreshes = activeRefreshes;
            this.refreshInterval = refreshInterval;
        }
        
        public int getTotalScheduled() { return totalScheduled; }
        public int getActiveRefreshes() { return activeRefreshes; }
        public Duration getRefreshInterval() { return refreshInterval; }
        
        @Override
        public String toString() {
            return String.format("RefreshStats[total=%d, active=%d, interval=%s]", 
                totalScheduled, activeRefreshes, refreshInterval);
        }
    }
}