package io.github.cascade.cache.examples;

import io.github.cascade.cache.core.Cache;
import io.github.cascade.cache.core.CacheRefresher;
import io.github.cascade.cache.core.CacheSync;
import io.github.cascade.cache.impl.*;
import io.github.cascade.cache.config.CascadeCacheProperties;
import org.redisson.api.RedissonClient;

import java.util.function.Function;

/**
 * @author lionel lionelk@163.com
 * =============================
 * Date: 2025/10/29
 * Time: 22:45
 * =============================
 *
 * DistributedAutoRefreshCache 使用示例
 * 演示如何在分布式环境下同时实现自动刷新和缓存同步
 */
public class DistributedAutoRefreshCacheExample {

    /**
     * 示例1：基础使用 - 创建分布式自动刷新缓存
     */
    public static void example1_BasicUsage(RedissonClient redissonClient,
                                           CascadeCacheProperties config) {
        System.out.println("=== 示例1：基础使用 ===\n");

        // 步骤1：创建 L1 本地缓存
        CaffeineL1Cache<String, User> l1Cache = new CaffeineL1Cache<>("users", config);

        // 步骤2：创建 L2 分布式缓存
        RedissonL2Cache<String, User> l2Cache = new RedissonL2Cache<>("users", redissonClient, config);

        // 步骤3：创建数据加载器
        Function<String, User> userLoader = userId -> {
            System.out.println("从数据库加载用户: " + userId);
            return new User(userId, "User-" + userId, "user" + userId + "@example.com");
        };

        // 步骤4：创建函数式缓存（L1 + L2 管道）
        FunctionalCache<String, User> functionalCache = new FunctionalCache<>(
                "users",
                String.class,
                User.class,
                l1Cache,
                l2Cache,
                userLoader,
                FunctionalCache.CacheStrategy.L1_L2
        );

        // 步骤5：创建刷新器
        CacheRefresher<String, User> refresher = new ScheduledCacheRefresher<>(
                "users",
                functionalCache,
                userLoader,
                config
        );

        // 步骤6：创建同步器
        CacheSync<String, User> cacheSync = new RedisCacheSync<>(
                redissonClient,
                "cache:sync:users"
        );

        // 步骤7：创建分布式自动刷新缓存（最终产物）
        DistributedAutoRefreshCache<String, User> cache = new DistributedAutoRefreshCache<>(
                functionalCache,
                refresher,
                cacheSync,
                "node-1",      // 节点ID
                300,           // 默认刷新间隔：5分钟
                true           // 自动启动刷新器
        );

        // ==================== 使用缓存 ====================

        System.out.println(">>> 第一次访问 user123（触发加载）");
        User user = cache.get("user123").orElse(null);
        System.out.println("结果: " + user);
        System.out.println("✅ 自动追踪到刷新列表，将在5分钟后自动刷新\n");

        System.out.println(">>> 第二次访问 user123（命中缓存）");
        user = cache.get("user123").orElse(null);
        System.out.println("结果: " + user);
        System.out.println("✅ 直接从缓存返回，不触发加载\n");

        System.out.println(">>> 更新缓存");
        User updatedUser = new User("user123", "Updated Name", "updated@example.com");
        cache.put("user123", updatedUser);
        System.out.println("✅ 本地缓存已更新");
        System.out.println("✅ 自动发布同步事件到 Redis Pub/Sub");
        System.out.println("✅ 其他节点会收到通知并更新本地缓存\n");

        System.out.println(">>> 已追踪的热点 key 数量: " + cache.getTrackedKeyCount());
        System.out.println(">>> 热点 key 列表: " + cache.getTrackedKeys());
    }

    /**
     * 示例2：分布式场景 - 多节点同步
     */
    public static void example2_MultiNodeSync(RedissonClient redissonClient,
                                              CascadeCacheProperties config) {
        System.out.println("\n=== 示例2：多节点同步 ===\n");

        // 创建两个节点的缓存实例
        DistributedAutoRefreshCache<String, User> node1Cache = createNodeCache(
                redissonClient, config, "node-1");
        DistributedAutoRefreshCache<String, User> node2Cache = createNodeCache(
                redissonClient, config, "node-2");

        System.out.println(">>> 节点1 写入数据");
        User user = new User("user456", "Alice", "alice@example.com");
        node1Cache.put("user456", user);
        System.out.println("✅ 节点1 本地缓存已更新");
        System.out.println("✅ 发布同步事件到 Redis Pub/Sub\n");

        // 模拟延迟
        sleep(100);

        System.out.println(">>> 节点2 读取数据");
        User userFromNode2 = node2Cache.get("user456").orElse(null);
        System.out.println("结果: " + userFromNode2);
        System.out.println("✅ 节点2 自动接收到同步事件，本地缓存已同步\n");

        System.out.println(">>> 节点1 删除缓存");
        node1Cache.evict("user456");
        System.out.println("✅ 节点1 本地缓存已删除");
        System.out.println("✅ 发布 EVICT 事件\n");

        // 模拟延迟
        sleep(100);

        System.out.println(">>> 节点2 检查缓存");
        boolean exists = node2Cache.containsKey("user456");
        System.out.println("是否存在: " + exists);
        System.out.println("✅ 节点2 自动接收到 EVICT 事件，本地缓存已删除");
    }

