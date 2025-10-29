package io.github.cascade.cache.loader;

import io.github.cascade.cache.core.CacheLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


/**
 * CacheLoader自动解析器
 * <p>
 * 功能特点：
 * 1. 自动发现：扫描Spring容器中所有CacheLoader实现
 * 2. 泛型匹配：根据K、V类型自动匹配合适的CacheLoader
 * 3. 缓存机制：缓存已解析的CacheLoader，提高性能
 * 4. 类型安全：严格的类型检查和转换
 *
 * @author cascade
 */
public class CacheLoaderResolver<K, V> {

    private static final Logger log = LoggerFactory.getLogger(CacheLoaderResolver.class);

    private ApplicationContext applicationContext;

    // 缓存已解析的CacheLoader，格式：keyType-valueType -> CacheLoader
    private final Map<String, CacheLoader<K, V>> resolvedLoaders = new ConcurrentHashMap<>();

    // 缓存类型信息，格式：CacheLoader实例 -> 类型信息
    private final Map<CacheLoader<K, V>, TypeInfo> typeInfoCache = new ConcurrentHashMap<>();

    /**
     * 设置Spring应用上下文
     */
    public void setApplicationContext(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
        log.info("CacheLoaderResolver设置ApplicationContext完成");
    }

    /**
     * 根据键值类型查找匹配的CacheLoader
     *
     * @param keyType   键类型
     * @param valueType 值类型
     * @return 匹配的CacheLoader，如果未找到返回null
     */
    public CacheLoader<K, V> resolveCacheLoader(Class<K> keyType, Class<V> valueType) {
        if (applicationContext == null) {
            log.warn("ApplicationContext未设置，无法自动解析CacheLoader");
            return null;
        }

        String cacheKey = generateCacheKey(keyType, valueType);

        // 先从缓存中查找
        CacheLoader<K, V> cachedLoader = resolvedLoaders.get(cacheKey);
        if (cachedLoader != null) {
            log.debug("从缓存中找到CacheLoader: keyType={}, valueType={}", keyType.getSimpleName(),
                    valueType.getSimpleName());
            return cachedLoader;
        }

        // 从Spring容器中查找
        CacheLoader<K, V> loader = findMatchingCacheLoader(keyType, valueType);
        if (loader != null) {
            resolvedLoaders.put(cacheKey, loader);
            log.info("找到匹配的CacheLoader: keyType={}, valueType={}, loader={}",
                    keyType.getSimpleName(), valueType.getSimpleName(), loader.getClass().getSimpleName());
        } else {
            log.debug("未找到匹配的CacheLoader: keyType={}, valueType={}", keyType.getSimpleName(),
                    valueType.getSimpleName());
        }

        return loader;
    }

    /**
     * 从Spring容器中查找匹配的CacheLoader
     */
    private CacheLoader<K, V> findMatchingCacheLoader(Class<K> keyType, Class<V> valueType) {
        Map<String, CacheLoader> loaderBeans = getLoaderBeans(keyType, valueType);
        if (loaderBeans == null) {
            return null;
        }

        log.debug("扫描到{}个CacheLoader实现", loaderBeans.size());

        for (Map.Entry<String, CacheLoader> entry : loaderBeans.entrySet()) {
            String beanName = entry.getKey();
            CacheLoader<K, V> loader = entry.getValue();

            CacheLoader<K, V> matchedLoader = tryMatchLoader(beanName, loader, keyType, valueType);
            if (matchedLoader != null) {
                return matchedLoader;
            }
        }

        return null;
    }

