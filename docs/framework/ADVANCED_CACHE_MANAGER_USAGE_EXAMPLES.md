# AdvancedCacheManager 使用示例大全

## 📋 概述

本文档提供了 `AdvancedCacheManager` 的完整使用示例，包括配置方法、代码示例、最佳实践等。

## 🔧 基础配置

### 1. 启用 AdvancedCacheManager

```yaml
# application.yml
cascade:
  cache:
    enabled: true
    manager:
      type: advanced  # 🎯 启用智能管理器
    defaults:
      l1:
        enabled: true
        maximumSize: 10000
        expireAfterWrite: PT1H  # 1小时
      l2:
        enabled: true
        keyPrefix: "myapp:"
        defaultTtl: PT2H  # 2小时
```

### 2. Redis 配置（可选）

```yaml
# Redis配置 - 如果需要分布式缓存
spring:
  redis:
    host: localhost
    port: 6379
    password: your-password
    timeout: 2000ms
    lettuce:
      pool:
        max-active: 8
        max-idle: 8
        min-idle: 0
```

## 💡 基础使用示例

### 1. 简单缓存创建

```java
@Service
public class UserService {
    
    @Autowired
    private AdvancedCacheManager cacheManager;
    
    @PostConstruct
    public void init() {
        // 🎯 最简单的缓存创建 - 智能自动配置
        Cache<String, User> userCache = cacheManager.<String, User>cacheBuilder("users")
                .build();
    }
    
    public User getUser(String userId) {
        Cache<String, User> cache = cacheManager.getCache("users");
        return cache.get(userId, this::loadUserFromDatabase);
    }
    
    private User loadUserFromDatabase(String userId) {
        // 模拟数据库查询
        return new User(userId, "User-" + userId);
    }
}
```

### 2. 高级配置缓存

```java
@Service
public class ProductService {
    
    @Autowired
    private AdvancedCacheManager cacheManager;
    
    @PostConstruct
    public void initCaches() {
        // 🚀 高级配置：多级缓存 + 防护机制 + 同步
        Cache<String, Product> productCache = cacheManager.<String, Product>cacheBuilder("products")
                .withRedis()                    // 启用Redis L2缓存
                .withProtection()               // 启用防护机制（防穿透、雪崩、热点）
                .withSync()                     // 启用缓存同步
                .maximumSize(5000)              // L1缓存大小
                .expireAfterWrite(Duration.ofMinutes(30))
                .build();
        
        // 🎯 VIP产品缓存 - 更长过期时间
        Cache<String, Product> vipCache = cacheManager.<String, Product>cacheBuilder("vip-products")
                .withRedis()
                .maximumSize(1000)
                .expireAfterWrite(Duration.ofHours(2))
                .l2DefaultTtl(Duration.ofHours(4))
                .build();
    }
}
```

## 🎯 智能策略示例

### 1. 自动环境适配

```java
@Component
public class CacheSetupExample {
    
    @Autowired
    private AdvancedCacheManager cacheManager;
    
    @PostConstruct
    public void setupIntelligentCaching() {
        // 🧠 智能策略：AUTO_DETECT（默认已设置）
        // 系统会自动检测：
        // - 有Redis → 多级缓存 + 同步
        // - 无Redis → 纯本地缓存
        
        logCurrentStrategy();
        setupApplicationCaches();
    }
    
    private void logCurrentStrategy() {
        // 检查当前使用的策略
        if (cacheManager.getRedissonClient() != null) {
            System.out.println("🌐 检测到Redis环境 - 使用多级缓存策略");
        } else {
            System.out.println("🏠 本地环境 - 使用纯本地缓存策略");
        }
    }
    
    private void setupApplicationCaches() {
        // 批量创建应用缓存
        cacheManager.createCaches(
            "users", "products", "orders", 
            "sessions", "configs", "permissions"
        );
    }
}
```

### 2. 手动策略控制

```java
@Configuration
public class CustomCacheStrategy {
    
    @Autowired
    private AdvancedCacheManager cacheManager;
    
    @PostConstruct
    public void configureStrategy() {
        // 🎛️ 根据不同场景手动设置策略
        
        if (isProductionEnvironment()) {
            // 生产环境：强制多级缓存
            cacheManager.setCreationStrategy(
                AdvancedCacheManager.CacheCreationStrategy.MULTI_LEVEL
            );
        } else if (isDevelopmentEnvironment()) {
            // 开发环境：仅本地缓存，避免Redis依赖
            cacheManager.setCreationStrategy(
                AdvancedCacheManager.CacheCreationStrategy.LOCAL_ONLY
            );
        } else {
            // 其他环境：智能检测
            cacheManager.setCreationStrategy(
                AdvancedCacheManager.CacheCreationStrategy.AUTO_DETECT
            );
        }
    }
    
    private boolean isProductionEnvironment() {
        return "prod".equals(System.getProperty("spring.profiles.active"));
    }
    
    private boolean isDevelopmentEnvironment() {
        return "dev".equals(System.getProperty("spring.profiles.active"));
    }
}
```

