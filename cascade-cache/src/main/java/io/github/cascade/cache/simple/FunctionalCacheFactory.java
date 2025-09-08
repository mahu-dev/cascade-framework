package io.github.cascade.cache.simple;

import io.github.cascade.cache.config.CascadeCacheProperties;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Function;

/**
 * 函数式缓存工厂 - 创建基于函数组合的缓存实例
 * <p>
 * 核心改进：
 * 1. 创建FunctionalCache实例，使用函数式管道
 * 2. 支持多种缓存策略（标准、防击穿、异步优化等）
 * 3. 简化工厂逻辑，减少复杂性
 * 4. 更好的扩展性和可测试性
 * 
 * @author cascade
 */
public class FunctionalCacheFactory implements CacheFactory {
    
    private static final Logger log = LoggerFactory.getLogger(FunctionalCacheFactory.class);
    
    private final RedissonClient redissonClient;
    private final FunctionalCache.CacheStrategy defaultStrategy;
    
    public FunctionalCacheFactory(RedissonClient redissonClient) {
        this(redissonClient, FunctionalCache.CacheStrategy.STANDARD);
    }
    
    public FunctionalCacheFactory(RedissonClient redissonClient, FunctionalCache.CacheStrategy defaultStrategy) {
        this.redissonClient = redissonClient;
        this.defaultStrategy = defaultStrategy;
    }
    
    @Override
    public <K, V> Cache<K, V> createCache(CacheDefinition<K, V> definition) {
        definition.validate();
        
        if (!supports(definition.getType())) {
            throw new IllegalArgumentException(
                String.format("FunctionalCacheFactory不支持缓存类型: %s", definition.getType())
            );
        }
        
        log.info("创建函数式缓存: name={}, type={}, keyType={}, valueType={}", 
                definition.getName(),
                definition.getType(),
                definition.getKeyType().getSimpleName(), 
                definition.getValueType().getSimpleName());
        
        return switch (definition.getType()) {
            case TIERED -> createTieredCache(definition);
            case L1_ONLY -> createL1OnlyCache(definition);
            case L2_ONLY -> createL2OnlyCache(definition);
        };
    }
    
    /**
     * 创建多级函数式缓存
     */
    private <K, V> Cache<K, V> createTieredCache(CacheDefinition<K, V> definition) {
        CascadeCacheProperties config = definition.getConfig();
        
        // 创建L1缓存（如果启用）
        Cache<K, V> l1Cache = config.isL1Enabled() ? 
            new CaffeineL1Cache<>(definition.getName(), config) : null;
            
        // 创建L2缓存（如果启用）  
        Cache<K, V> l2Cache = config.isL2Enabled() ?
            new RedissonL2Cache<>(definition.getName(), redissonClient, config) : null;
            
        // 创建加载器
        CacheLoader<K, V> loader = createLoader(definition);
        
        // 确定缓存策略
        FunctionalCache.CacheStrategy strategy = determineStrategy(config);
        
        Cache<K, V> cache = new FunctionalCache<>(
            definition.getName(),
            definition.getKeyType(),
            definition.getValueType(), 
            l1Cache,
            l2Cache,
            loader,
            strategy
        );
        
        log.info("多级函数式缓存创建完成: name={}, strategy={}, l1={}, l2={}, hasLoader={}", 
                definition.getName(), strategy,
                l1Cache != null, l2Cache != null, loader != null);
                
        return cache;
    }
    
    /**
     * 创建仅L1缓存
     */
    private <K, V> Cache<K, V> createL1OnlyCache(CacheDefinition<K, V> definition) {
        CascadeCacheProperties config = definition.getConfig();
        
        Cache<K, V> l1Cache = new CaffeineL1Cache<>(definition.getName(), config);
        CacheLoader<K, V> loader = createLoader(definition);
        
        Cache<K, V> cache = new FunctionalCache<>(
            definition.getName(),
            definition.getKeyType(),
            definition.getValueType(),
            l1Cache,
            null,
            loader,
            FunctionalCache.CacheStrategy.L1_ONLY
        );
        
        log.info("L1函数式缓存创建完成: name={}, hasLoader={}", 
                definition.getName(), loader != null);
                
        return cache;
    }
    
    /**
     * 创建仅L2缓存
     */
    private <K, V> Cache<K, V> createL2OnlyCache(CacheDefinition<K, V> definition) {
        CascadeCacheProperties config = definition.getConfig();
        
        Cache<K, V> l2Cache = new RedissonL2Cache<>(
            definition.getName(), redissonClient, config);
        CacheLoader<K, V> loader = createLoader(definition);
        
        Cache<K, V> cache = new FunctionalCache<>(
            definition.getName(),
            definition.getKeyType(),
            definition.getValueType(),
            null,
            l2Cache,
            loader,
            FunctionalCache.CacheStrategy.L2_ONLY
        );
        
        log.info("L2函数式缓存创建完成: name={}, hasLoader={}", 
                definition.getName(), loader != null);
                
        return cache;
    }
    
