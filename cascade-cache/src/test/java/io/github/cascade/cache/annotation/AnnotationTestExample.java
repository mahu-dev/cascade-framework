package io.github.cascade.cache.annotation;

import io.github.cascade.cache.annotation.processor.CacheLoaderRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * 注解功能测试示例
 * 演示如何使用新实现的增强注解功能
 * 
 * @author cascade
 */
@Slf4j
@Service
public class AnnotationTestExample {
    
    // ==================== @CascadeCacheable 增强功能测试 ====================
    
    /**
     * 基本缓存功能测试
     */
    @CascadeCacheable(
        value = "userCache",
        key = "#userId",
        ttl = "PT30M",
        enableL1 = true,
        enableL2 = true,
        loader = "userCacheLoader"
    )
    public UserInfo getUserInfo(Long userId) {
        log.info("Loading user info from database for userId: {}", userId);
        // 模拟数据库查询
        return new UserInfo(userId, "User" + userId, "user" + userId + "@example.com");
    }
    
    /**
     * 基本缓存功能测试（不含刷新）
     */
    @CascadeCacheable(
        value = "productCache",
        key = "#productId",
        loader = "productCacheLoader",
        enableL1 = true,
        enableL2 = true,
        l1MaximumSize = 1000,
        l2DefaultTtl = "PT1H"
    )
    @CascadeCacheRefresh(
        value = "productCache",
        refreshInterval = "PT10M",
        allowConcurrentRefresh = false,
        loader = "productCacheLoader"
    )
    public ProductInfo getProductInfo(String productId) {
        log.info("Loading product info for productId: {}", productId);
        return new ProductInfo(productId, "Product " + productId, 99.99);
    }
    
    /**
     * 带布隆过滤器防护的缓存
     */
    @CascadeCacheable(
        value = "orderCache", 
        key = "#orderId",
        enableBloomFilter = true,
        bloomExpectedElements = 10000,
        bloomFalsePositiveRate = 0.01,
        enableDistributedLock = true,
        lockTimeout = "PT30S",
        loader = "orderCacheLoader"
    )
    public OrderInfo getOrderInfo(String orderId) {
        log.info("Loading order info for orderId: {}", orderId);
        return new OrderInfo(orderId, "Order " + orderId, 199.99, "PENDING");
    }
    
    /**
     * 启用随机TTL防护的缓存
     */
    @CascadeCacheable(
        value = "sessionCache",
        key = "#sessionId", 
        enableRandomTtl = true,
        randomTtlBase = "PT30M",
        randomTtlJitterRange = "PT10M",
        enableSync = true,
        syncTopic = "cache:session:sync",
        enableMonitoring = true,
        enableMetrics = true
    )
    public SessionInfo getSessionInfo(String sessionId) {
        log.info("Loading session info for sessionId: {}", sessionId);
        return new SessionInfo(sessionId, "user123", System.currentTimeMillis());
    }
    
    // ==================== @CacheLoaderMethod 功能测试 ====================
    
    /**
     * 用户缓存加载器
     */
    @CacheLoaderMethod(
        name = "userCacheLoader",
        cacheNames = "userCache",
        async = false,
        supportsBatch = true,
        maxBatchSize = 50,
        onFailure = CacheLoaderMethod.FailureStrategy.RETURN_NULL,
        enableMetrics = true
    )
    public UserInfo loadUserInfo(Long userId) {
        log.info("CacheLoader: Loading user info for userId: {}", userId);
        // 模拟数据库查询延迟
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return new UserInfo(userId, "LoaderUser" + userId, "loader" + userId + "@example.com");
    }
    
    /**
     * 产品缓存加载器 - 异步加载
     */
    @CacheLoaderMethod(
        name = "productCacheLoader",
        cacheNames = "productCache",
        async = true,
        asyncTimeout = "PT30S",
        onFailure = CacheLoaderMethod.FailureStrategy.FALLBACK,
        fallbackMethod = "loadProductFallback"
    )
    public CompletableFuture<ProductInfo> loadProductInfo(String productId) {
        return CompletableFuture.supplyAsync(() -> {
            log.info("CacheLoader: Async loading product info for productId: {}", productId);
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new ProductInfo(productId, "LoaderProduct " + productId, 88.88);
        });
    }
    
    /**
     * 产品加载器的降级方法
     */
    public ProductInfo loadProductFallback(String productId) {
        log.warn("Fallback: Loading default product info for productId: {}", productId);
        return new ProductInfo(productId, "Default Product", 0.0);
    }
    
