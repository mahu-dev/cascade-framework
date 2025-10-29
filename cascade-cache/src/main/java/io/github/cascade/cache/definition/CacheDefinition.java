package io.github.cascade.cache.definition;

import io.github.cascade.cache.config.CascadeCacheProperties;
import io.github.cascade.cache.loader.CacheLoaderResolver;
import lombok.Getter;

import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.UnaryOperator;

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

    @Getter
    private final String name;
    @Getter
    private final Class<K> keyType;
    @Getter
    private final Class<V> valueType;
    @Getter
    private final CacheType type;
    @Getter
    private final CascadeCacheProperties config;
    private final Function<K, V> loader;
    private final CacheLoaderResolver<K, V> loaderResolver;

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

    public Optional<Function<K, V>> getLoader() {
        return Optional.ofNullable(loader);
    }

    public Optional<CacheLoaderResolver<K, V>> getLoaderResolver() {
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
    private void validateWith(String message, BooleanSupplier condition) {
        if (!condition.getAsBoolean()) {
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

        if (l1Enabled && l2Enabled) {
            return CacheType.TIERED;
        }
        if (l1Enabled) {
            return CacheType.L1_ONLY;
        }
        if (l2Enabled) {
            return CacheType.L2_ONLY;
        }

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
        private CacheLoaderResolver<K, V> loaderResolver;

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

        public Builder<K, V> loaderResolver(CacheLoaderResolver<K, V> loaderResolver) {
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
         * 条件执行操作（当条件为真时）
         */
        public Builder<K, V> whenTrue(boolean condition, UnaryOperator<Builder<K, V>> action) {
            if (condition) {
                return action.apply(this);
            }
            return this;
        }

        /**
         * 无条件执行操作
         */
        public Builder<K, V> apply(UnaryOperator<Builder<K, V>> action) {
            return action.apply(this);
        }

        /**
         * 设置加载器（当条件为真时）
         */
        public Builder<K, V> loaderWhenTrue(boolean condition, Function<K, V> loader) {
            if (condition) {
                return loader(loader);
            }
            return this;
        }

        /**
         * 设置加载器（无条件）
         */
        public Builder<K, V> withLoader(Function<K, V> loader) {
            return loader(loader);
        }

        /**
         * 使用默认配置
         */
        public void withDefaultConfig() {
            config(CascadeCacheProperties.defaults());
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
            CacheType result = CacheType.TIERED; // 默认值
            if (l1Enabled && l2Enabled) {
                result = CacheType.TIERED;
            } else if (l1Enabled) {
                result = CacheType.L1_ONLY;
            } else if (l2Enabled) {
                result = CacheType.L2_ONLY;
            }
            return result;
        }
    }

    // ==================== Object方法 ====================

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }

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
        return String.format(
                "CacheDefinition{name='%s', keyType=%s, valueType=%s, type=%s, hasLoader=%s, hasResolver=%s}",
                name,
                keyType.getSimpleName(),
                valueType.getSimpleName(),
                type,
                hasLoader(),
                hasLoaderResolver());
    }


}