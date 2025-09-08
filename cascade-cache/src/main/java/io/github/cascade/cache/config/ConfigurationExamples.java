package io.github.cascade.cache.config;

import java.time.Duration;

/**
 * 增强配置系统使用示例
 * <p>
 * 展示如何使用新的Builder模式和函数式API创建复杂的缓存配置
 *
 * @author cascade
 */
public class ConfigurationExamples {

    /**
     * 基础Builder模式示例
     */
    public static EnhancedCacheConfiguration basicExample() {
        return EnhancedCacheConfiguration.namedBuilder("userCache")
                .enabled(true)
                .type(EnhancedCacheConfiguration.CacheType.TIERED)
                .l1(EnhancedCacheConfiguration.L1Config.builder()
                        .maximumSize(10000)
                        .expireAfterWrite(Duration.ofMinutes(30))
                        .recordStats(true)
                        .build())
                .l2(EnhancedCacheConfiguration.L2Config.builder()
                        .enabled(true)
                        .keyPrefix("user:")
                        .defaultTtl(Duration.ofHours(2))
                        .build())
                .build();
    }

    /**
     * DSL风格配置示例
     */
    public static EnhancedCacheConfiguration dslExample() {
        return EnhancedCacheConfiguration.configure("productCache", builder -> builder
                .type(EnhancedCacheConfiguration.CacheType.TIERED)
                .l1(EnhancedCacheConfiguration.L1Config.builder()
                        .maximumSize(5000)
                        .expireAfterAccess(Duration.ofMinutes(15))
                        .build())
                .l2(EnhancedCacheConfiguration.L2Config.builder()
                        .enabled(true)
                        .keyPrefix("product:")
                        .defaultTtl(Duration.ofHours(1))
                        .enableBatch(true)
                        .batchSize(200)
                        .build())
                .sync(EnhancedCacheConfiguration.SyncConfig.builder()
                        .enabled(true)
                        .type(EnhancedCacheConfiguration.SyncConfig.SyncType.REDIS)
                        .topicPrefix("product:sync:")
                        .build()));
    }

    /**
     * 条件配置示例
     */
    public static EnhancedCacheConfiguration conditionalExample() {
        boolean isProduction = "production".equals(System.getProperty("spring.profiles.active"));
        boolean highMemoryEnvironment = Runtime.getRuntime().maxMemory() > 4L * 1024 * 1024 * 1024; // 4GB

        var builder = EnhancedCacheConfiguration.namedBuilder("conditionalCache")
                .type(EnhancedCacheConfiguration.CacheType.TIERED);
                
        // 使用函数式组合应用条件配置
        builder = EnhancedCacheConfiguration.when(isProduction, 
                EnhancedCacheConfiguration.configureL1(l1 -> l1.maximumSize(50000)))
                .apply(builder);
                
        builder = EnhancedCacheConfiguration.when(isProduction,
                EnhancedCacheConfiguration.configureSync(sync -> sync
                        .enabled(true)
                        .type(EnhancedCacheConfiguration.SyncConfig.SyncType.REDIS)))
                .apply(builder);
                
        builder = EnhancedCacheConfiguration.when(highMemoryEnvironment,
                EnhancedCacheConfiguration.highPerformance())
                .apply(builder);
                
        builder = EnhancedCacheConfiguration.unless(highMemoryEnvironment,
                EnhancedCacheConfiguration.lowMemory())
                .apply(builder);

        return builder.build();
    }

