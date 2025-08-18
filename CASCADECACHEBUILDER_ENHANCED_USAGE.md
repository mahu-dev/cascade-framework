# CascadeCacheBuilder 增强功能使用指南

## 🎯 概述

本文档展示了`CascadeCacheBuilder`中新完善的功能，这些功能之前只是定义了字段但没有实际使用。现在已经完全集成到缓存构建流程中。

## ✨ 新完善的功能

### 1. **自定义异步执行器 (executor)**

#### 功能说明
允许用户自定义异步操作的线程池执行器，用于所有异步缓存操作。

#### 使用示例
```java
// 创建自定义线程池
ThreadPoolExecutor customExecutor = new ThreadPoolExecutor(
    4,                          // 核心线程数
    8,                          // 最大线程数
    60L, TimeUnit.SECONDS,      // 线程存活时间
    new LinkedBlockingQueue<>(1000),  // 任务队列
    r -> new Thread(r, "CascadeCache-" + r.hashCode())  // 线程工厂
);

// 使用自定义执行器构建缓存
Cache<String, User> cache = CascadeCacheBuilder.<String, User>newBuilder("users")
    .loader(userLoader)
    .executor(customExecutor)    // ✅ 设置自定义执行器
    .build();

// 异步操作将使用自定义线程池
CompletableFuture<User> future = cache.getAsync("user123");
CompletableFuture<Void> putFuture = cache.putAsync("user123", user);
```

#### 默认行为
```java
// 如果不设置executor，默认使用ForkJoinPool.commonPool()
Cache<String, User> cache = CascadeCacheBuilder.<String, User>newBuilder("users")
    .loader(userLoader)
    // .executor(...) // 不设置，使用默认
    .build();
```

### 2. **自动刷新功能 (refreshAfterWrite)**

#### 功能说明
配置缓存在写入后自动刷新的时间间隔，适用于需要定期更新的热点数据。

#### 使用示例
```java
// 配置5分钟后自动刷新
Cache<String, Config> configCache = CascadeCacheBuilder.<String, Config>newBuilder("config")
    .loader(configLoader)
    .refreshAfterWrite(Duration.ofMinutes(5))  // ✅ 5分钟后自动刷新
    .expireAfterWrite(Duration.ofHours(1))     // 1小时后过期
    .build();

// 数据会在写入5分钟后自动在后台刷新
configCache.put("app.version", "1.0.0");
// 5分钟后，系统会自动调用configLoader重新加载这个配置
```

#### 与过期时间的区别
```java
Cache<String, Data> cache = CascadeCacheBuilder.<String, Data>newBuilder("data")
    .loader(dataLoader)
    .expireAfterWrite(Duration.ofHours(2))    // 2小时后过期删除
    .refreshAfterWrite(Duration.ofMinutes(30)) // 30分钟后后台刷新，但不删除旧数据
    .build();

// 时间线：
// 0分钟：put(key, value)
// 30分钟：后台自动刷新，但如果刷新失败，旧数据仍然可用
// 120分钟：数据过期，必须重新加载
```

### 3. **智能随机TTL防雪崩 (randomTtlJitterRatio)**

#### 功能说明
通过比例自动计算TTL抖动范围，避免缓存雪崩问题。

#### 基本使用
```java
Cache<String, User> cache = CascadeCacheBuilder.<String, User>newBuilder("users")
    .loader(userLoader)
    .enableProtection(true)
    .randomTtl(true, Duration.ofHours(2), 0.1)  // ✅ 基础TTL 2小时，10%抖动
    .build();

// 实际效果：
// 基础TTL: 2小时 = 7200秒
// 抖动范围: 7200 * 0.1 = 720秒 (12分钟)
// 实际TTL: 1小时48分钟 到 2小时12分钟之间随机
```

#### 多种配置方式
```java
// 方式1：使用比例计算抖动
Cache<String, User> cache1 = CascadeCacheBuilder.<String, User>newBuilder("users1")
    .enableProtection(true)
    .randomTtl(true, Duration.ofHours(1), 0.15)  // 1小时基础，15%抖动
    .build();

// 方式2：使用固定抖动范围
Cache<String, User> cache2 = CascadeCacheBuilder.<String, User>newBuilder("users2")
    .enableProtection(true)
    .randomTtl(true, Duration.ofHours(1), Duration.ofMinutes(10))  // 1小时基础，±10分钟抖动
    .build();

// 方式3：只设置比例，使用默认基础TTL
Cache<String, User> cache3 = CascadeCacheBuilder.<String, User>newBuilder("users3")
    .enableProtection(true)
    .randomTtl(true, 0.2)  // 使用默认基础TTL，20%抖动
    .build();
```

