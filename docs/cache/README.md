# Cascade Cache 监控与可观测性

相关文档

- 架构与功能设计: [ARCHITECTURE_AND_FUNCTIONAL_DESIGN.md](./ARCHITECTURE_AND_FUNCTIONAL_DESIGN.md)
- 类的引用关系图: [Class Relations](./diagrams/class-relations.svg)

Cascade Cache V2 提供了完善的监控和可观测性支持，通过指标收集和统计，帮助您了解缓存的运行状态和性能表现。

## 核心组件

### 1. CacheMetricsCollector

指标收集器，负责收集缓存的各项运行指标：

```java
public class CacheMetricsCollector {
    private final MeterRegistry registry;
    private final String cacheName;

    // L1/L2 命中指标
    void incL1Hit();
    void incL2Hit();
    void incMiss();

    // 回填指标
    void incBackfillL1();
    void incBackfillL2();

    // 刷新指标
    void incRefreshSuccess();
    void incRefreshFail();
    void recordRefreshLatency(long latencyMs);

    // 同步指标
    void incSyncPublish();
    void incSyncConsume();
    void recordEventLag(long lagMs);

    // 防护指标
    void incSingleFlightJoin();
    void incDistLockDegrade();
}
```

### 2. CacheStatsSnapshot

缓存统计快照，提供某一时刻的统计信息：

```java
public class CacheStatsSnapshot {
    private final long l1Hit;
    private final long l2Hit;
    private final long miss;
    private final long backfillL1;
    private final long backfillL2;
    private final long refreshSuccess;
    private final long refreshFail;

    // 计算命中率
    public double getHitRate() {
        long total = l1Hit + l2Hit + miss;
        return total == 0 ? 0.0 : (double)(l1Hit + l2Hit) / total;
    }

    // 计算 L1 命中率
    public double getL1HitRate() {
        long total = l1Hit + miss;
        return total == 0 ? 0.0 : (double)l1Hit / total;
    }
}
```

## 指标类型

### 1. 基础指标

- **cascade.cache.l1.hit**: L1 缓存命中次数
- **cascade.cache.l2.hit**: L2 缓存命中次数
- **cascade.cache.miss**: 缓存未命中次数
- **cascade.cache.backfill.l1**: L1 缓存回填次数
- **cascade.cache.backfill.l2**: L2 缓存回填次数

### 2. 刷新指标

- **cascade.cache.refresh.success**: 刷新成功次数
- **cascade.cache.refresh.fail**: 刷新失败次数
- **cascade.cache.refresh.latency**: 刷新延迟（Timer）

### 3. 同步指标

- **cascade.cache.sync.publish**: 失效事件发布次数
- **cascade.cache.sync.publish.retry**: 发布重试次数
- **cascade.cache.sync.publish.reject**: 发布拒绝次数
- **cascade.cache.sync.publish.fail**: 发布失败次数
- **cascade.cache.sync.deadletter**: 死信队列次数
- **cascade.cache.sync.consume**: 失效事件消费次数
- **cascade.cache.sync.consume.fail**: 消费失败次数
- **cascade.cache.sync.update.fallback**: 更新回退次数
- **cascade.cache.sync.event.lag**: 事件延迟（DistributionSummary）

### 4. 防护指标

- **cascade.cache.singleflight.join**: SingleFlight 合并次数
- **cascade.cache.distlock.degrade**: 分布式锁降级次数

## 配置

### 启用指标收集

```yaml
cascade:
  cache:
    l1:
      record-stats: true  # 启用 L1 统计
```

### Micrometer 集成

```java
@Configuration
public class MetricsConfig {
    @Bean
    public MeterRegistry meterRegistry() {
        return new SimpleMeterRegistry();
    }
}
```

### 查看指标

```java
@Autowired
private CacheManager cacheManager;

public void printMetrics() {
    Cache<String, User> cache = cacheManager.getCache("userCache");
    if (cache instanceof EngineBackedCache) {
        EngineBackedCache<?, ?> engineCache = (EngineBackedCache<?, ?>) cache;
        CacheStatsSnapshot stats = engineCache.getStats();

        System.out.println("L1 命中率: " + stats.getL1HitRate());
        System.out.println("总命中率: " + stats.getHitRate());
        System.out.println("刷新成功: " + stats.getRefreshSuccess());
        System.out.println("刷新失败: " + stats.getRefreshFail());
    }
}
```

## 使用 Micrometer

### 添加依赖

```xml
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-core</artifactId>
</dependency>
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

### 配置 Prometheus

```yaml
management:
  endpoints:
    web:
      exposure:
        include: prometheus
  metrics:
    export:
      prometheus:
        enabled: true
```

### 查询指标

Prometheus 查询示例：

```promql
# L1 命中率
rate(cascade_cache_l1_hit_total[1m]) / (rate(cascade_cache_l1_hit_total[1m]) + rate(cascade_cache_miss_total[1m]))

# 刷新延迟
cascade_cache_refresh_latency_seconds_max

# 同步事件延迟
cascade_cache_sync_event_lag_max
```

## 性能考虑

1. **指标收集开销**：指标收集本身有一定开销，但非常小（原子操作）
2. **MeterRegistry 为空时降级**：如果没有 MeterRegistry，指标收集器会自动降级为 no-op
3. **异步发布**：指标发布是异步的，不会阻塞缓存操作
4. **合理采样**：对于高频操作，可以使用采样减少指标数量

## 最佳实践

### 1. 监控关键指标

- **命中率**：L1 命中率应 > 80%，总命中率应 > 90%
- **刷新延迟**：刷新延迟应 < 100ms
- **事件延迟**：同步事件延迟应 < 50ms
- **错误率**：刷新失败率应 < 1%

### 2. 设置告警

```yaml
# Prometheus 告警规则
groups:
  - name: cache_alerts
    rules:
      - alert: LowHitRate
        expr: rate(cascade_cache_l1_hit_total[5m]) / rate(cascade_cache_l1_hit_total[5m]) + rate(cascade_cache_miss_total[5m]) < 0.8
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Low L1 hit rate"

      - alert: HighRefreshLatency
        expr: cascade_cache_refresh_latency_seconds_max > 0.1
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "High refresh latency"
```

### 3. 定期检查

- 每天检查命中率趋势
- 每周检查刷新延迟趋势
- 每月检查错误率趋势

## 示例

完整的监控示例：

```java
@Component
public class CacheMonitor {
    @Autowired
    private CacheManager cacheManager;

    @Scheduled(cron = "0 */5 * * * ?")
    public void monitorCache() {
        EngineBackedCache<?, ?> cache = getEngineCache("userCache");
        if (cache != null) {
            CacheStatsSnapshot stats = cache.getStats();

            log.info("Cache metrics:");
            log.info("  L1 hit rate: {}", stats.getL1HitRate());
            log.info("  Total hit rate: {}", stats.getHitRate());
            log.info("  Refresh success: {}", stats.getRefreshSuccess());
            log.info("  Refresh fail: {}", stats.getRefreshFail());

            // 检查是否需要告警
            if (stats.getL1HitRate() < 0.8) {
                log.warn("Low L1 hit rate detected!");
            }
        }
    }

    private EngineBackedCache<?, ?> getEngineCache(String cacheName) {
        Cache<?, ?> cache = cacheManager.getCache(cacheName);
        if (cache instanceof EngineBackedCache) {
            return (EngineBackedCache<?, ?>) cache;
        }
        return null;
    }
}
```
