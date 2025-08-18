package io.github.cascade.cache.api;

/**
 * 缓存统计信息接口
 *
 * @author cascade
 */
public interface CacheStats {

    /**
     * 获取缓存命中次数
     *
     * @return 命中次数
     */
    long hitCount();

    /**
     * 获取缓存未命中次数
     *
     * @return 未命中次数
     */
    long missCount();

    /**
     * 获取缓存请求总次数
     *
     * @return 请求总次数
     */
    long requestCount();

    /**
     * 获取缓存命中率
     *
     * @return 命中率（0.0-1.0）
     */
    double hitRate();

    /**
     * 获取缓存未命中率
     *
     * @return 未命中率（0.0-1.0）
     */
    double missRate();

    /**
     * 获取加载次数
     *
     * @return 加载次数
     */
    long loadCount();

    /**
     * 获取加载异常次数
     *
     * @return 加载异常次数
     */
    long loadExceptionCount();

    /**
     * 获取总加载时间（纳秒）
     *
     * @return 总加载时间
     */
    long totalLoadTime();

    /**
     * 获取平均加载时间（纳秒）
     *
     * @return 平均加载时间
     */
    double averageLoadPenalty();

    /**
     * 获取驱逐次数
     *
     * @return 驱逐次数
     */
    long evictionCount();

    /**
     * 获取驱逐权重
     *
     * @return 驱逐权重
     */
    long evictionWeight();

    /**
     * 重置统计信息
     */
    void reset();

    /**
     * 获取统计信息的字符串表示
     *
     * @return 统计信息字符串
     */
    String toString();
}