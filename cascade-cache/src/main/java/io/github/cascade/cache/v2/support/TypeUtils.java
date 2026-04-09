package io.github.cascade.cache.v2.support;

/**
 * 类型工具。
 */
public final class TypeUtils {

    private TypeUtils() {
    }

    @SuppressWarnings("unchecked")
    public static <T> Class<T> boxedType(Class<T> type) {
        if (type == null || !type.isPrimitive()) {
            return type;
        }
        if (type == boolean.class) {
            return (Class<T>) Boolean.class;
        }
        if (type == byte.class) {
            return (Class<T>) Byte.class;
        }
        if (type == short.class) {
            return (Class<T>) Short.class;
        }
        if (type == int.class) {
            return (Class<T>) Integer.class;
        }
        if (type == long.class) {
            return (Class<T>) Long.class;
        }
        if (type == float.class) {
            return (Class<T>) Float.class;
        }
        if (type == double.class) {
            return (Class<T>) Double.class;
        }
        if (type == char.class) {
            return (Class<T>) Character.class;
        }
        if (type == void.class) {
            return (Class<T>) Void.class;
        }
        return type;
    }
}
