package io.github.cascade.cache.util;

import lombok.Getter;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

/**
 * 类型引用工具类，用于捕获泛型类型信息
 * 类似于Jackson的TypeReference，通过匿名内部类捕获泛型类型
 *
 * @param <T> 要捕获的类型
 * @author cascade
 */
@Getter
public abstract class TypeReference<T> {

    /**
     * -- GETTER --
     * 获取捕获的类型
     */
    private final Type type;

    protected TypeReference() {
        Type superClass = getClass().getGenericSuperclass();
        if (superClass instanceof ParameterizedType) {
            this.type = ((ParameterizedType) superClass).getActualTypeArguments()[0];
        } else {
            throw new IllegalArgumentException("TypeReference must be parameterized");
        }
    }

    /**
     * 获取类型名称
     */
    public String getTypeName() {
        return type.getTypeName();
    }

    /**
     * 获取原始类型
     */
    @SuppressWarnings("unchecked")
    public Class<T> getRawType() {
        if (type instanceof Class) {
            return (Class<T>) type;
        } else if (type instanceof ParameterizedType) {
            return (Class<T>) ((ParameterizedType) type).getRawType();
        } else {
            throw new IllegalArgumentException("Cannot determine raw type for: " + type);
        }
    }

    /**
     * 检查是否为泛型类型
     */
    public boolean isParameterized() {
        return type instanceof ParameterizedType;
    }

    /**
     * 获取泛型参数类型
     */
    public Type[] getTypeArguments() {
        if (type instanceof ParameterizedType) {
            return ((ParameterizedType) type).getActualTypeArguments();
        }
        return new Type[0];
    }

    @Override
    public String toString() {
        return "TypeReference{" + type + "}";
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof TypeReference)) return false;
        TypeReference<?> that = (TypeReference<?>) obj;
        return type.equals(that.type);
    }

    @Override
    public int hashCode() {
        return type.hashCode();
    }
}