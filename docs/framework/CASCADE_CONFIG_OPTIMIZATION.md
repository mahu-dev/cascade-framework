# Cascade配置类优化建议

## 当前问题

1. **配置类重复**：CascadeCacheProperties和CascadeCacheConfig有很多重复的属性定义
2. **转换复杂**：在AutoConfiguration中需要手动转换两个配置类
3. **维护成本**：两个配置类需要同步维护

## 优化方案

### 方案1：统一配置类 + 适配器模式

```java
// 保留CascadeCacheConfig作为核心配置
// CascadeCacheProperties继承或组合CascadeCacheConfig

@ConfigurationProperties(prefix = "cascade.cache")
public class CascadeCacheProperties extends CascadeCacheConfig {
    // 只需要添加Spring Boot特有的配置
    private Map<String, CascadeCacheConfig> caches;
}
```

### 方案2：配置转换器模式

```java
// 创建专门的转换器
@Component
public class CacheConfigConverter {
    public CascadeCacheConfig convert(CascadeCacheProperties properties) {
        // 转换逻辑
    }
}
```

### 方案3：注解驱动配置

```java
// 使用注解简化配置绑定
@ConfigurationPropertiesBinding
@Component
public class CascadeCacheConfigBinder implements Converter<CascadeCacheProperties, CascadeCacheConfig> {
    // 自动转换
}
```

## 推荐方案

建议采用**方案1**，因为：
1. 减少代码重复
2. 简化维护
3. 保持向后兼容性
4. 利用继承的多态特性