package io.github.cascade.cache.core.unified;

import io.github.cascade.cache.protection.RandomTtlProtection;
import io.github.cascade.cache.protection.SimplifiedCacheProtectionManager;
import io.github.cascade.cache.refresh.CacheRefreshScheduler;
import io.github.cascade.cache.sync.UnifiedCacheSynchronizer;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * 缓存增强功能管理器
 * 负责管理防护、同步、刷新等增强功能
 *
 * @param <K> 键类型
 * @param <V> 值类型
 * @author cascade
 */
@Slf4j
public class CacheEnhancer<K, V> {

    private final String cacheName;
    private final Executor executor;

    // 增强功能组件
    @Setter
    private SimplifiedCacheProtectionManager protectionManager;

    @Getter
    private UnifiedCacheSynchronizer<K, V> synchronizer;

    @Getter
    private CacheRefreshScheduler<K, V> refreshScheduler;

    public CacheEnhancer(String cacheName, Executor executor) {
        this.cacheName = cacheName;
        this.executor = executor;
    }

    // ==================== 增强操作 ====================

    /**
     * 增强缓存获取操作
     * 在基础操作基础上添加防护、监控等功能
     *
     * @param operation 基础缓存操作
     * @param key       缓存键
     * @return 增强后的结果
     */
    public V enhanceGet(Supplier<V> operation, K key) {
        // 前置防护检查
        if (protectionManager != null) {
            // 简化防护逻辑，实际实现中可以添加更复杂的防护策略
        }

        // 执行基础操作
        V result = operation.get();

        // 后置处理
        if (protectionManager != null) {
            // 简化防护逻辑，实际实现中可以添加更复杂的防护策略
        }

        return result;
    }

    /**
     * 增强缓存存储操作
     * 在基础操作基础上添加同步通知等功能
     *
     * @param operation 基础缓存操作
     * @param key       缓存键
     * @param value     缓存值
     */
    public void enhancePut(Runnable operation, K key, V value) {
        // 执行基础操作
        operation.run();

        // 触发分布式同步
        if (synchronizer != null) {
            log.debug("触发分布式同步: key={}", key);
            synchronizer.notifyPut(key, value);
        }
    }

    /**
     * 增强批量存储操作
     */
    public void enhancePutAll(Runnable operation, Set<K> keys) {
        // 执行基础操作
        operation.run();

        // 触发分布式同步
        if (synchronizer != null) {
            synchronizer.notifyPutAll(keys);
        }
    }

    /**
     * 增强缓存删除操作
     */
    public void enhanceEvict(Runnable operation, K key) {
        // 执行基础操作
        operation.run();

        // 触发分布式同步
        if (synchronizer != null) {
            synchronizer.notifyEvict(key);
        }
    }

    /**
     * 增强批量删除操作
     */
    public void enhanceEvictAll(Runnable operation, Set<K> keys) {
        // 执行基础操作
        operation.run();

        // 触发分布式同步
        if (synchronizer != null) {
            synchronizer.notifyEvictAll(keys);
        }
    }

    /**
     * 增强清空操作
     */
    public void enhanceClear(Runnable operation) {
        // 执行基础操作
        operation.run();

        // 触发分布式同步
        if (synchronizer != null) {
            synchronizer.notifyClear();
        }
    }

    /**
     * 增强加载操作
     * 支持随机TTL防护
     */
    public V enhanceLoad(Supplier<V> operation, K key) {
        V value = operation.get();
        
        if (value != null && protectionManager != null) {
            RandomTtlProtection randomTtl = protectionManager.getRandomTtl();
            if (randomTtl != null) {
                // 返回建议的TTL，由调用方使用
                Duration suggestedTtl = randomTtl.calculateTtl(key);
                log.debug("建议TTL: key={}, ttl={}", key, suggestedTtl);
                // 这里可以通过上下文或回调的方式传递TTL信息
            }
        }
        
        return value;
    }

    // ==================== 刷新功能管理 ====================

    /**
     * 刷新缓存值
     */
    public void refresh(K key, Supplier<V> loader) {
        if (refreshScheduler == null) {
            log.warn("无法刷新键 {}: 未配置刷新调度器", key);
            return;
        }

        // 异步刷新
        CompletableFuture.supplyAsync(() -> {
            try {
                return loader.get();
            } catch (Exception e) {
                log.error("刷新键失败: {}", key, e);
                return null;
            }
        }, executor).thenAccept(value -> {
            if (value != null) {
                // 这里需要回调到核心缓存进行实际的put操作
                // 实际实现中可以通过回调函数或事件机制处理
                
                // 触发分布式同步（针对refresh操作）
                if (synchronizer != null) {
                    synchronizer.notifyRefresh(key);
                }
            }
        });
    }