### 4. **全局统计开关 (recordStats)**

#### 功能说明
统一控制缓存统计信息的收集，影响L1缓存的统计行为。

#### 使用示例
```java
// 启用全局统计
Cache<String, User> cache = CascadeCacheBuilder.<String, User>newBuilder("users")
    .loader(userLoader)
    .recordStats(true)          // ✅ 启用全局统计
    .l1RecordStats(false)       // L1专用统计关闭
    .build();
// 结果：L1缓存统计=true (全局优先级更高)

// 详细统计配置
Cache<String, Product> productCache = CascadeCacheBuilder.<String, Product>newBuilder("products")
    .loader(productLoader)
    .recordStats(true)          // 全局统计开启
    .l1RecordStats(true)        // L1专用统计也开启
    .build();
// 结果：L1缓存统计=true (两个都是true)
```

#### 统计信息获取
```java
// 获取缓存统计
CacheStats stats = cache.getStats();
System.out.println("命中率: " + stats.hitRate());
System.out.println("命中次数: " + stats.hitCount());
System.out.println("未命中次数: " + stats.missCount());
System.out.println("加载次数: " + stats.loadCount());
System.out.println("平均加载时间: " + stats.averageLoadPenalty() + "ns");

// 分层级统计
if (cache instanceof SyncableMultiLevelCascadeCache<String, User> multiCache) {
    Map<CacheTier, CacheStats> allStats = multiCache.getAllStats();
    CacheStats l1Stats = allStats.get(CacheTier.L1);
    CacheStats l2Stats = allStats.get(CacheTier.L2);
    
    System.out.println("L1命中率: " + (l1Stats != null ? l1Stats.hitRate() : "N/A"));
    System.out.println("L2命中率: " + (l2Stats != null ? l2Stats.hitRate() : "N/A"));
}
```

## 🚀 完整配置示例

### 高性能Web应用缓存
```java
// 创建自定义线程池用于异步操作
ForkJoinPool customPool = new ForkJoinPool(8);

// 构建高性能缓存
Cache<Long, User> userCache = CascadeCacheBuilder.<Long, User>newBuilder("users")
    // 基础配置
    .loader(userLoader)
    .executor(customPool)
    .maximumSize(50000)
    
    // 过期策略
    .expireAfterWrite(Duration.ofHours(4))      // 4小时后过期
    .expireAfterAccess(Duration.ofHours(1))     // 1小时无访问后过期
    .refreshAfterWrite(Duration.ofMinutes(30))   // 30分钟后后台刷新
    
    // L1本地缓存
    .enableL1Cache(true)
    .l1MaximumSize(10000L)
    .l1ExpireAfterWrite(Duration.ofMinutes(30))  // L1更快过期
    
    // L2分布式缓存
    .enableL2Cache(true, redissonClient)
    .l2KeyPrefix("app:users:")
    .l2DefaultTtl(Duration.ofHours(6))
    
    // 分布式同步
    .enableSync(true)
    
    // 防护机制
    .enableProtection(true)
    .bloomFilter(100000L, 0.01)                  // 10万预期，1%误判率
    .randomTtl(true, Duration.ofHours(4), 0.15)  // 防雪崩：4小时基础，15%抖动
    .distributedLock(true, Duration.ofSeconds(30), "user:lock:") // 防击穿
    
    // 监控统计
    .recordStats(true)
    
    .build();
```

### 配置数据缓存
```java
Cache<String, String> configCache = CascadeCacheBuilder.<String, String>newBuilder("config")
    .loader(configLoader)
    .executor(Executors.newFixedThreadPool(2))   // 专用线程池
    
    // 配置特点：高频刷新，长期有效
    .maximumSize(5000)
    .expireAfterWrite(Duration.ofDays(1))        // 1天过期
    .refreshAfterWrite(Duration.ofMinutes(10))    // 10分钟刷新
    
    // 只使用本地缓存，配置数据通常不大
    .enableL1Cache(true)
    .l1MaximumSize(5000L)
    
    // 轻量级防护
    .enableProtection(true)
    .randomTtl(true, Duration.ofDays(1), 0.05)   // 1天基础，5%抖动
    
    .recordStats(true)
    .build();
```

