package cc.coderm.cascade.bloom.exception;

/**
 * 布隆过滤器运行时异常基类
 *
 * @author lionel lionelk@163.com
 * =============================
 * Date: 2025-01-10
 * Time: 16:00:00
 * =============================
 */
public class BloomFilterException extends RuntimeException {

    public BloomFilterException(String message) {
        super(message);
    }

    public BloomFilterException(String message, Throwable cause) {
        super(message, cause);
    }
}