# LoadingCache 完全使用指南

## 🎯 什么是 LoadingCache

`LoadingCache` 是cascade-cache框架中的核心接口，它提供了**自动加载**功能的缓存。当缓存未命中时，它会自动调用预配置的`CacheLoader`来加载数据，无需手动处理缓存未命中的情况。

## 💡 核心概念

### 传统缓存 vs LoadingCache

```java
// ❌ 传统缓存 - 需要手动处理缓存未命中
public User getUser(Long id) {
    User user = cache.get(id);
    if (user == null) {
        user = userService.findById(id);  // 手动加载
        cache.put(id, user);              // 手动放入缓存
    }
    return user;
}

// ✅ LoadingCache - 自动处理缓存未命中
LoadingCache<Long, User> userCache = ...;
public User getUser(Long id) {
    return userCache.getUnchecked(id);  // 自动加载，一行搞定！
}
```

### CacheLoader - 数据加载器

`CacheLoader`定义了如何加载缓存数据：

```java
public interface CacheLoader<K, V> {
    V load(K key) throws Exception;           // 单个加载
    Map<K, V> loadAll(Set<K> keys);          // 批量加载
    V reload(K key, V oldValue);             // 刷新加载
}
```

## 🚀 基本使用方式

### 1. 创建 CacheLoader

```java
// 用户数据加载器
public class UserCacheLoader implements CacheLoader<Long, User> {
    
    @Autowired
    private UserRepository userRepository;
    
    @Override
    public User load(Long userId) throws Exception {
        System.out.println("从数据库加载用户: " + userId);
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            throw new RuntimeException("用户不存在: " + userId);
        }
        return user;
    }
    
    @Override
    public Map<Long, User> loadAll(Set<Long> userIds) throws Exception {
        System.out.println("批量从数据库加载用户: " + userIds);
        List<User> users = userRepository.findAllById(userIds);
        return users.stream().collect(Collectors.toMap(User::getId, u -> u));
    }
    
    @Override
    public boolean supportsBatchLoading() {
        return true;  // 支持批量加载
    }
}
```

### 2. 创建 LoadingCache

```java
@Service
public class UserCacheService {
    
    private final LoadingCache<Long, User> userCache;
    
    public UserCacheService(CascadeCacheManager cacheManager, UserCacheLoader loader) {
        // 方式1: 通过Builder创建
        this.userCache = CascadeCacheBuilder.<Long, User>newBuilder("users")
                .loader(loader)                    // 设置加载器
                .maximumSize(10000)                // 最大条目数
                .expireAfterWrite(Duration.ofHours(2))  // 2小时过期
                .recordStats(true)                 // 记录统计
                .build();
                
        // 方式2: 使用现有Cache包装为LoadingCache
        Cache<Long, User> baseCache = cacheManager.getCache("users");
        this.userCache = new DefaultLoadingCache<>(baseCache, loader);
    }
    
    // 使用LoadingCache
    public User getUser(Long id) {
        return userCache.getUnchecked(id);  // 自动加载
    }
    
    public Map<Long, User> getUsers(Set<Long> ids) {
        return userCache.getAllUnchecked(ids);  // 批量自动加载
    }
}
```

### 3. Spring Boot自动配置方式

```java
// 配置CacheLoader
@Configuration
public class CacheConfig {
    
    @Bean
    public CacheLoader<Long, User> userCacheLoader(UserRepository userRepository) {
        return new CacheLoader<Long, User>() {
            @Override
            public User load(Long id) throws Exception {
                return userRepository.findById(id)
                    .orElseThrow(() -> new RuntimeException("用户不存在: " + id));
            }
        };
    }
}

// 注入使用
@Service
public class UserService {
    
    @Autowired
    private CascadeCacheManager cacheManager;
    
    @Autowired
    private CacheLoader<Long, User> userCacheLoader;
    
    private LoadingCache<Long, User> userCache;
    
    @PostConstruct
    public void init() {
        Cache<Long, User> baseCache = cacheManager.getCache("users");
        this.userCache = new DefaultLoadingCache<>(baseCache, userCacheLoader);
    }
    
    public User findUser(Long id) {
        return userCache.getUnchecked(id);
    }
}
```

## 🔧 高级功能

### 1. 异步加载

```java
@Service
public class AsyncUserService {
    
    private final LoadingCache<Long, User> userCache;
    
    // 异步获取单个用户
    public CompletableFuture<User> getUserAsync(Long id) {
        return userCache.getAsync(id);
    }
    
    // 异步批量获取用户
    public CompletableFuture<Map<Long, User>> getUsersAsync(Set<Long> ids) {
        return userCache.getAllAsync(ids);
    }
    
    // 使用示例
    public void asyncExample() {
        userCache.getAsync(1L)
                .thenAccept(user -> System.out.println("用户: " + user))
                .exceptionally(ex -> {
                    System.err.println("加载失败: " + ex.getMessage());
                    return null;
                });
    }
}
```

### 2. 缓存刷新