### API响应缓存
```java
Cache<String, ApiResponse> apiCache = CascadeCacheBuilder.<String, ApiResponse>newBuilder("api-responses")
    .loader(apiResponseLoader)
    .executor(ForkJoinPool.commonPool())
    
    // API响应特点：短期有效，需要快速刷新
    .maximumSize(20000)
    .expireAfterWrite(Duration.ofMinutes(15))    // 15分钟过期
    .refreshAfterWrite(Duration.ofMinutes(5))    // 5分钟刷新
    
    // 双层缓存
    .enableL1Cache(true)
    .l1MaximumSize(5000L)
    .l1ExpireAfterWrite(Duration.ofMinutes(5))   // L1更快过期
    
    .enableL2Cache(true, redissonClient)
    .l2DefaultTtl(Duration.ofMinutes(20))
    
    // 强防护（API容易被攻击）
    .enableProtection(true)
    .bloomFilter(50000L, 0.005)                  // 5万预期，0.5%误判率
    .randomTtl(true, Duration.ofMinutes(15), 0.2) // 15分钟基础，20%抖动
    .distributedLock(true, Duration.ofSeconds(10), "api:lock:")
    
    .recordStats(true)
    .build();
```

## 📊 性能监控和调优

### 监控异步操作
```java
// 监控自定义执行器
if (customExecutor instanceof ThreadPoolExecutor tpe) {
    System.out.println("活跃线程: " + tpe.getActiveCount());
    System.out.println("队列大小: " + tpe.getQueue().size());
    System.out.println("完成任务: " + tpe.getCompletedTaskCount());
}

// 监控异步缓存操作
CompletableFuture<User> future = userCache.getAsync("user123");
future.whenComplete((user, throwable) -> {
    if (throwable == null) {
        System.out.println("异步加载成功: " + user);
    } else {
        System.err.println("异步加载失败: " + throwable.getMessage());
    }
});
```

### 刷新效果监控
```java
// 监控刷新统计
CacheStats stats = configCache.getStats();
System.out.println("总加载次数: " + stats.loadCount());
System.out.println("加载时间: " + stats.totalLoadTime() / 1_000_000 + "ms");

// 手动触发刷新测试
configCache.refresh("app.version");
configCache.refreshAll(Set.of("app.version", "app.name", "app.env"));
```

### TTL抖动效果验证
```java
// 验证TTL抖动
SimplifiedCacheProtectionManager.ProtectionStats protectionStats = 
    cache.getSimplifiedProtectionManager().getStats();
    
System.out.println("防雪崩保护次数: " + protectionStats.getAvalancheProtections());
System.out.println("防穿透保护次数: " + protectionStats.getPenetrationProtections());
System.out.println("防击穿保护次数: " + protectionStats.getHotspotProtections());
```

## 💡 最佳实践

### 1. 执行器选择
- **CPU密集型操作**: 使用`ForkJoinPool`
- **IO密集型操作**: 使用`ThreadPoolExecutor`
- **混合操作**: 创建专门的线程池

### 2. 刷新时间设置
- **refreshAfterWrite** < **expireAfterWrite**
- 刷新时间设为过期时间的1/4到1/2
- 考虑数据更新频率和加载成本

### 3. TTL抖动配置
- 一般使用5%-20%的抖动比例
- 高并发场景可以适当提高到30%
- 关键业务数据使用较小抖动（5%-10%）

### 4. 统计开关
- 生产环境建议开启统计用于监控
- 性能极致敏感场景可考虑关闭
- 开发测试环境始终开启

## 🎯 总结

通过完善这些之前未使用的字段，`CascadeCacheBuilder`现在提供了更加完整和强大的缓存配置能力：

- ✅ **自定义执行器**: 优化异步性能
- ✅ **自动刷新**: 保证数据新鲜度
- ✅ **智能防雪崩**: 灵活的TTL抖动策略
- ✅ **统一统计**: 完整的监控能力

这些增强使得Cascade缓存框架更加适用于各种复杂的生产环境场景。