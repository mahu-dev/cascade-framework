# Cascade Cache 简化使用指南

Cascade Cache 提供了多种简化的创建方式，让您可以用最少的代码创建各种类型的缓存。

## 快速开始

### 1. 最简单的缓存
只需要缓存名称，框架会自动检测Spring容器中的RedissonClient并启用多级缓存：
```java
Cache<String, String> cache = CascadeCacheBuilder.simpleCache("my_cache");
```
> 如果Spring容器中有RedissonClient，会自动启用L1+L2多级缓存；否则只创建L1本地缓存

### 2. 指定大小的缓存
```java
Cache<String, String> cache = CascadeCacheBuilder.simpleCache("my_cache", 1000);
```

### 3. 指定大小和过期时间的缓存
```java
Cache<String, String> cache = CascadeCacheBuilder.simpleCache("my_cache", 1000, Duration.ofMinutes(30));
```

### 4. 明确要求多级缓存
```java
Cache<String, String> cache = CascadeCacheBuilder.multiLevelCache("my_cache");
```
> 如果Spring容器中没有RedissonClient会抛出异常

### 5. 带防护机制的多级缓存
```java
Cache<String, String> cache = CascadeCacheBuilder.protectedMultiLevelCache("my_cache");
```
> 自动启用布隆过滤器、分布式锁、随机TTL等防护机制

### 6. 手动指定RedissonClient（可选）
```java
Cache<String, String> cache = CascadeCacheBuilder.multiLevelCache("my_cache", redissonClient);
Cache<String, String> cache = CascadeCacheBuilder.protectedMultiLevelCache("my_cache", redissonClient);
```

## 链式调用的便捷方法

### 一键配置模式（自动获取RedissonClient）
```java
Cache<String, String> cache = CascadeCacheBuilder
    .<String, String>newBuilder("my_cache")
    .withRedis()                  // 自动获取Spring中的RedissonClient
    .withProtection()             // 启用所有防护机制（布隆过滤器、分布式锁、随机TTL）
    .withSync()                   // 启用缓存同步
    .withSize(10000, Duration.ofHours(1))  // 设置大小和过期时间
    .build();
```

### 手动指定RedissonClient（可选）
```java
Cache<String, String> cache = CascadeCacheBuilder
    .<String, String>newBuilder("my_cache")
    .withRedis(redissonClient)    // 手动指定RedissonClient
    .withProtection()
    .withSync()
    .build();
```

### 传统方式 vs 简化方式对比

**传统方式（13行代码）：**
```java
Cache<String, String> cache = CascadeCacheBuilder
    .<String, String>newBuilder("my_cache")
    .enableL1Cache(true)
    .l1MaximumSize(10000L)
    .l1ExpireAfterWrite(Duration.ofMinutes(30))
    .enableL2Cache(true, redissonClient)
    .l2KeyPrefix("app:")
    .l2DefaultTtl(Duration.ofHours(2))
    .enableProtection(true)
    .bloomFilter(100000, 0.01)
    .randomTtl(true, 0.1)
    .distributedLock(true, Duration.ofSeconds(30), "lock:")
    .enableSync(true)
    .build();
```

**简化方式（4行代码）：**
```java
Cache<String, String> cache = CascadeCacheBuilder
    .<String, String>newBuilder("my_cache")
    .withRedis()                // 自动获取RedissonClient
    .withProtection()
    .withSync()
    .withSize(10000, Duration.ofMinutes(30))
    .build();
```

**最简方式（1行代码）：**
```java
Cache<String, String> cache = CascadeCacheBuilder.protectedMultiLevelCache("my_cache");
```

## 自动配置机制

Cascade Cache具有智能的自动配置机制：

### Spring集成
- **自动检测**: 框架会自动检测Spring容器中的RedissonClient
- **智能选择**: 如果有RedissonClient，自动启用L1+L2多级缓存；否则只创建L1本地缓存
- **无缝集成**: 无需手动传递RedissonClient参数

### 使用前提
```yaml
# application.yml - 启用Redisson自动配置
spring:
  redis:
    host: localhost
    port: 6379
    
# 或者手动配置Redisson Bean
```

