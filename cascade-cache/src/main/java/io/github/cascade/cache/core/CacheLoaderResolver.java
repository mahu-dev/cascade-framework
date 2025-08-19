package io.github.cascade.cache.core;

import io.github.cascade.cache.api.CacheLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CacheLoader自动解析器
 * 负责从Spring容器中自动发现和匹配合适的CacheLoader
 *
 * @author Cascade Framework
 */
@Component
public class CacheLoaderResolver implements ApplicationContextAware {

    private static final Logger log = LoggerFactory.getLogger(CacheLoaderResolver.class);

    private ApplicationContext applicationContext;
    private final Map<String, CacheLoader<?, ?>> loaderCache = new ConcurrentHashMap<>();

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
        log.info("CacheLoaderResolver initialized with ApplicationContext");

        // 打印所有可用的CacheLoader
        if (log.isDebugEnabled()) {
            Map<String, CacheLoader> loaderBeans = applicationContext.getBeansOfType(CacheLoader.class);
            log.debug("Found {} CacheLoader beans: {}", loaderBeans.size(), loaderBeans.keySet());
        }
    }

    /**
     * 根据泛型类型自动查找匹配的CacheLoader
     *
     * @param keyType   键类型
     * @param valueType 值类型
     * @return 匹配的CacheLoader，如果没找到返回null
     */
    @SuppressWarnings("unchecked")
    public <K, V> CacheLoader<K, V> resolveCacheLoader(Class<K> keyType, Class<V> valueType) {
        if (applicationContext == null) {
            log.warn("ApplicationContext not available, cannot resolve CacheLoader");
            return null;
        }

        String cacheKey = keyType.getName() + ":" + valueType.getName();

        // 先检查缓存
        CacheLoader<?, ?> cachedLoader = loaderCache.get(cacheKey);
        if (cachedLoader != null) {
            return (CacheLoader<K, V>) cachedLoader;
        }

        // 从Spring容器中查找所有CacheLoader实现
        Map<String, CacheLoader> loaderBeans = applicationContext.getBeansOfType(CacheLoader.class);

        for (Map.Entry<String, CacheLoader> entry : loaderBeans.entrySet()) {
            String beanName = entry.getKey();
            CacheLoader<?, ?> loader = entry.getValue();

            if (isLoaderCompatible(loader, keyType, valueType)) {
                log.info("Found compatible CacheLoader: {} for types <{}, {}>",
                        beanName, keyType.getSimpleName(), valueType.getSimpleName());

                // 缓存结果
                loaderCache.put(cacheKey, loader);
                return (CacheLoader<K, V>) loader;
            }
        }

        log.debug("No compatible CacheLoader found for types <{}, {}>",
                keyType.getSimpleName(), valueType.getSimpleName());
        return null;
    }

    /**
     * 根据缓存名称和泛型类型查找CacheLoader
     * 支持按命名约定查找，例如 UserLoader 对应 "user" 缓存
     */
    @SuppressWarnings("unchecked")
    public <K, V> CacheLoader<K, V> resolveCacheLoader(String cacheName, Class<K> keyType, Class<V> valueType) {
        if (applicationContext == null) {
            return null;
        }

        // 1. 首先尝试按命名约定查找
        CacheLoader<K, V> namedLoader = findLoaderByNamingConvention(cacheName, keyType, valueType);
        if (namedLoader != null) {
            return namedLoader;
        }

        // 2. 降级到类型匹配
        return resolveCacheLoader(keyType, valueType);
    }

    /**
     * 按命名约定查找CacheLoader
     * 支持的命名模式:
     * - {cacheName}Loader (如: userLoader)
     * - {cacheName}CacheLoader (如: userCacheLoader)
     * - {ValueType}Loader (如: UserLoader)
     */
    @SuppressWarnings("unchecked")
    private <K, V> CacheLoader<K, V> findLoaderByNamingConvention(String cacheName, Class<K> keyType, Class<V> valueType) {
        String[] possibleNames = {
                cacheName + "Loader",
                cacheName + "CacheLoader",
                valueType.getSimpleName() + "Loader",
                lowercaseFirst(valueType.getSimpleName()) + "Loader"
        };

        for (String beanName : possibleNames) {
            try {
                if (applicationContext.containsBean(beanName)) {
                    Object bean = applicationContext.getBean(beanName);
                    if (bean instanceof CacheLoader) {
                        CacheLoader<?, ?> loader = (CacheLoader<?, ?>) bean;
                        if (isLoaderCompatible(loader, keyType, valueType)) {
                            log.info("Found CacheLoader by naming convention: {} -> {}", beanName, loader.getClass().getSimpleName());
                            return (CacheLoader<K, V>) loader;
                        }
                    }
                }
            } catch (BeansException e) {
                log.debug("Bean {} not found or not accessible: {}", beanName, e.getMessage());
            }
        }

        return null;
    }

    /**
     * 检查CacheLoader是否与指定的泛型类型兼容
     */
    private boolean isLoaderCompatible(CacheLoader<?, ?> loader, Class<?> keyType, Class<?> valueType) {
        try {
            // 获取CacheLoader的泛型信息
            Type[] genericTypes = getGenericTypes(loader);
            if (genericTypes == null || genericTypes.length != 2) {
                return false;
            }

            Class<?> loaderKeyType = resolveGenericType(genericTypes[0]);
            Class<?> loaderValueType = resolveGenericType(genericTypes[1]);

            // 检查类型兼容性
            boolean keyCompatible = loaderKeyType == null || keyType.isAssignableFrom(loaderKeyType) || loaderKeyType.isAssignableFrom(keyType);
            boolean valueCompatible = loaderValueType == null || valueType.isAssignableFrom(loaderValueType) || loaderValueType.isAssignableFrom(valueType);

            log.debug("Type compatibility check: CacheLoader<{}, {}> vs <{}, {}> -> key:{}, value:{}",
                    loaderKeyType != null ? loaderKeyType.getSimpleName() : "?",
                    loaderValueType != null ? loaderValueType.getSimpleName() : "?",
                    keyType.getSimpleName(), valueType.getSimpleName(),
                    keyCompatible, valueCompatible);

            return keyCompatible && valueCompatible;
        } catch (Exception e) {
            log.debug("Error checking loader compatibility: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 获取CacheLoader的泛型类型信息
     */
    private Type[] getGenericTypes(CacheLoader<?, ?> loader) {
        Class<?> loaderClass = loader.getClass();

        // 查找实现的CacheLoader接口
        Type[] genericInterfaces = loaderClass.getGenericInterfaces();
        for (Type interfaceType : genericInterfaces) {
            if (interfaceType instanceof ParameterizedType) {
                ParameterizedType paramType = (ParameterizedType) interfaceType;
                if (paramType.getRawType() == CacheLoader.class) {
                    return paramType.getActualTypeArguments();
                }
            }
        }

        // 检查父类
        Type genericSuperclass = loaderClass.getGenericSuperclass();
        if (genericSuperclass instanceof ParameterizedType) {
            ParameterizedType paramType = (ParameterizedType) genericSuperclass;
            return paramType.getActualTypeArguments();
        }

        return null;
    }

    /**
     * 解析泛型类型为具体的Class
     */
    private Class<?> resolveGenericType(Type type) {
        if (type instanceof Class) {
            return (Class<?>) type;
        } else if (type instanceof ParameterizedType) {
            return (Class<?>) ((ParameterizedType) type).getRawType();
        }
        return null;
    }

    /**
     * 首字母小写
     */
    private String lowercaseFirst(String str) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        return Character.toLowerCase(str.charAt(0)) + str.substring(1);
    }

    /**
     * 清除缓存（用于测试或重新加载）
     */
    public void clearCache() {
        loaderCache.clear();
        log.debug("CacheLoader cache cleared");
    }
}