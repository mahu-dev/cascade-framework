# Cascade 缓存管理器选择指南

## 📋 概述

Cascade框架现在支持两种缓存管理器，你可以根据需求选择最适合的：

| 管理器类型 | 特点 | 适用场景 |
|-----------|------|---------|
| **SpringBootCacheManager** (默认) | Spring Boot深度集成，简单易用 | 一般Spring Boot应用 |
| **AdvancedCacheManager** | 功能强大，智能策略 | 复杂业务场景，需要智能化管理 |

## 🔧 配置方式

### 1. 使用SpringBootCacheManager (默认)

```yaml
# application.yml
cascade:
  cache:
    enabled: true
    manager:
      type: simple  # 或者不配置，默认就是simple
    defaults:
      l1:
        enabled: true
        maximumSize: 10000
      l2:
        enabled: true
        keyPrefix: "app:"
```

### 2. 使用AdvancedCacheManager (推荐高级用户)

```yaml
# application.yml  
cascade:
  cache:
    enabled: true
    manager:
      type: advanced  # 启用智能管理器
    defaults:
      l1:
        enabled: true
        maximumSize: 10000
      l2:
        enabled: true
        keyPrefix: "app:"
```

## 🚀 AdvancedCacheManager的强大功能

### 1. **自动环境检测**
```java
// 无需配置，自动检测Redis环境
// 有Redis → 自动启用多级缓存 + 同步
// 无Redis → 自动退化为本地缓存
```

### 2. **智能缓存策略**
- `LOCAL_ONLY`: 仅本地缓存
- `REMOTE_ONLY`: 仅分布式缓存  
- `MULTI_LEVEL`: 多级缓存
- `AUTO_DETECT`: 🌟 **智能检测**（推荐）
- `CUSTOM_FACTORY`: 自定义工厂

### 3. **命名配置支持**
```java
@Autowired
private AdvancedCacheManager cacheManager;

// 为特定缓存设置专门配置
cacheManager.setNamedConfig("userCache", userCacheConfig);
cacheManager.setNamedConfig("productCache", productCacheConfig);
```

### 4. **批量操作**
```java
// 批量创建缓存
cacheManager.createCaches("cache1", "cache2", "cache3");
```

## 💡 使用示例

### SpringBootCacheManager 示例
```java
@Service
public class UserService {
    
    @Autowired
    private SpringBootCacheManager cacheManager;
    
    public void init() {
        // 简单直接的缓存创建
        Cache<String, User> userCache = cacheManager.<String, User>cacheBuilder("users")
                .maximumSize(1000)
                .expireAfterWrite(Duration.ofHours(1))
                .build();
    }
}
```

### AdvancedCacheManager 示例
```java
@Service 
public class CacheService {
    
    @Autowired
    private AdvancedCacheManager cacheManager;
    
    @PostConstruct
    public void init() {
        // 智能化缓存管理
        
        // 1. 自动环境检测 + 智能策略
        cacheManager.setCreationStrategy(CacheCreationStrategy.AUTO_DETECT);
        
        // 2. 为不同缓存设置专门配置
        CacheConfig userConfig = new CacheConfig();
        userConfig.setMaxSize(10000);
        cacheManager.setNamedConfig("users", userConfig);
        
        CacheConfig productConfig = new CacheConfig(); 
        productConfig.setMaxSize(50000);
        cacheManager.setNamedConfig("products", productConfig);
        
        // 3. 批量创建缓存
        cacheManager.createCaches("users", "products", "orders");
        
        // 4. 使用Builder模式精确控制
        Cache<String, User> vipCache = cacheManager.<String, User>cacheBuilder("vip-users")
                .withRedis()                    // 启用Redis
                .withProtection()               // 启用防护机制
                .withSync()                     // 启用同步
                .maximumSize(1000)
                .expireAfterWrite(Duration.ofHours(2))
                .build();
    }
}
```

## 🎯 选择建议

### 选择 SpringBootCacheManager 当你：
- ✅ 使用标准的Spring Boot应用
- ✅ 不需要复杂的缓存策略
- ✅ 喜欢简单直接的配置
- ✅ 主要通过配置文件管理

### 选择 AdvancedCacheManager 当你：
- ✅ 需要智能的环境适配
- ✅ 有复杂的缓存需求
- ✅ 需要为不同缓存设置不同策略
- ✅ 希望缓存管理更自动化
- ✅ 需要批量操作功能

## 🔄 迁移指南

从SpringBootCacheManager迁移到AdvancedCacheManager：

```yaml
# 1. 修改配置
cascade:
  cache:
    manager:
      type: advanced  # 改为advanced
```

```java
// 2. 更新注入类型
@Autowired
private AdvancedCacheManager cacheManager;  // 改为AdvancedCacheManager

// 3. 利用新功能
cacheManager.setCreationStrategy(CacheCreationStrategy.AUTO_DETECT);
```

## 💼 总结

**新的架构让你可以根据需求选择合适的管理器：**

- 🎯 **简单场景** → SpringBootCacheManager
- 🚀 **复杂场景** → AdvancedCacheManager  

**两种管理器都支持相同的Builder API，迁移成本很低！**