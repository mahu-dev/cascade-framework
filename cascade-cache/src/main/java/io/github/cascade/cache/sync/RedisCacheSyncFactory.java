package io.github.cascade.cache.sync;

import io.github.cascade.cache.config.SyncProperties;
import io.github.cascade.cache.definition.CacheSyncFactory;
import io.github.cascade.cache.core.CacheSync;
import io.github.cascade.cache.impl.RedisCacheSync;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Redis缓存同步工厂
 * <p>
 * 设计原则：
 * 1. 单一职责：专门创建Redis类型的缓存同步器
 * 2. 依赖注入：使用Spring管理RedissonClient依赖
 * 3. 优雅降级：当Redis不可用时提供空实现
 * 4. 日志记录：详细记录创建过程
 *
 * @author cascade
 */
public record RedisCacheSyncFactory(RedissonClient redissonClient) implements CacheSyncFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger(RedisCacheSyncFactory.class);

    @Override
    public <K, V> CacheSync<K, V> createCacheSync(SyncProperties syncConfig) {
        if (!supports(syncConfig.getType())) {
            throw new IllegalArgumentException("不支持的同步类型: " + syncConfig.getType());
        }

        if (!syncConfig.isEnabled()) {
            LOGGER.debug("同步功能未启用，返回空同步器");
            return createNoOpCacheSync();
        }

        return Optional.ofNullable(redissonClient)
                .filter(RedisCacheSyncFactory::isRedisAvailable)
                .<CacheSync<K, V>>map(client -> createRedisCacheSync(client, syncConfig))
                .orElseGet(() -> {
                    LOGGER.warn("Redis不可用，使用空同步器实现");
                    return createNoOpCacheSync();
                });
    }

    @Override
    public boolean supports(SyncProperties.SyncType syncType) {
        return syncType == SyncProperties.SyncType.REDIS;
    }

    // ==================== 私有方法 ====================

    /**
     * 创建Redis缓存同步器
     */
    private static <K, V> CacheSync<K, V> createRedisCacheSync(RedissonClient client, SyncProperties config) {
        try {
            RedisCacheSync<K, V> sync = new RedisCacheSync<>(client, config.getTopicPrefix());
            LOGGER.info("创建Redis缓存同步器成功: topicPrefix={}", config.getTopicPrefix());
            return sync;
        } catch (RuntimeException e) {
            LOGGER.error("创建Redis缓存同步器失败: {}", e.getMessage(), e);
            return createNoOpCacheSync();
        }
    }

    /**
     * 创建空操作同步器
     */
    private static <K, V> CacheSync<K, V> createNoOpCacheSync() {
        return new NoOpCacheSync<>();
    }

    /**
     * 检查Redis是否可用
     */
    private static boolean isRedisAvailable(RedissonClient client) {
        try {
            // 使用简单的测试操作来检查Redis连接
            // 尝试获取一个测试键的存在性，这是轻量级操作
            String testKey = "cascade:cache:ping:" + System.currentTimeMillis();
            client.getBucket(testKey).isExists();
            return true;
        } catch (RuntimeException e) {
            LOGGER.warn("Redis连接检查失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 空操作同步器实现（当Redis不可用时的降级方案）
     */
    private static class NoOpCacheSync<K, V> implements CacheSync<K, V> {
        private static final Logger log = LoggerFactory.getLogger(NoOpCacheSync.class);

        @Override
        public CompletableFuture<Void> publishEvent(SyncEvent<K, V> event) {
            LOGGER.trace("空同步器忽略事件: {}", event);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void subscribe(String cacheName, Consumer<SyncEvent<K, V>> eventHandler) {
            LOGGER.debug("空同步器忽略订阅: cacheName={}", cacheName);
        }

        @Override
        public void unsubscribe(String cacheName) {
            LOGGER.debug("空同步器忽略取消订阅: cacheName={}", cacheName);
        }

        @Override
        public void start() {
            LOGGER.debug("空同步器启动（无操作）");
        }

        @Override
        public void stop() {
            LOGGER.debug("空同步器停止（无操作）");
        }

        @Override
        public boolean isRunning() {
            return true; // 空实现总是"运行中"
        }

        @Override
        public String toString() {
            return "NoOpCacheSync{}";
        }
    }
}