    /**
     * 订单缓存加载器 - 批量加载
     */
    @CacheLoaderMethod(
        name = "orderCacheLoader",
        cacheNames = "orderCache",
        supportsBatch = true,
        maxBatchSize = 20,
        batchTimeout = "PT60S"
    )
    public Map<String, OrderInfo> loadOrderInfoBatch(Set<String> orderIds) {
        log.info("CacheLoader: Batch loading order info for orderIds: {}", orderIds);
        return orderIds.stream()
                .collect(java.util.stream.Collectors.toMap(
                    id -> id,
                    id -> new OrderInfo(id, "BatchOrder " + id, 299.99, "CONFIRMED")
                ));
    }
    
    // ==================== @CascadeCacheRefresh 功能测试 ====================
    
    /**
     * 专用刷新调度器配置
     */
    @CascadeCacheRefresh(
        value = "statsCache",
        refreshInterval = "PT5M",
        allowConcurrentRefresh = true,
        maxRetries = 3,
        retryInterval = "PT1M",
        enablePreload = true,
        preloadBatchSize = 10,
        preloadConcurrency = 2,
        loader = "statsCacheLoader"
    )
    public void setupStatsRefresh() {
        log.info("Configured refresh scheduler for stats cache");
    }
    
    @CacheLoaderMethod(
        name = "statsCacheLoader", 
        cacheNames = "statsCache"
    )
    public StatsInfo loadStatsInfo(String key) {
        log.info("CacheLoader: Loading stats info for key: {}", key);
        return new StatsInfo(key, System.currentTimeMillis(), Math.random() * 1000);
    }
    
    // ==================== @CascadeCacheEvict 和 @CascadeCachePut 测试 ====================
    
    /**
     * 缓存清理
     */
    @CascadeCacheEvict(
        value = "userCache",
        key = "#userId",
        beforeInvocation = false
    )
    public void deleteUser(Long userId) {
        log.info("Deleting user: {}", userId);
        // 模拟删除用户操作
    }
    
    /**
     * 缓存更新
     */
    @CascadeCachePut(
        value = "userCache",
        key = "#userInfo.id",
        ttl = "PT30M"
    )
    public UserInfo updateUser(UserInfo userInfo) {
        log.info("Updating user: {}", userInfo);
        // 模拟更新用户操作
        return userInfo;
    }
    
    // ==================== 数据模型类 ====================
    
    public static class UserInfo {
        public final Long id;
        public final String name;
        public final String email;
        
        public UserInfo(Long id, String name, String email) {
            this.id = id;
            this.name = name;
            this.email = email;
        }
        
        @Override
        public String toString() {
            return String.format("UserInfo{id=%d, name='%s', email='%s'}", id, name, email);
        }
    }
    
    public static class ProductInfo {
        public final String id;
        public final String name; 
        public final Double price;
        
        public ProductInfo(String id, String name, Double price) {
            this.id = id;
            this.name = name;
            this.price = price;
        }
        
        @Override
        public String toString() {
            return String.format("ProductInfo{id='%s', name='%s', price=%.2f}", id, name, price);
        }
    }
    
    public static class OrderInfo {
        public final String id;
        public final String description;
        public final Double amount;
        public final String status;
        
        public OrderInfo(String id, String description, Double amount, String status) {
            this.id = id;
            this.description = description;
            this.amount = amount;
            this.status = status;
        }
        
        @Override
        public String toString() {
            return String.format("OrderInfo{id='%s', description='%s', amount=%.2f, status='%s'}", 
                    id, description, amount, status);
        }
    }
    
    public static class SessionInfo {
        public final String sessionId;
        public final String userId;
        public final Long timestamp;
        
        public SessionInfo(String sessionId, String userId, Long timestamp) {
            this.sessionId = sessionId;
            this.userId = userId;
            this.timestamp = timestamp;
        }
        
        @Override
        public String toString() {
            return String.format("SessionInfo{sessionId='%s', userId='%s', timestamp=%d}", 
                    sessionId, userId, timestamp);
        }
    }
    
    public static class StatsInfo {
        public final String key;
        public final Long timestamp;
        public final Double value;
        
        public StatsInfo(String key, Long timestamp, Double value) {
            this.key = key;
            this.timestamp = timestamp;
            this.value = value;
        }
        
        @Override
        public String toString() {
            return String.format("StatsInfo{key='%s', timestamp=%d, value=%.2f}", 
                    key, timestamp, value);
        }
    }
}