package io.github.cascade.cache.protection;

/**
 * 布隆过滤器通用接口
 * 支持不同的布隆过滤器实现（本地实现和Redisson分布式实现）
 *
 * @author Cascade Framework
 */
public interface CascadeBloomFilter {
    
    /**
     * 添加元素到布隆过滤器
     *
     * @param element 要添加的元素
     */
    void add(String element);
    
    /**
     * 检查元素是否可能存在
     *
     * @param element 要检查的元素
     * @return true表示可能存在，false表示一定不存在
     */
    boolean mightContain(String element);
    
    /**
     * 检查元素是否一定不存在
     *
     * @param element 要检查的元素
     * @return true表示一定不存在，false表示可能存在
     */
    default boolean definitelyNotContain(String element) {
        return !mightContain(element);
    }
    
    /**
     * 清空布隆过滤器
     */
    void clear();
    
    /**
     * 获取统计信息
     *
     * @return 统计信息
     */
    BloomFilterStats getStats();
    
    /**
     * 布隆过滤器统计信息通用接口
     */
    interface BloomFilterStats {
        long getAddedElements();
        long getExpectedElements();
        double getFalsePositiveRate();
        double getElementUtilization();
    }
}