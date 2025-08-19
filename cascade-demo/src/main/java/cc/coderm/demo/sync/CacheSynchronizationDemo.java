package cc.coderm.demo.sync;

import io.github.cascade.cache.api.Cache;
import io.github.cascade.cache.core.unified.UnifiedCacheBuilder;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 分布式缓存同步演示
 */
@Component
public class CacheSynchronizationDemo {

    private RedissonClient redissonClient;
    private Cache<String, String> distributedCache;

    /**
     * 初始化分布式缓存
     */
    public void initializeDistributedCache() {
        // 创建Redis配置
        Config config = new Config();
        config.useSingleServer().setAddress("redis://localhost:6379");
        redissonClient = Redisson.create(config);

        // 创建具有完整分布式同步功能的缓存
        distributedCache = UnifiedCacheBuilder
                .stringCache("user-session-cache", String.class)
                // 基础配置
                .basicConfig(1000, Duration.ofMinutes(30))
                // 启用L2 Redis缓存
                .withRedis(redissonClient)
                // 启用分布式同步
                .withSync()
                // 启用保护机制
                .withProtection()
                .build();

        System.out.println("分布式缓存初始化完成，已启用同步功能");
    }

    /**
     * 演示分布式同步操作
     */
    public void demonstrateSyncOperations() {
        System.out.println("=== 分布式缓存同步演示 ===");

        // 1. 写入数据 - 会触发分布式同步
        System.out.println("1. 写入用户会话数据...");
        distributedCache.put("user:1001", "session-token-abc123");
        distributedCache.put("user:1002", "session-token-def456");
        distributedCache.put("user:1003", "session-token-ghi789");

        // 2. 读取数据
        System.out.println("2. 读取用户会话数据...");
        String session1 = distributedCache.get("user:1001");
        System.out.println("用户1001的会话: " + session1);

        // 3. 删除数据 - 会触发分布式同步
        System.out.println("3. 删除用户会话数据...");
        distributedCache.evict("user:1002");

        // 4. 清空缓存 - 会触发分布式同步
        System.out.println("4. 清空所有会话数据...");
        // distributedCache.clear();

        // 5. 显示同步统计信息
        showSyncStats();
    }

    /**
     * 显示同步统计信息
     */
    private void showSyncStats() {
        if (distributedCache instanceof io.github.cascade.cache.core.unified.UnifiedCache) {
            var unifiedCache = (io.github.cascade.cache.core.unified.UnifiedCache<String, String>) distributedCache;
            var synchronizer = unifiedCache.getSynchronizer();
            
            if (synchronizer != null) {
                System.out.println("=== 同步统计信息 ===");
                var stats = synchronizer.getStats();
                System.out.println("发送消息数: " + stats.getSentMessageCount());
                System.out.println("接收消息数: " + stats.getReceivedMessageCount());
                System.out.println("失败消息数: " + stats.getFailedMessageCount());
                System.out.println("平均发送时间: " + String.format("%.2f ms", stats.getAverageSendTime() / 1_000_000.0));
                System.out.println("上次同步时间: " + stats.getLastSyncTime());
            }
        }
    }

    /**
     * 模拟多节点同步场景
     */
    public void simulateMultiNodeSync() throws InterruptedException {
        System.out.println("=== 多节点同步模拟 ===");

        // 创建第二个缓存实例（模拟另一个节点）
        Cache<String, String> cache2 = UnifiedCacheBuilder
                .stringCache("user-session-cache", String.class)
                .basicConfig(1000, Duration.ofMinutes(30))
                .withRedis(redissonClient)
                .withSync()
                .build();

        // 节点1写入数据
        System.out.println("节点1写入数据...");
        distributedCache.put("node1:key", "value-from-node1");

        // 等待同步
        Thread.sleep(100);

        // 节点2读取数据（应该能读到节点1写入的数据）
        System.out.println("节点2尝试读取节点1的数据...");
        String value = cache2.get("node1:key");
        System.out.println("节点2读取到: " + value);

        // 节点2写入数据
        System.out.println("节点2写入数据...");
        cache2.put("node2:key", "value-from-node2");

        // 等待同步
        Thread.sleep(100);

        // 节点1读取数据（应该能读到节点2写入的数据）
        System.out.println("节点1尝试读取节点2的数据...");
        String value2 = distributedCache.get("node2:key");
        System.out.println("节点1读取到: " + value2);

        // 显示两个节点的统计信息
        System.out.println("节点1统计信息:");
        showSyncStats();
        
        if (cache2 instanceof io.github.cascade.cache.core.unified.UnifiedCache) {
            var unifiedCache2 = (io.github.cascade.cache.core.unified.UnifiedCache<String, String>) cache2;
            var synchronizer2 = unifiedCache2.getSynchronizer();
            
            if (synchronizer2 != null) {
                System.out.println("节点2统计信息:");
                var stats2 = synchronizer2.getStats();
                System.out.println("发送消息数: " + stats2.getSentMessageCount());
                System.out.println("接收消息数: " + stats2.getReceivedMessageCount());
                System.out.println("失败消息数: " + stats2.getFailedMessageCount());
            }
        }
    }

    /**
     * 关闭资源
     */
    public void cleanup() {
        if (redissonClient != null) {
            redissonClient.shutdown();
        }
        System.out.println("资源清理完成");
    }
}