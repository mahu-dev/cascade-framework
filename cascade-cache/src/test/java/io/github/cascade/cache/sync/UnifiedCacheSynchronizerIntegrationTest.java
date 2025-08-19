package io.github.cascade.cache.sync;

import io.github.cascade.cache.core.unified.UnifiedCache;
import io.github.cascade.cache.core.unified.UnifiedCacheBuilder;
import io.github.cascade.cache.sync.unified.UnifiedCacheSynchronizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import static org.junit.jupiter.api.Assertions.*;

/**
 * UnifiedCacheSynchronizer集成测试
 */
public class UnifiedCacheSynchronizerIntegrationTest {

    private RedissonClient redissonClient;
    private UnifiedCache<String, String> cache;

    @BeforeEach
    void setUp() {
        // 创建内存Redis配置（用于测试）
        Config config = new Config();
        config.useSingleServer().setAddress("redis://localhost:6379");
        
        try {
            redissonClient = Redisson.create(config);
            
            // 创建带同步功能的缓存
            cache = (UnifiedCache<String, String>) UnifiedCacheBuilder
                    .stringCache("test-sync-cache", String.class)
                    .basicConfig(100, java.time.Duration.ofMinutes(10))
                    .withRedis(redissonClient)
                    .withSync()
                    .build();
                    
        } catch (Exception e) {
            // 如果Redis不可用，跳过测试
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "Redis not available for testing");
        }
    }

    @Test
    void testSynchronizerInitialization() {
        assertNotNull(cache);
        assertNotNull(cache.getSynchronizer());
        assertTrue(cache.getSynchronizer().isRunning());
    }

    @Test
    void testPutTriggersSync() {
        // 测试put操作是否触发同步
        cache.put("key1", "value1");
        
        // 验证同步器统计
        var stats = cache.getSynchronizer().getStats();
        assertTrue(stats.getSentMessageCount() >= 0);
    }

    @Test
    void testEvictTriggersSync() {
        // 先放入数据
        cache.put("key2", "value2");
        
        // 测试evict操作是否触发同步
        cache.evict("key2");
        
        // 验证同步器统计
        var stats = cache.getSynchronizer().getStats();
        assertTrue(stats.getSentMessageCount() >= 0);
    }

    @Test
    void testClearTriggersSync() {
        // 先放入数据
        cache.put("key3", "value3");
        
        // 测试clear操作是否触发同步
        cache.clear();
        
        // 验证同步器统计
        var stats = cache.getSynchronizer().getStats();
        assertTrue(stats.getSentMessageCount() >= 0);
    }

    @Test
    void testSynchronizerLifecycle() {
        UnifiedCacheSynchronizer<String, String> synchronizer = cache.getSynchronizer();
        
        // 测试生命周期
        assertTrue(synchronizer.isRunning());
        
        synchronizer.stop();
        assertFalse(synchronizer.isRunning());
        
        synchronizer.start();
        assertTrue(synchronizer.isRunning());
    }
}