# 自动CacheLoader发现机制使用指南

## 概述

Cascade缓存框架现在支持自动发现Spring容器中的CacheLoader，无需手动配置即可实现缓存的自动加载功能。

## 核心特性

### 🎯 自动发现机制

1. **类型匹配**: 根据缓存的泛型类型自动匹配Compatible的CacheLoader
2. **命名约定**: 支持多种命名模式的自动发现
3. **优先级策略**: 手动配置 > 命名约定 > 类型匹配
4. **Spring集成**: 与Spring IoC容器无缝集成

### 🏗️ 支持的命名约定

- `{cacheName}Loader` (如: userLoader)  
- `{cacheName}CacheLoader` (如: userCacheLoader)
- `{ValueType}Loader` (如: UserLoader)
- `{valueType}Loader` (如: userLoader，首字母小写)

## 使用示例

### 1. 基础用法 - 类型匹配

```java
// 定义实体类
public class User {
    private String id;
    private String name;
    private String email;
    // constructors, getters, setters...
}

// 创建CacheLoader组件
@Service
public class UserLoader implements CacheLoader<String, User> {
    
    @Autowired
    private UserService userService;

    @Override
    public User load(String userId) throws Exception {
        return userService.findById(userId);
    }

    @Override 
    public Map<String, User> loadAll(Set<String> userIds) throws Exception {
        return userService.findAllByIds(userIds);
    }
}

// 创建缓存 - 自动发现UserLoader
@Configuration
public class CacheConfig {
    
    @Bean
    public Cache<String, User> userCache() {
        return UnifiedCacheBuilder.stringCache("userCache", User.class)
            .enableL1(true)
            .maximumSize(1000)
            .expireAfterWrite(Duration.ofMinutes(30))
            .build(); // 自动发现并配置UserLoader
    }
}
```

### 2. 命名约定用法

```java
// 按照命名约定创建CacheLoader
@Service("userLoader") // 对应缓存名称 "user"
public class UserCacheLoader implements CacheLoader<String, User> {
    
    @Autowired
    private UserRepository userRepository;

    @Override
    public User load(String key) throws Exception {
        return userRepository.findById(key)
            .orElseThrow(() -> new RuntimeException("User not found: " + key));
    }
}

// 创建名为"user"的缓存 - 自动发现userLoader
@Bean
public Cache<String, User> userCache() {
    return UnifiedCacheBuilder.stringCache("user", User.class)
        .enableL1(true)
        .enableL2(true)  
        .withRedis(redissonClient)
        .build(); // 自动发现userLoader
}
```

### 3. 多种类型的CacheLoader

```java
// 用户加载器
@Service
public class UserLoader implements CacheLoader<String, User> {
    @Override
    public User load(String key) throws Exception {
        // 加载用户逻辑
    }
}

// 产品加载器  
@Service
public class ProductLoader implements CacheLoader<Long, Product> {
    @Override
    public Product load(Long key) throws Exception {
        // 加载产品逻辑
    }
}

// 订单加载器
@Service("orderCacheLoader")
public class OrderLoader implements CacheLoader<String, Order> {
    @Override
    public Order load(String key) throws Exception {
        // 加载订单逻辑
    }
}

// 配置多个缓存
@Configuration
public class MultiCacheConfig {
    
    @Bean
    public Cache<String, User> userCache() {
        return UnifiedCacheBuilder.stringCache("userCache", User.class)
            .build(); // 自动发现UserLoader
    }
    
    @Bean  
    public Cache<Long, Product> productCache() {
        return UnifiedCacheBuilder.longCache("productCache", Product.class)
            .build(); // 自动发现ProductLoader
    }
    
    @Bean
    public Cache<String, Order> orderCache() {
        return UnifiedCacheBuilder.stringCache("order", Order.class)
            .build(); // 自动发现orderCacheLoader
    }
}
```

### 4. 禁用自动发现

```java
@Bean
public Cache<String, User> userCache() {
    return UnifiedCacheBuilder.stringCache("userCache", User.class)
        .autoDiscoverLoader(false) // 禁用自动发现
        .cacheLoader(customUserLoader) // 手动指定
        .build();
}
```

### 5. Spring Boot自动配置

```java
@SpringBootApplication
@EnableAutoConfiguration // 自动启用CacheLoader发现机制
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}

// 在任何地方创建缓存都会自动发现CacheLoader
@RestController
public class UserController {
    
    private final Cache<String, User> userCache;
    
    public UserController() {
        // 自动发现CacheLoader
        this.userCache = UnifiedCacheBuilder.stringCache("user", User.class).build();
    }
    
    @GetMapping("/users/{id}")
    public User getUser(@PathVariable String id) {
        return userCache.get(id); // 自动加载如果缓存未命中
    }
}
```

## 高级特性

### 1. 泛型类型兼容性

```java
// 支持继承关系的类型匹配
public class Admin extends User {
    // 扩展字段
}

@Service
public class UserLoader implements CacheLoader<String, User> {
    // 实现
}

// Admin缓存可以复用UserLoader（因为Admin继承User）
Cache<String, Admin> adminCache = UnifiedCacheBuilder
    .stringCache("admin", Admin.class)
    .build(); // 自动发现UserLoader
```

### 2. 条件性CacheLoader

```java
@Service
@ConditionalOnProperty(name = "app.cache.user.enabled", havingValue = "true")
public class UserLoader implements CacheLoader<String, User> {
    // 只在配置启用时生效
}
```

### 3. 优先级控制

```java
@Service
@Primary // 优先选择此CacheLoader
public class PrimaryUserLoader implements CacheLoader<String, User> {
    // 主要的用户加载器
}

@Service
public class BackupUserLoader implements CacheLoader<String, User> {
    // 备用的用户加载器
}
```

## 调试和监控

### 1. 日志输出

```java
# 启用DEBUG日志查看自动发现过程
logging.level.io.github.cascade.cache.core.loader=DEBUG
```

输出示例：
```
INFO  - Found compatible CacheLoader: UserLoader for types <String, User>  
INFO  - Auto-discovered CacheLoader: UserLoader for cache: userCache
DEBUG - Type compatibility check: CacheLoader<String, User> vs <String, User> -> key:true, value:true
```

### 2. 发现失败处理

```java
// 如果自动发现失败，可以手动指定
@Bean
public Cache<String, User> userCache(
    @Autowired(required = false) CacheLoader<String, User> userLoader) {
    
    var builder = UnifiedCacheBuilder.stringCache("user", User.class);
    
    if (userLoader != null) {
        builder.cacheLoader(userLoader);
    } else {
        log.warn("No CacheLoader found for User cache, cache-through disabled");
    }
    
    return builder.build();
}
```

## 最佳实践

1. **命名规范**: 使用清晰的命名约定，如 `{EntityType}Loader`
2. **类型安全**: 确保CacheLoader的泛型类型与缓存类型完全匹配
3. **异常处理**: 在CacheLoader中妥善处理异常，避免影响缓存操作
4. **性能考虑**: CacheLoader应该尽可能快，避免长时间阻塞
5. **测试**: 为CacheLoader编写单元测试，确保逻辑正确

## 注意事项

1. **泛型擦除**: Java的泛型擦除可能影响类型匹配，建议明确指定类型
2. **Spring上下文**: 确保CacheLoader在Spring容器启动后才创建缓存
3. **循环依赖**: 避免CacheLoader和缓存之间的循环依赖
4. **线程安全**: 确保CacheLoader实现是线程安全的

这个自动发现机制大大简化了缓存配置，让开发者能够专注于业务逻辑而不是缓存配置的细节。