    /**
     * 环境相关配置示例
     */
    public static EnhancedCacheConfiguration environmentSpecificExample() {
        var builder = EnhancedCacheConfiguration.namedBuilder("sessionCache");
        
        // 根据环境应用不同配置
        builder = EnhancedCacheConfiguration.forEnvironment("development", b -> b
                .type(EnhancedCacheConfiguration.CacheType.L1_ONLY)
                .l1(EnhancedCacheConfiguration.L1Config.builder()
                        .maximumSize(100)
                        .expireAfterWrite(Duration.ofMinutes(5))
                        .build()))
                .apply(builder);
                        
        builder = EnhancedCacheConfiguration.forEnvironment("testing", b -> b
                .type(EnhancedCacheConfiguration.CacheType.L1_ONLY)
                .l1(EnhancedCacheConfiguration.L1Config.builder()
                        .maximumSize(500)
                        .expireAfterWrite(Duration.ofMinutes(10))
                        .build()))
                .apply(builder);
                        
        builder = EnhancedCacheConfiguration.forEnvironment("production", b -> b
                .type(EnhancedCacheConfiguration.CacheType.TIERED)
                .l1(EnhancedCacheConfiguration.L1Config.builder()
                        .maximumSize(10000)
                        .expireAfterWrite(Duration.ofMinutes(30))
                        .build())
                .l2(EnhancedCacheConfiguration.L2Config.builder()
                        .enabled(true)
                        .defaultTtl(Duration.ofHours(2))
                        .build())
                .sync(EnhancedCacheConfiguration.SyncConfig.builder()
                        .enabled(true)
                        .type(EnhancedCacheConfiguration.SyncConfig.SyncType.REDIS)
                        .build()))
                .apply(builder);
                
        return builder.build();
    }

    /**
     * 预设配置示例
     */
    public static void presetExamples() {
        // 仅L1缓存
        EnhancedCacheConfiguration l1Only = EnhancedCacheConfiguration.l1Only("simpleCache");

        // 仅L2缓存
        EnhancedCacheConfiguration l2Only = EnhancedCacheConfiguration.l2Only("distributedCache");

        // 多级缓存
        EnhancedCacheConfiguration tiered = EnhancedCacheConfiguration.tiered("complexCache");

        System.out.println("预设配置创建完成:");
        System.out.println("L1 Only: " + l1Only.name() + " (Type: " + l1Only.type() + ")");
        System.out.println("L2 Only: " + l2Only.name() + " (Type: " + l2Only.type() + ")");
        System.out.println("Tiered: " + tiered.name() + " (Type: " + tiered.type() + ")");
    }

    /**
     * 配置修改和复制示例
     */
    public static EnhancedCacheConfiguration modificationExample() {
        // 创建基础配置
        EnhancedCacheConfiguration baseConfig = EnhancedCacheConfiguration.l1Only("baseCache");

        // 基于现有配置创建新配置
        return baseConfig.toBuilder()
                .name("modifiedCache")
                .type(EnhancedCacheConfiguration.CacheType.TIERED)
                .l2(EnhancedCacheConfiguration.L2Config.builder()
                        .enabled(true)
                        .keyPrefix("modified:")
                        .defaultTtl(Duration.ofHours(1))
                        .build())
                .sync(EnhancedCacheConfiguration.SyncConfig.builder()
                        .enabled(true)
                        .type(EnhancedCacheConfiguration.SyncConfig.SyncType.REDIS)
                        .topicPrefix("modified:sync:")
                        .build())
                .build();
    }

    /**
     * 复杂业务场景配置示例
     */
    public static EnhancedCacheConfiguration businessScenarioExample() {
        return EnhancedCacheConfiguration.configure("businessCache", builder -> builder
                .type(EnhancedCacheConfiguration.CacheType.TIERED)
                .l1(EnhancedCacheConfiguration.L1Config.builder()
                        .maximumSize(20000)
                        .expireAfterWrite(Duration.ofMinutes(20))
                        .expireAfterAccess(Duration.ofMinutes(10))
                        .recordStats(true)
                        .initialCapacity(1000)
                        .build())
                .l2(EnhancedCacheConfiguration.L2Config.builder()
                        .enabled(true)
                        .keyPrefix("business:")
                        .defaultTtl(Duration.ofHours(4))
                        .enableBatch(true)
                        .batchSize(500)
                        .serializer("protobuf")
                        .build())
                .sync(EnhancedCacheConfiguration.SyncConfig.builder()
                        .enabled(true)
                        .type(EnhancedCacheConfiguration.SyncConfig.SyncType.REDIS)
                        .topicPrefix("business:sync:")
                        .build())
                .refresh(EnhancedCacheConfiguration.RefreshConfig.builder()
                        .enabled(true)
                        .defaultInterval(Duration.ofMinutes(15))
                        .build())
                .loader(EnhancedCacheConfiguration.LoaderConfig.builder()
                        .autoDiscover(true)
                        .timeout(Duration.ofSeconds(10))
                        .build()));
    }

