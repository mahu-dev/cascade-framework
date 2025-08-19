# UnifiedCache CacheLoader 优雅实现方案

## 实现特性

### 🎯 核心功能

1. **自动加载**: 缓存未命中时自动从CacheLoader加载数据
2. **重试机制**: 支持指数退避的重试策略（最多3次）
3. **防并发**: 防止同一key的并发加载（基于防护管理器）
4. **异常处理**: 优雅处理加载异常，不影响系统稳定性
5. **日志记录**: 详细的加载过程日志，便于调试和监控

### 🏗️ 架构设计

```java
public V get(K key) {
    if (key == null) return null;

    // 1. 防护检查（布隆过滤器等）
    if (protectionManager != null) {
        // 防护逻辑
    }

    // 2. 从缓存层获取
    V value = getFromTiers(key);

    // 3. 缓存未命中时自动加载
    if (value == null && cacheLoader != null) {
        value = loadValueWithBackoff(key);
    }

    // 4. 后处理
    if (protectionManager != null) {
        // 后处理逻辑
    }

    return value;
}
```

## 使用示例

### 基础用法

```java
// 创建缓存时设置CacheLoader
UnifiedCache<String, User> userCache = UnifiedCacheBuilder.<String, User>builder("userCache")
    .cacheLoader(userId -> {
        // 从数据库加载用户
        return userService.findById(userId);
    })
    .build();

// 使用时自动加载
User user = userCache.get("user123"); // 如果缓存中没有，会自动从数据库加载
```

### 高级用法

```java
// 带重试和监控的CacheLoader
CacheLoader<String, User> userLoader = new CacheLoader<String, User>() {
    @Override
    public User load(String userId) throws Exception {
        // 可能失败的数据库查询
        return userRepository.findById(userId)
            .orElseThrow(() -> new UserNotFoundException("User not found: " + userId));
    }
    
    @Override
    public Map<String, User> loadAll(Set<String> userIds) throws Exception {
        // 批量加载
        return userRepository.findAllByIds(userIds);
    }
};

UnifiedCache<String, User> userCache = UnifiedCacheBuilder.<String, User>builder("userCache")
    .enableL1(true)
    .maximumSize(1000)
    .expireAfterWrite(Duration.ofMinutes(30))
    .enableL2(true)
    .withRedis(redissonClient)
    .cacheLoader(userLoader)
    .build();
```

### Spring Boot集成

```java
@Service
public class UserCacheService {
    
    private final UnifiedCache<String, User> userCache;
    
    public UserCacheService(UserService userService, RedissonClient redissonClient) {
        this.userCache = UnifiedCacheBuilder.<String, User>builder("userCache")
            .enableL1(true)
            .maximumSize(1000)
            .enableL2(true)
            .withRedis(redissonClient)
            .cacheLoader(userService::findById) // 方法引用
            .build();
    }
    
    public User getUser(String userId) {
        return userCache.get(userId); // 自动加载，无需手动处理缓存未命中
    }
    
    public Map<String, User> getUsers(Set<String> userIds) {
        return userCache.getAll(userIds); // 批量获取，自动处理未命中项
    }
}
```

## 技术特点

### 1. 指数退避重试

```java
// 重试延迟计算：100ms, 200ms, 400ms
long delay = baseDelayMs * (1L << (attempt - 1));
```

### 2. 防并发加载

- 使用防护管理器防止相同key的并发加载
- 避免缓存击穿问题
- 支持分布式锁机制

### 3. 异常处理策略

- **加载失败**: 重试最多3次，然后返回null
- **中断处理**: 正确处理InterruptedException
- **降级机制**: 防护失败时降级到直接加载

### 4. 日志级别

- **DEBUG**: 成功加载、null值返回
- **WARN**: 重试、防护失败
- **ERROR**: 最终失败

## 性能优化

### 1. 异步刷新

```java
// 后台异步刷新，不阻塞当前请求
cache.refresh("user123");
```

### 2. 批量加载

```java
// 自动优化批量加载
Set<String> userIds = Set.of("user1", "user2", "user3");
Map<String, User> users = cache.getAll(userIds);
```

### 3. 多级缓存

```java
// L1缓存未命中时从L2加载，L2未命中时从CacheLoader加载
V value = getFromTiers(key);
if (value == null && cacheLoader != null) {
    value = loadValueWithBackoff(key);
}
```

## 监控指标

加载过程会产生以下指标：

- **loadCount**: 加载次数
- **loadSuccessCount**: 加载成功次数
- **loadExceptionCount**: 加载异常次数
- **averageLoadTime**: 平均加载时间
- **maxLoadTime**: 最大加载时间

## 最佳实践

1. **CacheLoader实现**:
   - 确保线程安全
   - 处理null值情况
   - 设置合理的超时时间

2. **异常处理**:
   - 避免在CacheLoader中抛出RuntimeException
   - 使用特定的业务异常类型

3. **性能考虑**:
   - CacheLoader操作应该尽可能快
   - 避免在CacheLoader中进行复杂计算
   - 考虑使用批量加载接口

4. **监控**:
   - 监控加载成功率
   - 监控平均加载时间
   - 设置加载异常告警

这个实现提供了生产级别的缓存加载能力，既保证了功能的完整性，又提供了优雅的错误处理和性能优化。