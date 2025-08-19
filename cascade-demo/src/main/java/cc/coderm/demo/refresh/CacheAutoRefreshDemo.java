package cc.coderm.demo.refresh;

import io.github.cascade.cache.api.Cache;
import io.github.cascade.cache.api.CacheLoader;
import io.github.cascade.cache.core.unified.UnifiedCache;
import io.github.cascade.cache.core.unified.UnifiedCacheBuilder;
import io.github.cascade.cache.refresh.CacheRefreshScheduler;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 缓存自动刷新演示
 */
@Component
public class CacheAutoRefreshDemo {

    private Cache<String, String> autoRefreshCache;

    /**
     * 初始化带自动刷新功能的缓存
     */
    public void initializeAutoRefreshCache() {
        // 创建测试用的CacheLoader
        CacheLoader<String, String> dataLoader = new CacheLoader<String, String>() {
            @Override
            public String load(String key) {
                // 模拟从数据库或外部API加载数据
                String timestamp = LocalDateTime.now().toString();
                System.out.println("🔄 Loading data for key: " + key + " at " + timestamp);
                return "Fresh data for " + key + " loaded at " + timestamp;
            }

            @Override
            public String getName() {
                return "DemoDataLoader";
            }
        };

        // 创建具有自动刷新功能的缓存
        autoRefreshCache = UnifiedCacheBuilder
                .stringCache("auto-refresh-demo", String.class)
                // 基础配置
                .basicConfig(1000, Duration.ofMinutes(30))
                // 设置数据加载器
                .loader(dataLoader)
                // 启用自动刷新 - 每10秒刷新一次（演示用）
                .withAutoRefresh(Duration.ofSeconds(10))
                .build();

        System.out.println("✅ 自动刷新缓存初始化完成");
    }

    /**
     * 演示自动刷新功能
     */
    public void demonstrateAutoRefresh() throws InterruptedException {
        System.out.println("\n=== 缓存自动刷新功能演示 ===");

        // 1. 初始数据加载
        System.out.println("\n1. 初始加载数据:");
        String value1 = autoRefreshCache.get("user:1001");
        String value2 = autoRefreshCache.get("user:1002");
        System.out.println("user:1001 = " + value1);
        System.out.println("user:1002 = " + value2);

        // 2. 启用这些键的自动刷新
        System.out.println("\n2. 启用自动刷新:");
        if (autoRefreshCache instanceof UnifiedCache) {
            UnifiedCache<String, String> unifiedCache = (UnifiedCache<String, String>) autoRefreshCache;
            
            // 启用单个键的自动刷新
            unifiedCache.enableAutoRefresh("user:1001");
            
            // 批量启用多个键的自动刷新
            Set<String> keys = Set.of("user:1002");
            unifiedCache.enableAutoRefreshAll(keys);
            
            System.out.println("已启用 user:1001 和 user:1002 的自动刷新");
        }

        // 3. 显示刷新统计
        showRefreshStats();

        // 4. 等待并观察自动刷新
        System.out.println("\n3. 等待自动刷新发生 (20秒)...");
        for (int i = 0; i < 4; i++) {
            Thread.sleep(5000);  // 等待5秒
            System.out.println("\n⏰ " + (i + 1) * 5 + "秒后状态:");
            
            // 读取缓存数据（不会触发加载，只是读取）
            String currentValue1 = autoRefreshCache.get("user:1001");
            String currentValue2 = autoRefreshCache.get("user:1002");
            
            System.out.println("user:1001 = " + (currentValue1.length() > 80 ? 
                currentValue1.substring(0, 80) + "..." : currentValue1));
            System.out.println("user:1002 = " + (currentValue2.length() > 80 ? 
                currentValue2.substring(0, 80) + "..." : currentValue2));
            
            // 显示刷新统计
            showRefreshStats();
        }

        // 5. 禁用自动刷新
        System.out.println("\n4. 禁用自动刷新:");
        if (autoRefreshCache instanceof UnifiedCache) {
            UnifiedCache<String, String> unifiedCache = (UnifiedCache<String, String>) autoRefreshCache;
            unifiedCache.disableAutoRefresh("user:1001");
            unifiedCache.disableAutoRefresh("user:1002");
            System.out.println("已禁用所有键的自动刷新");
        }
    }

    /**
     * 演示手动刷新vs自动刷新
     */
    public void demonstrateRefreshComparison() {
        System.out.println("\n=== 手动刷新 vs 自动刷新对比 ===");

        // 手动刷新
        System.out.println("\n📖 手动刷新:");
        String beforeRefresh = autoRefreshCache.get("manual-key");
        System.out.println("刷新前: " + beforeRefresh);

        if (autoRefreshCache instanceof UnifiedCache) {
            UnifiedCache<String, String> unifiedCache = (UnifiedCache<String, String>) autoRefreshCache;
            unifiedCache.refresh("manual-key");  // 手动触发刷新
        }

        // 等待一下让异步刷新完成
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        String afterRefresh = autoRefreshCache.get("manual-key");
        System.out.println("刷新后: " + afterRefresh);

        // 自动刷新
        System.out.println("\n🔄 自动刷新:");
        System.out.println("自动刷新会在后台定期更新数据，无需手动触发");
        if (autoRefreshCache instanceof UnifiedCache) {
            UnifiedCache<String, String> unifiedCache = (UnifiedCache<String, String>) autoRefreshCache;
            unifiedCache.enableAutoRefresh("auto-key");
            System.out.println("已为 auto-key 启用自动刷新");
        }
    }

    /**
     * 显示刷新统计信息
     */
    private void showRefreshStats() {
        if (autoRefreshCache instanceof UnifiedCache) {
            UnifiedCache<String, String> unifiedCache = (UnifiedCache<String, String>) autoRefreshCache;
            CacheRefreshScheduler.RefreshStats stats = unifiedCache.getRefreshStats();
            
            System.out.println("📊 刷新统计:");
            System.out.println("  - 已调度任务数: " + stats.getTotalScheduled());
            System.out.println("  - 活跃刷新任务: " + stats.getActiveRefreshes());
            System.out.println("  - 刷新间隔: " + stats.getRefreshInterval().toSeconds() + "秒");
        }
    }

    /**
     * 演示缓存的完整生命周期
     */
    public void demonstrateLifecycle() {
        System.out.println("\n=== 缓存生命周期演示 ===");

        // 创建缓存
        System.out.println("1. 创建缓存...");
        initializeAutoRefreshCache();

        // 使用缓存
        System.out.println("2. 使用缓存...");
        autoRefreshCache.get("lifecycle-key");

        // 启用自动刷新
        System.out.println("3. 启用自动刷新...");
        if (autoRefreshCache instanceof UnifiedCache) {
            UnifiedCache<String, String> unifiedCache = (UnifiedCache<String, String>) autoRefreshCache;
            unifiedCache.enableAutoRefresh("lifecycle-key");
        }

        // 显示状态
        System.out.println("4. 显示状态...");
        showRefreshStats();

        // 关闭缓存
        System.out.println("5. 关闭缓存...");
        if (autoRefreshCache instanceof UnifiedCache) {
            UnifiedCache<String, String> unifiedCache = (UnifiedCache<String, String>) autoRefreshCache;
            unifiedCache.close();
            System.out.println("✅ 缓存已关闭，所有资源已清理");
        }
    }

    /**
     * 清理资源
     */
    public void cleanup() {
        if (autoRefreshCache instanceof UnifiedCache) {
            ((UnifiedCache<String, String>) autoRefreshCache).close();
            System.out.println("🧹 缓存资源清理完成");
        }
    }
}