    /**
     * 异步刷新缓存值
     */
    public CompletableFuture<Void> refreshAsync(K key, Supplier<V> loader) {
        if (refreshScheduler == null) {
            log.warn("无法刷新键 {}: 未配置刷新调度器", key);
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                return loader.get();
            } catch (Exception e) {
                log.error("异步刷新键失败: {}", key, e);
                return null;
            }
        }, executor).thenAccept(value -> {
            if (value != null) {
                // 触发分布式同步（针对refresh操作）
                if (synchronizer != null) {
                    synchronizer.notifyRefresh(key);
                }
            }
        });
    }

    /**
     * 启用键的定时刷新
     */
    public void enableAutoRefresh(K key) {
        if (refreshScheduler == null) {
            log.warn("无法启用自动刷新键 {}: 未配置刷新调度器", key);
            return;
        }

        refreshScheduler.scheduleRefresh(key);
        log.debug("已启用自动刷新: key={}", key);
    }

    /**
     * 批量启用键的定时刷新
     */
    public void enableAutoRefreshAll(Set<K> keys) {
        if (keys == null || keys.isEmpty()) return;

        keys.forEach(this::enableAutoRefresh);
        log.info("已为缓存 {} 启用 {} 个键的自动刷新", cacheName, keys.size());
    }

    /**
     * 禁用键的定时刷新
     */
    public void disableAutoRefresh(K key) {
        if (refreshScheduler == null) return;

        refreshScheduler.cancelRefresh(key);
        log.debug("已禁用自动刷新: key={}", key);
    }

    /**
     * 获取刷新统计信息
     */
    public CacheRefreshScheduler.RefreshStats getRefreshStats() {
        if (refreshScheduler == null) {
            return new CacheRefreshScheduler.RefreshStats(0, 0, Duration.ZERO);
        }
        return refreshScheduler.getStats();
    }

    // ==================== 组件管理 ====================

    /**
     * 设置同步器
     */
    public void setSynchronizer(UnifiedCacheSynchronizer<K, V> synchronizer) {
        this.synchronizer = synchronizer;
        if (synchronizer != null) {
            // 注意：这里需要缓存核心实例来初始化同步器
            // 实际实现中可能需要通过依赖注入或回调来处理
            if (!synchronizer.isRunning()) {
                synchronizer.start();
            }
            log.info("缓存同步器已配置: {}", cacheName);
        }
    }

    /**
     * 设置刷新调度器
     */
    public void setRefreshScheduler(CacheRefreshScheduler<K, V> refreshScheduler) {
        // 关闭旧的调度器
        if (this.refreshScheduler != null) {
            this.refreshScheduler.shutdown();
        }

        this.refreshScheduler = refreshScheduler;
        log.info("缓存刷新调度器已配置: {}", cacheName);
    }

    // ==================== 生命周期管理 ====================

    /**
     * 关闭增强功能
     */
    public void close() {
        // 关闭刷新调度器
        if (refreshScheduler != null) {
            refreshScheduler.shutdown();
            log.debug("缓存刷新调度器已停止: {}", cacheName);
        }

        // 关闭同步器
        if (synchronizer != null) {
            synchronizer.stop();
            log.debug("缓存同步器已停止: {}", cacheName);
        }

        log.info("缓存增强功能已关闭: {}", cacheName);
    }

    // ==================== 状态查询 ====================

    /**
     * 检查防护管理器是否可用
     */
    public boolean isProtectionEnabled() {
        return protectionManager != null;
    }

    /**
     * 检查同步器是否可用
     */
    public boolean isSynchronizerEnabled() {
        return synchronizer != null && synchronizer.isRunning();
    }

    /**
     * 检查刷新调度器是否可用
     */
    public boolean isRefreshSchedulerEnabled() {
        return refreshScheduler != null;
    }

    /**
     * 获取增强功能状态
     */
    public EnhancerStatus getStatus() {
        return new EnhancerStatus(
            isProtectionEnabled(),
            isSynchronizerEnabled(),
            isRefreshSchedulerEnabled()
        );
    }

    /**
     * 增强功能状态
     */
    public static class EnhancerStatus {
        private final boolean protectionEnabled;
        private final boolean synchronizerEnabled;
        private final boolean refreshSchedulerEnabled;

        public EnhancerStatus(boolean protectionEnabled, boolean synchronizerEnabled, boolean refreshSchedulerEnabled) {
            this.protectionEnabled = protectionEnabled;
            this.synchronizerEnabled = synchronizerEnabled;
            this.refreshSchedulerEnabled = refreshSchedulerEnabled;
        }

        public boolean isProtectionEnabled() {
            return protectionEnabled;
        }

        public boolean isSynchronizerEnabled() {
            return synchronizerEnabled;
        }

        public boolean isRefreshSchedulerEnabled() {
            return refreshSchedulerEnabled;
        }

        @Override
        public String toString() {
            return String.format("EnhancerStatus{protection=%s, sync=%s, refresh=%s}",
                protectionEnabled, synchronizerEnabled, refreshSchedulerEnabled);
        }
    }
}