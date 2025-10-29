package io.github.cascade.cache.util;

import io.github.cascade.cache.exception.CacheConfigurationException;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author lionel lionelk@163.com
 * =============================
 * Date: 2025/10/29
 * Time: 17:22
 * =============================
 */

/**
 * 缓存类型解析器
 * <p>
 * 负责从方法签名、注解等上下文中推断缓存的键值类型
 * 提供统一的类型推断机制，支持泛型类型解析
 * <p>
 * 设计原则：
 * 1. 智能推断：通过方法签名自动推断泛型类型
 * 2. 缓存优化：缓存类型推断结果，避免重复反射
 * 3. 兼容性：支持各种复杂泛型场景
 * 4. 降级策略：推断失败时提供合理的默认类型
 *
 * @author cascade
 */
public class CacheTypeResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger(CacheTypeResolver.class);

    // 类型推断结果缓存
    private static final Map<String, TypeInferenceResult> TYPE_INFERENCE_CACHE = new ConcurrentHashMap<>();

    // 默认缓存类型
    private static final Class<?> DEFAULT_KEY_TYPE = String.class;
    private static final Class<?> DEFAULT_VALUE_TYPE = Object.class;

    public CacheTypeResolver() {
        // 工具类构造器
    }

    // ==================== 静态工具方法 ====================

    /**
     * 从JoinPoint推断键类型
     */
    public static Class<?> inferKeyType(JoinPoint joinPoint) {
        try {
            Method method = getMethod(joinPoint);
            return inferKeyTypeFromMethod(method, joinPoint.getArgs());
        } catch (Exception e) {
            LOGGER.warn("无法推断键类型，使用默认类型: method={}, error={}",
                joinPoint.getSignature().toShortString(), e.getMessage());
            return DEFAULT_KEY_TYPE;
        }
    }

    /**
     * 从JoinPoint推断值类型
     */
    public static Class<?> inferValueType(JoinPoint joinPoint) {
        try {
            Method method = getMethod(joinPoint);
            return inferValueTypeFromMethod(method, joinPoint.getArgs());
        } catch (Exception e) {
            LOGGER.warn("无法推断值类型，使用默认类型: method={}, error={}",
                joinPoint.getSignature().toShortString(), e.getMessage());
            return DEFAULT_VALUE_TYPE;
        }
    }

    /**
     * 从方法签名推断键类型
     */
    public static Class<?> inferKeyTypeFromMethod(Method method, Object[] args) {
        if (args == null || args.length == 0) {
            LOGGER.debug("方法无参数，使用默认键类型: {}", DEFAULT_KEY_TYPE.getSimpleName());
            return DEFAULT_KEY_TYPE;
        }

        // 优先使用第一个参数的类型作为键类型
        Class<?> keyType = args[0].getClass();

        // 处理集合类型
        if (Iterable.class.isAssignableFrom(keyType)) {
            // 如果是集合，尝试推断泛型参数类型
            keyType = inferCollectionElementType(args[0]);
        }

        LOGGER.debug("推断键类型: method={}, keyType={}", method.getName(), keyType.getSimpleName());
        return keyType;
    }

    /**
     * 从方法签名推断值类型
     */
    public static Class<?> inferValueTypeFromMethod(Method method, Object[] args) {
        String cacheKey = buildCacheKey(method);

        // 尝试从缓存获取
        TypeInferenceResult cached = TYPE_INFERENCE_CACHE.get(cacheKey);
        if (cached != null) {
            LOGGER.debug("从缓存获取值类型: method={}, valueType={}", method.getName(), cached.valueType.getSimpleName());
            return cached.valueType;
        }

        // 执行类型推断
        Class<?> valueType = doInferValueType(method);

        // 缓存推断结果
        TypeInferenceResult result = new TypeInferenceResult(null, valueType);
        TYPE_INFERENCE_CACHE.put(cacheKey, result);

        LOGGER.debug("推断值类型: method={}, valueType={}", method.getName(), valueType.getSimpleName());
        return valueType;
    }

    // ==================== 类型验证方法 ====================

    /**
     * 验证键类型是否有效
     */
    public static boolean isValidKeyType(Class<?> keyType) {
        if (keyType == null) {
            return false;
        }

        // 键类型必须实现equals和hashCode
        try {
            keyType.getMethod("equals", Object.class);
            keyType.getMethod("hashCode");
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    /**
     * 验证值类型是否有效
     */
    public static boolean isValidValueType(Class<?> valueType) {
        return valueType != null && !valueType.isPrimitive();
    }

    /**
     * 验证类型组合是否有效
     */
    public static void validateTypePair(Class<?> keyType, Class<?> valueType) {
        if (!isValidKeyType(keyType)) {
            throw new CacheConfigurationException("keyType", keyType,
                "键类型必须实现equals和hashCode方法", null);
        }

        if (!isValidValueType(valueType)) {
            throw new CacheConfigurationException("valueType", valueType,
                "值类型不能为基本类型", null);
        }

        // 检查是否为void类型
        if (valueType == void.class || valueType == Void.class) {
            throw new CacheConfigurationException("valueType", valueType,
                "值类型不能为void", null);
        }
    }

    // ==================== 类型转换方法 ====================

    /**
     * 安全转换键类型
     */
    @SuppressWarnings("unchecked")
    public static <T> T castKey(Object key, Class<T> targetType) {
        if (key == null) {
            return null;
        }

        if (targetType.isInstance(key)) {
            return (T) key;
        }

        // 尝试基本类型转换
        if (targetType == String.class) {
            return (T) key.toString();
        }

        if (targetType.isPrimitive() || Number.class.isAssignableFrom(targetType)) {
            return convertNumber(key, targetType);
        }

        throw new CacheConfigurationException("keyType", targetType,
            "无法将键从" + key.getClass().getSimpleName() + "转换为" + targetType.getSimpleName(), null);
    }

    /**
     * 安全转换值类型
     */
    @SuppressWarnings("unchecked")
    public static <T> T castValue(Object value, Class<T> targetType) {
        if (value == null) {
            return null;
        }

        if (targetType.isInstance(value)) {
            return (T) value;
        }

        // 处理Optional包装
        if (targetType == Optional.class) {
            return (T) Optional.ofNullable(value);
        }

        // 基本类型转换
        return convertNumber(value, targetType);
    }

    // ==================== 私有工具方法 ====================

    /**
     * 执行值类型推断的核心逻辑
     */
    private static Class<?> doInferValueType(Method method) {
        // 1. 通过方法返回类型推断
        Class<?> returnType = method.getReturnType();

        // 处理CompletableFuture包装
        if (CompletableFuture.class.isAssignableFrom(returnType)) {
            return inferAsyncValueType(method.getGenericReturnType());
        }

        // 处理Optional包装
        if (Optional.class.isAssignableFrom(returnType)) {
            return inferOptionalValueType(method.getGenericReturnType());
        }

        // 处理集合类型
        if (Iterable.class.isAssignableFrom(returnType)) {
            return inferCollectionElementType(method.getGenericReturnType());
        }

        // 处理Map类型
        if (Map.class.isAssignableFrom(returnType)) {
            return inferMapValueType(method.getGenericReturnType());
        }

        // 直接返回类型
        return returnType;
    }

    /**
     * 推断集合元素类型
     */
    private static Class<?> inferCollectionElementType(Object collection) {
        if (collection instanceof Iterable<?>) {
            Iterable<?> iterable = (Iterable<?>) collection;
            for (Object element : iterable) {
                if (element != null) {
                    return element.getClass();
                }
            }
        }
        return Object.class;
    }

    /**
     * 推断集合元素类型（基于泛型）
     */
    private static Class<?> inferCollectionElementType(Type genericType) {
        if (genericType instanceof ParameterizedType) {
            ParameterizedType parameterizedType = (ParameterizedType) genericType;
            Type[] typeArguments = parameterizedType.getActualTypeArguments();

            if (typeArguments.length > 0 && typeArguments[0] instanceof Class) {
                return (Class<?>) typeArguments[0];
            }
        }
        return Object.class;
    }

    /**
     * 推断Optional包装的类型
     */
    private static Class<?> inferOptionalValueType(Type genericType) {
        if (genericType instanceof ParameterizedType) {
            ParameterizedType parameterizedType = (ParameterizedType) genericType;
            Type[] typeArguments = parameterizedType.getActualTypeArguments();

            if (typeArguments.length > 0 && typeArguments[0] instanceof Class) {
                return (Class<?>) typeArguments[0];
            }
        }
        return Object.class;
    }

    /**
     * 推断异步返回值的类型
     */
    private static Class<?> inferAsyncValueType(Type genericType) {
        if (genericType instanceof ParameterizedType) {
            ParameterizedType parameterizedType = (ParameterizedType) genericType;
            Type[] typeArguments = parameterizedType.getActualTypeArguments();

            if (typeArguments.length > 0 && typeArguments[0] instanceof Class) {
                return (Class<?>) typeArguments[0];
            }
        }
        return Object.class;
    }

    /**
     * 推断Map的值类型
     */
    private static Class<?> inferMapValueType(Type genericType) {
        if (genericType instanceof ParameterizedType) {
            ParameterizedType parameterizedType = (ParameterizedType) genericType;
            Type[] typeArguments = parameterizedType.getActualTypeArguments();

            if (typeArguments.length >= 2 && typeArguments[1] instanceof Class) {
                return (Class<?>) typeArguments[1];
            }
        }
        return Object.class;
    }

    private static Method getMethod(JoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        return signature.getMethod();
    }

    /**
     * 构建缓存键
     */
    private static String buildCacheKey(Method method) {
        return method.getDeclaringClass().getName() + "." + method.getName() +
               Arrays.toString(method.getParameterTypes());
    }

    @SuppressWarnings("unchecked")
    private static <T> T convertNumber(Object value, Class<T> targetType) {
        if (value instanceof Number) {
            Number number = (Number) value;

            if (targetType == Integer.class || targetType == int.class) {
                return (T) Integer.valueOf(number.intValue());
            } else if (targetType == Long.class || targetType == long.class) {
                return (T) Long.valueOf(number.longValue());
            } else if (targetType == Double.class || targetType == double.class) {
                return (T) Double.valueOf(number.doubleValue());
            } else if (targetType == Float.class || targetType == float.class) {
                return (T) Float.valueOf(number.floatValue());
            } else if (targetType == Short.class || targetType == short.class) {
                return (T) Short.valueOf(number.shortValue());
            } else if (targetType == Byte.class || targetType == byte.class) {
                return (T) Byte.valueOf(number.byteValue());
            }
        }

        throw new CacheConfigurationException("typeConversion", targetType,
            "无法将值从" + value.getClass().getSimpleName() + "转换为" + targetType.getSimpleName(), null);
    }

    // ==================== 缓存管理方法 ====================

    /**
     * 清理类型推断缓存
     */
    public static void clearCache() {
        int count = TYPE_INFERENCE_CACHE.size();
        TYPE_INFERENCE_CACHE.clear();
        LOGGER.info("类型推断缓存已清理，共清理: {} 个条目", count);
    }

    /**
     * 获取类型推断统计信息
     */
    public static TypeInferenceStats getStats() {
        return TypeInferenceStats.builder()
                .cachedInferenceCount(TYPE_INFERENCE_CACHE.size())
                .build();
    }

    // ==================== 内部类 ====================

    /**
     * 类型推断结果
     */
    private static class TypeInferenceResult {
        final Class<?> keyType;
        final Class<?> valueType;

        TypeInferenceResult(Class<?> keyType, Class<?> valueType) {
            this.keyType = keyType;
            this.valueType = valueType;
        }
    }

    /**
     * 类型推断统计信息
     */
    public static class TypeInferenceStats {
        private final int cachedInferenceCount;

        private TypeInferenceStats(Builder builder) {
            this.cachedInferenceCount = builder.cachedInferenceCount;
        }

        public static Builder builder() {
            return new Builder();
        }

        public int getCachedInferenceCount() {
            return cachedInferenceCount;
        }

        @Override
        public String toString() {
            return String.format("TypeInferenceStats{cachedInferenceCount=%d}", cachedInferenceCount);
        }

        public static class Builder {
            private int cachedInferenceCount;

            public Builder cachedInferenceCount(int cachedInferenceCount) {
                this.cachedInferenceCount = cachedInferenceCount;
                return this;
            }

            public TypeInferenceStats build() {
                return new TypeInferenceStats(this);
            }
        }
    }
}