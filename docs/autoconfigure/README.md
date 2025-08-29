# Cascade Auto Configuration

Cascade框架的Spring Boot自动配置模块，提供开箱即用的分布式缓存能力。

## 功能特性

- 🚀 **零配置启动** - 引入依赖即可使用
- 🔧 **灵活配置** - 支持详细的YAML配置
- 🏗️ **多级缓存** - L1(Caffeine) + L2(Redis)架构
- 🔄 **缓存同步** - 分布式环境下的缓存一致性
- 🛡️ **防护机制** - 防穿透、防雪崩、防击穿
- 📊 **监控集成** - 内置Micrometer指标收集
- 🎯 **Spring集成** - 完全兼容Spring Cache注解

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>cc.coderm</groupId>
    <artifactId>cascade-spring-boot-starter</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```

### 2. 基础配置

```yaml
cascade:
  cache:
    enabled: true
    defaults:
      l1:
        enabled: true
        maximum-size: 10000
      l2:
        enabled: true
        key-prefix: "app:"
```

### 3. 使用Spring Cache注解

```java
@Service
public class UserService {
    
    @Cacheable(value = "users", key = "#id")
    public User findById(Long id) {
        return userRepository.findById(id);
    }
    
    @CacheEvict(value = "users", key = "#user.id")
    public void updateUser(User user) {
        userRepository.save(user);
    }
}
```

### 4. 使用Cascade API

#### 4.1 编程式缓存创建（推荐）

```java
@Autowired
private CacheManager cacheManager;

public void example() {
    // 使用Builder模式创建缓存 - 主要方式
    Cache<String, User> userCache = cacheManager.cacheBuilder("users")
        .maximumSize(10000)
        .expireAfterWrite(Duration.ofHours(2))
        .enableL2Cache(true, redissonClient)
        .enableProtection(true)  // 启用防护机制
        .build();
    
    // 快速创建简单缓存
    Cache<String, Product> productCache = cacheManager.createCache("products");
    
    // 创建带加载器的缓存
    Cache<String, User> loadingCache = cacheManager.createCache("usersByEmail", 
        email -> userService.findByEmail(email));
    
    // 基本操作
    userCache.put("user:1", user);
    User user = userCache.get("user:1");
    
    // 异步操作
    CompletableFuture<User> future = userCache.getAsync("user:1");
    
    // 批量操作
    Map<String, User> users = userCache.getAll(Arrays.asList("user:1", "user:2"));
}
```

#### 4.2 获取已存在的缓存

```java
public void getCacheExample() {
    // 只获取已存在的缓存，不会自动创建
    Cache<String, User> existingCache = cacheManager.getCache("users");
    if (existingCache != null) {
        // 使用缓存
        User user = existingCache.get("key");
    }
}
```

## 架构说明

### CacheManager vs CacheBuilder

Cascade框架采用清晰的职责分离设计：

- **CacheManager**: 负责缓存的**管理**（获取、注册、监控、健康检查等）
- **CacheBuilder**: 负责缓存的**构建配置**（L1/L2配置、防护机制、同步等）

```java
// CacheManager - 管理职责
cacheManager.getCache("users");           // 获取缓存
cacheManager.getCacheNames();             // 查询所有缓存
cacheManager.health();                    // 健康检查

// CacheBuilder - 构建职责  
cacheManager.cacheBuilder("users")        // 创建构建器
    .maximumSize(10000)                   // 配置选项
    .enableL2Cache(true, redissonClient)  // 配置选项
    .build();                             // 构建并注册
```

**重要变更**：
- `CascadeCacheBuilder` 的静态方法已弃用，请使用 `CacheManager.cacheBuilder()` 
- 缓存创建统一由 `CacheManager` 管理，确保了一致的注册和生命周期管理

## 配置详解

### 完整配置示例

```yaml
cascade:
  cache:
    enabled: true
    
    # 默认配置
    defaults:
      record-stats: true
      allow-null-values: true
      
      # L1缓存 (Caffeine)
      l1:
        enabled: true
        maximum-size: 10000
        expire-after-write: PT30M
        expire-after-access: PT2H
        record-stats: true
        
      # L2缓存 (Redis)
      l2:
        enabled: true
        key-prefix: "cascade:"
        serializer: json
        default-ttl: PT24H
        timeout: PT5S
        
    # 缓存同步
    sync:
      enabled: true
      
    # 缓存预热
    warmup:
      enabled: false
      
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

