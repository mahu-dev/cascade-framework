package io.github.cascade.cache.simple;

import java.util.function.Supplier;

/**
 * Try工具类 - 提供函数式异常处理的便捷方法
 * <p>
 * 这是一个工具类，提供了创建AbstractTry实例的静态方法，
 * 用于保持向后兼容性和提供更简洁的API。
 *
 * @author cascade
 */
public final class Try {
    
    private Try() {
        throw new UnsupportedOperationException("Try is a utility class");
    }

    /**
     * 创建成功的Try实例
     */
    public static <T> AbstractTry<T> success(T value) {
        return AbstractTry.success(value);
    }

    /**
     * 创建失败的Try实例
     */
    public static <T> AbstractTry<T> failure(Exception exception) {
        return AbstractTry.failure(exception);
    }

    /**
     * 从Supplier创建Try实例
     */
    public static <T> AbstractTry<T> of(Supplier<T> supplier) {
        return AbstractTry.of(supplier);
    }
}