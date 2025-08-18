# SyncableMultiLevelCascadeCache 组件详细说明

## 📋 组件概述

`SyncableMultiLevelCascadeCache`是Cascade框架的核心缓存实现组件，它集成了多级缓存、自动加载、异步操作和分布式同步等企业级缓存能力于一体。该组件采用L1(本地缓存) + L2(分布式缓存)的分层架构，通过Redis Pub/Sub实现跨节点的缓存同步。

## 🏗️ 架构设计

### 整体架构图

```
┌─────────────────────────────────────────────────────────────┐
│                SyncableMultiLevelCascadeCache               │
├─────────────────────────────────────────────────────────────┤
│  接口实现:                                                   │
│  • TieredCache<K,V>    - 多级缓存操作                        │
│  • LoadingCache<K,V>   - 自动加载功能                        │
│  • AsyncCache<K,V>     - 异步操作支持                        │
│  • CacheSyncListener   - 分布式同步监听                       │
├─────────────────────────────────────────────────────────────┤
│  核心组件:                                                   │
│  ┌─────────────────┐  ┌─────────────────┐                    │
│  │ L1: Caffeine    │  │ L2: Redis       │                    │
│  │ 本地高速缓存     │  │ 分布式缓存       │                    │
│  │ 毫秒级访问       │  │ 持久化存储       │                    │
│  └─────────────────┘  └─────────────────┘                    │
│  ┌─────────────────┐  ┌─────────────────┐                    │
│  │ CacheLoader     │  │ SyncManager     │                    │
│  │ 自动数据加载     │  │ 同步事件管理     │                    │
│  └─────────────────┘  └─────────────────┘                    │
└─────────────────────────────────────────────────────────────┘
```

### 数据流转模式

```
读取流程:
GET(key) → L1缓存 → L2缓存 → CacheLoader → 存储到L1&L2
         ↓        ↓        ↓
       命中返回  命中+提升   加载新数据

写入流程:
PUT(key,value) → L1缓存 & L2缓存 → 发布同步事件 → 其他节点L1失效
              ↓
            本地存储成功

同步流程:
节点A操作 → Redis Pub/Sub → 节点B/C/D接收 → 更新L1缓存
```

## 🎯 核心功能

### 1. 多级缓存管理

#### L1缓存特性
- **技术栈**: Caffeine (高性能本地缓存)
- **访问速度**: 纳秒级，极致性能
- **容量限制**: 内存受限，适合热点数据
- **生命周期**: 进程级别

#### L2缓存特性  
- **技术栈**: Redis (分布式缓存)
- **访问速度**: 毫秒级，网络延迟
- **容量限制**: 可扩展，适合大数据集
- **生命周期**: 集群级别，持久化

#### 缓存层级操作

```java
// 按层级获取数据
V l1Value = cache.get(key, CacheTier.L1);    // 仅从L1获取
V l2Value = cache.get(key, CacheTier.L2);    // 仅从L2获取
V value = cache.get(key);                    // 智能多级获取

// 数据层级迁移
cache.promote(key);      // 从L2提升到L1
cache.demote(key);       // 从L1降级到L2
cache.sync(key);         // 同步L1和L2数据
```

### 2. 自动加载机制

#### CacheLoader集成
组件内置`CacheLoader`支持，实现缓存未命中时的自动数据加载：

```java
// 单个数据加载
private V loadValue(K key) {
    // 防重复加载检查
    if (currentlyLoading.contains(key)) {
        return null; // 其他线程正在加载
    }
    
    // 加载状态管理
    currentlyLoading.add(key);
    loadingStats.recordActiveLoadStart();
    
    long startTime = System.nanoTime();
    try {
        // 调用CacheLoader加载数据
        V value = cacheLoader.load(key);
        if (value != null) {
            put(key, value); // 自动存储到缓存
            loadingStats.recordLoadSuccess();
        }
        return value;
    } catch (Exception e) {
        loadingStats.recordLoadFailure();
        throw new RuntimeException("Failed to load value for key: " + key, e);
    } finally {
        // 清理状态
        currentlyLoading.remove(key);
        loadingStats.recordActiveLoadEnd();
        loadingStats.recordLoadTime(System.nanoTime() - startTime);
    }
}
```