### 配置参数说明

#### L1缓存配置 (cache.defaults.l1)

| 参数 | 类型 | 默认值 | 说明 |
|-----|------|--------|------|
| enabled | boolean | true | 是否启用L1缓存 |
| maximum-size | long | 10000 | 最大缓存条目数 |
| expire-after-write | Duration | - | 写入后过期时间 |
| expire-after-access | Duration | PT2H | 访问后过期时间 |
| record-stats | boolean | true | 是否记录统计信息 |

#### L2缓存配置 (cache.defaults.l2)

| 参数 | 类型 | 默认值 | 说明 |
|-----|------|--------|------|
| enabled | boolean | true | 是否启用L2缓存 |
| key-prefix | string | "cascade:" | 键前缀 |
| serializer | string | json | 序列化器 (json/kryo) |
| default-ttl | Duration | PT24H | 默认TTL |
| timeout | Duration | PT5S | 操作超时时间 |

## 高级特性

### 1. 缓存防护

框架内置多种缓存防护机制：

- **防穿透**: 布隆过滤器 + 空值缓存
- **防雪崩**: 随机TTL + 限流
- **防击穿**: 分布式锁 + 异步刷新

### 2. 缓存同步

在分布式环境下，支持跨节点的缓存同步：

```yaml
cascade:
  cache:
    sync:
      enabled: true
      topic: "cache:sync"
```

### 3. 监控指标

自动注册Micrometer指标：

- `cascade.cache.requests` - 缓存请求数
- `cascade.cache.hits` - 缓存命中数
- `cascade.cache.misses` - 缓存未命中数
- `cascade.cache.evictions` - 缓存驱逐数

### 4. 健康检查

提供Spring Boot Actuator健康检查：

```bash
curl http://localhost:8080/actuator/health/cascade-cache
```

## 自动配置类

| 配置类 | 说明 |
|-------|------|
| CascadeCacheAutoConfiguration | 核心缓存配置 |
| CascadeCacheAnnotationConfiguration | 注解支持配置 |
| CascadeCacheProperties | 配置属性绑定 |

## 条件注解

自动配置使用以下条件：

- `@ConditionalOnProperty` - 基于配置属性
- `@ConditionalOnClass` - 基于类路径
- `@ConditionalOnBean` - 基于Bean存在
- `@ConditionalOnMissingBean` - 基于Bean缺失

## 开发建议

### 生产环境配置

```yaml
cascade:
  cache:
    defaults:
      l1:
        maximum-size: 50000
        expire-after-access: PT1H
      l2:
        default-ttl: PT24H
        timeout: PT3S
    sync:
      enabled: true
    warmup:
      enabled: false  # 谨慎启用
```

### 开发环境配置

```yaml
cascade:
  cache:
    defaults:
      l1:
        maximum-size: 1000
        expire-after-access: PT30M
      l2:
        enabled: false  # 可以不启用
    sync:
      enabled: false
```

## 故障排除

### 常见问题

1. **Redis连接失败**
   - 检查Redis服务是否启动
   - 确认连接配置正确

2. **缓存未生效**
   - 确认`cascade.cache.enabled=true`
   - 检查方法是否为public
   - 确认Bean是Spring管理的

3. **性能问题**
   - 调整L1缓存大小
   - 优化序列化器选择
   - 检查TTL配置

### 调试日志

```yaml
logging:
  level:
    io.github.cascade.cache: DEBUG
    io.github.cascade.autoconfigure: DEBUG
```

## 版本兼容性

| Cascade版本 | Spring Boot版本 | Java版本 |
|-------------|----------------|----------|
| 1.0.x | 3.2.x | 17+ |

## 参与贡献

欢迎提交Issue和Pull Request来帮助改进项目。

## 许可证

MIT License