## 🔥 高级特性示例

### 1. 命名配置管理

```java
@Service
public class CacheConfigurationService {
    
    @Autowired
    private AdvancedCacheManager cacheManager;
    
    @PostConstruct
    public void setupNamedConfigs() {
        // 🎯 为不同类型缓存设置专门配置
        
        // 用户缓存配置：中等大小，快速过期
        CacheConfig userConfig = new CacheConfig();
        userConfig.setMaxSize(10000);
        userConfig.setExpireAfterWrite(Duration.ofMinutes(15));
        cacheManager.setNamedConfig("users", userConfig);
        
        // 产品缓存配置：大容量，长期缓存
        CacheConfig productConfig = new CacheConfig();
        productConfig.setMaxSize(50000);
        productConfig.setExpireAfterWrite(Duration.ofHours(2));
        cacheManager.setNamedConfig("products", productConfig);
        
        // 会话缓存配置：小容量，超长缓存
        CacheConfig sessionConfig = new CacheConfig();
        sessionConfig.setMaxSize(1000);
        sessionConfig.setExpireAfterWrite(Duration.ofHours(8));
        cacheManager.setNamedConfig("sessions", sessionConfig);
        
        // 批量创建缓存（会自动应用对应的命名配置）
        cacheManager.createCaches("users", "products", "sessions");
    }
}
```

### 2. 缓存预热

```java
@Service
public class CacheWarmupService {
    
    @Autowired
    private AdvancedCacheManager cacheManager;
    
    @Autowired
    private UserRepository userRepository;
    
    @PostConstruct
    public void warmupCaches() {
        // 🔥 系统启动时预热关键缓存
        warmupUserCache();
        warmupProductCache();
    }
    
    private void warmupUserCache() {
        Cache<String, User> userCache = cacheManager.<String, User>cacheBuilder("users")
                .withRedis()
                .maximumSize(10000)
                .build();
        
        // 预加载活跃用户
        List<User> activeUsers = userRepository.findActiveUsers();
        for (User user : activeUsers) {
            userCache.put(user.getId(), user);
        }
        
        System.out.println("✅ 用户缓存预热完成: " + activeUsers.size() + " 个用户");
    }
    
    private void warmupProductCache() {
        Cache<String, Product> productCache = cacheManager.<String, Product>cacheBuilder("products")
                .withRedis()
                .withProtection()  // 热门产品，启用防护
                .maximumSize(5000)
                .build();
        
        // 预加载热门产品
        List<Product> hotProducts = productRepository.findHotProducts();
        for (Product product : hotProducts) {
            productCache.put(product.getId(), product);
        }
        
        System.out.println("✅ 产品缓存预热完成: " + hotProducts.size() + " 个产品");
    }
}
```

### 3. 监控和管理

```java
@RestController
@RequestMapping("/admin/cache")
public class CacheAdminController {
    
    @Autowired
    private AdvancedCacheManager cacheManager;
    
    /**
     * 获取所有缓存状态
     */
    @GetMapping("/status")
    public Map<String, Object> getCacheStatus() {
        Map<String, Object> status = new HashMap<>();
        
        // 基本信息
        status.put("managerType", "AdvancedCacheManager");
        status.put("totalCaches", cacheManager.getCacheCount());
        status.put("cacheNames", cacheManager.getCacheNames());
        status.put("currentState", cacheManager.getState().name());
        
        // 健康状态
        status.put("managerHealth", cacheManager.health());
        status.put("cacheHealths", cacheManager.getAllCacheHealth());
        
        return status;
    }
    
    /**
     * 清空指定缓存
     */
    @PostMapping("/{cacheName}/clear")
    public Map<String, Object> clearCache(@PathVariable String cacheName) {
        boolean success = cacheManager.clear(cacheName);
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", success);
        result.put("message", success ? "缓存清空成功" : "缓存清空失败");
        result.put("cacheName", cacheName);
        
        return result;
    }
    
    /**
     * 刷新指定缓存
     */
    @PostMapping("/{cacheName}/refresh")
    public Map<String, Object> refreshCache(@PathVariable String cacheName) {
        boolean success = cacheManager.refreshCache(cacheName);
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", success);
        result.put("message", success ? "缓存刷新成功" : "缓存刷新失败");
        result.put("cacheName", cacheName);
        
        return result;
    }
    
    /**
     * 批量操作
     */
    @PostMapping("/batch/clear")
    public Map<String, Object> clearAllCaches() {
        cacheManager.clearAll();
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "所有缓存已清空");
        result.put("totalCaches", cacheManager.getCacheCount());
        
        return result;
    }
}
```

