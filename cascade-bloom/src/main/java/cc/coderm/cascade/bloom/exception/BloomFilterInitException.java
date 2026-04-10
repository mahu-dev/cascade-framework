package cc.coderm.cascade.bloom.exception;

/**
 * 布隆过滤器初始化异常
 *
 * @author lionel lionelk@163.com
 * =============================
 * Date: 2025-01-10
 * Time: 16:00:00
 * =============================
 */
public class BloomFilterInitException extends BloomFilterException {

    public BloomFilterInitException(String message) {
        super(message);
    }

    public BloomFilterInitException(String message, Throwable cause) {
        super(message, cause);
    }
}
