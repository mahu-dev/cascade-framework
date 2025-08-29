# Cascade缓存管理器当前状态

## 当前三种缓存管理器

### 1. SimpleCacheManager ✅
- **位置**: `cascade-cache/src/main/java/io/github/cascade/cache/manager/SimpleCacheManager.java`
- **状态**: 已重构完成
- **特点**: 基础缓存管理器，轻量级实现
- **适用**: 简单项目，完全手动控制

### 2. AdvancedCacheManager ✅
- **位置**: `cascade-cache/src/main/java/io/github/cascade/cache/manager/AdvancedCacheManager.java`
- **状态**: 已重构完成 (原名: FactoryCascadeCacheManager)
- **特点**: 智能工厂模式，自动环境检测
- **适用**: 中大型项目，需要智能化管理

### 3. SpringBootCacheManager ✅
- **位置**: `cascade-cache/src/main/java/io/github/cascade/cache/manager/SpringBootCacheManager.java`
- **状态**: 重构完成，位置合理
- **特点**: Spring Boot 深度集成，自动配置
- **适用**: Spring Boot 企业级应用

### 4. CascadeCacheManager (已废弃) ❌
- **位置**: `cascade-autoconfigure/src/main/java/io/github/cascade/autoconfigure/CascadeCacheManager.java`
- **状态**: 标记为 @Deprecated，计划在v2.0中移除
- **替代**: 使用 SpringBootCacheManager 替代

## 分包问题

### 现状
```
cascade-cache/manager/
├── SimpleCacheManager.java       ✅ 位置合理
├── AdvancedCacheManager.java     ✅ 位置合理
└── SpringBootCacheManager.java   ✅ 位置合理

cascade-autoconfigure/
└── CascadeCacheManager.java      ⚠️ 已废弃，待删除
```

### 目标结构 (已实现)
```
cascade-cache/manager/
├── SimpleCacheManager.java       # 基础管理器
├── AdvancedCacheManager.java     # 智能管理器
└── SpringBootCacheManager.java   # Spring集成管理器 ✅

cascade-autoconfigure/
├── CascadeCacheAutoConfiguration.java   # 纯自动配置类
└── CascadeCacheManager.java             # 已废弃，计划删除
```

## 命名演化历程

| 阶段 | 基础版 | 智能版 | Spring版 |
|------|--------|--------|----------|
| **原始** | SimpleCascadeCacheManager | FactoryCascadeCacheManager | CascadeCacheManager |
| **当前** | SimpleCacheManager ✅ | AdvancedCacheManager ✅ | CascadeCacheManager ⚠️ |
| **目标** | SimpleCacheManager | AdvancedCacheManager | SpringBootCacheManager |

## 下一步行动

### 需要做的事情
1. **移动文件**: 将 `CascadeCacheManager` 从 `autoconfigure` 移动到 `cascade-cache/manager`
2. **重命名**: `CascadeCacheManager` → `SpringBootCacheManager`
3. **更新引用**: 更新 `CascadeCacheAutoConfiguration` 中的引用
4. **更新文档**: 更新相关使用文档和示例

### 为什么要这样做？
1. **架构统一**: 所有缓存管理器在同一个模块下
2. **职责分离**: autoconfigure 只负责自动配置，不包含业务逻辑类
3. **命名清晰**: SpringBootCacheManager 明确表示这是 Spring Boot 专用版本
4. **维护方便**: 相关功能集中管理

## 兼容性考虑

### 保持向后兼容
- 可以在 autoconfigure 中提供别名 Bean
- 添加 `@Deprecated` 注解引导迁移
- 提供详细的迁移文档

### 示例配置
```java
// 新的Bean定义
@Bean
@ConditionalOnMissingBean
public SpringBootCacheManager springBootCacheManager(CascadeCacheConfig config,
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

这样的重构将使整个架构更加清晰和易于理解。