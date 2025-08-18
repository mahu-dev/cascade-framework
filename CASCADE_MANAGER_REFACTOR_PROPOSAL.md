# CascadeCacheManager 分包重构建议

## 当前问题分析

### 1. 当前架构问题
- `CascadeCacheManager` 位于 `cascade-autoconfigure` 模块，与其他两个管理器分离
- 三个管理器命名相似，容易混淆
- autoconfigure 模块包含了具体的业务逻辑类，违反了职责分离原则

### 2. 模块职责混乱
```
cascade-cache/manager/
├── SimpleCacheManager.java             # 基础管理器 (已重命名)
└── AdvancedCacheManager.java           # 工厂管理器 (已重命名)

cascade-autoconfigure/
└── CascadeCacheManager.java            # Spring集成管理器 (位置不合理)
```

## 重构方案

### 方案一：统一分包 + 重命名 (推荐)

```
cascade-cache/src/main/java/io/github/cascade/cache/manager/
├── SimpleCacheManager.java             # 基础管理器 (重命名)
├── FactoryCacheManager.java            # 工厂管理器 (重命名) 
└── SpringBootCacheManager.java         # Spring集成管理器 (移动+重命名)

cascade-autoconfigure/src/main/java/io/github/cascade/autoconfigure/
└── CascadeCacheAutoConfiguration.java  # 只保留自动配置逻辑
```

### 方案二：按集成方式分包

```
cascade-cache/src/main/java/io/github/cascade/cache/manager/
├── core/
│   ├── SimpleCacheManager.java         # 基础管理器
│   └── FactoryCacheManager.java        # 工厂管理器
└── spring/
    └── SpringBootCacheManager.java     # Spring集成管理器

cascade-autoconfigure/
└── CascadeCacheAutoConfiguration.java  # 纯配置类
```

## 具体重构步骤

### 1. 当前状态和要做的事
```bash
# 已完成的重命名
SimpleCascadeCacheManager → SimpleCacheManager ✓
FactoryCascadeCacheManager → AdvancedCacheManager ✓

# 待做：移动Spring集成管理器
cascade-autoconfigure/CascadeCacheManager → cascade-cache/manager/SpringBootCacheManager
```

### 2. 更新自动配置类
```java
@Bean
@ConditionalOnMissingBean
public SpringBootCacheManager cascadeCacheManager(CascadeCacheConfig config,
                                                  RedissonClient redissonClient,
                                                  CascadeCacheProperties properties) {
    return new SpringBootCacheManager(config, redissonClient, properties);
}
```

### 3. 更新依赖注入
```java
// 用户代码中
@Autowired
private SpringBootCacheManager cacheManager;  // 明确的类型
```

## 重构的好处

### 1. 架构更清晰
- 所有缓存管理器统一在 `cascade-cache` 模块
- autoconfigure 模块只负责 Spring 自动配置
- 职责分离更明确

### 2. 命名更清晰
- `SimpleCacheManager`: 基础缓存管理器 (已存在)
- `AdvancedCacheManager`: 高级/智能管理器 (已存在)  
- `SpringBootCacheManager`: Spring Boot 集成管理器 (待创建)

### 3. 依赖关系更合理
```
cascade-autoconfigure
    ↓ (依赖)
cascade-cache
    ↓ (依赖)
cascade-core
```

### 4. 使用更直观
```java
// 明确知道这是 Spring Boot 版本
@Autowired
private SpringBootCacheManager springBootCacheManager;

// 或者使用接口
@Autowired  
private CacheManager cacheManager;  // 会自动注入 SpringBootCacheManager
```

## 向后兼容性

### 1. 提供别名Bean
```java
@Bean
@ConditionalOnMissingBean(name = "cascadeCacheManager")
public SpringBootCacheManager cascadeCacheManager(CascadeCacheConfig config,
                                                  RedissonClient redissonClient, 
                                                  CascadeCacheProperties properties) {
    return new SpringBootCacheManager(config, redissonClient, properties);
}

// 兼容性别名
@Bean
@Primary
public CacheManager cacheManager(SpringBootCacheManager springBootCacheManager) {
    return springBootCacheManager;
}
```

### 2. 添加迁移文档
```markdown
## 迁移指南 v2.0

### 类名变更
- `CascadeCacheManager` → `SpringBootCacheManager`
- `SimpleCascadeCacheManager` → `SimpleCacheManager`  
- `FactoryCascadeCacheManager` → `FactoryCacheManager`

### 包路径变更
- `io.github.cascade.autoconfigure.CascadeCacheManager` → 
  `io.github.cascade.cache.manager.SpringBootCacheManager`
```

## 实施建议

### 阶段一：重命名和移动 (Breaking Change)
1. 创建新的管理器类
2. 标记旧类为 `@Deprecated`
3. 提供迁移指南

### 阶段二：清理 (Next Major Version)
1. 删除废弃的类
2. 更新文档和示例

这样的重构将使架构更加清晰，减少用户的困惑，并且符合模块职责分离的原则。