## 默认配置

当您使用简化方法时，框架会自动应用以下默认配置：

### L1缓存（本地缓存）
- **启用状态**: 默认启用
- **最大大小**: 10,000条记录
- **过期策略**: 根据使用情况决定
- **统计**: 关闭（提升性能）

### L2缓存（Redis缓存）
- **启用条件**: 提供RedissonClient时自动启用
- **Key前缀**: `cascade:`
- **默认TTL**: 1小时
- **刷新策略**: 智能刷新

### 防护机制
- **布隆过滤器**: 自动创建分布式布隆过滤器
  - 预期元素数量: 100,000
  - 假阳性率: 1%
- **分布式锁**: Redisson分布式锁
  - 锁超时: 30秒
  - 等待超时: 10秒
- **随机TTL**: 防雪崩
  - 抖动比例: 10%

### 同步机制
- **同步Topic**: `cascade:cache:sync`
- **同步策略**: 实时同步

## LoadingCache - 自动加载缓存

Cascade Cache提供了多种优雅的方式来创建LoadingCache，支持数据的自动加载。

### 最简单的LoadingCache
```java
// 只需要缓存名称和加载器，自动启用多级缓存
Cache<String, User> cache = CascadeCacheBuilder.loadingCache("user_cache", 
    userId -> userService.getUserById(userId)
);

// 使用 - 如果缓存中没有数据会自动加载
User user = cache.get("user123");  // 自动调用loader
```

### 带大小限制的LoadingCache
```java
Cache<String, User> cache = CascadeCacheBuilder.loadingCache("user_cache", 
    userId -> userService.getUserById(userId), 
    10000  // 最大10000条记录
);
```

### 带大小和过期时间的LoadingCache
```java
Cache<String, User> cache = CascadeCacheBuilder.loadingCache("user_cache", 
    userId -> userService.getUserById(userId), 
    10000, 
    Duration.ofHours(1)  // 1小时过期
);
```

### 强制多级缓存的LoadingCache
```java
// 如果Spring中没有RedissonClient会抛出异常
Cache<String, User> cache = CascadeCacheBuilder.loadingMultiLevelCache("user_cache", 
    userId -> userService.getUserById(userId)
);
```

### 带防护机制的LoadingCache
```java
// 自动启用布隆过滤器、分布式锁、随机TTL等防护机制
Cache<String, User> cache = CascadeCacheBuilder.protectedLoadingCache("user_cache", 
    userId -> userService.getUserById(userId)
);
```

### 带自动刷新的LoadingCache
```java
// 数据写入5分钟后自动异步刷新
Cache<String, User> cache = CascadeCacheBuilder.refreshingLoadingCache("user_cache", 
    userId -> userService.getUserById(userId),
    Duration.ofMinutes(5)  // 5分钟后自动刷新
);
```

### 链式调用创建LoadingCache
```java
Cache<String, User> cache = CascadeCacheBuilder
    .<String, User>newBuilder("user_cache")
    .withLoader(userId -> userService.getUserById(userId))  // 配置加载器
    .withRedis()        // 启用Redis
    .withProtection()   // 启用防护
    .withSync()         // 启用同步
    .withSize(10000, Duration.ofMinutes(30))
    .build();
```

## 基本使用

### 普通缓存操作
```java
// 写入
cache.put("key1", "value1");

// 读取
String value = cache.get("key1");

// 条件写入
boolean success = cache.putIfAbsent("key2", "value2");

// 批量操作
Map<String, String> data = Map.of("key3", "value3", "key4", "value4");
cache.putAll(data);

// 删除
cache.evict("key1");

// 清空
cache.clear();
```

### LoadingCache特有操作
```java
// 自动加载 - 如果缓存中没有会自动调用loader
User user = cache.get("user123");

// 手动刷新 - 重新调用loader加载最新数据
cache.refresh("user123");

// 异步刷新
CompletableFuture<Void> future = cache.refreshAsync("user123");

// 批量获取 - 缓存中没有的会批量加载
Set<String> userIds = Set.of("user1", "user2", "user3");
Map<String, User> users = cache.getAll(userIds);
```

