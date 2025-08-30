package io.github.cascade.cache.simple;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 缓存工厂注册中心
 * 管理所有的缓存工厂实现，支持根据缓存类型自动选择工厂
 * 
 * @author cascade
 */
@Component
public class CacheFactoryRegistry {
    
    private static final Logger log = LoggerFactory.getLogger(CacheFactoryRegistry.class);
    
    /**
     * 工厂映射表：CacheType -> CacheFactory
     */
    private final Map<CacheType, CacheFactory> factoryMap = new ConcurrentHashMap<>();
    
    /**
     * 默认构造函数
     */
    public CacheFactoryRegistry() {
        log.info("创建缓存工厂注册中心");
    }
    
    /**
     * 构造函数，自动注册所有工厂
     * Spring会自动注入所有CacheFactory实现
     */
    public CacheFactoryRegistry(List<CacheFactory> factories) {
        this();
        registerFactories(factories);
    }
    
    /**
     * 根据缓存类型查找对应的工厂
     * 
     * @param cacheType 缓存类型
     * @return 缓存工厂
     * @throws IllegalArgumentException 如果找不到支持的工厂
     */
    public CacheFactory findFactory(CacheType cacheType) {
        if (cacheType == null) {
            throw new IllegalArgumentException("缓存类型不能为空");
        }
        
        CacheFactory factory = factoryMap.get(cacheType);
        if (factory == null) {
            throw new IllegalArgumentException("找不到支持缓存类型的工厂: " + cacheType);
        }
        
        return factory;
    }
    
    /**
     * 检查是否支持指定的缓存类型
     * 
     * @param cacheType 缓存类型
     * @return 是否支持
     */
    public boolean supports(CacheType cacheType) {
        return cacheType != null && factoryMap.containsKey(cacheType);
    }
    
    /**
     * 使用指定工厂创建缓存
     * 
     * @param definition 缓存定义
     * @param <K> 键类型
     * @param <V> 值类型
     * @return 创建的缓存实例
     */
    public <K, V> Cache<K, V> createCache(CacheDefinition<K, V> definition) {
        if (definition == null) {
            throw new IllegalArgumentException("缓存定义不能为空");
        }
        
        definition.validate();
        CacheType cacheType = definition.getType();
        
        CacheFactory factory = findFactory(cacheType);
        
        log.debug("使用工厂创建缓存: name={}, type={}, factory={}", 
                definition.getName(), cacheType, factory.getFactoryName());
        
        return factory.createCache(definition);
    }
    
    /**
     * 注册工厂列表
     * 
     * @param factories 工厂列表
     */
    private void registerFactories(List<CacheFactory> factories) {
        if (factories == null || factories.isEmpty()) {
            log.warn("没有找到任何缓存工厂实现");
            return;
        }
        
        log.info("开始注册缓存工厂，总数: {}", factories.size());
        
        for (CacheFactory factory : factories) {
            registerFactory(factory);
        }
        
        log.info("缓存工厂注册完成: {}", factoryMap.keySet());
    }
    
    /**
     * 注册单个工厂
     * 
     * @param factory 缓存工厂
     */
    public void registerFactory(CacheFactory factory) {
        if (factory == null) {
            log.warn("忽略空的缓存工厂");
            return;
        }
        
        // 检查工厂支持的缓存类型
        for (CacheType cacheType : CacheType.values()) {
            if (factory.supports(cacheType)) {
                CacheFactory existing = factoryMap.put(cacheType, factory);
                if (existing != null) {
                    log.warn("缓存工厂被覆盖: type={}, old={}, new={}", 
                            cacheType, existing.getFactoryName(), factory.getFactoryName());
                } else {
                    log.info("缓存工厂注册成功: type={}, factory={}", 
                            cacheType, factory.getFactoryName());
                }
            }
        }
    }
    
    /**
     * 获取所有注册的工厂信息
     * 
     * @return 工厂信息映射
     */
    public Map<CacheType, String> getRegisteredFactories() {
        Map<CacheType, String> result = new ConcurrentHashMap<>();
        factoryMap.forEach((type, factory) -> result.put(type, factory.getFactoryName()));
        return result;
    }
    
    /**
     * 获取注册的工厂总数
     * 
     * @return 工厂总数
     */
    public int getFactoryCount() {
        return factoryMap.size();
    }
    
    @Override
    public String toString() {
        return String.format("CacheFactoryRegistry{factoryCount=%d, types=%s}", 
                getFactoryCount(), factoryMap.keySet());
    }
}