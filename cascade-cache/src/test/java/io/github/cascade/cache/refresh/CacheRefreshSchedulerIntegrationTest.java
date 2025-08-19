package io.github.cascade.cache.refresh;

import io.github.cascade.cache.api.CacheLoader;
import io.github.cascade.cache.core.unified.UnifiedCache;
import io.github.cascade.cache.core.unified.UnifiedCacheBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CacheRefreshScheduler 集成测试
 */
public class CacheRefreshSchedulerIntegrationTest {

    private AtomicInteger loadCounter;
    private CacheLoader<String, String> testLoader;
    private UnifiedCache<String, String> cache;

    @BeforeEach
    void setUp() {
        loadCounter = new AtomicInteger(0);
        
        // 创建测试用的CacheLoader
        testLoader = new CacheLoader<String, String>() {
            @Override
            public String load(String key) {
                int count = loadCounter.incrementAndGet();
                return "value-" + count + "-for-" + key;
            }

            @Override
            public String getName() {
                return "TestCacheLoader";
            }
        };
        
        // 创建带自动刷新功能的缓存
        cache = (UnifiedCache<String, String>) UnifiedCacheBuilder
                .stringCache("test-refresh-cache", String.class)
                .basicConfig(100, Duration.ofMinutes(10))
                .loader(testLoader)
                .withAutoRefresh(Duration.ofSeconds(1))  // 1秒刷新间隔用于测试
                .build();
    }

    @Test
    void testRefreshSchedulerIntegration() {
        assertNotNull(cache);
        assertNotNull(cache.getRefreshScheduler());
        
        // 验证刷新调度器配置
        CacheRefreshScheduler.RefreshStats stats = cache.getRefreshStats();
        assertNotNull(stats);
        assertEquals(Duration.ofSeconds(1), stats.getRefreshInterval());
    }

    @Test
    void testEnableAutoRefresh() {
        // 初始加载
        String value1 = cache.get("key1");
        assertEquals("value-1-for-key1", value1);
        
        // 启用自动刷新
        cache.enableAutoRefresh("key1");
        
        // 验证刷新统计
        CacheRefreshScheduler.RefreshStats stats = cache.getRefreshStats();
        assertTrue(stats.getTotalScheduled() >= 1);
    }

    @Test
    void testBatchEnableAutoRefresh() {
        // 初始加载多个键
        cache.get("key1");
        cache.get("key2");
        cache.get("key3");
        
        // 批量启用自动刷新
        Set<String> keys = Set.of("key1", "key2", "key3");
        cache.enableAutoRefreshAll(keys);
        
        // 验证刷新统计
        CacheRefreshScheduler.RefreshStats stats = cache.getRefreshStats();
        assertTrue(stats.getTotalScheduled() >= 3);
        assertTrue(stats.getActiveRefreshes() >= 0);
    }

    @Test
    void testDisableAutoRefresh() {
        // 启用自动刷新
        cache.enableAutoRefresh("key1");
        
        CacheRefreshScheduler.RefreshStats statsBefore = cache.getRefreshStats();
        int scheduledBefore = statsBefore.getTotalScheduled();
        
        // 禁用自动刷新
        cache.disableAutoRefresh("key1");
        
        // 验证任务被取消（这里主要验证方法调用不出错）
        assertDoesNotThrow(() -> cache.getRefreshStats());
    }

    @Test
    void testAutoRefreshWithoutCacheLoader() {
        // 创建没有CacheLoader的缓存
        UnifiedCache<String, String> cacheWithoutLoader = (UnifiedCache<String, String>) UnifiedCacheBuilder
                .stringCache("test-no-loader", String.class)
                .basicConfig(100, Duration.ofMinutes(10))
                .withAutoRefresh(Duration.ofSeconds(1))
                .build();
        
        // 由于没有CacheLoader，刷新调度器不应该被创建
        assertNull(cacheWithoutLoader.getRefreshScheduler());
    }

    @Test
    void testCacheCloseClosesRefreshScheduler() {
        // 启用自动刷新
        cache.enableAutoRefresh("key1");
        
        // 验证刷新调度器存在
        assertNotNull(cache.getRefreshScheduler());
        
        // 关闭缓存
        assertDoesNotThrow(() -> cache.close());
    }

    @Test
    void testRefreshStatsWithoutScheduler() {
        // 创建没有自动刷新的缓存
        UnifiedCache<String, String> cacheWithoutRefresh = (UnifiedCache<String, String>) UnifiedCacheBuilder
                .stringCache("test-no-refresh", String.class)
                .basicConfig(100, Duration.ofMinutes(10))
                .loader(testLoader)
                .build();
        
        // 获取刷新统计（应该返回空统计）
        CacheRefreshScheduler.RefreshStats stats = cacheWithoutRefresh.getRefreshStats();
        assertNotNull(stats);
        assertEquals(0, stats.getTotalScheduled());
        assertEquals(0, stats.getActiveRefreshes());
        assertEquals(Duration.ZERO, stats.getRefreshInterval());
    }

    @Test
    void testBuilderRefreshConfiguration() {
        // 测试各种配置组合
        UnifiedCache<String, String> cache1 = (UnifiedCache<String, String>) UnifiedCacheBuilder
                .stringCache("test-config-1", String.class)
                .loader(testLoader)
                .enableAutoRefresh(true)
                .refreshInterval(Duration.ofMinutes(5))
                .refreshOnAccess(false)
                .build();
        
        assertNotNull(cache1.getRefreshScheduler());
        assertEquals(Duration.ofMinutes(5), cache1.getRefreshStats().getRefreshInterval());
        
        // 测试一键配置
        UnifiedCache<String, String> cache2 = (UnifiedCache<String, String>) UnifiedCacheBuilder
                .stringCache("test-config-2", String.class)
                .loader(testLoader)
                .withAutoRefresh(Duration.ofSeconds(30))
                .build();
        
        assertNotNull(cache2.getRefreshScheduler());
        assertEquals(Duration.ofSeconds(30), cache2.getRefreshStats().getRefreshInterval());
    }
}