    /**
     * 创建缓存加载器
     */
    @SuppressWarnings("unchecked")
    private <K, V> CacheLoader<K, V> createLoader(CacheDefinition<K, V> definition) {
        // 优先使用直接指定的loader
        if (definition.getLoader().isPresent()) {
            return CacheLoader.of(definition.getLoader().get());
        }
        
        // 尝试自动发现loader（如果启用了自动发现功能）
        if (definition.getLoaderResolver().isPresent() && 
            definition.getConfig().getLoader().isAutoDiscover()) {
            try {
                return definition.getLoaderResolver().get().resolveCacheLoader(
                    definition.getKeyType(), definition.getValueType());
            } catch (Exception e) {
                log.warn("自动发现CacheLoader失败: cache={}, error={}", 
                        definition.getName(), e.getMessage());
            }
        }
        
        return null;
    }
    
    /**
     * 根据配置确定缓存策略
     */
    private FunctionalCache.CacheStrategy determineStrategy(CascadeCacheProperties config) {
        // 根据配置特性推断策略
        boolean highConcurrency = config.getL1MaxSize() > 10000 || config.getL2DefaultTtlSeconds() < 60;
        boolean needProtection = config.getLoader().isAutoDiscover();
        
        if (highConcurrency) {
            return FunctionalCache.CacheStrategy.ASYNC_OPTIMIZED;
        } else if (needProtection) {
            return FunctionalCache.CacheStrategy.PROTECTED;
        } else {
            return defaultStrategy;
        }
    }
    
    @Override
    public boolean supports(CacheType cacheType) {
        return cacheType == CacheType.TIERED || 
               cacheType == CacheType.L1_ONLY || 
               cacheType == CacheType.L2_ONLY;
    }
    
    @Override
    public String getFactoryName() {
        return "FunctionalCacheFactory";
    }
    
    /**
     * 创建自定义策略的缓存
     */
    public <K, V> Cache<K, V> createCache(CacheDefinition<K, V> definition, 
                                         FunctionalCache.CacheStrategy strategy) {
        // 直接创建带指定策略的缓存
        CascadeCacheProperties config = definition.getConfig();
        Function<K, V> finalLoader = resolveLoader(definition);
        
        // 创建缓存组件
        Cache<K, V> l1Cache = config.isL1Enabled() ? 
            new CaffeineL1Cache<>(definition.getName(), config) : null;
        Cache<K, V> l2Cache = config.isL2Enabled() ?
            new RedissonL2Cache<>(definition.getName(), redissonClient, config) : null;
        CacheLoader<K, V> loader = finalLoader != null ? CacheLoader.of(finalLoader) : null;
        
        return new FunctionalCache<>(
            definition.getName(),
            definition.getKeyType(),
            definition.getValueType(),
            l1Cache,
            l2Cache,
            loader,
            strategy
        );
    }
    
    /**
     * 解析加载器
     */
    @SuppressWarnings("unchecked")
    private <K, V> Function<K, V> resolveLoader(CacheDefinition<K, V> definition) {
        if (definition.getLoader().isPresent()) {
            return definition.getLoader().get();
        }
        
        if (definition.getLoaderResolver().isPresent()) {
            try {
                CacheLoader<K, V> resolvedLoader = definition.getLoaderResolver().get()
                    .resolveCacheLoader(definition.getKeyType(), definition.getValueType());
                return resolvedLoader != null ? resolvedLoader::apply : null;
            } catch (Exception e) {
                log.warn("解析加载器失败: {}", e.getMessage());
            }
        }
        
        return null;
    }
    
    /**
     * 创建带自定义管道的缓存
     */
    public <K, V> Cache<K, V> createCustomPipelineCache(String name, 
                                                        Class<K> keyType, 
                                                        Class<V> valueType,
                                                        java.util.function.Function<K, V> customPipeline) {
        Cache<K, V> cache = new FunctionalCache<>(name, keyType, valueType, customPipeline);
        log.info("自定义管道缓存创建完成: name={}", name);
        return cache;
    }
    
    @Override
    public String toString() {
        return String.format("%s{supports=[%s,%s,%s], defaultStrategy=%s}", 
                getFactoryName(), 
                CacheType.TIERED, CacheType.L1_ONLY, CacheType.L2_ONLY,
                defaultStrategy);
    }
}