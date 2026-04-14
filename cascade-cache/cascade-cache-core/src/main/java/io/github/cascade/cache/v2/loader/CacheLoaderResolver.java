package io.github.cascade.cache.v2.loader;

import io.github.cascade.cache.v2.api.CacheLoader;
import io.github.cascade.cache.v2.api.annotations.CacheLoaderBinding;
import io.github.cascade.cache.v2.api.annotations.CacheLoaderBindings;
import io.github.cascade.cache.v2.common.exception.CacheConfigurationException;
import io.github.cascade.cache.v2.support.TypeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CacheLoader注册与解析器。
 * <p>
 * 绑定维度严格为三元组：cacheName + keyType + valueType。
 * <p>
 * 设计目标：
 * 1. 精确匹配：禁止按宽泛类型自动推断，避免类型擦除导致误配
 * 2. 启动校验：自动发现阶段即检测缺失绑定和冲突绑定并fail-fast
 * 3. 运行时保护：调用loader前后进行键值类型校验，防止raw type污染
 */
public class CacheLoaderResolver {

    private static final Logger log = LoggerFactory.getLogger(CacheLoaderResolver.class);

    /** 编程式显式注册的loader */
    private final Map<LoaderBindingKey, RegisteredLoader<?, ?>> explicitLoaders = new ConcurrentHashMap<>();

    /** 自动发现注册的loader */
    private final Map<LoaderBindingKey, RegisteredLoader<?, ?>> discoveredLoaders = new ConcurrentHashMap<>();

    private final AtomicBoolean autoDiscoveryInitialized = new AtomicBoolean(false);

    private volatile ApplicationContext applicationContext;
    private volatile boolean autoDiscoverEnabled = true;

    /**
     * 设置是否启用自动发现（默认true）。
     */
    public void setAutoDiscoverEnabled(boolean autoDiscoverEnabled) {
        this.autoDiscoverEnabled = autoDiscoverEnabled;
        initializeAutoDiscoveryIfNeeded();
    }

    /**
     * 设置Spring应用上下文。
     */
    public void setApplicationContext(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
        this.autoDiscoveryInitialized.set(false);
        log.info("CacheLoaderResolver设置ApplicationContext完成: autoDiscoverEnabled={}", autoDiscoverEnabled);
        initializeAutoDiscoveryIfNeeded();
    }

    /**
     * 强类型注册loader。
     */
    public <K, V> void registerLoader(String cacheName,
                                      Class<K> keyType,
                                      Class<V> valueType,
                                      CacheLoader<K, V> loader) {
        registerLoaderInternal(
                explicitLoaders,
                normalizeCacheName(cacheName),
                keyType,
                valueType,
                loader,
                "programmatic"
        );
    }

    /**
     * 按三元组解析loader（默认允许自动发现loader）。
     */
    public <K, V> CacheLoader<K, V> resolveCacheLoader(String cacheName,
                                                       Class<K> keyType,
                                                       Class<V> valueType) {
        return resolveCacheLoader(cacheName, keyType, valueType, true);
    }

    /**
     * 按三元组解析loader。
     *
     * @param allowDiscovered 是否允许返回自动发现loader
     */
    @SuppressWarnings("unchecked")
    public <K, V> CacheLoader<K, V> resolveCacheLoader(String cacheName,
                                                       Class<K> keyType,
                                                       Class<V> valueType,
                                                       boolean allowDiscovered) {
        // 仅当允许返回自动发现loader时才触发自动发现初始化，
        // 以保证显式解析路径（allowDiscovered=false）不会引入扫描/校验副作用。
        if (allowDiscovered) {
            initializeAutoDiscoveryIfNeeded();
        }

        LoaderBindingKey key = new LoaderBindingKey(
                normalizeCacheName(cacheName),
                requireType(keyType, "keyType"),
                requireType(valueType, "valueType")
        );

        RegisteredLoader<?, ?> explicit = explicitLoaders.get(key);
        if (explicit != null) {
            return (CacheLoader<K, V>) explicit.guardedLoader();
        }
        if (!allowDiscovered) {
            return null;
        }

        RegisteredLoader<?, ?> discovered = discoveredLoaders.get(key);
        return discovered != null ? (CacheLoader<K, V>) discovered.guardedLoader() : null;
    }

