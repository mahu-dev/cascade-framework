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
     * 如果过滤器已存在于 Redis，直接返回现有实例（不会重置数据）；
     * 如果不存在，则以指定参数创建新过滤器。
     *
     * @param name               过滤器名称（对应 Redis key 前缀）
     * @param expectedInsertions 预期元素数量（正整数）
     * @param falseProbability   期望误判率，范围 (0, 1)
     * @param <T>                元素类型
     * @return 布隆过滤器实例
     */
    <T> CascadeBloomFilter<T> getOrCreate(String name, long expectedInsertions, double falseProbability);

    /**
     * 判断指定名称的过滤器是否已在 Redis 中存在
     *
     * @param name 过滤器名称
     * @return {@code true} 表示过滤器已存在
     */
    boolean exists(String name);

    /**
     * 删除指定名称的过滤器
     * <p>
     * 同时从本地缓存和 Redis 中移除。
     *
     * @param name 过滤器名称
     */
    void remove(String name);

    /**
     * 列出所有已注册（本地缓存）的过滤器名称
     *
     * @return 过滤器名称集合
     */
    Set<String> listFilterNames();
}