    /**
     * 安全获取所有CacheLoader类型的Bean
     */
    private Map<String, CacheLoader> getLoaderBeans(Class<K> keyType, Class<V> valueType) {
        try {
            return applicationContext.getBeansOfType(CacheLoader.class);
        } catch (RuntimeException e) {
            log.error("查找CacheLoader失败: keyType={}, valueType={}, error={}",
                    keyType.getSimpleName(), valueType.getSimpleName(), e.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * 尝试匹配单个CacheLoader
     */
    private CacheLoader<K, V> tryMatchLoader(String beanName, CacheLoader<K, V> loader,
                                             Class<K> keyType, Class<V> valueType) {
        try {
            TypeInfo typeInfo = getOrAnalyzeTypeInfo(loader);
            if (typeInfo != null && typeInfo.matches(keyType, valueType)) {
                log.debug("找到匹配的CacheLoader Bean: name={}, keyType={}, valueType={}",
                        beanName, typeInfo.keyType.getSimpleName(), typeInfo.valueType.getSimpleName());
                return loader;
            }
        } catch (RuntimeException e) {
            log.warn("分析CacheLoader类型失败: beanName={}, error={}", beanName, e.getMessage());
        }
        return null;
    }

    /**
     * 获取或分析CacheLoader的类型信息
     */
    private TypeInfo getOrAnalyzeTypeInfo(CacheLoader<K, V> loader) {
        return typeInfoCache.computeIfAbsent(loader, this::analyzeTypeInfo);
    }

    /**
     * 分析CacheLoader的泛型类型信息
     */
    private TypeInfo analyzeTypeInfo(CacheLoader<K, V> loader) {
        TypeInfo result = null;

        try {
            Class<?> loaderClass = loader.getClass();

            // 首先尝试从接口中解析
            result = analyzeInterfaceTypes(loaderClass);

            // 如果接口解析失败，尝试从父类中解析
            if (result == null) {
                result = analyzeSuperclassTypes(loaderClass);
            }

            if (result == null) {
                log.debug("无法解析CacheLoader的泛型类型: class={}", loaderClass.getName());
            }

        } catch (RuntimeException e) {
            log.warn("分析CacheLoader类型信息失败: loader={}, error={}",
                    loader.getClass().getName(), e.getMessage());
        }

        return result;
    }

    /**
     * 从接口中分析类型信息
     */
    private TypeInfo analyzeInterfaceTypes(Class<?> loaderClass) {
        Type[] genericInterfaces = loaderClass.getGenericInterfaces();
        for (Type genericInterface : genericInterfaces) {
            TypeInfo typeInfo = tryExtractTypeInfo(genericInterface);
            if (typeInfo != null) {
                return typeInfo;
            }
        }
        return null;
    }

    /**
     * 从父类中分析类型信息
     */
    private TypeInfo analyzeSuperclassTypes(Class<?> loaderClass) {
        Type genericSuperclass = loaderClass.getGenericSuperclass();
        if (genericSuperclass instanceof ParameterizedType superclass) {
            return analyzeParameterizedType(superclass);
        }
        return null;
    }

    /**
     * 尝试从泛型类型中提取类型信息
     */
    private TypeInfo tryExtractTypeInfo(Type type) {
        if (!(type instanceof ParameterizedType parameterizedType)) {
            return null;
        }

        Type rawType = parameterizedType.getRawType();
        if (rawType != CacheLoader.class) {
            return null;
        }
        return extractTypeArgumentsAsTypeInfo(parameterizedType);
    }

    /**
     * 从参数化类型中提取类型参数
     */
    private static TypeInfo extractTypeArgumentsAsTypeInfo(ParameterizedType parameterizedType) {
        Type[] typeArguments = parameterizedType.getActualTypeArguments();
        if (typeArguments.length != 2) {
            return null;
        }

        Type keyType = typeArguments[0];
        Type valueType = typeArguments[1];

        if (keyType instanceof Class && valueType instanceof Class) {
            return new TypeInfo((Class<?>) keyType, (Class<?>) valueType);
        }

        return null;
    }

    /**
     * 分析参数化类型
     */
    private TypeInfo analyzeParameterizedType(ParameterizedType parameterizedType) {
        Type rawType = parameterizedType.getRawType();

        // 检查是否是CacheLoader类型或其子类
        if (rawType instanceof Class && CacheLoader.class.isAssignableFrom((Class<?>) rawType)) {
            return extractTypeArgumentsAsTypeInfo(parameterizedType);
        }

        return null;
    }

    /**
     * 生成缓存键
     */
    private static String generateCacheKey(Class<?> keyType, Class<?> valueType) {
        return keyType.getName() + "-" + valueType.getName();
    }

    /**
     * 清空缓存
     */
    public void clearCache() {
        resolvedLoaders.clear();
        typeInfoCache.clear();
        log.debug("CacheLoaderResolver缓存已清空");
    }

    /**
     * 获取统计信息
     */
    public ResolverStats getStats() {
        return new ResolverStats(
                resolvedLoaders.size(),
                typeInfoCache.size(),
                applicationContext != null
        );
    }

    /**
     * 类型信息内部类
     */
    private record TypeInfo(Class<?> keyType, Class<?> valueType) {
        /**
         * 检查是否匹配指定的类型
         */
        boolean matches(Class<?> targetKeyType, Class<?> targetValueType) {
            return isAssignableFrom(keyType, targetKeyType) && isAssignableFrom(valueType, targetValueType);
        }

        /**
         * 检查类型兼容性
         */
        private static boolean isAssignableFrom(Class<?> sourceType, Class<?> targetType) {
            return sourceType.isAssignableFrom(targetType);
        }

        @Override
        public String toString() {
            return String.format("TypeInfo{keyType=%s, valueType=%s}",
                    keyType.getSimpleName(), valueType.getSimpleName());
        }
    }

    /**
     * 统计信息
     */
    public record ResolverStats(int resolvedLoaderCount, int typeInfoCacheSize, boolean applicationContextSet) {
        @Override
        public String toString() {
            return String.format("ResolverStats{resolved=%d, cached=%d, contextSet=%s}",
                    resolvedLoaderCount, typeInfoCacheSize, applicationContextSet);
        }
    }
}