## 🛡️ 防护机制示例

### 1. 缓存穿透防护

```java
@Service
public class ProtectedCacheService {
    
    @Autowired
    private AdvancedCacheManager cacheManager;
    
    @PostConstruct
    public void setupProtectedCache() {
        // 🛡️ 启用完整防护机制
        Cache<String, Product> productCache = cacheManager.<String, Product>cacheBuilder("protected-products")
                .withRedis()
                .withProtection()  // 自动启用：布隆过滤器 + 随机TTL + 分布式锁
                .maximumSize(10000)
                .expireAfterWrite(Duration.ofMinutes(30))
                .build();
    }
    
    public Product getProtectedProduct(String productId) {
        Cache<String, Product> cache = cacheManager.getCache("protected-products");
        
        // 🎯 即使恶意请求大量不存在的ID，也不会击穿到数据库
        return cache.get(productId, this::loadProductFromDatabase);
    }
    
    private Product loadProductFromDatabase(String productId) {
        // 模拟数据库查询
        // 由于布隆过滤器的保护，不存在的数据很少会到达这里
        return productRepository.findById(productId);
    }
}
```

### 2. 自定义防护配置

```java
@Service
public class CustomProtectionService {
    
    @Autowired
    private AdvancedCacheManager cacheManager;
    
    @PostConstruct
    public void setupCustomProtection() {
        // 🎛️ 自定义防护参数
        Cache<String, User> userCache = cacheManager.<String, User>cacheBuilder("custom-protected-users")
                .withRedis()
                .enableProtection(true)
                .bloomFilter(100000, 0.01)  // 10万元素，1%误判率
                .randomTtl(true, Duration.ofMinutes(30), Duration.ofMinutes(10))  // 30±10分钟随机TTL
                .distributedLock(true, Duration.ofSeconds(30), "user-lock:")
                .build();
    }
}
```

## 📊 性能优化示例

### 1. 分层缓存策略

```java
@Service
public class LayeredCacheService {
    
    @Autowired
    private AdvancedCacheManager cacheManager;
    
    @PostConstruct
    public void setupLayeredCaches() {
        // 🎯 热点数据：L1 + L2 + 同步
        Cache<String, User> hotUserCache = cacheManager.<String, User>cacheBuilder("hot-users")
                .withRedis()
                .withSync()
                .l1MaximumSize(1000)     // L1: 1000个最热用户
                .l2DefaultTtl(Duration.ofHours(2))  // L2: 2小时
                .expireAfterWrite(Duration.ofMinutes(15))  // L1: 15分钟
                .build();
        
        // 🎯 普通数据：仅L2缓存
        Cache<String, User> normalUserCache = cacheManager.<String, User>cacheBuilder("normal-users")
                .enableL1Cache(false)    // 关闭L1
                .enableL2Cache(true, cacheManager.getRedissonClient())
                .l2DefaultTtl(Duration.ofHours(1))
                .build();
        
        // 🎯 临时数据：仅L1缓存
        Cache<String, String> tempCache = cacheManager.<String, String>cacheBuilder("temp-data")
                .enableL2Cache(false)    // 关闭L2
                .maximumSize(500)
                .expireAfterWrite(Duration.ofMinutes(5))
                .build();
    }
}
```

### 2. 异步刷新

```java
@Service
public class AsyncRefreshService {
    
    @Autowired
    private AdvancedCacheManager cacheManager;
    
    @Autowired
    private TaskExecutor taskExecutor;
    
    @PostConstruct
    public void setupAsyncCache() {
        // 🚀 异步刷新缓存
        Cache<String, Report> reportCache = cacheManager.<String, Report>cacheBuilder("async-reports")
                .withRedis()
                .executor(taskExecutor)  // 使用异步执行器
                .refreshAfterWrite(Duration.ofMinutes(10))  // 10分钟后异步刷新
                .expireAfterWrite(Duration.ofMinutes(30))   // 30分钟过期
                .build();
    }
    
    @Scheduled(fixedRate = 60000)  // 每分钟检查
    public void scheduleRefresh() {
        // 定期刷新重要缓存
        cacheManager.refreshCache("async-reports");
    }
}
```

