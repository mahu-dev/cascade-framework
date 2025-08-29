# 🎯 CacheLoader 配置方法

## 核心接口方法

CacheLoader 只需要实现一个核心方法：

```java
V load(K key) throws Exception;
```

## 配置方式一览

### 1. 匿名内部类方式

```java
CacheLoader<String, User> userLoader = new CacheLoader<String, User>() {
    @Override
    public User load(String userId) throws Exception {
        // 从数据库加载用户信息
        return userService.findById(userId);
    }
};

Cache<String, User> cache = CascadeCacheBuilder.<String, User>newBuilder("users")
    .loader(userLoader)  // ✅ 配置加载器
    .refreshAfterWrite(Duration.ofMinutes(30))
    .build();
```

### 2. Lambda 表达式方式

```java
// 简单加载逻辑
Cache<String, User> userCache = CascadeCacheBuilder.<String, User>newBuilder("users")
    .loader(userId -> userService.findById(userId))  // ✅ Lambda方式
    .refreshAfterWrite(Duration.ofMinutes(15))
    .build();

// 复杂加载逻辑
Cache<String, ProductInfo> productCache = CascadeCacheBuilder.<String, ProductInfo>newBuilder("products")
    .loader(productId -> {
        // 多步骤加载
        Product product = productService.findById(productId);
        if (product == null) {
            throw new ProductNotFoundException(productId);
        }

        // 补充库存信息
        int stock = inventoryService.getStock(productId);

        // 补充价格信息
        Price price = priceService.getPrice(productId);

        return new ProductInfo(product, stock, price);
    })
    .refreshAfterWrite(Duration.ofMinutes(10))
    .build();
```

### 3. 方法引用方式

```java
// 直接方法引用
Cache<Long, Order> orderCache = CascadeCacheBuilder.<Long, Order>newBuilder("orders")
    .loader(orderService::findById)  // ✅ 方法引用
    .refreshAfterWrite(Duration.ofMinutes(20))
    .build();

// 静态方法引用
Cache<String, Config> configCache = CascadeCacheBuilder.<String, Config>newBuilder("config")
    .loader(ConfigUtils::loadConfig)  // ✅ 静态方法引用
    .refreshAfterWrite(Duration.ofMinutes(5))
    .build();
```

### 4. 实现类方式

```java
// 创建专门的加载器类
public class UserCacheLoader implements CacheLoader<String, User> {

    private final UserService userService;
    private final UserProfileService profileService;

    public UserCacheLoader(UserService userService, UserProfileService profileService) {
        this.userService = userService;
        this.profileService = profileService;
    }

    @Override
    public User load(String userId) throws Exception {
        // 加载基本用户信息
        User user = userService.findById(userId);
        if (user == null) {
            throw new UserNotFoundException("User not found: " + userId);
        }

        // 补充用户详细信息
        UserProfile profile = profileService.getProfile(userId);
        user.setProfile(profile);

        return user;
    }

    @Override
    public Map<String, User> loadAll(Set<String> userIds) throws Exception {
        // 批量优化加载
        List<User> users = userService.findByIds(userIds);
        Map<String, UserProfile> profiles = profileService.getProfiles(userIds);

        Map<String, User> result = new HashMap<>();
        for (User user : users) {
            UserProfile profile = profiles.get(user.getId());
            if (profile != null) {
                user.setProfile(profile);
            }
            result.put(user.getId(), user);
        }

        return result;
    }

    @Override
    public boolean supportsBatchLoading() {
        return true;  // 支持批量加载
    }
}

// 使用实现类
UserCacheLoader userLoader = new UserCacheLoader(userService, profileService);
Cache<String, User> cache = CascadeCacheBuilder.<String, User>newBuilder("users")
    .loader(userLoader)  // ✅ 使用自定义加载器
    .refreshAfterWrite(Duration.ofMinutes(30))
    .build();
```

### 5. Spring Bean 方式

```java
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
    public String getName() {
        return "ProductCacheLoader";
    }

    @Override
    public long getLoadTimeoutMillis() {
        return 5000;  // 5秒超时
    }
}

// 配置缓存
@Service
public class CacheService {

    @Autowired
    private ProductCacheLoader productLoader;

    @Autowired
    private RedissonClient redissonClient;

    @PostConstruct
    public void initCache() {
        Cache<Long, Product> productCache = CascadeCacheBuilder.<Long, Product>newBuilder("products")
            .loader(productLoader)  // ✅ 注入Spring Bean
            .refreshAfterWrite(Duration.ofMinutes(15))
            .enableL2Cache(true, redissonClient)
            .build();
    }
}
```

### 6. 异步加载器