## LoadingCache高级功能

### 复杂的CacheLoader示例

#### 1. 带异常处理的Loader
```java
Cache<String, User> cache = CascadeCacheBuilder.loadingCache("user_cache", 
    userId -> {
        try {
            User user = userService.getUserById(userId);
            if (user == null) {
                // 可以抛出异常或返回默认值
                throw new UserNotFoundException("User not found: " + userId);
            }
            return user;
        } catch (Exception e) {
            log.error("Failed to load user: {}", userId, e);
            // 返回默认用户避免频繁加载失败
            return User.getDefaultUser(userId);
        }
    }
);
```

#### 2. 带重试机制的Loader
```java
Cache<String, Product> cache = CascadeCacheBuilder.protectedLoadingCache("product_cache", 
    productId -> {
        int maxRetries = 3;
        Exception lastException = null;
        
        for (int i = 0; i < maxRetries; i++) {
            try {
                return productService.getProductById(productId);
            } catch (Exception e) {
                lastException = e;
                if (i < maxRetries - 1) {
                    Thread.sleep(100 * (i + 1)); // 递增延迟
                }
            }
        }
        throw new RuntimeException("Failed to load product after " + maxRetries + " retries", lastException);
    }
);
```

#### 3. 支持批量加载的Loader
```java
// 创建支持批量加载的CacheLoader
CacheLoader<String, User> batchLoader = new CacheLoader<String, User>() {
    @Override
    public User load(String userId) {
        // 单个加载
        return userService.getUserById(userId);
    }
    
    @Override
    public Map<String, User> loadAll(Set<String> userIds) {
        // 批量加载，提高性能
        List<User> users = userService.getUsersByIds(userIds);
        return users.stream().collect(Collectors.toMap(User::getId, Function.identity()));
    }
};

Cache<String, User> cache = CascadeCacheBuilder.loadingCache("user_cache", batchLoader);

// 使用批量获取
Set<String> userIds = Set.of("user1", "user2", "user3");
Map<String, User> users = cache.getAll(userIds); // 会调用loadAll方法
```

### Spring集成示例

#### 1. Service层集成
```java
@Service
public class UserCacheService {
    
    @Autowired
    private UserRepository userRepository;
    
    private Cache<Long, User> userCache;
    
    @PostConstruct
    public void initCache() {
        userCache = CascadeCacheBuilder.protectedLoadingCache("user_cache", 
            this::loadUserFromDatabase
        );
    }
    
    private User loadUserFromDatabase(Long userId) {
        return userRepository.findById(userId)
            .orElseThrow(() -> new UserNotFoundException("User not found: " + userId));
    }
    
    public User getUser(Long userId) {
        return userCache.get(userId); // 自动加载
    }
    
    public void evictUser(Long userId) {
        userCache.evict(userId);
    }
    
    public void refreshUser(Long userId) {
        userCache.refresh(userId);
    }
}
```

#### 2. Controller层使用
```java
@RestController
@RequestMapping("/api/users")
public class UserController {
    
    @Autowired
    private UserCacheService userCacheService;
    
    @GetMapping("/{userId}")
    public ResponseEntity<User> getUser(@PathVariable Long userId) {
        try {
            User user = userCacheService.getUser(userId); // 自动缓存
            return ResponseEntity.ok(user);
        } catch (UserNotFoundException e) {
            return ResponseEntity.notFound().build();
        }
    }
    
    @DeleteMapping("/{userId}/cache")
    public ResponseEntity<Void> evictUserCache(@PathVariable Long userId) {
        userCacheService.evictUser(userId);
        return ResponseEntity.ok().build();
    }
    
    @PostMapping("/{userId}/refresh")
    public ResponseEntity<Void> refreshUserCache(@PathVariable Long userId) {
        userCacheService.refreshUser(userId);
        return ResponseEntity.ok().build();
    }
}
```

### 实际业务场景示例