#### 批量加载优化
支持批量数据加载，避免N+1查询问题：

```java
// 批量加载实现
private Map<K, V> loadValues(Set<K> keys) {
    // 过滤正在加载的键
    Set<K> keysToLoad = keys.stream()
        .filter(k -> !currentlyLoading.contains(k))
        .collect(Collectors.toSet());
    
    if (keysToLoad.isEmpty()) {
        return Map.of();
    }
    
    // 批量加载
    try {
        Map<K, V> loadedValues = cacheLoader.loadAll(keysToLoad);
        if (loadedValues != null && !loadedValues.isEmpty()) {
            putAll(loadedValues); // 批量存储
        }
        return loadedValues;
    } finally {
        // 清理加载状态
        keysToLoad.forEach(currentlyLoading::remove);
    }
}
```

### 3. 分布式缓存同步

#### 同步事件机制
通过Redis Pub/Sub实现分布式节点间的缓存同步：

```java
// 发布同步事件
private void publishSyncEvent(CacheSyncEvent event) {
    if (enableSync && syncManager != null && syncManager.isRunning()) {
        try {
            syncManager.publishEvent(event);
        } catch (Exception e) {
            log.warn("Failed to publish sync event: {}", event, e);
        }
    }
}

// 处理同步事件
@Override
public void onSyncEvent(CacheSyncEvent event) {
    try {
        switch (event.getOperation()) {
            case PUT -> handlePutSync(event);      // 同步写入
            case EVICT -> handleEvictSync(event);  // 同步删除
            case CLEAR -> handleClearSync(event);  // 同步清空
            case REFRESH -> handleRefreshSync(event); // 同步刷新
        }
    } catch (Exception e) {
        log.error("Error handling sync event: {}", event, e);
    }
}
```

#### 同步策略
- **PUT操作**: 只同步到其他节点的L1缓存，避免L2重复写入
- **EVICT操作**: 同时失效其他节点的L1缓存
- **CLEAR操作**: 清空其他节点的L1缓存
- **节点识别**: 通过nodeId避免自己同步给自己

### 4. 异步操作支持

#### 完整异步API
所有缓存操作都提供异步版本：

```java
// 异步读取
@Override
public CompletableFuture<V> getAsync(K key) {
    return CompletableFuture.supplyAsync(() -> get(key), executor);
}

// 异步写入
@Override
public CompletableFuture<Void> putAsync(K key, V value) {
    return CompletableFuture.runAsync(() -> put(key, value), executor);
}

// 异步批量操作
@Override
public CompletableFuture<Map<K, V>> getAllAsync(Set<K> keys) {
    return CompletableFuture.supplyAsync(() -> getAll(keys), executor);
}

// 异步刷新
@Override
public CompletableFuture<Void> refreshAsync(K key) {
    return CompletableFuture.runAsync(() -> refresh(key), executor);
}
```

#### 自定义执行器
默认使用`ForkJoinPool.commonPool()`，支持自定义线程池以优化性能。

## 📊 性能优化特性

### 1. 智能缓存策略

#### 多级命中优化
```java
@Override
public V get(K key) {
    // 1. L1缓存快速查找
    V value = l1Cache.get(key);
    if (value != null) {
        return value; // L1命中，最快返回
    }
    
    // 2. L2缓存查找
    if (l2Cache != null) {
        value = l2Cache.get(key);
        if (value != null) {
            l1Cache.put(key, value); // 自动提升到L1
            return value;
        }
    }
    
    // 3. 自动加载
    if (cacheLoader != null) {
        return loadValue(key);
    }
    
    return null;
}
```

