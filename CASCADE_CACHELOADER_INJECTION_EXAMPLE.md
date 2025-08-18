# CacheLoader自动注入实现总结

## 实现思路总结

根据您的需求，我们实现了一个完整的Spring自动配置解决方案，通过以下方式实现CacheLoader自动注入到类型匹配的Cache中：

### 核心实现方案

1. **通过AdvancedCacheManager获取缓存名称后设置CacheLoader** - 正如您注释中提到的
2. **多种fallback策略** - 确保在不同情况下都能成功注入
3. **类型安全的泛型匹配** - 确保K和V类型完全匹配

## 实现架构

```
CacheLoaderAutoConfiguration (自动配置类)
├── CacheLoaderInjectionPostProcessor (Bean后处理器)
│   ├── 检测Cache Bean创建
│   ├── 提取泛型类型信息
│   ├── 查找匹配的CacheLoader
│   └── 执行注入策略
└── 注入策略 (多种fallback方式)
    ├── 1. 通过CacheManager.setCacheLoader()
    ├── 2. 重新创建带CacheLoader的Cache并替换
    └── 3. 直接在Cache上设置CacheLoader
```

## 核心代码解析

### 1. 注入策略实现

```java
private void injectLoaderToCache(Cache<Object, Object> cache, CacheLoader<Object, Object> loader,
                                 String cacheBeanName, String loaderBeanName) {
    try {
        // 方案1：通过CacheManager重新配置Cache的CacheLoader
        CacheManager cacheManager = applicationContext.getBean(CacheManager.class);
        
        // 获取缓存名称 - 这是关键步骤
        String cacheName = cache.getName();
        if (cacheName == null || cacheName.isEmpty()) {
            cacheName = cacheBeanName; // 使用Bean名称作为fallback
        }
        
        // 检查CacheManager类型并尝试设置CacheLoader
        if (cacheManager instanceof AdvancedCacheManager) {
            AdvancedCacheManager advancedManager = (AdvancedCacheManager) cacheManager;
            
            // 方法1：通过AdvancedCacheManager直接设置CacheLoader
            if (trySetLoaderOnManager(advancedManager, cacheName, loader)) {
                logger.info("成功通过AdvancedCacheManager为Cache {} 设置CacheLoader {}", cacheName, loaderBeanName);
                return;
            }
            
            // 方法2：重新创建带CacheLoader的Cache并替换
            if (tryReplaceWithLoaderCache(advancedManager, cacheName, loader, cacheBeanName)) {
                logger.info("成功通过重新创建方式为Cache {} 设置CacheLoader {}", cacheName, loaderBeanName);
                return;
            }
        }
        // ... 其他CacheManager类型的处理
        
    } catch (Exception e) {
        logger.error("注入CacheLoader失败: {}", e.getMessage());
    }
}
```

### 2. CacheManager扩展方法

为了支持这个功能，我们为CacheManager添加了两个关键方法：

```java
// AdvancedCacheManager.java 和 SimpleCacheManager.java
public <K, V> boolean setCacheLoader(String cacheName, CacheLoader<K, V> loader) {
    Cache<?, ?> cache = caches.get(cacheName);
    if (cache == null) {
        return false;
    }
    
    try {
        // 尝试通过反射调用setLoader方法
        Method method = cache.getClass().getMethod("setLoader", CacheLoader.class);
        method.invoke(cache, loader);
        return true;
    } catch (Exception e) {
        return false;
    }
}

public <K, V> boolean replaceCacheWithLoader(String cacheName, CacheLoader<K, V> loader) {
    // 创建新的带CacheLoader的缓存并替换原有缓存
    Cache<K, V> newCache = cacheBuilder(cacheName + "_temp").loader(loader).build();
    forceRegisterCache(cacheName, newCache);
    return true;
}
```

## 完整使用示例

### 1. 定义实体和CacheLoader

```java
// 产品实体
public class Product {
    private Long id;
    private String name;
    private BigDecimal price;
    // getters/setters...
}

// ProductCacheLoader - 实现CacheLoader<Long, Product>
@Component
public class ProductCacheLoader implements CacheLoader<Long, Product> {
    
    @Autowired
    private ProductRepository productRepository;
    
    @Override
    public Product load(Long productId) throws Exception {
        return productRepository.findById(productId)
            .orElseThrow(() -> new ProductNotFoundException("Product not found: " + productId));
    }
}
```

### 2. 配置Cache Bean

```java
@Configuration
@EnableCascadeCache
public class CacheConfiguration {
    
    @Autowired
    private CacheManager cacheManager;
    
    // 这个Cache<Long, Product>会自动匹配到ProductCacheLoader<Long, Product>
    @Bean
    @AutoConfigureLoader  // 可选注解，用于明确标识
    public Cache<Long, Product> productCache() {
        return cacheManager.cacheBuilder("products")
            .refreshAfterWrite(Duration.ofMinutes(15))
            .expireAfterWrite(Duration.ofHours(2))
            .build();
    }
}
```

### 3. 自动注入过程

1. **Spring容器启动**
2. **ProductCacheLoader创建** - 作为@Component被Spring管理
3. **productCache Bean创建** - Cache<Long, Product>类型
4. **CacheLoaderInjectionPostProcessor触发**:
   - 检测到Cache Bean创建
   - 提取泛型类型: Long, Product
   - 在容器中查找CacheLoader类型的Bean
   - 找到ProductCacheLoader，提取其泛型类型: Long, Product
   - 类型匹配成功
   - 获取Cache名称 "products"
   - 调用AdvancedCacheManager.setCacheLoader("products", productCacheLoader)
   - 注入成功

### 4. 使用缓存

```java
@Service
public class ProductService {
    
    @Autowired
    @Qualifier("productCache")
    private Cache<Long, Product> productCache;
    
    public Product getProduct(Long productId) {
        // CacheLoader会在缓存未命中时自动加载数据
        return productCache.get(productId);
    }
}
```

## 关键优势

1. **零配置** - 开发者只需定义CacheLoader Bean，框架自动处理注入
2. **类型安全** - 通过泛型类型匹配确保类型安全  
3. **多种注入策略** - 提供多个fallback方案确保成功注入
4. **非侵入性** - 不需要修改现有的Cache或CacheLoader代码
5. **灵活的缓存名称处理** - 通过cache.getName()获取名称，fallback到Bean名称

## 工作流程图

```
Spring容器启动
       ↓
   创建CacheLoader Bean
       ↓
   创建Cache Bean
       ↓
BeanPostProcessor拦截Cache Bean
       ↓
   提取Cache泛型类型<K,V>
       ↓
   查找容器中的CacheLoader Bean
       ↓
   提取CacheLoader泛型类型<K,V>  
       ↓
   类型匹配检查
       ↓
   获取Cache名称(cache.getName())
       ↓
   通过CacheManager.setCacheLoader()注入
       ↓
   注入成功，CacheLoader自动工作
```

这个实现完全满足您的需求：**通过AdvancedCacheManager获取对应的缓存名称后来设置CacheLoader**，并且当K和V类型匹配时自动设置。