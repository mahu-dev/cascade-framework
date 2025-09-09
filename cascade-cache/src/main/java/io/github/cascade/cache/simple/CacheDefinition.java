package io.github.cascade.cache.simple;

import io.github.cascade.cache.config.CascadeCacheProperties;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 函数式缓存定义 - 不可变Builder模式实现
 * <p>
 * 核心改进：
 * 1. 不可变设计：创建后不可修改，线程安全
 * 2. Builder模式：流式API，更优雅的构建方式
 * 3. 函数式验证：使用Predicate进行条件验证
 * 4. Optional支持：避免null值，更安全的API
 *
 * @param <K> 键类型
 * @param <V> 值类型
 * @author cascade
 */
public final class CacheDefinition<K, V> {

    private final String name;
    private final Class<K> keyType;
    private final Class<V> valueType;
    private final CacheType type;
    private final CascadeCacheProperties config;
    private final Function<K, V> loader;
    private final CacheLoaderResolver loaderResolver;

    // 私有构造函数，只能通过Builder创建
    private CacheDefinition(Builder<K, V> builder) {
        this.name = builder.name;
        this.keyType = builder.keyType;
        this.valueType = builder.valueType;
        this.type = builder.type;
        this.config = builder.config;
        this.loader = builder.loader;
        this.loaderResolver = builder.loaderResolver;

        // 构建时进行验证
        validate();
    }

    // ==================== Getter方法 ====================

    public String getName() {
        return name;
    }

    public Class<K> getKeyType() {
        return keyType;
    }

    public Class<V> getValueType() {
        return valueType;
    }

    public CacheType getType() {
        return type;
    }

    public CascadeCacheProperties getConfig() {
        return config;
    }

    public Optional<Function<K, V>> getLoader() {
        return Optional.ofNullable(loader);
    }

    public Optional<CacheLoaderResolver> getLoaderResolver() {
        return Optional.ofNullable(loaderResolver);
    }

    // ==================== 静态工厂方法 ====================

    /**
     * 创建缓存定义构建器
     */
    public static <K, V> Builder<K, V> builder(String name, Class<K> keyType, Class<V> valueType) {
        return new Builder<K, V>()
                .name(name)
                .keyType(keyType)
                .valueType(valueType);
    }

    /**
     * 快速创建方法（向后兼容）
     */
    public static <K, V> CacheDefinition<K, V> of(String name, Class<K> keyType, Class<V> valueType) {
        return builder(name, keyType, valueType).build();
    }

    // ==================== 函数式验证 ====================

    /**
     * 验证定义的完整性 - 使用函数式方法
     */
    public void validate() {
        // 定义验证规则
        validateWith("缓存名称不能为空", () -> name != null && !name.trim().isEmpty());
        validateWith("键类型不能为空", () -> keyType != null);
        validateWith("值类型不能为空", () -> valueType != null);
        validateWith("缓存配置不能为空", () -> config != null);

        // 如果类型未设置，自动推断
        if (type == null) {
            throw new IllegalArgumentException("缓存类型不能为空，请使用Builder设置类型或调用autoInferType()");
        }
    }

    /**
     * 通用验证方法
     */
    private void validateWith(String message, Supplier<Boolean> condition) {
        if (Boolean.FALSE.equals(condition.get())) {
            throw new IllegalArgumentException(message + ": " + name);
        }
    }

    /**
     * 根据配置推断缓存类型
     */
    public CacheType inferType() {
        return Optional.ofNullable(config)
                .map(this::inferTypeFromConfig)
                .orElse(CacheType.TIERED);
    }

    private CacheType inferTypeFromConfig(CascadeCacheProperties config) {
        boolean l1Enabled = config.isL1Enabled();
        boolean l2Enabled = config.isL2Enabled();

        if (l1Enabled && l2Enabled) return CacheType.TIERED;
        if (l1Enabled) return CacheType.L1_ONLY;
        if (l2Enabled) return CacheType.L2_ONLY;

        throw new IllegalArgumentException("至少需要启用L1或L2缓存之一: " + name);
    }

    // ==================== 便捷方法 ====================

    /**
     * 检查是否有加载器
     */
    public boolean hasLoader() {
        return loader != null;
    }

    /**
     * 检查是否有加载器解析器
     */
    public boolean hasLoaderResolver() {
        return loaderResolver != null;
    }


    // ==================== Builder类 ====================

    public static final class Builder<K, V> {
        private String name;
        private Class<K> keyType;
        private Class<V> valueType;
        private CacheType type;
        private CascadeCacheProperties config;
        private Function<K, V> loader;
        private CacheLoaderResolver loaderResolver;

        private Builder() {
        }

        public Builder<K, V> name(String name) {
            this.name = name;
            return this;
        }

        public Builder<K, V> keyType(Class<K> keyType) {
            this.keyType = keyType;
            return this;
        }

        public Builder<K, V> valueType(Class<V> valueType) {
            this.valueType = valueType;
            return this;
        }

        public Builder<K, V> type(CacheType type) {
            this.type = type;
            return this;
        }

        public Builder<K, V> config(CascadeCacheProperties config) {
            this.config = config;
            return this;
        }

        public Builder<K, V> loader(Function<K, V> loader) {
            this.loader = loader;
            return this;
        }

        public Builder<K, V> loaderResolver(CacheLoaderResolver loaderResolver) {
            this.loaderResolver = loaderResolver;
            return this;
        }

        /**
         * 自动推断缓存类型
         */
        public Builder<K, V> autoInferType() {
            if (config != null) {
                this.type = inferTypeFromConfig(config);
            } else {
                this.type = CacheType.TIERED; // 默认值
            }
            return this;
        }

        /**
         * 条件设置 - 函数式API
         */
        public Builder<K, V> when(boolean condition, Function<Builder<K, V>, Builder<K, V>> action) {
            return condition ? action.apply(this) : this;
        }

        /**
         * 条件设置加载器
         */
        public Builder<K, V> loaderIf(boolean condition, Function<K, V> loader) {
            return condition ? loader(loader) : this;
        }

        /**
         * 使用默认配置
         */
        public Builder<K, V> withDefaultConfig() {
            return config(CascadeCacheProperties.defaults());
        }

        /**
         * 构建不可变实例
         */
        public CacheDefinition<K, V> build() {
            // 如果没有配置，使用默认配置
            if (config == null) {
                withDefaultConfig();
            }

            // 如果没有类型，自动推断
            if (type == null) {
                autoInferType();
            }

            return new CacheDefinition<>(this);
        }

        private static CacheType inferTypeFromConfig(CascadeCacheProperties config) {
            boolean l1Enabled = config.isL1Enabled();
            boolean l2Enabled = config.isL2Enabled();

            if (l1Enabled && l2Enabled) return CacheType.TIERED;
            if (l1Enabled) return CacheType.L1_ONLY;
            if (l2Enabled) return CacheType.L2_ONLY;

            return CacheType.TIERED; // 默认值，让validate方法处理错误
        }
    }

    // ==================== Object方法 ====================

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;

        CacheDefinition<?, ?> that = (CacheDefinition<?, ?>) obj;
        return Objects.equals(name, that.name) &&
                Objects.equals(keyType, that.keyType) &&
                Objects.equals(valueType, that.valueType) &&
                type == that.type;
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, keyType, valueType, type);
    }

    @Override
    public String toString() {
        return String.format("CacheDefinition{name='%s', keyType=%s, valueType=%s, type=%s, hasLoader=%s, hasResolver=%s}",
                name,
                keyType.getSimpleName(),
                valueType.getSimpleName(),
                type,
                hasLoader(),
                hasLoaderResolver());
    }


}