#### 批量操作优化
```java
@Override
public Map<K, V> getAll(Set<K> keys) {
    Map<K, V> result = new HashMap<>();
    Set<K> missingKeys = new HashSet<>(keys);
    
    // 1. 批量从L1获取
    Map<K, V> l1Results = l1Cache.getAll(keys);
    result.putAll(l1Results);
    missingKeys.removeAll(l1Results.keySet());
    
    // 2. 批量从L2获取剩余键
    if (!missingKeys.isEmpty() && l2Cache != null) {
        Map<K, V> l2Results = l2Cache.getAll(missingKeys);
        result.putAll(l2Results);
        l1Cache.putAll(l2Results); // 批量提升到L1
        missingKeys.removeAll(l2Results.keySet());
    }
    
    // 3. 批量加载剩余键
    if (!missingKeys.isEmpty() && cacheLoader != null) {
        Map<K, V> loadedValues = loadValues(missingKeys);
        result.putAll(loadedValues);
    }
    
    return result;
}
```

### 2. 并发控制

#### 防重复加载
使用`ConcurrentHashMap.newKeySet()`管理正在加载的键，避免相同数据的并发加载：

```java
private final Set<K> currentlyLoading = ConcurrentHashMap.newKeySet();

private V loadValue(K key) {
    // 检查是否已在加载中
    if (currentlyLoading.contains(key)) {
        return null; // 等待其他线程完成
    }
    
    // 标记正在加载
    currentlyLoading.add(key);
    try {
        // 执行加载逻辑
        return doLoad(key);
    } finally {
        // 清理标记
        currentlyLoading.remove(key);
    }
}
```

#### 加载取消机制
支持取消正在进行的加载操作：

```java
@Override
public boolean cancelLoading(K key) {
    if (currentlyLoading.remove(key)) {
        loadingStats.recordLoadCancel();
        loadingStats.recordActiveLoadEnd();
        return true;
    }
    return false;
}

@Override
public int cancelAllLoading() {
    int count = currentlyLoading.size();
    currentlyLoading.clear();
    return count;
}
```

### 3. 异常处理与降级

#### Redis故障降级
当Redis不可用时，自动降级到L1缓存：

```java
private void handleRedisException(String operation, Object key, Exception e) {
    if (e instanceof RedisConnectionException) {
        log.warn("Redis connection error during {} operation for key {}: {}", 
            operation, key, e.getMessage());
        // 继续使用L1缓存，不影响业务
    } else if (e instanceof RedisTimeoutException) {
        log.warn("Redis timeout during {} operation for key {}", operation, key);
        // 记录超时但不抛出异常
    }
    // 其他异常类型的处理...
}
```

#### 加载失败处理
支持加载失败时的优雅降级：

```java
private V loadValue(K key) {
    try {
        V value = cacheLoader.load(key);
        loadingStats.recordLoadSuccess();
        return value;
    } catch (Exception e) {
        loadingStats.recordLoadFailure();
        
        // 尝试返回过期的缓存值
        V oldValue = l1Cache.get(key);
        if (oldValue != null) {
            log.warn("Load failed for key {}, returning stale value", key, e);
            return oldValue;
        }
        
        throw new RuntimeException("Failed to load value for key: " + key, e);
    }
}
```

## 📈 监控与统计

### 1. 加载统计信息

组件提供详细的加载性能统计：

```java
// 获取加载统计
LoadingStats stats = cache.getLoadingStats();

// 统计信息包括:
- 加载总次数
- 成功/失败次数  
- 平均加载时间
- 当前活跃加载数
- 超时次数
- 取消次数
- 批量加载统计
```

### 2. 缓存统计信息

按层级提供缓存统计：

```java
// 获取L1缓存统计
CacheStats l1Stats = cache.getStats(CacheTier.L1);

// 获取L2缓存统计  
CacheStats l2Stats = cache.getStats(CacheTier.L2);

// 获取所有层级统计
Map<CacheTier, CacheStats> allStats = cache.getAllStats();
```

### 3. 实时状态监控

```java
// 检查加载状态
boolean isLoading = cache.isLoading(key);
Set<K> loadingKeys = cache.getLoadingKeys();

// 检查层级可用性
boolean l1Available = cache.isTierAvailable(CacheTier.L1);
boolean l2Available = cache.isTierAvailable(CacheTier.L2);

// 检查同步状态
boolean syncEnabled = cache.isSyncEnabled();
CacheSyncManager syncManager = cache.getSyncManager();
```