    /**
     * 示例3：自定义刷新间隔
     */
    public static void example3_CustomRefreshInterval(RedissonClient redissonClient,
                                                      CascadeCacheProperties config) {
        System.out.println("\n=== 示例3：自定义刷新间隔 ===\n");

        DistributedAutoRefreshCache<String, User> cache = createNodeCache(
                redissonClient, config, "node-1");

        System.out.println(">>> 设置不同 key 的刷新间隔");
        cache.setRefreshInterval("vip-user", 60L);      // VIP用户：1分钟刷新
        cache.setRefreshInterval("normal-user", 300L);  // 普通用户：5分钟刷新
        System.out.println("✅ 刷新间隔已设置\n");

        System.out.println(">>> 访问 vip-user");
        cache.get("vip-user");
        System.out.println("✅ 将在1分钟后自动刷新\n");

        System.out.println(">>> 访问 normal-user");
        cache.get("normal-user");
        System.out.println("✅ 将在5分钟后自动刷新\n");

        System.out.println(">>> 手动触发刷新");
        cache.manualRefresh("vip-user");
        System.out.println("✅ vip-user 已立即刷新");
        System.out.println("✅ 刷新后自动同步到其他节点");
    }

    /**
     * 示例4：使用静态工厂方法
     */
    public static void example4_StaticFactoryMethod(RedissonClient redissonClient,
                                                    CascadeCacheProperties config) {
        System.out.println("\n=== 示例4：使用静态工厂方法 ===\n");

        // 创建底层缓存
        FunctionalCache<String, User> functionalCache = createFunctionalCache(redissonClient, config);
        CacheRefresher<String, User> refresher = createRefresher(functionalCache, config);
        CacheSync<String, User> cacheSync = createCacheSync(redissonClient);

        // 使用静态工厂方法包装
        DistributedAutoRefreshCache<String, User> cache = DistributedAutoRefreshCache.wrap(
                functionalCache,
                refresher,
                cacheSync,
                "node-1",
                300  // 5分钟刷新间隔
        );

        System.out.println(">>> 使用静态工厂方法创建的缓存");
        System.out.println(cache);
        System.out.println("✅ 创建成功，功能完全相同");
    }

    /**
     * 示例5：监控和统计
     */
    public static void example5_MonitoringAndStats(RedissonClient redissonClient,
                                                   CascadeCacheProperties config) {
        System.out.println("\n=== 示例5：监控和统计 ===\n");

        DistributedAutoRefreshCache<String, User> cache = createNodeCache(
                redissonClient, config, "node-1");

        // 访问一些 key
        cache.get("user1");
        cache.get("user2");
        cache.get("user3");
        cache.put("user4", new User("user4", "Bob", "bob@example.com"));

        System.out.println(">>> 缓存统计信息");
        System.out.println("缓存名称: " + cache.getName());
        System.out.println("缓存大小: " + cache.size());
        System.out.println("已追踪的热点 key 数量: " + cache.getTrackedKeyCount());
        System.out.println("热点 key 列表: " + cache.getTrackedKeys());
        System.out.println("是否已关闭: " + cache.isClosed());
    }

    // ==================== 辅助方法 ====================

    private static DistributedAutoRefreshCache<String, User> createNodeCache(
            RedissonClient redissonClient,
            CascadeCacheProperties config,
            String nodeId) {

        FunctionalCache<String, User> functionalCache = createFunctionalCache(redissonClient, config);
        CacheRefresher<String, User> refresher = createRefresher(functionalCache, config);
        CacheSync<String, User> cacheSync = createCacheSync(redissonClient);

        return new DistributedAutoRefreshCache<>(
                functionalCache,
                refresher,
                cacheSync,
                nodeId,
                300,
                true
        );
    }

    private static FunctionalCache<String, User> createFunctionalCache(
            RedissonClient redissonClient,
            CascadeCacheProperties config) {

        CaffeineL1Cache<String, User> l1Cache = new CaffeineL1Cache<>("users", config);
        RedissonL2Cache<String, User> l2Cache = new RedissonL2Cache<>("users", redissonClient, config);

        Function<String, User> userLoader = userId ->
                new User(userId, "User-" + userId, "user" + userId + "@example.com");

        return new FunctionalCache<>(
                "users",
                String.class,
                User.class,
                l1Cache,
                l2Cache,
                userLoader,
                FunctionalCache.CacheStrategy.L1_L2
        );
    }

    private static CacheRefresher<String, User> createRefresher(
            Cache<String, User> cache,
            CascadeCacheProperties config) {

        Function<String, User> userLoader = userId ->
                new User(userId, "User-" + userId, "user" + userId + "@example.com");

        return new ScheduledCacheRefresher<>(
                "users",
                cache,
                userLoader,
                config
        );
    }

    private static CacheSync<String, User> createCacheSync(RedissonClient redissonClient) {
        return new RedisCacheSync<>(redissonClient, "cache:sync:users");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ==================== 测试用实体类 ====================

    static class User {
        private final String id;
        private final String name;
        private final String email;

        public User(String id, String name, String email) {
            this.id = id;
            this.name = name;
            this.email = email;
        }

        @Override
        public String toString() {
            return String.format("User{id='%s', name='%s', email='%s'}", id, name, email);
        }
    }
}