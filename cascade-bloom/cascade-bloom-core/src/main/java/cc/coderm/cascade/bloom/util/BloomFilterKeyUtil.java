package cc.coderm.cascade.bloom.util;

import java.lang.reflect.Array;
import java.util.IdentityHashMap;
import java.util.Objects;

/**
 * 布隆过滤器 Key 工具类
 * <p>
 * 提供统一的 key 构建与标准化方法，确保不同类型的值在写入和查询时产生一致的 key。
 *
 * @author lionel lionelk@163.com
 * =============================
 * Date: 2025-01-10
 * Time: 16:00:00
 * =============================
 */
public final class BloomFilterKeyUtil {

    private BloomFilterKeyUtil() {
        // 工具类，禁止实例化
    }

    /**
     * 将任意对象转换为布隆过滤器 key 字符串
     * <p>
     * 规则：
     * <ul>
     *   <li>null → 抛出 {@link NullPointerException}</li>
     *   <li>数组 → 按内容递归序列化（如 {@code [1,2]} / {@code [u,[1,2],null]}）</li>
     *   <li>其他 → {@code toString()}</li>
     * </ul>
     *
     * @param value 待转换值（不可为 null）
     * @return key 字符串
     */
    public static String toKey(Object value) {
        Objects.requireNonNull(value, "BloomFilter key must not be null");
        return toKeyInternal(value, new IdentityHashMap<>());
    }

    private static String toKeyInternal(Object value, IdentityHashMap<Object, Boolean> visitingArrays) {
        if (!value.getClass().isArray()) {
            return value.toString();
        }
        return toArrayKey(value, visitingArrays);
    }

    private static String toArrayKey(Object arrayValue, IdentityHashMap<Object, Boolean> visitingArrays) {
        if (visitingArrays.put(arrayValue, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("Cyclic array reference detected while building bloom filter key");
        }
        try {
            Class<?> componentType = arrayValue.getClass().getComponentType();
            if (componentType != null && componentType.isPrimitive()) {
                return toPrimitiveArrayKey(arrayValue);
            }
            return toObjectArrayKey(arrayValue, visitingArrays);
        } finally {
            visitingArrays.remove(arrayValue);
        }
    }

    private static String toPrimitiveArrayKey(Object primitiveArrayValue) {
        int length = Array.getLength(primitiveArrayValue);
        StringBuilder builder = new StringBuilder(length * 8 + 2);
        builder.append("[");
        for (int i = 0; i < length; i++) {
            if (i > 0) {
                builder.append(",");
            }
            builder.append(Array.get(primitiveArrayValue, i));
        }
        builder.append("]");
        return builder.toString();
    }

    private static String toObjectArrayKey(Object objectArrayValue, IdentityHashMap<Object, Boolean> visitingArrays) {
        int length = Array.getLength(objectArrayValue);
        StringBuilder builder = new StringBuilder(length * 8 + 2);
        builder.append("[");
        for (int i = 0; i < length; i++) {
            if (i > 0) {
                builder.append(",");
            }
            Object element = Array.get(objectArrayValue, i);
            if (element == null) {
                builder.append("null");
                continue;
            }
            builder.append(toKeyInternal(element, visitingArrays));
        }
        builder.append("]");
        return builder.toString();
    }

    /**
     * 组合多个片段构建复合 key，各片段之间以 {@code ":"} 连接
     *
     * @param parts key 片段（不可为 null，各片段不可为 null）
     * @return 复合 key，如 {@code "userId:orderId"}
     */
    public static String buildKey(Object... parts) {
        Objects.requireNonNull(parts, "Key parts must not be null");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            Objects.requireNonNull(parts[i], "Key part[" + i + "] must not be null");
            if (i > 0) {
                sb.append(":");
            }
            sb.append(toKey(parts[i]));
        }
        return sb.toString();
    }

    /**
     * 构建带前缀的 key
     *
     * @param prefix 前缀
     * @param key    原始 key
     * @return {@code prefix + ":" + key}
     */
    public static String withPrefix(String prefix, String key) {
        Objects.requireNonNull(prefix, "prefix must not be null");
        Objects.requireNonNull(key, "key must not be null");
        return prefix + ":" + key;
    }
}
