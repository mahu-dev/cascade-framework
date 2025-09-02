package io.github.cascade.cache.simple;

import io.github.cascade.cache.config.CascadeCacheProperties;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 多级缓存工厂实现
 * 创建包含L1+L2的多级缓存
 * 
 * @author cascade
 */
public class TieredCacheFactory implements CacheFactory {
    
    private static final Logger log = LoggerFactory.getLogger(TieredCacheFactory.class);
    
    private final RedissonClient redissonClient;
    
    public TieredCacheFactory(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }
    
    @Override
    public <K, V> Cache<K, V> createCache(CacheDefinition<K, V> definition) {
        definition.validate();
        
        if (!supports(definition.getType())) {
            throw new IllegalArgumentException(
                String.format("TieredCacheFactory不支持缓存类型: %s，仅支持TIERED", definition.getType())
            );
        }
        
        log.info("创建多级缓存: name={}, keyType={}, valueType={}", 
                definition.getName(), 
                definition.getKeyType().getSimpleName(), 
                definition.getValueType().getSimpleName());
        
        CascadeCacheProperties config = definition.getConfig();
        
        // 创建L1缓存
        Cache<K, V> l1Cache = null;
        if (config.isL1Enabled()) {
            l1Cache = new CaffeineL1Cache<>(definition.getName(), config);
            log.debug("L1缓存已创建: maxSize={}, expireAfterWrite={}s", 
                    config.getL1MaxSize(), config.getL1ExpireAfterWriteSeconds());
        }
        
        // 创建L2缓存
        Cache<K, V> l2Cache = null;
        if (config.isL2Enabled()) {
            l2Cache = new RedissonL2Cache<>(
                    definition.getName(),
                    redissonClient,
                    config
            );
            log.debug("L2缓存已创建: keyPrefix={}, defaultTtl={}s", 
                    config.getL2KeyPrefix(), config.getL2DefaultTtlSeconds());
        }
        
        // 创建多级缓存
        Cache<K, V> tieredCache = new TieredCache<>(
                definition.getName(),
                config,
                l1Cache,
                l2Cache,
                (CacheLoader<K, V>) definition.getLoader(),
                definition.getLoaderResolver(),
                definition.getKeyType(),
                definition.getValueType()
        );
        
        log.info("多级缓存创建完成: name={}, l1Enabled={}, l2Enabled={}, hasLoader={}", 
                definition.getName(),
                l1Cache != null,
                l2Cache != null,
                definition.getLoader() != null);
        
        return tieredCache;
    }
    
    @Override
    public boolean supports(CacheType cacheType) {
        return cacheType == CacheType.TIERED;
    }
    
    @Override
    public String getFactoryName() {
        return "TieredCacheFactory";
    }
    
    @Override
    public String toString() {
        return String.format("%s{supports=%s}", getFactoryName(), CacheType.TIERED);
    }
}