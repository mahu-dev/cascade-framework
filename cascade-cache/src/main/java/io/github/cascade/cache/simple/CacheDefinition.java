package io.github.cascade.cache.simple;

import io.github.cascade.cache.config.CascadeCacheProperties;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.function.Function;

/**
 * 缓存定义配置类
 * 包含创建缓存所需的所有参数
 * 
 * @param <K> 键类型
 * @param <V> 值类型
 * @author cascade
 */
@Data
@Accessors(chain = true)
public class CacheDefinition<K, V> {
    
    /**
     * 缓存名称
     */
    private String name;
    
    /**
     * 键类型
     */
    private Class<K> keyType;
    
    /**
     * 值类型
     */
    private Class<V> valueType;
    
    /**
     * 缓存类型
     */
    private CacheType type;
    
    /**
     * 缓存配置
     */
    private CascadeCacheProperties config;
    
    /**
     * 数据加载器（可选）
     */
    private Function<K, V> loader;
    
    /**
     * 缓存加载器解析器（可选）
     */
    private CacheLoaderResolver loaderResolver;
    
    /**
     * 创建缓存定义的静态方法
     */
    public static <K, V> CacheDefinition<K, V> of(String name, Class<K> keyType, Class<V> valueType) {
        return new CacheDefinition<K, V>()
                .setName(name)
                .setKeyType(keyType)
                .setValueType(valueType);
    }
    
    /**
     * 根据配置自动推断缓存类型
     */
    public CacheType inferType() {
        if (config == null) {
            return CacheType.TIERED; // 默认多级缓存
        }
        
        boolean l1Enabled = config.isL1Enabled();
        boolean l2Enabled = config.isL2Enabled();
        
        if (l1Enabled && l2Enabled) {
            return CacheType.TIERED;
        } else if (l1Enabled) {
            return CacheType.L1_ONLY;
        } else if (l2Enabled) {
            return CacheType.L2_ONLY;
        } else {
            // 至少启用一个缓存层
            throw new IllegalArgumentException("至少需要启用L1或L2缓存之一: " + name);
        }
    }
    
    /**
     * 验证定义的完整性
     */
    public void validate() {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("缓存名称不能为空");
        }
        if (keyType == null) {
            throw new IllegalArgumentException("键类型不能为空: " + name);
        }
        if (valueType == null) {
            throw new IllegalArgumentException("值类型不能为空: " + name);
        }
        if (config == null) {
            throw new IllegalArgumentException("缓存配置不能为空: " + name);
        }
        
        // 自动推断类型
        if (type == null) {
            type = inferType();
        }
    }
    
    @Override
    public String toString() {
        return String.format("CacheDefinition{name='%s', keyType=%s, valueType=%s, type=%s, hasLoader=%s}", 
                name, keyType.getSimpleName(), valueType.getSimpleName(), type, loader != null);
    }
}