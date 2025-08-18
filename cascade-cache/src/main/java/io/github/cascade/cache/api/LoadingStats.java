package io.github.cascade.cache.api;

/**
 * 加载统计信息接口
 *
 * @author cascade
 */
public interface LoadingStats {

    /**
     * 获取加载请求总数
     *
     * @return 加载请求总数
     */
    long loadCount();

    /**
     * 获取加载成功次数
     *
     * @return 加载成功次数
     */
    long loadSuccessCount();

    /**
     * 获取加载失败次数
     *
     * @return 加载失败次数
     */
    long loadFailureCount();

    /**
     * 获取加载成功率
     *
     * @return 加载成功率（0.0-1.0）
     */
    double loadSuccessRate();

    /**
     * 获取加载失败率
     *
     * @return 加载失败率（0.0-1.0）
     */
    double loadFailureRate();

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
    double averageLoadTime();

    /**
     * 获取最大加载时间（纳秒）
     *
     * @return 最大加载时间
     */
    long maxLoadTime();

    /**
     * 获取最小加载时间（纳秒）
     *
     * @return 最小加载时间
     */
    long minLoadTime();

    /**
     * 获取当前正在进行的加载操作数量
     *
     * @return 正在加载的操作数量
     */
    long activeLoadCount();

    /**
     * 获取批量加载请求次数
     *
     * @return 批量加载请求次数
     */
    long batchLoadCount();

    /**
     * 获取批量加载的平均批次大小
     *
     * @return 平均批次大小
     */
    double averageBatchSize();

    /**
     * 获取加载超时次数
     *
     * @return 加载超时次数
     */
    long loadTimeoutCount();

    /**
     * 获取加载取消次数
     *
     * @return 加载取消次数
     */
    long loadCancelCount();

    /**
     * 重置加载统计信息
     */
    void reset();

    /**
     * 获取统计信息的字符串表示
     *
     * @return 统计信息字符串
     */
    String toString();
}