## 🛠️ 生命周期管理

### 组件生命周期

```java
// 1. 初始化
cache.initialize();  // 注册同步监听器

// 2. 启动
cache.start();       // 启动同步管理器

// 3. 运行时操作
cache.get(key);
cache.put(key, value);
cache.refresh(key);

// 4. 停止
cache.stop();        // 停止同步管理器

// 5. 关闭
cache.close();       // 清理资源
```

### 优雅关闭

```java
@Override
public void close() {
    try {
        // 1. 停止接收新的操作
        stop();
        
        // 2. 等待正在进行的加载完成
        cancelAllLoading();
        
        // 3. 清理缓存数据
        if (l1Cache != null) {
            l1Cache.clear();
        }
        if (l2Cache != null) {
            l2Cache.clear();
        }
        
        log.info("Cache {} closed successfully", name);
    } catch (Exception e) {
        log.error("Error closing cache {}", name, e);
    }
}
```

## 🚀 使用示例

### 1. 基本使用

```java
// 创建缓存实例
SyncableMultiLevelCascadeCache<Long, User> userCache = 
    new SyncableMultiLevelCascadeCache<>(
        "user-cache",           // 缓存名称
        l1Cache,               // Caffeine本地缓存
        l2Cache,               // Redis远程缓存  
        userLoader,            // 用户数据加载器
        syncManager            // 同步管理器
    );

// 初始化并启动
userCache.initialize();
userCache.start();

// 使用缓存
User user = userCache.getUnchecked(userId);      // 自动加载
Map<Long, User> users = userCache.getAll(userIds); // 批量获取
userCache.put(userId, updatedUser);              // 更新缓存
userCache.refresh(userId);                       // 刷新数据
```

### 2. 异步操作

```java
// 异步获取用户
CompletableFuture<User> future = userCache.getAsync(userId);
future.thenAccept(user -> {
    System.out.println("用户加载完成: " + user);
}).exceptionally(throwable -> {
    System.err.println("加载失败: " + throwable.getMessage());
    return null;
});

// 异步批量操作
CompletableFuture<Map<Long, User>> batchFuture = 
    userCache.getAllAsync(Arrays.asList(1L, 2L, 3L));
    
// 异步刷新
CompletableFuture<Void> refreshFuture = userCache.refreshAsync(userId);
```

### 3. 层级操作

```java
// 按层级查询
User l1User = userCache.get(userId, CacheTier.L1);  // 仅查L1
User l2User = userCache.get(userId, CacheTier.L2);  // 仅查L2

// 数据迁移
userCache.promote(userId);    // L2 → L1
userCache.demote(userId);     // L1 → L2
userCache.sync(userId);       // 同步L1和L2

// 批量迁移
Set<Long> hotUserIds = getHotUserIds();
userCache.promoteAll(hotUserIds);   // 批量提升热点数据
```

### 4. 监控和管理

```java
// 获取统计信息
LoadingStats loadingStats = userCache.getLoadingStats();
System.out.println("加载成功率: " + loadingStats.getLoadSuccessRate());
System.out.println("平均加载时间: " + loadingStats.getAverageLoadTime());

// 检查状态
if (userCache.isLoading(userId)) {
    System.out.println("用户 " + userId + " 正在加载中...");
}

// 取消加载
boolean cancelled = userCache.cancelLoading(userId);
if (cancelled) {
    System.out.println("已取消用户 " + userId + " 的加载");
}

// 获取缓存统计
Map<CacheTier, CacheStats> stats = userCache.getAllStats();
stats.forEach((tier, stat) -> {
    System.out.println(tier + " 缓存命中率: " + stat.hitRate());
});
```

## ⚡ 性能调优建议

### 1. L1缓存配置
```java
// 合理设置L1缓存大小
l1Cache.maximumSize(10000);           // 根据内存情况调整
l1Cache.expireAfterWrite(Duration.ofMinutes(30)); // 适当的过期时间
```