## 🔍 故障排查示例

### 1. 健康检查

```java
@Component
public class CacheHealthMonitor {
    
    @Autowired
    private AdvancedCacheManager cacheManager;
    
    @Scheduled(fixedRate = 30000)  // 每30秒检查
    public void monitorCacheHealth() {
        HealthStatus managerHealth = cacheManager.health();
        
        if (managerHealth.getStatus() != HealthStatus.Status.UP) {
            System.err.println("⚠️ 缓存管理器异常: " + managerHealth);
            handleManagerIssue(managerHealth);
        }
        
        // 检查各个缓存健康状态
        Map<String, HealthStatus> cacheHealths = cacheManager.getAllCacheHealth();
        for (Map.Entry<String, HealthStatus> entry : cacheHealths.entrySet()) {
            if (entry.getValue().getStatus() != HealthStatus.Status.UP) {
                System.err.println("⚠️ 缓存异常 [" + entry.getKey() + "]: " + entry.getValue());
                handleCacheIssue(entry.getKey(), entry.getValue());
            }
        }
    }
    
    private void handleManagerIssue(HealthStatus health) {
        // 处理管理器异常
        System.out.println("🔧 尝试重启缓存管理器...");
        try {
            cacheManager.stop();
            cacheManager.start();
        } catch (Exception e) {
            System.err.println("❌ 缓存管理器重启失败: " + e.getMessage());
        }
    }
    
    private void handleCacheIssue(String cacheName, HealthStatus health) {
        // 处理单个缓存异常
        System.out.println("🔧 尝试刷新异常缓存: " + cacheName);
        boolean success = cacheManager.refreshCache(cacheName);
        if (!success) {
            System.err.println("❌ 缓存刷新失败: " + cacheName);
        }
    }
}
```

### 2. 性能监控

```java
@Component
public class CachePerformanceMonitor {
    
    @Autowired
    private AdvancedCacheManager cacheManager;
    
    private final Map<String, CacheStats> previousStats = new ConcurrentHashMap<>();
    
    @Scheduled(fixedRate = 60000)  // 每分钟统计
    public void logPerformanceStats() {
        for (String cacheName : cacheManager.getCacheNames()) {
            Cache<?, ?> cache = cacheManager.getCache(cacheName);
            
            // 这里需要实际的统计接口，暂时用模拟数据
            logCachePerformance(cacheName, cache);
        }
    }
    
    private void logCachePerformance(String cacheName, Cache<?, ?> cache) {
        // 模拟性能统计
        System.out.println("📊 缓存性能 [" + cacheName + "]:");
        System.out.println("   - 缓存大小: " + getCacheSize(cache));
        System.out.println("   - 命中率: " + getCacheHitRate(cache) + "%");
        System.out.println("   - 平均加载时间: " + getAverageLoadTime(cache) + "ms");
    }
    
    private int getCacheSize(Cache<?, ?> cache) {
        // 实际实现需要访问底层统计
        return 0;
    }
    
    private double getCacheHitRate(Cache<?, ?> cache) {
        // 实际实现需要访问底层统计
        return 95.5;
    }
    
    private double getAverageLoadTime(Cache<?, ?> cache) {
        // 实际实现需要访问底层统计
        return 25.0;
    }
}
```

## 🎉 总结

`AdvancedCacheManager` 提供了强大而灵活的缓存管理能力：

### 🌟 核心优势
- **🧠 智能化**: 自动环境检测和策略选择
- **🛡️ 安全性**: 内置防护机制
- **⚡ 高性能**: 多级缓存和异步刷新  
- **🔧 灵活性**: 丰富的配置选项
- **📊 可观测**: 完善的监控和管理

### 💡 最佳实践
1. **生产环境**: 使用 `AUTO_DETECT` 策略 + 完整防护
2. **开发环境**: 使用 `LOCAL_ONLY` 策略，简化部署
3. **性能调优**: 根据数据特征选择合适的缓存层级
4. **监控运维**: 定期检查健康状态和性能指标

通过这些示例，你可以充分发挥 `AdvancedCacheManager` 的强大功能！🚀