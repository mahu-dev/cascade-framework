package cc.coderm.cascade.bloom.exception;

/**
 * 访问不存在的布隆过滤器时抛出
 *
 * @author lionel lionelk@163.com
 * =============================
 * Date: 2025-01-10
 * Time: 16:00:00
 * =============================
 */
public class BloomFilterNotFoundException extends BloomFilterException {

    public BloomFilterNotFoundException(String filterName) {
        super("BloomFilter not found: [" + filterName + "]. Please check configuration or create it first.");
    }
}