    /**
     * 性能优化配置示例
     */
    public static EnhancedCacheConfiguration performanceOptimizedExample() {
        var builder = EnhancedCacheConfiguration.namedBuilder("performanceCache");
        
        // 应用性能预设
        builder = EnhancedCacheConfiguration.highPerformance().apply(builder);
        
        return builder
                .l1(EnhancedCacheConfiguration.L1Config.builder()
                        .maximumSize(100000)
                        .expireAfterWrite(Duration.ofMinutes(45))
                        .initialCapacity(10000)
                        .build())
                .l2(EnhancedCacheConfiguration.L2Config.builder()
                        .enabled(true)
                        .enableBatch(true)
                        .batchSize(1000)
                        .defaultTtl(Duration.ofHours(6))
                        .build())
                .sync(EnhancedCacheConfiguration.SyncConfig.builder()
                        .enabled(true)
                        .type(EnhancedCacheConfiguration.SyncConfig.SyncType.REDIS)
                        .topicPrefix("perf:")
                        .build())
                .build();
    }

    /**
     * 内存优化配置示例
     */
    public static EnhancedCacheConfiguration memoryOptimizedExample() {
        var builder = EnhancedCacheConfiguration.namedBuilder("memoryOptimizedCache");
        
        // 应用内存优化预设
        builder = EnhancedCacheConfiguration.lowMemory().apply(builder);
        
        return builder
                .l1(EnhancedCacheConfiguration.L1Config.builder()
                        .maximumSize(500)
                        .expireAfterAccess(Duration.ofMinutes(2))
                        .expireAfterWrite(Duration.ofMinutes(5))
                        .build())
                .l2(EnhancedCacheConfiguration.L2Config.builder()
                        .enabled(true)
                        .defaultTtl(Duration.ofMinutes(30))
                        .enableBatch(false)
                        .build())
                .build();
    }

    /**
     * 演示所有配置示例
     */
    public static void main(String[] args) {
        System.out.println("=== 增强缓存配置示例 ===\\n");

        // 基础示例
        EnhancedCacheConfiguration basic = basicExample();
        System.out.println("基础配置: " + basic.name() + " (L1启用: " + basic.l1().enabled() + 
                          ", L2启用: " + basic.l2().enabled() + ")");

        // DSL示例
        EnhancedCacheConfiguration dsl = dslExample();
        System.out.println("DSL配置: " + dsl.name() + " (同步启用: " + dsl.sync().enabled() + ")");

        // 条件配置示例
        EnhancedCacheConfiguration conditional = conditionalExample();
        System.out.println("条件配置: " + conditional.name() + " (L1最大容量: " + 
                          conditional.l1().maximumSize() + ")");

        // 环境配置示例
        EnhancedCacheConfiguration envSpecific = environmentSpecificExample();
        System.out.println("环境配置: " + envSpecific.name() + " (类型: " + envSpecific.type() + ")");

        // 业务场景示例
        EnhancedCacheConfiguration business = businessScenarioExample();
        System.out.println("业务配置: " + business.name() + " (刷新启用: " + 
                          business.refresh().enabled() + ")");

        // 性能优化示例
        EnhancedCacheConfiguration performance = performanceOptimizedExample();
        System.out.println("性能配置: " + performance.name() + " (L1容量: " + 
                          performance.l1().maximumSize() + ")");

        // 内存优化示例
        EnhancedCacheConfiguration memory = memoryOptimizedExample();
        System.out.println("内存配置: " + memory.name() + " (L1容量: " + 
                          memory.l1().maximumSize() + ")");

        // 预设配置示例
        System.out.println("\\n=== 预设配置 ===");
        presetExamples();

        // 配置修改示例
        EnhancedCacheConfiguration modified = modificationExample();
        System.out.println("\\n修改配置: " + modified.name() + " (类型从L1_ONLY改为: " + 
                          modified.type() + ")");

        System.out.println("\\n所有配置示例演示完成！");
    }
}