#### 1. 用户权限缓存
```java
@Component
public class UserPermissionCache {
    
    @Autowired
    private PermissionService permissionService;
    
    private final Cache<String, Set<String>> permissionCache;
    
    public UserPermissionCache() {
        this.permissionCache = CascadeCacheBuilder.refreshingLoadingCache(
            "user_permissions",
            this::loadUserPermissions,
            Duration.ofMinutes(10) // 10分钟自动刷新
        );
    }
    
    private Set<String> loadUserPermissions(String userId) {
        return permissionService.getUserPermissions(userId);
    }
    
    public boolean hasPermission(String userId, String permission) {
        Set<String> permissions = permissionCache.get(userId);
        return permissions.contains(permission);
    }
    
    public void refreshUserPermissions(String userId) {
        permissionCache.refresh(userId);
    }
}
```

#### 2. 商品信息缓存
```java
@Component
public class ProductInfoCache {
    
    @Autowired
    private ProductService productService;
    
    private final Cache<String, ProductInfo> productCache;
    
    public ProductInfoCache() {
        this.productCache = CascadeCacheBuilder.loadingCache(
            "product_info",
            this::loadProductWithFallback,
            50000, // 缓存5万个商品
            Duration.ofHours(2) // 2小时过期
        );
    }
    
    private ProductInfo loadProductWithFallback(String productId) {
        try {
            ProductInfo product = productService.getProductInfo(productId);
            if (product == null) {
                // 返回空对象避免缓存穿透
                return ProductInfo.empty(productId);
            }
            return product;
        } catch (Exception e) {
            log.error("Failed to load product: {}", productId, e);
            // 返回降级数据
            return ProductInfo.degraded(productId);
        }
    }
    
    public ProductInfo getProduct(String productId) {
        return productCache.get(productId);
    }
    
    public List<ProductInfo> getProducts(List<String> productIds) {
        Set<String> keys = new HashSet<>(productIds);
        Map<String, ProductInfo> products = productCache.getAll(keys);
        return productIds.stream()
            .map(products::get)
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
    }
}
```

#### 3. 配置信息缓存
```java
@Component
public class ConfigCache {
    
    @Autowired
    private ConfigService configService;
    
    private final Cache<String, String> configCache;
    
    public ConfigCache() {
        this.configCache = CascadeCacheBuilder.refreshingLoadingCache(
            "system_config",
            this::loadConfig,
            Duration.ofMinutes(5) // 配置5分钟自动刷新
        );
    }
    
    private String loadConfig(String configKey) {
        String value = configService.getConfigValue(configKey);
        return value != null ? value : ""; // 避免null值
    }
    
    public String getConfig(String key) {
        return configCache.get(key);
    }
    
    public String getConfig(String key, String defaultValue) {
        String value = configCache.get(key);
        return value.isEmpty() ? defaultValue : value;
    }
    
    public boolean getBooleanConfig(String key, boolean defaultValue) {
        String value = getConfig(key);
        if (value.isEmpty()) return defaultValue;
        return Boolean.parseBoolean(value);
    }
    
    public int getIntConfig(String key, int defaultValue) {
        String value = getConfig(key);
        if (value.isEmpty()) return defaultValue;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
```

### 监控和调试

#### 1. 缓存统计
```java
Cache<String, User> cache = CascadeCacheBuilder.loadingCache("user_cache", 
    userId -> userService.getUserById(userId)
);

// 获取缓存统计信息
CacheStats stats = cache.getStats();
System.out.println("命中率: " + stats.hitRate());
System.out.println("缓存大小: " + cache.size());
System.out.println("加载次数: " + stats.loadCount());
System.out.println("平均加载时间: " + stats.averageLoadPenalty() + "ns");
```

#### 2. 缓存事件监听
```java
Cache<String, User> cache = CascadeCacheBuilder
    .<String, User>newBuilder("user_cache")
    .withLoader(userId -> userService.getUserById(userId))
    .withRedis()
    .recordStats(true) // 启用统计
    .build();

// 定期输出统计信息
@Scheduled(fixedRate = 60000) // 每分钟
public void printCacheStats() {
    CacheStats stats = cache.getStats();
    log.info("Cache stats - Hit rate: {}, Load count: {}, Size: {}", 
        stats.hitRate(), stats.loadCount(), cache.size());
}
```

