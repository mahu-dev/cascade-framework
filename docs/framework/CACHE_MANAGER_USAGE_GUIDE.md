# Cascade 缓存管理器使用指南

## 🎯 概览

本指南展示如何使用增强后的 Cascade 缓存管理体系，包括：
- 统一的 `CacheManager` 接口
- 重构的 `SimpleCascadeCacheManager`
- 工厂模式的 `FactoryCascadeCacheManager`
- 集成的 `CascadeCacheBuilder` 自动注册功能

## 📚 核心组件

### 1. CacheManager 接口

新的 `CacheManager` 接口继承了 `Lifecycle`，提供完整的缓存管理功能：

```java
public interface CacheManager extends Lifecycle {
    // 基础获取方法
    <K, V> Cache<K, V> getCache(String cacheName);
    <K, V> Cache<K, V> getCache(String cacheName, CacheLoader<K, V> loader);
    <K, V> Cache<K, V> getCache(String cacheName, CacheConfig config);
    <K, V> Cache<K, V> getCache(String cacheName, CacheConfig config, CacheLoader<K, V> loader);
    
    // 注册管理
    <K, V> boolean registerCache(String cacheName, Cache<K, V> cache);
    <K, V> Cache<K, V> forceRegisterCache(String cacheName, Cache<K, V> cache);
    
    // 运维功能
    Map<String, HealthStatus> getAllCacheHealth();
    HealthStatus getCacheHealth(String cacheName);
    boolean refreshCache(String cacheName);
    void refreshAll();
    
    // 配置管理
    void setCacheFactory(Function<String, Cache<?, ?>> cacheFactory);
    void setDefaultConfig(CacheConfig defaultConfig);
}
```

### 2. SimpleCascadeCacheManager

基础的缓存管理器实现，适合简单场景：

```java
@Configuration
public class CacheConfig {
    
    @Bean
    public CacheManager cacheManager() {
        SimpleCascadeCacheManager manager = new SimpleCascadeCacheManager();
        
        // 设置默认配置
        CacheConfig defaultConfig = new CacheConfig();
        manager.setDefaultConfig(defaultConfig);
        
        // 启动管理器
        try {
            manager.start();
        } catch (Exception e) {
            throw new RuntimeException("Failed to start cache manager", e);
        }
        
        return manager;
    }
}
```

### 3. FactoryCascadeCacheManager

智能的工厂模式缓存管理器，支持自动环境检测：

```java
@Configuration
public class AdvancedCacheConfig {
    
    @Bean
    public CacheManager cacheManager(@Autowired(required = false) RedissonClient redissonClient) {
        FactoryCascadeCacheManager manager = new FactoryCascadeCacheManager();
        
        // 设置创建策略
        if (redissonClient != null) {
            manager.setCreationStrategy(FactoryCascadeCacheManager.CacheCreationStrategy.MULTI_LEVEL);
        } else {
            manager.setCreationStrategy(FactoryCascadeCacheManager.CacheCreationStrategy.LOCAL_ONLY);
        }
        
        // 设置命名配置
        CacheConfig userCacheConfig = new CacheConfig();
        manager.setNamedConfig("users", userCacheConfig);
        
        // 启动管理器
        try {
            manager.start();
        } catch (Exception e) {
            throw new RuntimeException("Failed to start advanced cache manager", e);
        }
        
        return manager;
    }
}
```

## 🚀 使用方式

### 1. 基础使用

```java
@Service
public class UserService {
    
    @Autowired
    private CacheManager cacheManager;
    
    @Autowired
    private UserRepository userRepository;
    
    public User getUser(String userId) {
        // 获取或创建带加载器的缓存
        Cache<String, User> userCache = cacheManager.getCache("users", 
            userId -> userRepository.findById(userId).orElse(null));
        
        return userCache.get(userId);
    }
    
    public List<User> getAllUsers() {
        Cache<String, List<User>> allUsersCache = cacheManager.getCache("all-users");
        return allUsersCache.get("all", key -> userRepository.findAll());
    }
}
```

### 2. 高级使用 - 自动注册

