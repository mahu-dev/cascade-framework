# Cascade Cache Event System

缓存事件系统提供了一个强大的机制来监控和响应缓存操作。通过事件监听器，您可以收集统计信息、记录日志、实现自定义逻辑等。

## 核心组件

### 1. CacheEvent
基础事件类，定义了所有缓存事件的通用属性：
- `cacheName`: 缓存名称
- `eventType`: 事件类型（HIT, MISS, PUT, EVICT, CLEAR, SYNC, ERROR等）
- `timestamp`: 事件时间戳
- `source`: 事件源
- `metadata`: 附加元数据

### 2. CacheOperationEvent
具体的缓存操作事件，继承自CacheEvent，包含：
- `key`: 缓存键
- `value`: 缓存值
- `duration`: 操作耗时
- `success`: 操作是否成功
- `exception`: 异常信息（如果有）

### 3. CacheEventListener
事件监听器接口，定义了处理缓存事件的方法：
```java
public interface CacheEventListener<E extends CacheEvent> {
    void onEvent(E event);
    String getName();
    boolean shouldHandle(E event);
    boolean isActive();
    int getPriority();
}
```

### 4. CacheEventManager
事件管理器，负责：
- 注册和移除事件监听器
- 发布缓存事件
- 管理监听器的生命周期

## 内置监听器

### CacheStatisticsListener
收集缓存统计信息：
- 命中率
- 操作次数
- 平均响应时间
- 最大响应时间

### CacheLoggingListener
记录缓存操作日志：
- 可配置日志级别
- 支持详细日志模式
- 慢操作检测

## 配置

### 启用事件系统
```yaml
cascade:
  cache:
    events:
      enabled: true
      statistics:
        enabled: true
      logging:
        enabled: true
        level: DEBUG
        detail: true
        slow-threshold: 100ms
```

### 自定义监听器
```java
@Component
public class CustomCacheListener implements CacheEventListener<CacheOperationEvent> {
    
    @Override
    public void onEvent(CacheOperationEvent event) {
        // 处理事件逻辑
        if (event.getEventType() == CacheEvent.EventType.CACHE_HIT) {
            // 处理缓存命中
        }
    }
    
    @Override
    public String getName() {
        return "CustomCacheListener";
    }
    
    @Override
    public boolean shouldHandle(CacheOperationEvent event) {
        return true; // 处理所有事件
    }
    
    @Override
    public boolean isActive() {
        return true;
    }
    
    @Override
    public int getPriority() {
        return 100;
    }
}
```

### 程序化注册监听器
```java
@Autowired
private CacheEventManager eventManager;

@PostConstruct
public void init() {
    eventManager.registerListener(new CustomCacheListener());
}
```

## 事件类型

- `CACHE_HIT`: 缓存命中
- `CACHE_MISS`: 缓存未命中
- `CACHE_LOAD`: 缓存加载
- `CACHE_PUT`: 缓存写入
- `CACHE_EVICT`: 缓存驱逐
- `CACHE_CLEAR`: 缓存清空
- `CACHE_SYNC`: 缓存同步
- `CACHE_ERROR`: 缓存错误

## 性能考虑

1. **异步处理**: 事件发布默认是异步的，不会影响缓存操作性能
2. **监听器优先级**: 通过`getPriority()`方法控制监听器执行顺序
3. **条件处理**: 使用`shouldHandle()`方法过滤不需要的事件
4. **异常隔离**: 监听器异常不会影响缓存操作

## 最佳实践

1. **轻量级监听器**: 保持监听器逻辑简单，避免耗时操作
2. **异常处理**: 在监听器中妥善处理异常
3. **资源管理**: 及时清理不需要的监听器
4. **监控指标**: 使用统计监听器收集关键指标
5. **日志记录**: 合理配置日志级别，避免日志过多

## 示例

参考 `CacheEventExample` 类了解完整的使用示例。