### 2. 批量操作优化
```java
// 优先使用批量操作
Map<Long, User> users = cache.getAll(userIds);  // ✅ 好
// 避免循环单个操作
for (Long id : userIds) {
    User user = cache.get(id);  // ❌ 差
}
```

### 3. CacheLoader优化
```java
public class OptimizedUserLoader implements CacheLoader<Long, User> {
    @Override
    public Map<Long, User> loadAll(Set<Long> userIds) throws Exception {
        // ✅ 一次SQL查询获取所有用户
        List<User> users = userRepository.findAllById(userIds);
        return users.stream().collect(Collectors.toMap(User::getId, u -> u));
    }
    
    // ❌ 避免N+1查询
    // for (Long id : userIds) { userRepository.findById(id); }
}
```

### 4. 异步操作使用
```java
// 对于耗时操作使用异步
CompletableFuture<Void> future = cache.preloadAllAsync(allUserIds);
future.thenRun(() -> {
    System.out.println("预热完成");
});

// 不阻塞主线程
cache.refreshAsync(userId); // 后台刷新
```

## 🔍 故障排查

### 1. 常见问题

#### 缓存未命中率高
```java
// 检查L1缓存配置
CacheStats l1Stats = cache.getStats(CacheTier.L1);
if (l1Stats.hitRate() < 0.8) {
    // 可能需要增加L1缓存大小或调整过期时间
}

// 检查加载器性能
LoadingStats loadingStats = cache.getLoadingStats();
if (loadingStats.getAverageLoadTime() > 100_000_000) { // 100ms
    // 加载器性能需要优化
}
```

#### 同步延迟问题
```java
// 检查同步管理器状态
CacheSyncManager syncManager = cache.getSyncManager();
if (!syncManager.isRunning()) {
    // 同步服务未启动
    syncManager.start();
}

// 检查Redis连接
try {
    cache.put("test", "value");
} catch (Exception e) {
    // Redis连接异常
    log.error("Redis连接问题", e);
}
```

### 2. 性能诊断

```java
// 启用详细统计
cache.getStats().recordStats(true);

// 定期输出统计信息
@Scheduled(fixedRate = 60000) // 每分钟
public void logCacheStats() {
    LoadingStats stats = userCache.getLoadingStats();
    log.info("缓存统计 - 成功: {}, 失败: {}, 平均耗时: {}ms", 
        stats.getLoadSuccessCount(),
        stats.getLoadFailureCount(), 
        stats.getAverageLoadTime() / 1_000_000);
}
```

## 📝 最佳实践

### 1. 缓存设计原则
- **热点数据优先**: 将访问频繁的数据保持在L1缓存
- **批量操作**: 尽量使用批量API减少网络开销
- **合理过期**: 根据数据特性设置适当的TTL
- **监控告警**: 建立完善的缓存监控体系

### 2. 错误处理策略
- **优雅降级**: Redis故障时继续使用L1缓存
- **重试机制**: 对临时故障实施指数退避重试
- **熔断保护**: 避免缓存故障影响核心业务

### 3. 同步策略
- **最小化同步**: 只同步必要的缓存失效事件
- **幂等设计**: 确保同步操作的幂等性
- **监控延迟**: 监控同步事件的传播延迟

## 🎯 总结

`SyncableMultiLevelCascadeCache`是一个功能完整的企业级缓存解决方案，它将多级缓存、自动加载、异步操作和分布式同步等复杂特性统一封装，为应用提供：

### 核心价值
- **极致性能**: L1+L2分层架构，兼顾速度与容量
- **自动化**: 自动加载、自动同步、自动统计
- **高可用**: 多层降级、异常恢复、故障隔离  
- **易使用**: 统一API、丰富配置、详细监控

### 适用场景
- 高并发Web应用的数据缓存
- 分布式系统的缓存一致性
- 微服务架构的跨服务缓存
- 大数据应用的热点数据缓存

这个组件体现了现代缓存架构的最佳实践，是构建高性能分布式应用的重要基础设施。