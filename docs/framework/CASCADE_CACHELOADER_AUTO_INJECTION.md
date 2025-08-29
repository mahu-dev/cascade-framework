# CacheLoader 自动注入配置指南

## 功能概述

Cascade框架提供了智能的CacheLoader自动注入功能，当CacheLoader的泛型类型`<K, V>`与Cache的泛型类型匹配时，Spring会自动将CacheLoader注入到对应的Cache中。

## 基本使用方式

### 1. 定义CacheLoader Bean

```java
@Component
public class ProductCacheLoader implements CacheLoader<Long, Product> {
    
    @Autowired
    private ProductRepository productRepository;
    
    @Override
    public Product load(Long productId) throws Exception {
        Optional<Product> product = productRepository.findById(productId);
        if (product.isEmpty()) {
            throw new ProductNotFoundException("Product not found: " + productId);
        }
        return product.get();
    }
}

@Component  
public class UserCacheLoader implements CacheLoader<String, User> {
    
    @Autowired
    private UserService userService;
    
    @Override
    public User load(String userId) throws Exception {
        return userService.findById(userId);
    }
}
```

### 2. 定义Cache Bean

```java
@Configuration
public class CacheConfiguration {
    
    @Autowired
    private CacheManager cacheManager;
    
    // 自动注入ProductCacheLoader (Long, Product类型匹配)
    @Bean
    @AutoConfigureLoader
    public Cache<Long, Product> productCache() {
        return cacheManager.cacheBuilder("products")
            .refreshAfterWrite(Duration.ofMinutes(15))
            .expireAfterWrite(Duration.ofHours(2))
            .build();
    }
    
    // 自动注入UserCacheLoader (String, User类型匹配)
    @Bean
    @AutoConfigureLoader  
    public Cache<String, User> userCache() {
        return cacheManager.cacheBuilder("users")
            .refreshAfterWrite(Duration.ofMinutes(30))
            .expireAfterWrite(Duration.ofHours(4))
            .build();
    }
}
```

## 高级配置方式

### 1. 指定特定的CacheLoader

```java
@Configuration
public class AdvancedCacheConfiguration {
    
    @Bean
    @AutoConfigureLoader(loaderBean = "productCacheLoader")
    public Cache<Long, Product> productCache() {
        return cacheManager.cacheBuilder("products").build();
    }
    
    // 多个相同类型的CacheLoader时，指定具体使用哪个
    @Bean
    @AutoConfigureLoader(loaderBean = "premiumProductCacheLoader")
    public Cache<Long, Product> premiumProductCache() {
        return cacheManager.cacheBuilder("premium-products").build();
    }
}
```

### 2. 宽松类型匹配

```java
@Bean
@AutoConfigureLoader(strictTypeMatch = false)
public Cache<Number, Object> flexibleCache() {
    // 允许Long继承Number，Product继承Object的匹配
    return cacheManager.cacheBuilder("flexible").build();
}
```

## 自动匹配规则

### 类型匹配逻辑

1. **严格匹配模式** (`strictTypeMatch = true`, 默认)
   - CacheLoader的`<K, V>`必须与Cache的`<K, V>`完全相同
   - `ProductCacheLoader<Long, Product>` 只匹配 `Cache<Long, Product>`

2. **宽松匹配模式** (`strictTypeMatch = false`)
   - 允许继承关系匹配
   - `ProductCacheLoader<Long, Product>` 可以匹配 `Cache<Number, Object>`

### 匹配优先级

1. 使用`@AutoConfigureLoader(loaderBean = "specificLoader")`指定的Bean
2. 精确类型匹配的CacheLoader
3. 如果允许宽松匹配，则选择兼容类型的CacheLoader
4. 如果有多个匹配，选择第一个找到的

## 完整示例

### 电商产品缓存配置