    /**
     * 清空注册信息。
     */
    public void clearCache() {
        explicitLoaders.clear();
        discoveredLoaders.clear();
        autoDiscoveryInitialized.set(false);
        log.debug("CacheLoaderResolver注册表已清空");
    }

    /**
     * 获取统计信息。
     */
    public ResolverStats getStats() {
        return new ResolverStats(
                explicitLoaders.size(),
                discoveredLoaders.size(),
                applicationContext != null,
                autoDiscoverEnabled,
                autoDiscoveryInitialized.get()
        );
    }

    private void initializeAutoDiscoveryIfNeeded() {
        if (!autoDiscoverEnabled || applicationContext == null) {
            return;
        }
        if (!autoDiscoveryInitialized.compareAndSet(false, true)) {
            return;
        }

        try {
            discoverAndRegisterFromApplicationContext();
        } catch (RuntimeException e) {
            autoDiscoveryInitialized.set(false);
            throw e;
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void discoverAndRegisterFromApplicationContext() {
        Map<String, CacheLoader> loaderBeans = getLoaderBeans();
        if (loaderBeans.isEmpty()) {
            log.info("未扫描到CacheLoader Bean");
            return;
        }

        List<Map.Entry<String, CacheLoader>> sortedEntries = loaderBeans.entrySet().stream()
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .toList();

        for (Map.Entry<String, CacheLoader> entry : sortedEntries) {
            String beanName = entry.getKey();
            CacheLoader loader = entry.getValue();
            Class<?> beanType = resolveBeanType(beanName, loader);

            Set<CacheLoaderBinding> bindings =
                    AnnotatedElementUtils.getMergedRepeatableAnnotations(beanType,
                            CacheLoaderBinding.class,
                            CacheLoaderBindings.class);

            if (bindings == null || bindings.isEmpty()) {
                throw new CacheConfigurationException(
                        "loader.binding",
                        beanName,
                        "CacheLoader Bean缺少@CacheLoaderBinding: beanType=" + beanType.getName(),
                        null
                );
            }

            for (CacheLoaderBinding binding : bindings) {
                registerDiscoveredLoader(beanName, binding, loader);
            }
        }

        log.info("CacheLoader自动发现完成: discoveredBindings={}", discoveredLoaders.size());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void registerDiscoveredLoader(String beanName, CacheLoaderBinding binding, CacheLoader loader) {
        registerLoaderInternal(
                discoveredLoaders,
                normalizeCacheName(binding.cacheName()),
                (Class) requireType(binding.keyType(), "binding.keyType"),
                (Class) requireType(binding.valueType(), "binding.valueType"),
                loader,
                "bean:" + beanName
        );
    }

    private Class<?> resolveBeanType(String beanName, CacheLoader<?, ?> loader) {
        Class<?> beanType = applicationContext.getType(beanName);
        if (beanType != null) {
            return beanType;
        }
        return loader.getClass();
    }

    @SuppressWarnings("unchecked")
    private <K, V> void registerLoaderInternal(Map<LoaderBindingKey, RegisteredLoader<?, ?>> targetRegistry,
                                               String cacheName,
                                               Class<K> keyType,
                                               Class<V> valueType,
                                               CacheLoader<K, V> loader,
                                               String source) {
        Objects.requireNonNull(loader, "loader");

        LoaderBindingKey bindingKey = new LoaderBindingKey(cacheName, keyType, valueType);
        CacheLoader<K, V> guarded = guardLoader(cacheName, keyType, valueType, loader);
        RegisteredLoader<K, V> candidate = new RegisteredLoader<>(guarded, loader, source);

        RegisteredLoader<?, ?> existing = targetRegistry.putIfAbsent(bindingKey, candidate);
        if (existing == null) {
            log.info("注册CacheLoader成功: binding={}, source={}", bindingKey, source);
            return;
        }

        if (existing.originalLoader() == loader) {
            log.debug("CacheLoader重复注册且实例相同，忽略: binding={}, source={}", bindingKey, source);
            return;
        }

        throw new CacheConfigurationException(
                "loader.binding",
                bindingKey.toString(),
                "检测到重复CacheLoader绑定: existingSource=" + existing.source()
                        + ", newSource=" + source
                        + ", existingLoader=" + existing.originalLoader().getClass().getName()
                        + ", newLoader=" + loader.getClass().getName(),
                null
        );
    }

    private Map<String, CacheLoader> getLoaderBeans() {
        try {
            return applicationContext.getBeansOfType(CacheLoader.class);
        } catch (RuntimeException e) {
            log.error("扫描CacheLoader失败: error={}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    private static <K, V> CacheLoader<K, V> guardLoader(String cacheName,
                                                        Class<K> keyType,
                                                        Class<V> valueType,
                                                        CacheLoader<K, V> delegate) {
        Class<K> normalizedKeyType = TypeUtils.boxedType(keyType);
        Class<V> normalizedValueType = TypeUtils.boxedType(valueType);
        return rawKey -> {
            K key;
            try {
                key = normalizedKeyType.cast(rawKey);
            } catch (ClassCastException e) {
                throw new CacheConfigurationException(
                        "loader.keyType",
                        cacheName,
                        "CacheLoader入参类型不匹配: expectedKeyType=" + normalizedKeyType.getName()
                                + ", actualKeyType=" + className(rawKey)
                                + ", loader=" + delegate.getClass().getName(),
                        e
                );
            }

            V value = delegate.apply(key);
            if (value != null && !normalizedValueType.isInstance(value)) {
                throw new CacheConfigurationException(
                        "loader.valueType",
                        cacheName,
                        "CacheLoader返回值类型不匹配: expectedValueType=" + normalizedValueType.getName()
                                + ", actualValueType=" + value.getClass().getName()
                                + ", loader=" + delegate.getClass().getName(),
                        null
                );
            }
            return value;
        };
    }

    private static String normalizeCacheName(String cacheName) {
        if (cacheName == null) {
            throw new IllegalArgumentException("cacheName不能为空");
        }
        String normalized = cacheName.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("cacheName不能为空");
        }
        return normalized;
    }

    private static <T> Class<T> requireType(Class<T> type, String fieldName) {
        if (type == null) {
            throw new IllegalArgumentException(fieldName + "不能为空");
        }
        return TypeUtils.boxedType(type);
    }

    private static String className(Object target) {
        return target == null ? "null" : target.getClass().getName();
    }

    /**
     * 绑定键。
     */
    public record LoaderBindingKey(String cacheName, Class<?> keyType, Class<?> valueType) {
        public LoaderBindingKey {
            cacheName = normalizeCacheName(cacheName);
            keyType = requireType(keyType, "keyType");
            valueType = requireType(valueType, "valueType");
        }

        @Override
        public String toString() {
            return cacheName + "<" + keyType.getSimpleName() + "," + valueType.getSimpleName() + ">";
        }
    }

    /**
     * 注册项。
     */
    private record RegisteredLoader<K, V>(CacheLoader<K, V> guardedLoader,
                                          CacheLoader<?, ?> originalLoader,
                                          String source) {
        private RegisteredLoader {
            Objects.requireNonNull(guardedLoader, "guardedLoader");
            Objects.requireNonNull(originalLoader, "originalLoader");
            Objects.requireNonNull(source, "source");
        }
    }

    /**
     * 解析器统计信息。
     */
    public record ResolverStats(int explicitLoaderCount,
                                int discoveredLoaderCount,
                                boolean applicationContextSet,
                                boolean autoDiscoverEnabled,
                                boolean autoDiscoveryInitialized) {
        @Override
        public String toString() {
            return "ResolverStats{explicit=" + explicitLoaderCount
                    + ", discovered=" + discoveredLoaderCount
                    + ", contextSet=" + applicationContextSet
                    + ", autoDiscoverEnabled=" + autoDiscoverEnabled
                    + ", autoDiscoveryInitialized=" + autoDiscoveryInitialized
                    + "}";
        }
    }
}
