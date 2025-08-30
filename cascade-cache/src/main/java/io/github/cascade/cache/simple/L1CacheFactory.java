package io.github.cascade.cache.simple;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * L1缓存工厂实现
 * 专门创建本地Caffeine缓存
 * 
 * @author cascade
 */
@Component
public class L1CacheFactory implements CacheFactory {
    
    private static final Logger log = LoggerFactory.getLogger(L1CacheFactory.class);
    
    @Override
    public <K, V> Cache<K, V> createCache(CacheDefinition<K, V> definition) {
        definition.validate();
        
        if (!supports(definition.getType())) {
            throw new IllegalArgumentException(
                String.format("L1CacheFactory不支持缓存类型: %s，仅支持L1_ONLY", definition.getType())
            );
        }
        
        log.info("创建L1缓存: name={}, keyType={}, valueType={}", 
                definition.getName(), 
                definition.getKeyType().getSimpleName(), 
                definition.getValueType().getSimpleName());
        
        Cache<K, V> cache = new CaffeineL1Cache<>(definition.getName(), definition.getConfig());
        
        log.debug("L1缓存创建完成: name={}, maxSize={}, expireAfterWrite={}s", 
                definition.getName(),
                definition.getConfig().getL1MaxSize(),
                definition.getConfig().getL1ExpireAfterWriteSeconds());
        
        return cache;
    }
    
    @Override
    public boolean supports(CacheType cacheType) {
        return cacheType == CacheType.L1_ONLY;
    }
    
    @Override
    public String getFactoryName() {
        return "L1CacheFactory";
    }
    
    @Override
    public String toString() {
        return String.format("%s{supports=%s}", getFactoryName(), CacheType.L1_ONLY);
    }
}