```java
@Service
public class CacheRefreshService {
    
    private final LoadingCache<Long, User> userCache;
    
    // 刷新单个缓存项
    public void refreshUser(Long id) {
        userCache.refresh(id);  // 同步刷新
    }
    
    // 异步刷新
    public CompletableFuture<Void> refreshUserAsync(Long id) {
        return userCache.refreshAsync(id);
    }
    
    // 批量刷新
    public void refreshUsers(Set<Long> ids) {
        userCache.refreshAll(ids);
    }
    
    // 定时刷新示例
    @Scheduled(fixedRate = 300000)  // 5分钟
    public void scheduledRefresh() {
        Set<Long> activeUserIds = getActiveUserIds();
        userCache.refreshAllAsync(activeUserIds)
                .thenRun(() -> System.out.println("刷新完成"));
    }
}
```

### 3. 缓存预热

```java
@Service
public class CacheWarmupService {
    
    private final LoadingCache<Long, User> userCache;
    
    // 应用启动时预热
    @EventListener(ApplicationReadyEvent.class)
    public void warmupCache() {
        System.out.println("开始缓存预热...");
        
        // 预热热门用户
        Set<Long> hotUserIds = getHotUserIds();
        
        try {
            userCache.preloadAll(hotUserIds);  // 同步预热
            System.out.println("预热完成: " + hotUserIds.size() + " 个用户");
        } catch (Exception e) {
            System.err.println("预热失败: " + e.getMessage());
        }
    }
    
    // 异步预热
    public void asyncWarmup() {
        Set<Long> userIds = getAllUserIds();
        
        userCache.preloadAllAsync(userIds)
                .thenRun(() -> System.out.println("异步预热完成"))
                .exceptionally(ex -> {
                    System.err.println("预热失败: " + ex.getMessage());
                    return null;
                });
    }
    
    private Set<Long> getHotUserIds() {
        // 返回热门用户ID
        return Set.of(1L, 2L, 3L, 4L, 5L);
    }
    
    private Set<Long> getAllUserIds() {
        // 返回所有用户ID
        return userRepository.findAllIds();
    }
}
```

### 4. 加载状态监控

```java
@RestController
@RequestMapping("/api/cache")
public class CacheMonitorController {
    
    @Autowired
    private LoadingCache<Long, User> userCache;
    
    // 获取加载统计
    @GetMapping("/stats")
    public LoadingStats getLoadingStats() {
        return userCache.getLoadingStats();
    }
    
    // 检查是否正在加载
    @GetMapping("/loading/{id}")
    public boolean isLoading(@PathVariable Long id) {
        return userCache.isLoading(id);
    }
    
    // 获取正在加载的键
    @GetMapping("/loading-keys")
    public Set<Long> getLoadingKeys() {
        return userCache.getLoadingKeys();
    }
    
    // 取消加载
    @PostMapping("/cancel/{id}")
    public boolean cancelLoading(@PathVariable Long id) {
        return userCache.cancelLoading(id);
    }
    
    // 取消所有加载
    @PostMapping("/cancel-all")
    public int cancelAllLoading() {
        return userCache.cancelAllLoading();
    }
}
```

## 🎯 实际应用场景

### 1. 用户信息缓存

```java
@Component
public class UserInfoCache {
    
    private final LoadingCache<Long, UserInfo> cache;
    
    public UserInfoCache(UserService userService) {
        this.cache = CascadeCacheBuilder.<Long, UserInfo>newBuilder("user-info")
                .loader(new CacheLoader<Long, UserInfo>() {
                    @Override
                    public UserInfo load(Long userId) throws Exception {
                        // 从多个数据源聚合用户信息
                        User user = userService.findById(userId);
                        Profile profile = profileService.getProfile(userId);
                        List<Role> roles = roleService.getUserRoles(userId);
                        
                        return UserInfo.builder()
                                .user(user)
                                .profile(profile)
                                .roles(roles)
                                .build();
                    }
                    
                    @Override
                    public Map<Long, UserInfo> loadAll(Set<Long> userIds) throws Exception {
                        // 批量加载优化
                        Map<Long, User> users = userService.findByIds(userIds);
                        Map<Long, Profile> profiles = profileService.getProfiles(userIds);
                        Map<Long, List<Role>> roleMap = roleService.getUserRolesMap(userIds);
                        
                        return userIds.stream()
                                .collect(Collectors.toMap(
                                        id -> id,
                                        id -> UserInfo.builder()
                                                .user(users.get(id))
                                                .profile(profiles.get(id))
                                                .roles(roleMap.get(id))
                                                .build()
                                ));
                    }
                })
                .maximumSize(50000)
                .expireAfterWrite(Duration.ofHours(1))
                .refreshAfterWrite(Duration.ofMinutes(30))
                .recordStats(true)
                .build();
    }
    
    public UserInfo getUserInfo(Long userId) {
        return cache.getUnchecked(userId);
    }
    
    public Map<Long, UserInfo> getUserInfos(Set<Long> userIds) {
        return cache.getAllUnchecked(userIds);
    }
}
```