```java
@Configuration
public class CacheSetup {
    
    @Bean
    public CacheManager cacheManager() {
        FactoryCascadeCacheManager manager = new FactoryCascadeCacheManager();
        manager.setCreationStrategy(FactoryCascadeCacheManager.CacheCreationStrategy.AUTO_DETECT);
        
        // 设置为全局管理器，启用自动注册
        CascadeCacheBuilder.setGlobalCacheManager(manager);
        
        return manager;
    }
}

@Service
public class ProductService {
    
    @Autowired
    private ProductRepository productRepository;
    
    // 创建自动管理的缓存
    private final Cache<String, Product> productCache = 
        CascadeCacheBuilder.managedLoadingCache("products", 
            productId -> productRepository.findById(productId).orElse(null));
    
    private final Cache<String, List<Product>> categoryCache = 
        CascadeCacheBuilder.managedProtectedCache("categories");
    
    public Product getProduct(String productId) {
        return productCache.get(productId);
    }
    
    public List<Product> getProductsByCategory(String categoryId) {
        return categoryCache.get(categoryId, 
            catId -> productRepository.findByCategoryId(catId));
    }
}
```

### 3. 运维和监控

```java
@RestController
public class CacheAdminController {
    
    @Autowired
    private CacheManager cacheManager;
    
    @GetMapping("/admin/caches")
    public Map<String, Object> getCacheInfo() {
        Map<String, Object> info = new HashMap<>();
        info.put("cacheNames", cacheManager.getCacheNames());
        info.put("cacheCount", cacheManager.getCacheCount());
        info.put("managerHealth", cacheManager.health());
        info.put("allCacheHealth", cacheManager.getAllCacheHealth());
        return info;
    }
    
    @PostMapping("/admin/caches/{name}/refresh")
    public String refreshCache(@PathVariable String name) {
        boolean success = cacheManager.refreshCache(name);
        return success ? "Cache refreshed successfully" : "Cache not found";
    }
    
    @PostMapping("/admin/caches/refresh-all")
    public String refreshAllCaches() {
        cacheManager.refreshAll();
        return "All caches refreshed";
    }
    
    @DeleteMapping("/admin/caches/{name}")
    public String removeCache(@PathVariable String name) {
        boolean removed = cacheManager.removeCache(name);
        return removed ? "Cache removed successfully" : "Cache not found";
    }
    
    @GetMapping("/admin/caches/{name}/health")
    public HealthStatus getCacheHealth(@PathVariable String name) {
        HealthStatus health = cacheManager.getCacheHealth(name);
        return health != null ? health : HealthStatus.unknown();
    }
}
```

### 4. 自定义缓存工厂

```java
@Configuration
public class CustomCacheConfig {
    
    @Bean
    public CacheManager cacheManager() {
        FactoryCascadeCacheManager manager = new FactoryCascadeCacheManager();
        
        // 设置自定义缓存工厂
        manager.setCacheFactory(cacheName -> {
            if (cacheName.startsWith("user-")) {
                // 用户相关缓存使用特殊配置
                return CascadeCacheBuilder.newBuilder(cacheName)
                    .maximumSize(50000)
                    .expireAfterWrite(Duration.ofHours(2))
                    .enableProtection(true)
                    .build();
            } else if (cacheName.startsWith("product-")) {
                // 产品相关缓存使用另一种配置
                return CascadeCacheBuilder.newBuilder(cacheName)
                    .maximumSize(20000)
                    .expireAfterWrite(Duration.ofMinutes(30))
                    .enableL2Cache(true, redissonClient)
                    .build();
            } else {
                // 默认配置
                return CascadeCacheBuilder.newBuilder(cacheName).build();
            }
        });
        
        return manager;
    }
}
```

## ⚡ 便捷方法

### CascadeCacheBuilder 新增的管理方法

```java
// 创建并自动注册到全局管理器的缓存
Cache<String, User> userCache = CascadeCacheBuilder.managedCache("users");

Cache<String, User> loadingCache = CascadeCacheBuilder.managedLoadingCache("users", 
    userId -> userService.loadUser(userId));

Cache<String, Product> multiLevelCache = CascadeCacheBuilder.managedMultiLevelCache("products");

Cache<String, Order> protectedCache = CascadeCacheBuilder.managedProtectedCache("orders");

Cache<String, Customer> protectedLoadingCache = CascadeCacheBuilder.managedProtectedLoadingCache("customers",
    customerId -> customerService.loadCustomer(customerId));
```

### 手动控制自动注册

```java
// 明确启用自动注册
Cache<String, Data> cache1 = CascadeCacheBuilder
    .newBuilder("data1")
    .loader(dataLoader)
    .autoRegister(true)  // 启用自动注册
    .build();

// 明确禁用自动注册
Cache<String, Data> cache2 = CascadeCacheBuilder
    .newBuilder("data2")
    .loader(dataLoader)
    .autoRegister(false) // 禁用自动注册
    .build();
```

