package io.github.cascade.cache.simple;

import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * L2缓存工厂实现
 * 专门创建Redis分布式缓存
 * 
 * @author cascade
 */
@Component
public class L2CacheFactory implements CacheFactory {
    
    private static final Logger log = LoggerFactory.getLogger(L2CacheFactory.class);
    
    private final RedissonClient redissonClient;
    
    public L2CacheFactory(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }
    
    @Override
    public <K, V> Cache<K, V> createCache(CacheDefinition<K, V> definition) {
        definition.validate();
        
        if (!supports(definition.getType())) {
            throw new IllegalArgumentException(
                String.format("L2CacheFactory不支持缓存类型: %s，仅支持L2_ONLY", definition.getType())
            );
        }
        
        log.info("创建L2缓存: name={}, keyType={}, valueType={}", 
                definition.getName(), 
                definition.getKeyType().getSimpleName(), 
                definition.getValueType().getSimpleName());
        
        Cache<K, V> cache = new RedissonL2Cache<>(
                definition.getName(),
                redissonClient,
                definition.getConfig()
        );
        
        log.debug("L2缓存创建完成: name={}, keyPrefix={}, defaultTtl={}s", 
                definition.getName(),
                definition.getConfig().getL2KeyPrefix(),
                definition.getConfig().getL2DefaultTtlSeconds());
        
        return cache;
    }
    
    @Override
    public boolean supports(CacheType cacheType) {
        return cacheType == CacheType.L2_ONLY;
    }
    
    @Override
    public String getFactoryName() {
        return "L2CacheFactory";
    }
    
    @Override
    public String toString() {
        return String.format("%s{supports=%s}", getFactoryName(), CacheType.L2_ONLY);
    }
}