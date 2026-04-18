package cc.coderm.cascade.bloom.util;

import cc.coderm.cascade.bloom.exception.BloomFilterException;

import java.util.function.Supplier;

/**
 * 布隆过滤器参数校验与标准化工具
 * <p>
 * 用于统一编程式与注解式入口的参数校验策略，避免重复逻辑与语义分叉。
 */
public final class BloomFilterArgumentValidator {

    private BloomFilterArgumentValidator() {
        // utility class
    }

    /**
     * 校验并标准化过滤器名称（trim + 非空）
     */
    public static String normalizeFilterName(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new BloomFilterException("BloomFilter name must not be blank");
        }
        return name.trim();
    }

    /**
     * 校验创建参数合法性
     */
    public static void validateCreateParams(long expectedInsertions, double falseProbability) {
        if (expectedInsertions <= 0) {
            throw new BloomFilterException(
                    "expectedInsertions must be positive, but got: " + expectedInsertions);
        }
        if (falseProbability <= 0 || falseProbability >= 1) {
            throw new BloomFilterException(
                    "falseProbability must be in range (0, 1), but got: " + falseProbability);
        }
    }

    /**
     * 校验注解 SpEL key 表达式
     */
    public static String requireKeyExpression(String keyExpression) {
        if (keyExpression == null || keyExpression.isBlank()) {
            throw new BloomFilterException("@BloomFilter key expression must not be blank");
        }
        return keyExpression;
    }

    /**
     * 校验并标准化 key（null 不允许）
     */
    public static String normalizeKey(Object value, String fieldName) {
        if (value == null) {
            throw new BloomFilterException(fieldName + " must not be null");
        }
        return BloomFilterKeyUtil.toKey(value);
    }

    /**
     * 校验 loader 非空
     */
    public static <T> Supplier<T> requireLoader(Supplier<T> loader) {
        if (loader == null) {
            throw new BloomFilterException("loader must not be null");
        }
        return loader;
    }
}
