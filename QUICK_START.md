# Cascade Cache 快速上手指南

## 🚀 5分钟快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>cc.coderm</groupId>
    <artifactId>cascade-spring-boot-starter</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```

### 2. 最简配置

```yaml
# application.yml
cascade:
  cache:
    enabled: true
```

### 3. 启用缓存注解

```java
@SpringBootApplication
@EnableCaching
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

### 4. 使用缓存注解

```java
@Service
public class UserService {
    
    @Cacheable("users")
    public User findUser(Long id) {
        // 这个方法的结果会被缓存
        return fetchUserFromDatabase(id);
    }
    
    @CacheEvict("users")
    public void updateUser(User user) {
        // 更新后清除缓存
        saveUserToDatabase(user);
    }
}
```

### 5. 测试效果

```java
@RestController
public class TestController {
    
    @Autowired
    private UserService userService;
    
    @GetMapping("/user/{id}")
    public User getUser(@PathVariable Long id) {
        long start = System.currentTimeMillis();
        User user = userService.findUser(id);
        long time = System.currentTimeMillis() - start;
        System.out.println("查询耗时: " + time + "ms");
        return user;
    }
}
```

## 🔧 三种使用模式

### 模式1: 仅本地缓存 (最简单)

```yaml
cascade:
  cache:
    defaults:
      l1:
        enabled: true
        maximum-size: 10000
      l2:
        enabled: false
```

### 模式2: 本地+Redis双级缓存 (推荐)

```yaml
cascade:
  cache:
    defaults:
      l1:
        enabled: true
        maximum-size: 10000
        expire-after-access: PT1H
      l2:
        enabled: true
        key-prefix: "app:"
        default-ttl: PT24H

spring:
  data:
    redis:
      host: localhost
      port: 6379
```

### 模式3: 直接API使用

```java
@Autowired
private CascadeCacheManager cacheManager;

public void directApiExample() {
    // 获取缓存实例
    Cache<String, Object> cache = cacheManager.getCache("myCache");
    
    // 基本操作
    cache.put("key1", "value1");
    Object value = cache.get("key1");
    cache.evict("key1");
    
    // 异步操作
    CompletableFuture<Object> future = cache.getAsync("key1");
    
    // 批量操作
    Map<String, Object> result = cache.getAll(Arrays.asList("key1", "key2"));
}
```

## 📊 常用配置选项

```yaml
cascade:
  cache:
    enabled: true
    
    defaults:
      # L1缓存 (本地内存)
      l1:
        enabled: true
        maximum-size: 10000           # 最大条目数
        expire-after-access: PT2H     # 2小时不访问则过期
        expire-after-write: PT4H      # 4小时强制过期
        
      # L2缓存 (Redis)
      l2:
        enabled: true
        key-prefix: "cache:"          # Redis键前缀
        default-ttl: PT24H            # 24小时TTL
        timeout: PT5S                 # 操作超时
        
    # 特定缓存配置
    caches:
      users:
        l1:
          maximum-size: 5000
          expire-after-access: PT1H
        l2:
          key-prefix: "user:"
          default-ttl: PT12H
```

## 🎯 最佳实践

### 1. 缓存键设计

```java
@Cacheable(value = "users", key = "#id")
public User findById(Long id) { ... }

@Cacheable(value = "users", key = "#user.id + ':' + #user.version")
public User findByIdAndVersion(User user) { ... }
```

### 2. 条件缓存

```java
@Cacheable(value = "users", condition = "#id > 0")
public User findById(Long id) { ... }

@Cacheable(value = "users", unless = "#result == null")
public User findByEmail(String email) { ... }
```

### 3. 缓存更新策略

```java
// 更新时清除缓存
@CacheEvict(value = "users", key = "#user.id")
public User updateUser(User user) { ... }

// 更新时刷新缓存
@CachePut(value = "users", key = "#user.id")
public User saveUser(User user) { ... }

// 组合操作
@Caching(evict = {
    @CacheEvict(value = "users", key = "#user.id"),
    @CacheEvict(value = "userList", allEntries = true)
})
public User deleteUser(User user) { ... }
```

## 🔍 监控和调试

### 启用统计信息

```yaml
cascade:
  cache:
    defaults:
      l1:
        record-stats: true
      l2:
        record-stats: true

# 日志配置
logging:
  level:
    io.github.cascade.cache: DEBUG
```

### 监控端点

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,caches,metrics
```

访问监控：
- http://localhost:8080/actuator/caches
- http://localhost:8080/actuator/health
- http://localhost:8080/actuator/metrics

## 🚨 常见问题

### 1. 缓存不生效？

检查：
- 方法是否为public
- 类是否被Spring管理
- 是否启用了@EnableCaching

### 2. Redis连接失败？

缓存会自动降级到仅使用L1缓存，不会影响业务逻辑。

### 3. 性能优化？

```yaml
cascade:
  cache:
    defaults:
      l1:
        maximum-size: 50000      # 根据内存调整
        expire-after-access: PT1H # 根据数据更新频率调整
      l2:
        default-ttl: PT6H        # 根据数据时效性调整
```

## 🎉 完成！

现在你已经掌握了cascade-cache的基本使用方法。更多高级特性请参考完整示例文档。