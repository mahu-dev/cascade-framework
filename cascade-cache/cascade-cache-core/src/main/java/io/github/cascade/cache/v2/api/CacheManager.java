package io.github.cascade.cache.v2.api;

import io.github.cascade.cache.configuration.CascadeCacheProperties;
import io.github.cascade.cache.v2.policy.LockFailureStrategy;
import io.github.cascade.cache.v2.policy.SyncMode;

import java.util.Collection;
import java.util.Map;
import java.util.function.Function;

/**
 * @author lionel lionelk@163.com
 * =============================
 * Date: 2025/7/10
 * Time: 13:23
 * =============================
 */

/**
 * 缓存管理器接口
 * <p>
 * 设计原则：
 * 1. 统一管理：管理所有缓存实例（支持不同类型的缓存）
 * 2. 类型安全：支持方法级别泛型
 * 3. 配置灵活：支持不同配置策略
 * 4. 生命周期：管理缓存的创建和销毁
 * <p>
 * 重要变更（2025-10-29）：
 * - 移除接口级别的泛型约束，允许管理不同类型的缓存
 * - 所有方法使用方法级别的泛型参数，保持类型安全
 *
 * @author cascade
 */
public interface CacheManager {

    /**
     * 编程式 Builder 入口。
     */
    default CacheBuilderKeyStage newCache(String cacheName) {
        throw new UnsupportedOperationException("当前CacheManager不支持Builder API");
    }

    // ==================== 缓存创建与获取 ====================

    /**
     * 获取或创建缓存（使用默认配置）
     */
    <K, V> Cache<K, V> getOrCreateCache(String cacheName, Class<K> keyType, Class<V> valueType);

    /**
     * 获取或创建缓存（使用自定义配置）
     */
    <K, V> Cache<K, V> getOrCreateCache(String cacheName, Class<K> keyType, Class<V> valueType,
                                        CascadeCacheProperties config);

    /**
     * 获取或创建缓存（带加载器）
     */
    <K, V> Cache<K, V> getOrCreateCache(String cacheName, Class<K> keyType, Class<V> valueType,
                                        Function<K, V> loader);

    /**
     * 获取或创建缓存（完整配置）
     * <p>
     * 说明：根据配置在统一引擎中开启：
     * - 多级缓存回填（L2 -> L1）
     * - 自动刷新（软TTL触发异步刷新）
     * - 多节点失效同步（Redis topic 失效广播）
     */
    <K, V> Cache<K, V> getOrCreateCache(String cacheName, Class<K> keyType, Class<V> valueType,
                                        CascadeCacheProperties config, Function<K, V> loader);

    /**
     * 获取已存在的缓存
     *
     * @param cacheName 缓存名称
     * @return 缓存实例，如果不存在则返回 null
     */
    <K, V> Cache<K, V> getCache(String cacheName);

    /**
     * 强类型注册CacheLoader（绑定维度：cacheName + keyType + valueType）。
     * <p>
     * 注册后，调用同三元组的getOrCreateCache且未显式传入loader时将自动使用该loader。
     */
    default <K, V> void registerLoader(String cacheName,
                                       Class<K> keyType,
                                       Class<V> valueType,
                                       CacheLoader<K, V> loader) {
        throw new UnsupportedOperationException("当前CacheManager不支持registerLoader API");
    }

    // ==================== 缓存管理 ====================

    /**
     * 注册缓存实例。
     * <p>
     * 若同名缓存已存在且定义指纹（类型/策略）不一致，应抛出异常而非静默覆盖。
     */
    <K, V> boolean registerCache(String cacheName, Cache<K, V> cache);

    /**
     * 移除缓存
     */
    boolean removeCache(String cacheName);

    /**
     * 检查缓存是否存在
     */
    boolean containsCache(String cacheName);

    /**
     * 获取所有缓存名称
     */
    Collection<String> getCacheNames();

    /**
     * 清空所有缓存
     */
    void clearAll();

    /**
     * 获取缓存数量
     */
    int getCacheCount();

    // ==================== 生命周期管理 ====================

    /**
     * 关闭管理器
     */
    void close();

    /**
     * 检查是否已关闭
     */
    boolean isClosed();

    /**
     * 诊断快照（配置 + 运行时指标）。
     */
    default Map<String, Object> diagnostics(String cacheName) {
        return Map.of();
    }

    /**
     * 缓存定义指纹。
     * <p>
     * 用于判定同名缓存是否为“同一份定义”。
     */
    record CacheDefinitionFingerprint(
            String cacheName,
            String definitionType,
            String keyTypeName,
            String valueTypeName,
            String cacheImplType,
            boolean l1Enabled,
            boolean l2Enabled,
            long l1MaximumSize,
            int l1InitialCapacity,
            long l1ExpireAfterWriteSeconds,
            long l1ExpireAfterAccessSeconds,
            boolean l1RecordStats,
            String l2KeyPrefix,
            long hardTtlSeconds,
            long softTtlSeconds,
            long refreshIntervalSeconds,
            boolean autoRefreshEnabled,
            int refreshThreadPoolSize,
            int refreshQueueCapacity,
            boolean refreshAllowConcurrent,
            long refreshTimeoutSeconds,
            int refreshMaxRetries,
            long refreshRetryIntervalSeconds,
            boolean refreshStartOnInit,
            long refreshShutdownTimeoutSeconds,
            SyncMode syncMode,
            boolean syncUpdateEnabled,
            int syncUpdateMaxPayloadBytes,
            boolean singleFlightEnabled,
            boolean distributedLockEnabled,
            LockFailureStrategy lockFailureStrategy
    ) {
        public static CacheDefinitionFingerprint external(String cacheName, String cacheImplType) {
            return new CacheDefinitionFingerprint(
                    cacheName,
                    "external",
                    "*",
                    "*",
                    cacheImplType,
                    false,
                    false,
                    -1L,
                    -1,
                    -1L,
                    -1L,
                    false,
                    "*",
                    -1L,
                    -1L,
                    -1L,
                    false,
                    -1,
                    -1,
                    false,
                    -1L,
                    -1,
                    -1L,
                    false,
                    -1L,
                    SyncMode.NONE,
                    false,
                    -1,
                    false,
                    false,
                    LockFailureStrategy.DEGRADE
            );
        }
    }

    interface CacheBuilderKeyStage {
        <K> CacheBuilderValueStage<K> keyType(Class<K> keyType);
    }

    interface CacheBuilderValueStage<K> {
        <V> CacheBuilder<K, V> valueType(Class<V> valueType);
    }

    interface CacheBuilder<K, V> {
        CacheBuilder<K, V> loader(Function<K, V> loader);

        CacheBuilder<K, V> ttlSeconds(long ttlSeconds);

        CacheBuilder<K, V> softTtlSeconds(long softTtlSeconds);

        CacheBuilder<K, V> syncMode(SyncMode syncMode);

        CacheBuilder<K, V> autoRefresh(boolean enabled);

        CacheBuilder<K, V> enableL1(boolean enabled);

        CacheBuilder<K, V> enableL2(boolean enabled);

        Cache<K, V> build();
    }
}