## 高级功能

### 自定义加载器
```java
Cache<String, String> cache = CascadeCacheBuilder
    .<String, String>newBuilder("my_cache")
    .loader(key -> loadFromDatabase(key))  // 缓存未命中时自动加载
    .build();

// 自动加载
String value = cache.get("key1");  // 如果不存在会自动调用loader
```

### 自定义布隆过滤器
```java
// 创建Redisson布隆过滤器
RedissonBloomFilterProtection bloomFilter = RedissonBloomFilterProtection
    .builder(redissonClient)
    .filterName("my_bloom_filter")
    .expectedElements(1000000)
    .falsePositiveRate(0.001)
    .build();

Cache<String, String> cache = CascadeCacheBuilder
    .<String, String>newBuilder("my_cache")
    .customBloomFilter(bloomFilter)
    .build();
```

## LoadingCache最佳实践

### 1. 选择合适的LoadingCache类型

```java
// 简单业务场景 - 自动检测多级缓存
Cache<String, User> cache = CascadeCacheBuilder.loadingCache("user_cache", 
    userId -> userService.getUserById(userId)
);

// 高并发场景 - 强制多级缓存 + 防护
Cache<String, Product> cache = CascadeCacheBuilder.protectedLoadingCache("product_cache", 
    productId -> productService.getProductById(productId)
);

// 需要定时刷新的场景
Cache<String, Config> cache = CascadeCacheBuilder.refreshingLoadingCache("config_cache", 
    key -> configService.getConfig(key),
    Duration.ofMinutes(5)
);
```

### 2. 异常处理策略

```java
// 推荐：返回默认值，避免缓存雪崩
Cache<String, User> cache = CascadeCacheBuilder.loadingCache("user_cache", 
    userId -> {
        try {
            return userService.getUserById(userId);
        } catch (Exception e) {
            log.error("Failed to load user: {}", userId, e);
            return User.getDefaultUser(); // 返回默认值
        }
    }
);

// 或者：抛出业务异常，由上层处理
Cache<String, User> cache = CascadeCacheBuilder.loadingCache("user_cache", 
    userId -> {
        User user = userService.getUserById(userId);
        if (user == null) {
            throw new UserNotFoundException("User not found: " + userId);
        }
        return user;
    }
);
```

### 3. 性能优化技巧

#### 批量加载优化
```java
// 实现CacheLoader接口以支持批量加载
CacheLoader<String, User> batchLoader = new CacheLoader<String, User>() {
    @Override
    public User load(String userId) {
        return userService.getUserById(userId);
    }
    
    @Override
    public Map<String, User> loadAll(Set<String> userIds) {
        // 一次数据库查询获取多个用户，提高性能
        return userService.getUsersByIds(userIds)
            .stream()
            .collect(Collectors.toMap(User::getId, Function.identity()));
    }
};

Cache<String, User> cache = CascadeCacheBuilder.loadingCache("user_cache", batchLoader);
```

#### 异步刷新
```java
// 使用refreshAfterWrite避免同步刷新阻塞
Cache<String, User> cache = CascadeCacheBuilder
    .<String, User>newBuilder("user_cache")
    .withLoader(userId -> userService.getUserById(userId))
    .refreshAfterWrite(Duration.ofMinutes(5)) // 5分钟后异步刷新
    .build();
```

#### 预热缓存
```java
@Component
public class CacheWarmupService {
    
    @Autowired
    private Cache<String, User> userCache;
    
    @EventListener
    public void warmupCache(ApplicationReadyEvent event) {
        // 应用启动后预热热点数据
        List<String> hotUserIds = getHotUserIds();
        Map<String, User> hotUsers = userCache.getAll(new HashSet<>(hotUserIds));
        log.info("Warmed up {} user cache entries", hotUsers.size());
    }
}
```

### 4. 监控和调试

