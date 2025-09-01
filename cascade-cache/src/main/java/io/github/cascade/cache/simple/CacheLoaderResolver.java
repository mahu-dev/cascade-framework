package io.github.cascade.cache.simple;

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
            log.debug("从缓存中找到CacheLoader: keyType={}, valueType={}", keyType.getSimpleName(), valueType.getSimpleName());
            return cachedLoader;
        }

        // 从Spring容器中查找
        CacheLoader<K, V> loader = findMatchingCacheLoader(keyType, valueType);
        if (loader != null) {
            resolvedLoaders.put(cacheKey, loader);
            log.info("找到匹配的CacheLoader: keyType={}, valueType={}, loader={}",
                    keyType.getSimpleName(), valueType.getSimpleName(), loader.getClass().getSimpleName());
        } else {
            log.debug("未找到匹配的CacheLoader: keyType={}, valueType={}", keyType.getSimpleName(), valueType.getSimpleName());
        }

        return loader;
    }

    /**
     * 从Spring容器中查找匹配的CacheLoader
     */
    @SuppressWarnings("unchecked")
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
        } catch (Exception e) {
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
        } catch (Exception e) {
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
        try {
            Class<?> loaderClass = loader.getClass();

            // 查找CacheLoader接口的泛型参数
            Type[] genericInterfaces = loaderClass.getGenericInterfaces();
            for (Type genericInterface : genericInterfaces) {
                if (genericInterface instanceof ParameterizedType) {
                    ParameterizedType parameterizedType = (ParameterizedType) genericInterface;
                    Type rawType = parameterizedType.getRawType();

                    if (rawType == CacheLoader.class) {
                        Type[] typeArguments = parameterizedType.getActualTypeArguments();
                        if (typeArguments.length == 2) {
                            Type keyType = typeArguments[0];
                            Type valueType = typeArguments[1];

                            if (keyType instanceof Class && valueType instanceof Class) {
                                return new TypeInfo((Class<?>) keyType, (Class<?>) valueType);
                            }
                        }
                    }
                }
            }

            // 检查父类的泛型参数
            Type genericSuperclass = loaderClass.getGenericSuperclass();
            if (genericSuperclass instanceof ParameterizedType) {
                ParameterizedType parameterizedSuperclass = (ParameterizedType) genericSuperclass;
                return analyzeParameterizedType(parameterizedSuperclass);
            }

            log.debug("无法解析CacheLoader的泛型类型: class={}", loaderClass.getName());
            return null;

        } catch (Exception e) {
            log.warn("分析CacheLoader类型信息失败: loader={}, error={}",
                    loader.getClass().getName(), e.getMessage());
            return null;
        }
    }

    /**
     * 分析参数化类型
     */
    private TypeInfo analyzeParameterizedType(ParameterizedType parameterizedType) {
        // TODO: 实现复杂泛型解析逻辑
        // 这里可以添加更复杂的泛型解析，包括继承链的处理
        return null;
    }

    /**
     * 生成缓存键
     */
    private String generateCacheKey(Class<?> keyType, Class<?> valueType) {
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
    private static class TypeInfo {
        final Class<?> keyType;
        final Class<?> valueType;

        TypeInfo(Class<?> keyType, Class<?> valueType) {
            this.keyType = keyType;
            this.valueType = valueType;
        }

        /**
         * 检查是否匹配指定的类型
         */
        boolean matches(Class<?> targetKeyType, Class<?> targetValueType) {
            return isAssignableFrom(keyType, targetKeyType) && isAssignableFrom(valueType, targetValueType);
        }

        /**
         * 检查类型兼容性
         */
        private boolean isAssignableFrom(Class<?> sourceType, Class<?> targetType) {
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
    public static class ResolverStats {
        private final int resolvedLoaderCount;
        private final int typeInfoCacheSize;
        private final boolean applicationContextSet;

        public ResolverStats(int resolvedLoaderCount, int typeInfoCacheSize, boolean applicationContextSet) {
            this.resolvedLoaderCount = resolvedLoaderCount;
            this.typeInfoCacheSize = typeInfoCacheSize;
            this.applicationContextSet = applicationContextSet;
        }

        public int getResolvedLoaderCount() {
            return resolvedLoaderCount;
        }

        public int getTypeInfoCacheSize() {
            return typeInfoCacheSize;
        }

        public boolean isApplicationContextSet() {
            return applicationContextSet;
        }

        @Override
        public String toString() {
            return String.format("ResolverStats{resolved=%d, cached=%d, contextSet=%s}",
                    resolvedLoaderCount, typeInfoCacheSize, applicationContextSet);
        }
    }
}