```java
// 1. 定义实体类
public class Product {
    private Long id;
    private String name;
    private BigDecimal price;
    // getters/setters...
}

// 2. 定义CacheLoader
@Component
public class ProductCacheLoader implements CacheLoader<Long, Product> {
    
    @Autowired
    private ProductRepository productRepository;
    
    @Autowired
    private ProductValidator productValidator;
    
    @Override
    public Product load(Long productId) throws Exception {
        Optional<Product> product = productRepository.findById(productId);
        
        if (product.isEmpty()) {
            throw new ProductNotFoundException("Product not found: " + productId);
        }
        
        Product p = product.get();
        
        // 验证产品状态
        if (!productValidator.isValid(p)) {
            throw new InvalidProductException("Invalid product: " + productId);
        }
        
        return p;
    }
    
    @Override
    public Map<Long, Product> loadAll(Set<Long> productIds) throws Exception {
        // 批量优化加载
        List<Product> products = productRepository.findAllById(productIds);
        return products.stream()
            .collect(Collectors.toMap(Product::getId, Function.identity()));
    }
    
    @Override
    public boolean supportsBatchLoading() {
        return true;
    }
}

// 3. 缓存配置
@Configuration
@EnableCascadeCache
public class ProductCacheConfiguration {
    
    @Autowired
    private CacheManager cacheManager;
    
    @Bean
    @AutoConfigureLoader  // 自动注入ProductCacheLoader
    public Cache<Long, Product> productCache() {
        return cacheManager.cacheBuilder("products")
            .refreshAfterWrite(Duration.ofMinutes(15))    // 15分钟后刷新
            .expireAfterWrite(Duration.ofHours(2))        // 2小时后过期
            .maximumSize(10000)                           // 最大10K条目
            .enableL2Cache(true)                          // 启用Redis二级缓存
            .enableProtection(true)                       // 启用缓存击穿保护
            .build();
    }
    
    @Bean
    @AutoConfigureLoader(loaderBean = "productCacheLoader")
    public Cache<Long, Product> hotProductCache() {
        // 热点产品缓存，更激进的缓存策略
        return cacheManager.cacheBuilder("hot-products")
            .refreshAfterWrite(Duration.ofMinutes(5))     // 5分钟刷新
            .expireAfterWrite(Duration.ofMinutes(30))     // 30分钟过期
            .maximumSize(1000)                            // 最大1K条目
            .enableL2Cache(true)
            .build();
    }
}

// 4. 使用缓存
@Service
public class ProductService {
    
    @Autowired
    @Qualifier("productCache")
    private Cache<Long, Product> productCache;
    
    public Product getProduct(Long productId) {
        // CacheLoader会自动在缓存未命中时加载数据
        return productCache.get(productId);
    }
    
    public List<Product> getProducts(List<Long> productIds) {
        Set<Long> keySet = new HashSet<>(productIds);
        Map<Long, Product> products = productCache.getAll(keySet);
        return new ArrayList<>(products.values());
    }
}
```

## 配置验证

### 启动时检查

```java
@Component
public class CacheLoaderConfigurationValidator implements ApplicationListener<ContextRefreshedEvent> {
    
    @Autowired
    private ApplicationContext applicationContext;
    
    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        // 检查所有Cache是否都配置了合适的CacheLoader
        Map<String, Cache> cacheBeans = applicationContext.getBeansOfType(Cache.class);
        Map<String, CacheLoader> loaderBeans = applicationContext.getBeansOfType(CacheLoader.class);
        
        for (Map.Entry<String, Cache> entry : cacheBeans.entrySet()) {
            String cacheName = entry.getKey();
            Cache<?, ?> cache = entry.getValue();
            
            // 检查是否有@AutoConfigureLoader注解
            if (hasAutoConfigureLoaderAnnotation(cacheName)) {
                validateCacheLoaderInjection(cacheName, cache, loaderBeans);
            }
        }
    }
    
    private boolean hasAutoConfigureLoaderAnnotation(String beanName) {
        // 检查Bean定义是否有@AutoConfigureLoader注解
        // 实现略...
        return true;
    }
    
    private void validateCacheLoaderInjection(String cacheName, Cache<?, ?> cache, 
                                            Map<String, CacheLoader> loaderBeans) {
        // 验证CacheLoader是否正确注入
        // 实现略...
    }
}
```

## 最佳实践

### 1. 命名约定
- CacheLoader Bean: `{Entity}CacheLoader` (如 `ProductCacheLoader`)
- Cache Bean: `{entity}Cache` (如 `productCache`)

### 2. 类型安全
```java
// 推荐：使用具体类型
public class ProductCacheLoader implements CacheLoader<Long, Product> { ... }

// 避免：使用原始类型
public class ProductCacheLoader implements CacheLoader { ... }
```

### 3. 错误处理
```java
@Override
public Product load(Long productId) throws Exception {
    try {
        return productRepository.findById(productId)
            .orElseThrow(() -> new ProductNotFoundException("Product not found: " + productId));
    } catch (DataAccessException e) {
        // 数据库异常时返回默认值或重新抛出
        throw new CacheLoaderException("Failed to load product: " + productId, e);
    }
}
```

### 4. 性能优化
```java
@Override
public Map<Long, Product> loadAll(Set<Long> productIds) throws Exception {
    // 批量加载优化网络往返次数
    return productRepository.findAllByIdIn(productIds)
        .stream()
        .collect(Collectors.toMap(Product::getId, Function.identity()));
}

@Override
public boolean supportsBatchLoading() {
    return true;  // 启用批量加载优化
}
```

通过这种自动配置方式，您只需要定义好CacheLoader和Cache的Bean，框架会自动根据泛型类型进行匹配和注入，大大简化了配置工作。