#### 启用详细统计
```java
Cache<String, User> cache = CascadeCacheBuilder
    .<String, User>newBuilder("user_cache")
    .withLoader(userId -> userService.getUserById(userId))
    .recordStats(true) // 启用统计
    .build();

// 定期监控
@Scheduled(fixedRate = 300000) // 每5分钟
public void monitorCache() {
    CacheStats stats = cache.getStats();
    
    // 记录关键指标
    log.info("Cache[{}] - Hit rate: {:.2f}%, Load count: {}, Avg load time: {}ms", 
        cache.getName(),
        stats.hitRate() * 100,
        stats.loadCount(),
        stats.averageLoadPenalty() / 1_000_000);
    
    // 告警低命中率
    if (stats.hitRate() < 0.8) {
        log.warn("Low cache hit rate detected: {:.2f}%", stats.hitRate() * 100);
    }
}
```

#### 缓存调试工具
```java
@RestController
@RequestMapping("/admin/cache")
public class CacheDebugController {
    
    @Autowired
    private Cache<String, User> userCache;
    
    @GetMapping("/stats")
    public CacheStats getCacheStats() {
        return userCache.getStats();
    }
    
    @GetMapping("/size")
    public long getCacheSize() {
        return userCache.size();
    }
    
    @PostMapping("/evict/{key}")
    public void evictKey(@PathVariable String key) {
        userCache.evict(key);
    }
    
    @PostMapping("/refresh/{key}")
    public void refreshKey(@PathVariable String key) {
        userCache.refresh(key);
    }
    
    @PostMapping("/clear")
    public void clearCache() {
        userCache.clear();
    }
}
```

### 5. 避免常见陷阱

#### 避免缓存雪崩
```java
// 错误：所有缓存同时过期
Cache<String, User> badCache = CascadeCacheBuilder.loadingCache("user_cache", 
    userId -> userService.getUserById(userId),
    10000,
    Duration.ofHours(1) // 固定1小时过期
);

// 正确：使用随机TTL避免同时过期
Cache<String, User> goodCache = CascadeCacheBuilder
    .<String, User>newBuilder("user_cache")
    .withLoader(userId -> userService.getUserById(userId))
    .withProtection() // 自动启用随机TTL
    .withSize(10000, Duration.ofHours(1))
    .build();
```

#### 避免缓存穿透
```java
// 使用防护机制
Cache<String, User> cache = CascadeCacheBuilder.protectedLoadingCache("user_cache", 
    userId -> {
        User user = userService.getUserById(userId);
        if (user == null) {
            // 缓存空对象避免穿透
            return User.empty(userId);
        }
        return user;
    }
);
```

#### 避免内存泄漏
```java
// 设置合适的最大大小
Cache<String, User> cache = CascadeCacheBuilder.loadingCache("user_cache", 
    userId -> userService.getUserById(userId),
    10000 // 限制最大条目数
);

// 或设置基于权重的大小限制
Cache<String, LargeObject> cache = CascadeCacheBuilder
    .<String, LargeObject>newBuilder("large_object_cache")
    .withLoader(key -> loadLargeObject(key))
    .maximumWeight(100_000_000) // 100MB
    .weigher((key, value) -> value.getSize())
    .build();
```

## 最佳实践

### 普通缓存选择

1. **选择合适的缓存类型**：
   - 小数据量、低并发：使用 `simpleCache()`
   - 大数据量、高并发：使用 `multiLevelCache()`
   - 关键业务：使用 `protectedMultiLevelCache()`
   - 需要自动加载：使用 `loadingCache()` 系列方法

2. **合理设置缓存大小**：
   - 根据内存大小和数据特点设置L1缓存大小
   - L2缓存大小由Redis内存决定

3. **选择合适的过期策略**：
   - 热点数据：较长的过期时间
   - 冷数据：较短的过期时间
   - 实时性要求高的数据：较短的过期时间

4. **监控缓存性能**：
   ```java
   CacheStats stats = cache.getStats();
   System.out.println("命中率: " + stats.hitRate());
   System.out.println("缓存大小: " + cache.size());
   ```