# Cascade框架缓存管理器对比说明

## 概述

Cascade框架提供了三种不同类型的缓存管理器，每种都有其特定的使用场景和优势。本文档详细对比了这三种管理器的特点、适用场景和使用方式。

## 三种缓存管理器对比

| 特性 | SimpleCacheManager | AdvancedCacheManager | SpringBootCacheManager |
|------|-------------------|---------------------|----------------------|
| **定位** | 基础缓存管理器 | 智能工厂管理器 | Spring集成管理器 |
| **复杂度** | 简单 | 中等 | 高 |
| **创建策略** | 手动创建 | 智能自动检测 | Spring配置驱动 |
| **Spring集成** | 可选 | 支持 | 深度集成 |
| **配置来源** | 编程式 | 编程式+策略 | Spring配置文件 |
| **适用场景** | 简单项目/独立使用 | 中大型项目 | Spring Boot应用 |

## 详细说明

### 1. SimpleCacheManager

**位置**: `cascade-cache/src/main/java/io/github/cascade/cache/manager/SimpleCacheManager.java`

**特点**:
- 最基础的缓存管理器实现
- 纯手动控制，需要显式创建和配置缓存
- 轻量级，依赖最少
- 适合对缓存管理有完全控制需求的场景

**核心功能**:
```java
// 基础的缓存注册和检索
public <K, V> boolean registerCache(String cacheName, Cache<K, V> cache)
public <K, V> Cache<K, V> getCache(String cacheName)

// 编程式创建
public <K, V> CacheBuilder<K, V> cacheBuilder(String cacheName)
public <K, V> Cache<K, V> createCache(String cacheName)
```

**使用场景**:
- 简单的独立应用
- 需要完全手动控制缓存创建的场景
- 测试环境
- 对启动性能要求极高的场景

**示例**:
```java
SimpleCacheManager manager = new SimpleCacheManager();

// 手动创建缓存
Cache<String, User> userCache = manager.cacheBuilder("userCache")
    .maximumSize(1000)
    .expireAfterWrite(Duration.ofMinutes(10))
    .build();

// 或者直接创建
Cache<String, Product> productCache = manager.createCache("productCache");
```

### 2. AdvancedCacheManager

**位置**: `cascade-cache/src/main/java/io/github/cascade/cache/manager/AdvancedCacheManager.java`

**特点**:
- 智能工厂模式实现
- 自动环境检测和策略选择
- 支持多种缓存创建策略
- 可插拔的缓存工厂

**核心功能**:
```java
// 智能创建策略
public enum CacheCreationStrategy {
    LOCAL_ONLY,      // 仅本地缓存
    REMOTE_ONLY,     // 仅远程缓存
    MULTI_LEVEL,     // 多级缓存
    AUTO_DETECT,     // 自动检测
    CUSTOM_FACTORY   // 自定义工厂
}

// 策略配置
public void setCreationStrategy(CacheCreationStrategy strategy)
public void setCacheFactory(Function<String, Cache<?, ?>> cacheFactory)
```

**使用场景**:
- 中大型项目，需要根据环境自动选择缓存策略
- 开发/测试/生产环境配置不同的场景
- 需要批量创建相似配置缓存的场景
- 希望减少重复配置代码的场景

**示例**:
```java
AdvancedCacheManager manager = new AdvancedCacheManager();

// 设置创建策略
manager.setCreationStrategy(CacheCreationStrategy.AUTO_DETECT);

// 自动检测环境并创建合适的缓存
Cache<String, User> userCache = manager.createCache("userCache");

// 批量创建
manager.createCaches("cache1", "cache2", "cache3");
```

### 3. SpringBootCacheManager (Spring集成版)

**位置**: `cascade-cache/src/main/java/io/github/cascade/cache/manager/SpringBootCacheManager.java`

**特点**:
- 深度Spring Boot集成
- 支持配置文件驱动
- 自动装配和依赖注入
- 与Spring生命周期集成

**核心功能**:
```java
// Spring配置集成
public CascadeCacheManager(CascadeCacheConfig defaultConfig,
                          RedissonClient redissonClient,
                          CascadeCacheProperties properties)

// 配置访问
public CascadeCacheConfig getDefaultCascadeCacheConfig()
public CascadeCacheProperties.CacheProperties getCacheProperties(String cacheName)
```

**配置示例**:
```yaml
cascade:
  cache:
    enabled: true
    defaults:
      l1:
        enabled: true
        maximum-size: 1000
        expire-after-write: 10m
      l2:
        enabled: true
        key-prefix: "cascade:"
        default-ttl: 1h
    caches:
      userCache:
        l1:
          maximum-size: 5000
      productCache:
        l2:
          default-ttl: 30m
```

**使用场景**:
- Spring Boot应用（强烈推荐）
- 需要配置文件管理缓存的场景
- 企业级应用，需要统一配置管理
- 需要与Spring生态系统深度集成的场景

**示例**:
```java
@Autowired
private SpringBootCacheManager cacheManager;

public void someMethod() {
    // 编程式创建
    Cache<String, User> userCache = cacheManager.createCache("userCache");
    
    // 使用构建器，可以覆盖配置文件设置
    Cache<String, Product> productCache = cacheManager.cacheBuilder("productCache")
        .withRedis()
        .withProtection()
        .build();
}
```

## 选择建议

### 选择SimpleCacheManager的情况:
- 简单的独立Java应用
- 需要完全手动控制缓存配置
- 测试或演示项目
- 对依赖和启动时间敏感的场景

### 选择AdvancedCacheManager的情况:
- 非Spring项目但需要智能缓存管理
- 需要根据运行环境自动调整缓存策略
- 有复杂的缓存创建逻辑
- 需要可插拔的缓存工厂

### 选择SpringBootCacheManager的情况:
- Spring Boot项目（强烈推荐）
- 企业级应用
- 需要配置文件管理缓存参数
- 需要与Spring生态系统集成

## 迁移路径

### 从Simple到Advanced:
```java
// 之前
SimpleCacheManager simpleManager = new SimpleCacheManager();
Cache<K, V> cache = simpleManager.createCache("test");

// 迁移后
AdvancedCacheManager advancedManager = new AdvancedCacheManager();
advancedManager.setCreationStrategy(CacheCreationStrategy.LOCAL_ONLY);
Cache<K, V> cache = advancedManager.createCache("test");
```

### 从Advanced到Spring:
```java
// 之前
AdvancedCacheManager advancedManager = new AdvancedCacheManager();

// 迁移后 - Spring配置
@Bean
public SpringBootCacheManager cacheManager(CascadeCacheConfig config,
                                          RedissonClient redissonClient) {
    return new SpringBootCacheManager(config, redissonClient);
}
```

## 性能对比

| 管理器 | 启动时间 | 内存占用 | 功能丰富度 | 可扩展性 |
|--------|----------|----------|------------|----------|
| Simple | 最快 | 最低 | 基础 | 低 |
| Factory | 中等 | 中等 | 中等 | 高 |
| Spring | 较慢 | 较高 | 最丰富 | 最高 |

## 总结

三种缓存管理器各有优势，选择时应该根据项目的具体需求：

1. **SimpleCacheManager**: 适合简单场景，追求轻量和可控
2. **AdvancedCacheManager**: 适合复杂场景，需要智能化管理
3. **SpringBootCacheManager**: 适合Spring Boot项目，享受完整的企业级特性

在Spring Boot项目中，强烈推荐使用SpringBootCacheManager，它提供了最完整的功能和最佳的Spring集成体验。