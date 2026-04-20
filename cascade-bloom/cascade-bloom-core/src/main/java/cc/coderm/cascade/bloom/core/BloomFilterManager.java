package cc.coderm.cascade.bloom.core;

import java.util.Set;

/**
 * 布隆过滤器管理器接口
 * <p>
 * 负责多个布隆过滤器实例的创建、缓存与生命周期管理。
 * 类比 Spring 的 {@code CacheManager}，支持按名称获取或创建过滤器实例。
 *
 * @author lionel lionelk@163.com
 * =============================
 * Date: 2025-01-10
 * Time: 16:00:00
 * =============================
 */
public interface BloomFilterManager {

    /**
     * 获取指定名称的布隆过滤器（使用全局默认配置）
     * <p>
     * 如果过滤器尚未在 Redis 中初始化，将使用配置的默认容量和误判率自动创建。
     *
     * @param name 过滤器名称
     * @param <T>  元素类型
     * @return 布隆过滤器实例
     */
    <T> CascadeBloomFilter<T> getFilter(String name);

    /**
     * 获取或创建布隆过滤器（指定容量和误判率）
     * <p>
     * 如果过滤器不存在，则以指定参数创建新过滤器；
     * 如果过滤器已存在于 Redis/本地缓存，则会执行<strong>严格配置一致性校验</strong>：
     * 只有当现有过滤器的 {@code expectedInsertions} 与 {@code falseProbability}
     * 与本次请求参数完全一致时才返回实例。
     * <p>
     * 若配置不一致将抛出异常，避免同名过滤器在不同调用方之间出现静默配置漂移。
     *
     * @param name               过滤器名称（对应 Redis key 前缀）
     * @param expectedInsertions 预期元素数量（正整数）
     * @param falseProbability   期望误判率，范围 (0, 1)
     * @param <T>                元素类型
     * @return 布隆过滤器实例
     * @throws cc.coderm.cascade.bloom.exception.BloomFilterException
     * 配置不一致或创建失败时抛出
     */
    <T> CascadeBloomFilter<T> getOrCreate(String name, long expectedInsertions, double falseProbability);

    /**
     * 判断指定名称的过滤器是否存在（管理器视角，最终一致）
     * <p>
     * 该方法优先基于本地缓存和注册表快照判断，按探测窗口周期性与 Redis 同步，
     * 语义与 {@link #getFilter(String)} 保持一致，避免高频调用每次触发远程 I/O。
     *
     * @param name 过滤器名称
     * @return {@code true} 表示在管理器视角下过滤器存在
     */
    boolean exists(String name);

    /**
     * 判断指定名称的过滤器是否已在 Redis 中存在（强一致）
     * <p>
     * 每次调用都会直接查询 Redis，适用于需要强一致判定的管理/运维场景。
     *
     * @param name 过滤器名称
     * @return {@code true} 表示 Redis 中存在
     */
    boolean existsInRedis(String name);

    /**
     * 删除指定名称的过滤器
     * <p>
     * 同时从本地缓存和 Redis 中移除。
     *
     * @param name 过滤器名称
     */
    void remove(String name);

    /**
     * 列出当前本地缓存中的过滤器名称
     * <p>
     * 仅包含 JVM 内 LRU 缓存中的条目，不包含已被 LRU 淘汰或尚未加载到本地缓存的过滤器。
     *
     * @return 本地缓存中的过滤器名称集合
     */
    Set<String> listCachedFilterNames();

    /**
     * 列出 Redis 侧已注册的过滤器名称
     * <p>
     * 该列表由管理器在创建/删除过滤器时维护，用于表达“持久层已注册过滤器”的全量视图。
     *
     * @return Redis 持久注册的过滤器名称集合
     */
    Set<String> listRegisteredFilterNames();
}
