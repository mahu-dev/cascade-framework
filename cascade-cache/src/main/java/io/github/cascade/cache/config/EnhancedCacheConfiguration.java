package io.github.cascade.cache.config;

import lombok.Builder;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * 增强的缓存配置系统 - 使用Lombok简化代码
 * <p>
 * 设计原则：
 * 1. 不可变性：配置对象创建后不可修改
 * 2. 类型安全：编译时确保配置完整性
 * 3. 函数式验证：使用函数式验证链
 * 4. 流畅API：支持复杂的配置场景
 * 5. 配置DSL：领域特定的配置语言
 *
 * @author cascade
 */
@Data
@Builder(toBuilder = true)
@Accessors(fluent = true)
public final class EnhancedCacheConfiguration {

    private static final long DEFAULT_L1_SIZE = 10000L;
    private static final long HIGH_PERF_L1_SIZE = 50000L;
    private static final long LOW_MEM_L1_SIZE = 1000L;
    private static final String DEFAULT_KEY_PREFIX = "cascade:";

    // ==================== 核心配置字段 ====================

    @Builder.Default
    private final boolean enabled = true;
    
    private final String name;
    
    @Builder.Default
    private final CacheType type = CacheType.TIERED;
    
    @Builder.Default
    private final L1Config l1 = L1Config.builder().build();
    
    @Builder.Default
    private final L2Config l2 = L2Config.builder().build();
    
    @Builder.Default
    private final SyncConfig sync = SyncConfig.builder().build();
    
    @Builder.Default
    private final RefreshConfig refresh = RefreshConfig.builder().build();
    
    @Builder.Default
    private final LoaderConfig loader = LoaderConfig.builder().build();

    // ==================== 静态工厂方法 ====================

    /**
     * 创建配置构建器
     */
    public static EnhancedCacheConfigurationBuilder namedBuilder(String name) {
        return EnhancedCacheConfiguration.builder().name(name);
    }

    /**
     * DSL风格的配置创建
     */
    public static EnhancedCacheConfiguration configure(String name, 
                                                      Consumer<EnhancedCacheConfigurationBuilder> configurator) {
        EnhancedCacheConfigurationBuilder builder = EnhancedCacheConfiguration.builder().name(name);
        configurator.accept(builder);
        return builder.build();
    }

    // ==================== 预设配置 ====================

    /**
     * 仅L1缓存配置
     */
    public static EnhancedCacheConfiguration l1Only(String name) {
        return configure(name, builder -> builder
                .type(CacheType.L1_ONLY)
                .l1(L1Config.builder()
                        .enabled(true)
                        .maximumSize(DEFAULT_L1_SIZE)
                        .expireAfterWrite(Duration.ofHours(1))
                        .build())
                .l2(L2Config.builder().enabled(false).build())
                .sync(SyncConfig.builder().enabled(false).build()));
    }

    /**
     * 仅L2缓存配置
     */
    public static EnhancedCacheConfiguration l2Only(String name) {
        return configure(name, builder -> builder
                .type(CacheType.L2_ONLY)
                .l1(L1Config.builder().enabled(false).build())
                .l2(L2Config.builder()
                        .enabled(true)
                        .defaultTtl(Duration.ofHours(2))
                        .keyPrefix(DEFAULT_KEY_PREFIX)
                        .build()));
    }

    /**
     * 多级缓存配置
     */
    public static EnhancedCacheConfiguration tiered(String name) {
        return configure(name, builder -> builder
                .type(CacheType.TIERED)
                .l1(L1Config.builder()
                        .enabled(true)
                        .maximumSize(LOW_MEM_L1_SIZE)
                        .expireAfterWrite(Duration.ofMinutes(30))
                        .build())
                .l2(L2Config.builder()
                        .enabled(true)
                        .defaultTtl(Duration.ofHours(2))
                        .keyPrefix(DEFAULT_KEY_PREFIX)
                        .build())
                .sync(SyncConfig.builder()
                        .enabled(true)
                        .type(SyncConfig.SyncType.REDIS)
                        .build()));
    }

    // ==================== 验证逻辑 ====================

    public void validate() {
        validateRequired("name", name, Objects::nonNull);
        validateRequired("type", type, Objects::nonNull);
        
        // 条件验证
        if (type == CacheType.L1_ONLY || type == CacheType.TIERED) {
            validateRequired("L1 config", l1.enabled(), value -> value);
        }
        
        if (type == CacheType.L2_ONLY || type == CacheType.TIERED) {
            validateRequired("L2 config", l2.enabled(), value -> value);
        }
    }

    private static <T> void validateRequired(String field, T value, Predicate<T> condition) {
        if (!condition.test(value)) {
            throw new IllegalArgumentException("Invalid configuration: " + field);
        }
    }

    // ==================== 配置子类 ====================

    public enum CacheType {
        L1_ONLY, L2_ONLY, TIERED
    }

    /**
     * L1缓存配置
     */
    @Data
    @Builder(toBuilder = true)
    @Accessors(fluent = true)
    public static final class L1Config {
        @Builder.Default
        private final boolean enabled = true;
        
        @Builder.Default
        private final long maximumSize = DEFAULT_L1_SIZE;
        
        private final Duration expireAfterWrite;
        private final Duration expireAfterAccess;
        
        @Builder.Default
        private final boolean recordStats = true;
        
        @Builder.Default
        private final int initialCapacity = 16;

        public Optional<Duration> getExpireAfterWrite() { 
            return Optional.ofNullable(expireAfterWrite); 
        }
        
        public Optional<Duration> getExpireAfterAccess() { 
            return Optional.ofNullable(expireAfterAccess); 
        }
    }