### 2. 配置信息缓存

```java
@Component
public class ConfigCache {
    
    private final LoadingCache<String, String> cache;
    
    public ConfigCache(ConfigService configService) {
        this.cache = CascadeCacheBuilder.<String, String>newBuilder("config")
                .loader(new CacheLoader<String, String>() {
                    @Override
                    public String load(String key) throws Exception {
                        String value = configService.getConfig(key);
                        if (value == null) {
                            throw new IllegalArgumentException("配置不存在: " + key);
                        }
                        return value;
                    }
                    
                    @Override
                    public String reload(String key, String oldValue) throws Exception {
                        // 重新加载配置
                        System.out.println("重新加载配置: " + key);
                        return load(key);
                    }
                })
                .maximumSize(10000)
                .expireAfterWrite(Duration.ofMinutes(10))
                .build();
    }
    
    public String getConfig(String key) {
        return cache.getUnchecked(key);
    }
    
    public String getConfig(String key, String defaultValue) {
        try {
            return cache.getUnchecked(key);
        } catch (Exception e) {
            return defaultValue;
        }
    }
    
    // 刷新配置
    public void refreshConfig(String key) {
        cache.refresh(key);
    }
}
```

### 3. API响应缓存

```java
@Component
public class ApiResponseCache {
    
    private final LoadingCache<String, ApiResponse> cache;
    
    public ApiResponseCache(ExternalApiService apiService) {
        this.cache = CascadeCacheBuilder.<String, ApiResponse>newBuilder("api-response")
                .loader(new CacheLoader<String, ApiResponse>() {
                    @Override
                    public ApiResponse load(String apiKey) throws Exception {
                        // 调用外部API
                        return apiService.callApi(apiKey);
                    }
                    
                    @Override
                    public CompletableFuture<ApiResponse> loadAsync(String apiKey) {
                        // 异步调用外部API
                        return apiService.callApiAsync(apiKey);
                    }
                    
                    @Override
                    public ApiResponse handleLoadException(String key, Exception exception) throws Exception {
                        // 异常处理：返回默认响应
                        if (exception instanceof TimeoutException) {
                            return ApiResponse.timeout(key);
                        }
                        return ApiResponse.error(key, exception.getMessage());
                    }
                })
                .maximumSize(1000)
                .expireAfterWrite(Duration.ofMinutes(5))
                .build();
    }
    
    public ApiResponse getApiResponse(String apiKey) {
        return cache.getUnchecked(apiKey);
    }
    
    public CompletableFuture<ApiResponse> getApiResponseAsync(String apiKey) {
        return cache.getAsync(apiKey);
    }
}
```

## 📊 性能优化建议

### 1. 批量加载优化

```java
// ✅ 好的做法：实现批量加载
@Override
public Map<Long, User> loadAll(Set<Long> userIds) throws Exception {
    // 一次数据库查询获取所有用户
    List<User> users = userRepository.findAllById(userIds);
    return users.stream().collect(Collectors.toMap(User::getId, u -> u));
}

// ❌ 不好的做法：逐个加载
@Override
public Map<Long, User> loadAll(Set<Long> userIds) throws Exception {
    Map<Long, User> result = new HashMap<>();
    for (Long id : userIds) {
        result.put(id, load(id));  // N+1查询问题
    }
    return result;
}
```

### 2. 异步加载优化

```java
// 自定义线程池
@Override
public CompletableFuture<User> loadAsync(Long userId) {
    return CompletableFuture.supplyAsync(() -> {
        try {
            return load(userId);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }, customExecutor);  // 使用自定义线程池
}
```

### 3. 错误处理优化

```java
@Override
public User handleLoadException(Long key, Exception exception) throws Exception {
    if (exception instanceof NotFoundException) {
        // 对于不存在的数据，缓存一个特殊值避免重复查询
        return User.notFound(key);
    }
    if (exception instanceof TimeoutException) {
        // 超时时返回默认值
        return User.defaultUser(key);
    }
    throw exception;  // 其他异常继续抛出
}
```

## 🚨 注意事项

1. **null值处理**：CacheLoader的load方法不应返回null，如果没有数据应该抛出异常
2. **异常处理**：合理处理加载异常，避免缓存雪崩
3. **批量加载**：尽量实现批量加载以提高性能
4. **内存控制**：设置合理的缓存大小和过期时间
5. **监控统计**：启用统计功能监控缓存效果

## 📝 总结

LoadingCache是cascade-cache框架的核心特性，它通过自动加载机制大大简化了缓存的使用。主要优势：

- **自动化**：缓存未命中时自动加载数据
- **异步支持**：支持异步加载和刷新
- **批量操作**：支持批量加载提高性能
- **状态监控**：提供详细的加载状态和统计信息
- **错误处理**：灵活的异常处理机制

通过合理使用LoadingCache，可以大大提高应用的性能和用户体验。