```java
// 异步数据库查询
Cache<String, UserData> asyncCache = CascadeCacheBuilder.<String, UserData>newBuilder("async-users")
    .loader(userId -> {
        // 使用异步数据库访问
        CompletableFuture<User> userFuture = userRepository.findByIdAsync(userId);
        CompletableFuture<UserProfile> profileFuture = profileRepository.findByUserIdAsync(userId);

        // 并行等待两个查询完成
        CompletableFuture<UserData> combined = userFuture.thenCombine(profileFuture,
            (user, profile) -> new UserData(user, profile));

        return combined.get(); // 同步等待结果
    })
    .refreshAfterWrite(Duration.ofMinutes(20))
    .build();
```

### 7. 带重试机制的加载器

```java
public class RetryableCacheLoader<K, V> implements CacheLoader<K, V> {

    private final CacheLoader<K, V> delegate;
    private final int maxRetries;
    private final Duration retryDelay;

    public RetryableCacheLoader(CacheLoader<K, V> delegate, int maxRetries, Duration retryDelay) {
        this.delegate = delegate;
        this.maxRetries = maxRetries;
        this.retryDelay = retryDelay;
    }

    @Override
    public V load(K key) throws Exception {
        Exception lastException = null;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                return delegate.load(key);
            } catch (Exception e) {
                lastException = e;

                if (attempt < maxRetries) {
                    try {
                        Thread.sleep(retryDelay.toMillis());
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(ie);
                    }
                }
            }
        }

        throw new RuntimeException("Failed to load after " + maxRetries + " attempts", lastException);
    }
}

// 使用带重试的加载器
CacheLoader<String, ApiResponse> baseLoader = apiId -> apiClient.call(apiId);
CacheLoader<String, ApiResponse> retryableLoader = new RetryableCacheLoader<>(
    baseLoader, 3, Duration.ofSeconds(1)
);

Cache<String, ApiResponse> apiCache = CascadeCacheBuilder.<String, ApiResponse>newBuilder("api")
    .loader(retryableLoader)  // ✅ 带重试机制
    .refreshAfterWrite(Duration.ofMinutes(5))
    .build();
```

### 8. 组合加载器

```java
// 多数据源组合加载
Cache<String, UserFullInfo> compositeCache = CascadeCacheBuilder.<String, UserFullInfo>newBuilder("user-full")
    .loader(userId -> {
        // 并行加载多个数据源
        CompletableFuture<User> userFuture = CompletableFuture.supplyAsync(
            () -> userService.findById(userId)
        );

        CompletableFuture<List<Order>> ordersFuture = CompletableFuture.supplyAsync(
            () -> orderService.findByUserId(userId)
        );

        CompletableFuture<UserPreferences> prefFuture = CompletableFuture.supplyAsync(
            () -> preferencesService.getPreferences(userId)
        );

        // 组合所有结果
        return CompletableFuture.allOf(userFuture, ordersFuture, prefFuture)
            .thenApply(v -> new UserFullInfo(
                userFuture.join(),
                ordersFuture.join(),
                prefFuture.join()
            )).get();
    })
    .refreshAfterWrite(Duration.ofMinutes(45))
    .build();
```

## 🚀 实际应用示例

### 电商用户缓存

```java
@Service
public class UserCacheConfig {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RedissonClient redissonClient;

    public Cache<Long, User> createUserCache() {
        return CascadeCacheBuilder.<Long, User>newBuilder("users")
            .loader(userId -> {
                return userRepository.findById(userId)
                    .orElseThrow(() -> new UserNotFoundException("User not found: " + userId));
            })
            .refreshAfterWrite(Duration.ofMinutes(30))    // 30分钟刷新
            .expireAfterWrite(Duration.ofHours(2))        // 2小时过期
            .enableL2Cache(true, redissonClient)
            .enableProtection(true)
            .build();
    }
}
```

### 配置数据缓存

```java
Cache<String, String> configCache = CascadeCacheBuilder.<String, String>newBuilder("config")
    .loader(configKey -> {
        // 从配置中心加载
        String value = configCenter.getConfig(configKey);
        if (value == null) {
            // 提供默认值
            value = getDefaultConfig(configKey);
        }
        return value;
    })
    .refreshAfterWrite(Duration.ofMinutes(10))    // 10分钟刷新配置
    .expireAfterWrite(Duration.ofDays(1))         // 1天过期
    .build();
```

## 💡 最佳实践

1. **异常处理**: 在CacheLoader中适当处理异常，返回默认值或重新抛出
2. **性能优化**: 对于批量加载场景，重写loadAll方法进行优化
3. **超时控制**: 重写getLoadTimeoutMillis()方法设置加载超时
4. **依赖注入**: 在Spring环境中将CacheLoader配置为Bean便于管理
5. **监控日志**: 在加载方法中添加适当的日志和监控指标

通过这些方式，您可以灵活配置CacheLoader来满足不同的数据加载需求。