    /**
     * L2缓存配置
     */
    @Data
    @Builder(toBuilder = true)
    @Accessors(fluent = true)
    public static final class L2Config {
        @Builder.Default
        private final boolean enabled = false;
        
        @Builder.Default
        private final String keyPrefix = DEFAULT_KEY_PREFIX;
        
        @Builder.Default
        private final Duration defaultTtl = Duration.ofHours(2);
        
        @Builder.Default
        private final boolean enableBatch = true;
        
        @Builder.Default
        private final int batchSize = 100;
        
        @Builder.Default
        private final String serializer = "json";
    }

    /**
     * 同步配置
     */
    @Data
    @Builder(toBuilder = true)
    @Accessors(fluent = true)
    public static final class SyncConfig {
        @Builder.Default
        private final boolean enabled = false;
        
        @Builder.Default
        private final SyncType type = SyncType.REDIS;
        
        @Builder.Default
        private final String topicPrefix = "cascade:sync:";

        public enum SyncType {
            REDIS, LOCAL, CUSTOM
        }
    }

    /**
     * 刷新配置
     */
    @Data
    @Builder(toBuilder = true)
    @Accessors(fluent = true)
    public static final class RefreshConfig {
        @Builder.Default
        private final boolean enabled = true;
        
        @Builder.Default
        private final Duration defaultInterval = Duration.ofMinutes(10);
    }

    /**
     * 加载器配置
     */
    @Data
    @Builder(toBuilder = true)
    @Accessors(fluent = true)
    public static final class LoaderConfig {
        @Builder.Default
        private final boolean autoDiscover = true;
        
        @Builder.Default
        private final Duration timeout = Duration.ofSeconds(30);
    }

    // ==================== 扩展静态方法 ====================

    /**
     * 条件配置
     */
    public static UnaryOperator<EnhancedCacheConfigurationBuilder> when(boolean condition, 
            UnaryOperator<EnhancedCacheConfigurationBuilder> action) {
        return condition ? action : UnaryOperator.identity();
    }

    /**
     * 反向条件配置
     */
    public static UnaryOperator<EnhancedCacheConfigurationBuilder> unless(boolean condition, 
            UnaryOperator<EnhancedCacheConfigurationBuilder> action) {
        return !condition ? action : UnaryOperator.identity();
    }

    /**
     * 环境相关配置
     */
    public static UnaryOperator<EnhancedCacheConfigurationBuilder> forEnvironment(String environment, 
            UnaryOperator<EnhancedCacheConfigurationBuilder> configurator) {
        String currentEnv = System.getProperty("spring.profiles.active", "default");
        return currentEnv.equals(environment) ? configurator : UnaryOperator.identity();
    }

    /**
     * 性能预设
     */
    public static UnaryOperator<EnhancedCacheConfigurationBuilder> highPerformance() {
        return builder -> builder
                .l1(L1Config.builder()
                        .maximumSize(HIGH_PERF_L1_SIZE)
                        .recordStats(true)
                        .build())
                .l2(L2Config.builder()
                        .enableBatch(true)
                        .batchSize(500)
                        .build());
    }

    /**
     * 低内存预设
     */
    public static UnaryOperator<EnhancedCacheConfigurationBuilder> lowMemory() {
        return builder -> builder
                .l1(L1Config.builder()
                        .maximumSize(LOW_MEM_L1_SIZE)
                        .expireAfterAccess(Duration.ofMinutes(5))
                        .build());
    }

    /**
     * 配置L1缓存
     */
    public static UnaryOperator<EnhancedCacheConfigurationBuilder> configureL1(
            Consumer<L1Config.L1ConfigBuilder> configurator) {
        return builder -> {
            L1Config.L1ConfigBuilder l1Builder = L1Config.builder();
            configurator.accept(l1Builder);
            return builder.l1(l1Builder.build());
        };
    }

    /**
     * 配置L2缓存
     */
    public static UnaryOperator<EnhancedCacheConfigurationBuilder> configureL2(
            Consumer<L2Config.L2ConfigBuilder> configurator) {
        return builder -> {
            L2Config.L2ConfigBuilder l2Builder = L2Config.builder();
            configurator.accept(l2Builder);
            return builder.l2(l2Builder.build());
        };
    }

    /**
     * 配置同步
     */
    public static UnaryOperator<EnhancedCacheConfigurationBuilder> configureSync(
            Consumer<SyncConfig.SyncConfigBuilder> configurator) {
        return builder -> {
            SyncConfig.SyncConfigBuilder syncBuilder = SyncConfig.builder();
            configurator.accept(syncBuilder);
            return builder.sync(syncBuilder.build());
        };
    }

    /**
     * 配置刷新
     */
    public static UnaryOperator<EnhancedCacheConfigurationBuilder> configureRefresh(
            Consumer<RefreshConfig.RefreshConfigBuilder> configurator) {
        return builder -> {
            RefreshConfig.RefreshConfigBuilder refreshBuilder = RefreshConfig.builder();
            configurator.accept(refreshBuilder);
            return builder.refresh(refreshBuilder.build());
        };
    }

    /**
     * 配置加载器
     */
    public static UnaryOperator<EnhancedCacheConfigurationBuilder> configureLoader(
            Consumer<LoaderConfig.LoaderConfigBuilder> configurator) {
        return builder -> {
            LoaderConfig.LoaderConfigBuilder loaderBuilder = LoaderConfig.builder();
            configurator.accept(loaderBuilder);
            return builder.loader(loaderBuilder.build());
        };
    }
    
    /**
     * 带验证的构建方法
     */
    public static EnhancedCacheConfiguration buildAndValidate(EnhancedCacheConfigurationBuilder builder) {
        EnhancedCacheConfiguration config = builder.build();
        config.validate();
        return config;
    }
}