## 🔧 配置示例

### application.yml 配置

```yaml
cascade:
  cache:
    enabled: true
    defaults:
      l1:
        enabled: true
        maximum-size: 10000
        expire-after-write: 1h
        record-stats: true
      l2:
        enabled: true
        key-prefix: "app:"
        default-ttl: 2h
      sync:
        enabled: true
      protection:
        enabled: true
        enable-bloom-filter: true
        bloom-filter-expected-elements: 100000
        bloom-filter-false-positive-rate: 0.01
        enable-random-ttl: true
        random-ttl-jitter-ratio: 0.1
    caches:
      users:
        l1:
          maximum-size: 50000
          expire-after-write: 2h
        l2:
          key-prefix: "users:"
          default-ttl: 4h
      products:
        l1:
          maximum-size: 20000
          expire-after-write: 30m
```

## 📊 监控集成

### 健康检查

```java
@Component
public class CacheHealthIndicator implements HealthIndicator {
    
    @Autowired
    private CacheManager cacheManager;
    
    @Override
    public Health health() {
        HealthStatus managerHealth = cacheManager.health();
        Map<String, HealthStatus> allCacheHealth = cacheManager.getAllCacheHealth();
        
        Health.Builder builder = new Health.Builder();
        
        if (managerHealth.getStatus() == HealthStatus.Status.UP) {
            builder.up();
        } else {
            builder.down();
        }
        
        builder.withDetail("manager", managerHealth.getDetails())
               .withDetail("caches", allCacheHealth);
        
        return builder.build();
    }
}
```

### 指标导出

```java
@Component
public class CacheMetricsExporter {
    
    @Autowired
    private CacheManager cacheManager;
    
    @Autowired
    private MeterRegistry meterRegistry;
    
    @EventListener
    public void onApplicationReady(ApplicationReadyEvent event) {
        // 导出缓存数量指标
        Gauge.builder("cache.manager.total")
            .description("Total number of caches")
            .register(meterRegistry, cacheManager, CacheManager::getCacheCount);
        
        // 导出各个缓存的健康状态
        for (String cacheName : cacheManager.getCacheNames()) {
            Gauge.builder("cache.health")
                .tag("cache", cacheName)
                .description("Cache health status")
                .register(meterRegistry, cacheName, name -> {
                    HealthStatus health = cacheManager.getCacheHealth(name);
                    return health != null && health.getStatus() == HealthStatus.Status.UP ? 1 : 0;
                });
        }
    }
}
```

## 🎯 最佳实践

1. **统一管理**: 使用全局 `CacheManager` 统一管理所有缓存
2. **编程式创建**: 使用 `manager.createCache()` 或 `CascadeCacheBuilder.managedXxx()` 方法创建缓存
3. **初始化时机**: 在 `@PostConstruct` 中创建缓存，确保在使用前就已创建
4. **安全检查**: 始终检查 `getCache()` 的返回值是否为null，提供降级处理
5. **命名规范**: 使用有意义的缓存名称，如 "users"、"products"、"user-sessions"
6. **配置分离**: 使用 `setNamedConfig()` 为不同的缓存设置专门的配置
7. **监控集成**: 实现健康检查和指标导出，便于运维监控
8. **优雅关闭**: 在应用关闭时调用 `cacheManager.close()` 确保资源清理

## 🚨 注意事项

1. **新的创建模式**: `getCache()` 不再自动创建缓存，必须显式调用 `createCache()`
2. **空检查**: 始终检查 `getCache()` 返回值是否为null，防止NullPointerException
3. **名称冲突**: 避免创建同名缓存，使用 `registerCache()` 会在名称冲突时返回 `false`
4. **资源管理**: 及时调用 `close()` 方法释放资源，特别是在测试环境
5. **线程安全**: 所有 `CacheManager` 实现都是线程安全的
6. **异常处理**: 创建缓存失败时会返回null，需要做好降级处理
7. **配置兼容**: 确保 `CacheConfig` 与底层 `CascadeCacheBuilder` 的配置映射正确

这个缓存管理体系提供了企业级的缓存管理能力，通过编程式和注解方式创建缓存，确保了缓存创建的可控性和灵活性。

## 🔄 重要变更

**缓存创建方式变更**：

- **旧方式**：`cacheManager.getCache(name)` 会自动创建缓存
- **新方式**：`cacheManager.getCache(name)` 只获取已存在的缓存，不存在返回null
- **创建缓存**：必须使用`manager.createCache()`或`CascadeCacheBuilder.managedXxx()`