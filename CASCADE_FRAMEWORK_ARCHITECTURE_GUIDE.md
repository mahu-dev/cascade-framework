# Cascade Framework 架构与运行原理文档

## 📋 目录

1. [框架概述](#框架概述)
2. [Spring Boot自动配置原理](#spring-boot自动配置原理)
3. [核心组件架构](#核心组件架构)
4. [缓存层级设计](#缓存层级设计)
5. [自动刷新机制](#自动刷新机制)
6. [分布式同步原理](#分布式同步原理)
7. [防护机制详解](#防护机制详解)
8. [配置与使用](#配置与使用)
9. [监控与运维](#监控与运维)
10. [最佳实践](#最佳实践)

---

## 🎯 框架概述

### 设计理念

Cascade是一个基于Redisson深度封装的Spring Boot Starter缓存框架，采用**统一门面模式**和**流式API设计**，为Spring Boot应用提供开箱即用的分布式缓存能力。

### 核心特性

```
📦 Cascade Framework
├── 🏗️  模块化架构        # 插件化组件设计
├── 🚀  零配置启动        # Spring Boot自动配置
├── 🔄  多级缓存         # L1(Local) + L2(Redis)
├── ⚡  自动刷新         # L1/L2智能刷新策略
├── 🌐  分布式同步        # 跨节点缓存一致性
├── 🛡️  三重防护         # 防穿透/雪崩/击穿
├── 📊  监控集成         # Prometheus/Micrometer
└── 🔧  运维友好         # 丰富的管理接口
```

### 模块结构

```
cascade-spring-boot-starter/
├── cascade-core/                    # 核心抽象接口
├── cascade-cache/                   # 缓存实现模块
├── cascade-lock/                    # 分布式锁模块
├── cascade-bloom/                   # 布隆过滤器模块
├── cascade-limiter/                 # 限流器模块
├── cascade-queue/                   # 分布式队列模块
├── cascade-pubsub/                  # 发布订阅模块
├── cascade-autoconfigure/           # 自动配置模块
└── cascade-spring-boot-starter/     # Starter聚合模块
```

---

## 🔧 Spring Boot自动配置原理

### 1. 自动配置触发机制

#### META-INF配置

```
# META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
io.github.cascade.autoconfigure.CascadeCacheAutoConfiguration
io.github.cascade.autoconfigure.CascadeRedissonAutoConfiguration
io.github.cascade.autoconfigure.CascadeLockAutoConfiguration
io.github.cascade.autoconfigure.CascadeBloomAutoConfiguration
```

#### 依赖引入触发

```xml
<dependency>
    <groupId>cc.coderm</groupId>
    <artifactId>cascade-spring-boot-starter</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```

Spring Boot启动时自动扫描`META-INF`配置，加载所有`@AutoConfiguration`类。

### 2. 条件化配置策略

#### CascadeCacheAutoConfiguration

```java
@AutoConfiguration
@ConditionalOnClass({RedissonClient.class, CascadeCacheBuilder.class})
@ConditionalOnProperty(prefix = "cascade.cache", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties({CascadeCacheProperties.class, CascadeRedissonProperties.class})
public class CascadeCacheAutoConfiguration {
    
    @Bean
    @ConditionalOnMissingBean
    public RedissonClient redissonClient(CascadeRedissonProperties properties) {
        // 自动配置Redisson客户端
    }
    
    @Bean
    @ConditionalOnBean(RedissonClient.class)
    public CascadeCacheManager cascadeCacheManager(RedissonClient redissonClient) {
        // 自动配置缓存管理器
    }
}
```

#### 条件注解说明

| 注解 | 作用 | 示例 |
|------|------|------|
| `@ConditionalOnClass` | 类路径存在指定类时生效 | 检查Redisson依赖 |
| `@ConditionalOnProperty` | 配置属性满足条件时生效 | `cascade.cache.enabled=true` |
| `@ConditionalOnBean` | 存在指定Bean时生效 | 依赖RedissonClient |
| `@ConditionalOnMissingBean` | 不存在指定Bean时生效 | 避免重复配置 |

### 3. 配置属性绑定

#### application.yml配置示例

```yaml
cascade:
  cache:
    enabled: true
    default-cache-name: "default"
    l1:
      maximum-size: 10000
      expire-after-write: PT30M
      refresh-after-write: PT10M
    l2:
      enabled: true
      key-prefix: "cascade:"
      default-ttl: PT2H
      refresh-after-write: PT1H
    protection:
      enabled: true
      bloom-filter:
        expected-elements: 100000
        false-positive-rate: 0.01
      random-ttl:
        jitter-ratio: 0.1
      distributed-lock:
        timeout: PT30S
    sync:
      enabled: true
      topic: "cascade:cache:sync"
  
  redisson:
    single-server-config:
      address: "redis://localhost:6379"
      database: 0
      connection-pool-size: 64
      connection-minimum-idle-size: 10
```

#### 配置属性类

```java
@ConfigurationProperties(prefix = "cascade.cache")
@Data
public class CascadeCacheProperties {
    private boolean enabled = true;
    private String defaultCacheName = "default";
    
    private L1Properties l1 = new L1Properties();
    private L2Properties l2 = new L2Properties();
    private ProtectionProperties protection = new ProtectionProperties();
    private SyncProperties sync = new SyncProperties();
    
    @Data
    public static class L1Properties {
        private long maximumSize = 10000;
        private Duration expireAfterWrite;
        private Duration expireAfterAccess;
        private Duration refreshAfterWrite;
        private boolean recordStats = true;
    }
    
    @Data
    public static class L2Properties {
        private boolean enabled = false;
        private String keyPrefix = "cascade:";
        private Duration defaultTtl = Duration.ofHours(1);
        private Duration refreshAfterWrite;
    }
    
    // ... 其他配置类
}
```

---

## 🏗️ 核心组件架构

### 1. 缓存构建器模式

```java
public class CascadeCacheBuilder<K, V> {
    // 构建器模式核心实现
    
    public static <K, V> CascadeCacheBuilder<K, V> newBuilder(String cacheName) {
        return new CascadeCacheBuilder<>(cacheName);
    }
    
    public Cache<K, V> build() {
        validateConfig();
        
        // 1. 构建L1缓存
        SimpleCaffeineLocalTier<K, V> l1Cache = buildL1Cache();
        
        // 2. 构建L2缓存
        RedissonRemoteTier<K, V> l2Cache = enableL2 ? buildL2Cache() : null;
        
        // 3. 构建同步管理器
        CacheSyncManager syncManager = enableSync ? buildSyncManager() : null;
        
        // 4. 创建多级缓存
        SyncableMultiLevelCascadeCache<K, V> cache = new SyncableMultiLevelCascadeCache<>(
                cacheName, l1Cache, l2Cache, cacheLoader, syncManager, enableSync, executor
        );
        
        // 5. 配置防护机制
        if (enableProtection) {
            cache.setSimplifiedProtectionManager(configureSimplifiedProtection());
        }
        
        // 6. 配置自动刷新
        if (l2RefreshAfterWrite != null) {
            cache.setL2RefreshScheduler(l2RefreshAfterWrite);
        }
        
        return cache;
    }
}
```

### 2. 接口层次架构

```
Cache<K, V>                          # 基础缓存接口
├── LoadingCache<K, V>               # 支持CacheLoader的缓存
├── AsyncCache<K, V>                 # 异步缓存操作
├── TieredCache<K, V>                # 多级缓存抽象
└── SyncableMultiLevelCascadeCache   # 具体实现类
```

#### 接口职责分离

```java
// 基础缓存操作
public interface Cache<K, V> {
    V get(K key);
    void put(K key, V value);
    void evict(K key);
    CacheStats getStats();
}

// 加载缓存扩展
public interface LoadingCache<K, V> extends Cache<K, V> {
    V get(K key, Function<K, V> loader);
    void refresh(K key);
    void preload(K key);
}

// 异步操作扩展
public interface AsyncCache<K, V> {
    CompletableFuture<V> getAsync(K key);
    CompletableFuture<Void> putAsync(K key, V value);
    CompletableFuture<Void> evictAsync(K key);
}

// 多级缓存抽象
public interface TieredCache<K, V> extends LoadingCache<K, V>, AsyncCache<K, V> {
    Map<CacheTier, CacheStats> getAllStats();
    void warmUp(Map<K, V> data);
}
```

---

## 📊 缓存层级设计

### 1. L1本地缓存 (SimpleCaffeineLocalTier)

#### 技术选型：Caffeine

```java
public class SimpleCaffeineLocalTier<K, V> extends AbstractCacheTier<K, V> 
        implements LocalTier<K, V> {
    
    private final com.github.benmanes.caffeine.cache.Cache<K, V> caffeineCache;
    private final com.github.benmanes.caffeine.cache.LoadingCache<K, V> caffeineLoadingCache;
    private final boolean useLoadingCache;
    
    public SimpleCaffeineLocalTier(String name, LocalTierConfig config) {
        // 构建Caffeine缓存
        Caffeine<Object, Object> builder = Caffeine.newBuilder();
        
        // 基础配置
        if (config.getMaximumSize() > 0) {
            builder.maximumSize(config.getMaximumSize());
        }
        
        // 过期策略
        if (config.getExpireAfterWrite() != null) {
            builder.expireAfterWrite(config.getExpireAfterWrite());
        }
        
        // 自动刷新
        if (config.getRefreshAfterWrite() != null) {
            builder.refreshAfterWrite(config.getRefreshAfterWrite());
        }
        
        // 双模式支持
        this.useLoadingCache = config.getRefreshAfterWrite() != null && config.getCacheLoader() != null;
        
        if (useLoadingCache) {
            // LoadingCache模式：支持自动刷新
            com.github.benmanes.caffeine.cache.CacheLoader<K, V> caffeineCacheLoader = 
                key -> config.<K, V>getCacheLoader().load(key);
            this.caffeineLoadingCache = builder.build(caffeineCacheLoader);
            this.caffeineCache = caffeineLoadingCache;
        } else {
            // 普通Cache模式
            this.caffeineCache = builder.build();
            this.caffeineLoadingCache = null;
        }
    }
}
```

#### L1缓存特性

| 特性 | 实现方式 | 说明 |
|------|----------|------|
| **高性能** | JVM堆内存 | 亚毫秒级访问速度 |
| **自动过期** | 时间轮算法 | 支持TTL和TTI |
| **自动刷新** | LoadingCache | 后台异步刷新，不阻塞读取 |
| **统计监控** | 内置统计 | 命中率、加载时间等 |
| **内存管理** | LRU/LFU | 自动淘汰最不常用数据 |

### 2. L2分布式缓存 (RedissonRemoteTier)

#### 技术选型：Redisson

```java
public class RedissonRemoteTier<K, V> extends AbstractCacheTier<K, V> 
        implements RemoteTier<K, V> {
    
    private final RedissonClient redissonClient;
    private final String keyPrefix;
    private final Duration defaultTtl;
    
    @Override
    public V get(K key) {
        try {
            RBucket<V> bucket = redissonClient.getBucket(buildKey(key));
            V value = bucket.get();
            
            if (value != null) {
                recordHit();
            } else {
                recordMiss();
            }
            
            return value;
        } catch (Exception e) {
            handleRedisException("get", key, e);
            return null;
        }
    }
    
    @Override
    public void put(K key, V value, Duration ttl) {
        try {
            RBucket<V> bucket = redissonClient.getBucket(buildKey(key));
            
            Duration finalTtl = ttl != null ? ttl : defaultTtl;
            if (finalTtl != null && !finalTtl.isZero()) {
                bucket.set(value, finalTtl);
            } else {
                bucket.set(value);
            }
        } catch (Exception e) {
            handleRedisException("put", key, e);
        }
    }
    
    // 支持CacheLoader的刷新
    public void refresh(K key, Function<K, V> loader) {
        if (loader != null) {
            try {
                V newValue = loader.apply(key);
                if (newValue != null) {
                    put(key, newValue);
                } else {
                    evict(key);
                }
            } catch (Exception e) {
                evict(key); // 刷新失败时删除旧数据
            }
        } else {
            evict(key);
        }
    }
}
```

#### L2缓存特性

| 特性 | 实现方式 | 说明 |
|------|----------|------|
| **分布式共享** | Redis存储 | 多节点数据共享 |
| **持久化** | RDB/AOF | 数据持久化保护 |
| **高可用** | 主从/集群 | 故障自动切换 |
| **大容量** | 磁盘存储 | 突破内存限制 |
| **定时刷新** | 调度器 | 主动数据刷新 |

### 3. 多级缓存协调策略

#### 读取策略 (Cache-Aside Pattern)

```java
@Override
public V get(K key) {
    // 1. L1缓存查找
    V value = l1Cache.get(key);
    if (value != null) {
        return value;
    }
    
    // 2. L2缓存查找
    if (l2Cache != null) {
        value = l2Cache.get(key);
        if (value != null) {
            // 回写L1缓存
            l1Cache.put(key, value);
            return value;
        }
    }
    
    // 3. CacheLoader加载
    if (cacheLoader != null) {
        return loadValue(key);
    }
    
    return null;
}
```

#### 写入策略 (Write-Through Pattern)

```java
@Override
public void put(K key, V value, Duration ttl) {
    // 防雪崩TTL计算
    Duration finalTtl = calculateAvalancheProtectionTtl(ttl);
    
    // 同时写入L1和L2
    l1Cache.put(key, value, finalTtl);
    if (l2Cache != null) {
        l2Cache.put(key, value, finalTtl);
        
        // 调度L2刷新任务
        if (l2RefreshScheduler != null) {
            l2RefreshScheduler.scheduleRefresh(key);
        }
    }
    
    // 布隆过滤器标记
    addToBloomFilter(String.valueOf(key));
    
    // 发布同步事件
    publishSyncEvent(new CacheSyncEvent(getName(), key, value, nodeId));
}
```

---

## ⚡ 自动刷新机制

### 1. L1缓存自动刷新 (Caffeine原生)

#### refreshAfterWrite工作原理

```java
// 时间线示例：refreshAfterWrite = 30分钟
00:00  put(key, "value1")           缓存值="value1"
00:15  get(key) -> "value1"         未达刷新时间，直接返回
00:35  get(key) -> "value1"         达到刷新时间：
                                   1. 立即返回"value1"（非阻塞）
                                   2. 后台异步调用loader加载新值
00:36  [后台] loader.load(key)      异步执行，加载"value2"  
00:37  get(key) -> "value2"         使用刷新后的新值
```

#### LoadingCache自动刷新实现

```java
// Caffeine LoadingCache适配器
com.github.benmanes.caffeine.cache.CacheLoader<K, V> caffeineCacheLoader = 
    key -> config.<K, V>getCacheLoader().load(key);

this.caffeineLoadingCache = builder
    .refreshAfterWrite(refreshInterval.toMillis(), TimeUnit.MILLISECONDS)
    .build(caffeineCacheLoader);

// 刷新方法增强
@Override
public void refresh(K key) {
    if (useLoadingCache && caffeineLoadingCache != null) {
        // LoadingCache模式：触发后台异步刷新
        caffeineLoadingCache.refresh(key);
    } else {
        // 普通模式：简单失效
        caffeineCache.invalidate(key);
    }
}
```

### 2. L2缓存定时刷新 (自实现调度器)

#### CacheRefreshScheduler设计

```java
public class CacheRefreshScheduler<K, V> {
    private final ScheduledExecutorService scheduler;
    private final ConcurrentHashMap<K, ScheduledFuture<?>> scheduledRefreshes;
    private final RefreshCallback<K, V> refreshCallback;
    private final CacheLoader<K, V> cacheLoader;
    private final Duration refreshInterval;
    
    public void scheduleRefresh(K key) {
        // 取消现有任务
        ScheduledFuture<?> existing = scheduledRefreshes.get(key);
        if (existing != null && !existing.isDone()) {
            existing.cancel(false);
        }
        
        // 创建新任务
        ScheduledFuture<?> future = scheduler.schedule(
            () -> performRefresh(key),
            refreshInterval.toMillis(),
            TimeUnit.MILLISECONDS
        );
        
        scheduledRefreshes.put(key, future);
    }
    
    private void performRefresh(K key) {
        try {
            // 从数据源加载新值
            V newValue = cacheLoader.load(key);
            
            if (newValue != null) {
                // 更新L2缓存
                refreshCallback.refresh(key, newValue);
                
                // 重新调度下次刷新
                scheduleRefresh(key);
            } else {
                // 数据不存在，移除调度
                scheduledRefreshes.remove(key);
            }
        } catch (Exception e) {
            // 失败重试（延长间隔）
            scheduleRetry(key);
        }
    }
}
```

#### L2刷新调度时机

```java
@Override
public void put(K key, V value, Duration ttl) {
    // ... 其他逻辑
    
    // 写入L2缓存
    if (l2Cache != null) {
        l2Cache.put(key, value, finalTtl);
        
        // 🔥 关键：自动调度L2刷新任务
        if (l2RefreshScheduler != null) {
            l2RefreshScheduler.scheduleRefresh(key);
        }
    }
}
```

### 3. 多级刷新协调策略

#### 刷新时间配置策略

```yaml
cascade:
  cache:
    l1:
      refresh-after-write: PT30M    # L1: 30分钟后台刷新
      expire-after-write: PT2H     # L1: 2小时过期
    l2:
      refresh-after-write: PT1H    # L2: 1小时定时刷新
      default-ttl: PT6H           # L2: 6小时过期
```

#### 刷新策略对比

| 层级 | 刷新方式 | 触发时机 | 数据可用性 | 适用场景 |
|------|----------|----------|------------|----------|
| **L1** | 后台异步 | 访问时检查 | 始终可用 | 热点数据快速更新 |
| **L2** | 定时调度 | 周期性执行 | 刷新期间可用 | 分布式数据一致性 |

---

## 🌐 分布式同步原理

### 1. Redis Pub/Sub同步机制

#### CacheSyncManager设计

```java
public class RedissonCacheSyncManager implements CacheSyncManager {
    private final RedissonClient redissonClient;
    private final String topicName;
    private final String nodeId;
    private final Set<CacheSyncListener> listeners;
    
    @Override
    public void publishEvent(CacheSyncEvent event) {
        try {
            RTopic topic = redissonClient.getTopic(topicName);
            topic.publish(event);
            
            log.debug("Published sync event: {}", event);
        } catch (Exception e) {
            log.error("Failed to publish sync event: {}", event, e);
        }
    }
    
    @Override
    public void subscribe() {
        RTopic topic = redissonClient.getTopic(topicName);
        topic.addListener(CacheSyncEvent.class, (channel, event) -> {
            // 忽略本节点发出的事件
            if (!nodeId.equals(event.getSourceNodeId())) {
                notifyListeners(event);
            }
        });
        
        log.info("Subscribed to cache sync topic: {}", topicName);
    }
}
```

#### 同步事件类型

```java
public class CacheSyncEvent {
    public enum Operation {
        PUT,        // 数据更新
        EVICT,      // 数据删除  
        CLEAR,      // 清空缓存
        REFRESH     // 刷新数据
    }
    
    private String cacheName;
    private Operation operation;
    private Object key;
    private Object value;
    private String sourceNodeId;
    private long timestamp;
}
```

### 2. 同步事件处理流程

#### 事件发布

```java
// 数据更新时发布同步事件
@Override
public void put(K key, V value, Duration ttl) {
    // ... 本地缓存更新逻辑
    
    // 发布同步事件
    publishSyncEvent(new CacheSyncEvent(
        getName(), 
        CacheSyncEvent.Operation.PUT, 
        key, 
        value,
        getCurrentNodeId()
    ));
}

private void publishSyncEvent(CacheSyncEvent event) {
    if (enableSync && syncManager != null) {
        try {
            syncManager.publishEvent(event);
        } catch (Exception e) {
            log.warn("Failed to publish sync event: {}", event, e);
        }
    }
}
```

#### 事件处理

```java
@Override
public void onCacheSyncEvent(CacheSyncEvent event) {
    if (!name.equals(event.getCacheName())) {
        return; // 忽略其他缓存的事件
    }
    
    try {
        switch (event.getOperation()) {
            case PUT:
                handlePutSync(event);
                break;
            case EVICT:
                handleEvictSync(event);
                break;
            case CLEAR:
                handleClearSync(event);
                break;
            case REFRESH:
                handleRefreshSync(event);
                break;
        }
    } catch (Exception e) {
        log.error("Failed to handle sync event: {}", event, e);
    }
}

@SuppressWarnings("unchecked")
private void handleRefreshSync(CacheSyncEvent event) {
    if (event.getKey() != null) {
        K key = (K) event.getKey();
        l1Cache.refresh(key); // 只刷新L1缓存
    }
}
```

### 3. 同步策略与一致性保证

#### 最终一致性模型

```
节点A写入数据:
  ┌─────────────┐    ┌─────────────┐    ┌─────────────┐
  │   Node A    │    │   Node B    │    │   Node C    │
  │             │    │             │    │             │
  │ 1.put(k,v)  │    │             │    │             │
  │ 2.L1=新值   │    │             │    │             │
  │ 3.L2=新值   │    │             │    │             │
  │ 4.publish   │───▶│ 5.receive   │───▶│ 6.receive   │
  │   event     │    │   event     │    │   event     │
  │             │    │ 7.L1.refresh│    │ 8.L1.refresh│
  └─────────────┘    └─────────────┘    └─────────────┘
  
结果：所有节点L1缓存最终一致，L2缓存本来就共享
```

#### 同步异常处理

```java
public class CacheSyncReliabilityManager {
    private final ConcurrentHashMap<String, Long> pendingSyncEvents = new ConcurrentHashMap<>();
    private final ScheduledExecutorService retryScheduler;
    
    public void handleSyncFailure(CacheSyncEvent event, Exception e) {
        String eventKey = event.getCacheName() + ":" + event.getKey();
        
        // 记录失败事件
        pendingSyncEvents.put(eventKey, System.currentTimeMillis());
        
        // 延迟重试
        retryScheduler.schedule(() -> {
            try {
                retrySync(event);
                pendingSyncEvents.remove(eventKey);
            } catch (Exception retryE) {
                log.error("Sync retry failed: {}", event, retryE);
            }
        }, 5, TimeUnit.SECONDS);
    }
}
```

---

## 🛡️ 防护机制详解

### 1. 缓存穿透防护 (布隆过滤器)

#### BloomFilterProtection实现

```java
public class BloomFilterProtection {
    private final BloomFilter<String> bloomFilter;
    private final double falsePositiveRate;
    private final long expectedElements;
    
    public BloomFilterProtection(long expectedElements, double falsePositiveRate) {
        this.expectedElements = expectedElements;
        this.falsePositiveRate = falsePositiveRate;
        this.bloomFilter = BloomFilter.create(
            Funnels.stringFunnel(StandardCharsets.UTF_8),
            expectedElements,
            falsePositiveRate
        );
    }
    
    public void add(String key) {
        bloomFilter.put(key);
    }
    
    public boolean mightContain(String key) {
        return bloomFilter.mightContain(key);
    }
    
    public boolean definitelyNotContain(String key) {
        return !mightContain(key);
    }
}
```

#### 穿透防护流程

```java
// 简化的缓存防护管理器
public <T> T executeWithProtection(String key, 
                                 Supplier<T> l2Supplier,
                                 Supplier<T> dataLoader) 
        throws CacheProtectionException {
    
    // 1. 布隆过滤器检查
    if (bloomFilter != null && bloomFilter.definitelyNotContain(key)) {
        log.debug("Bloom filter indicates key {} definitely not exists", key);
        return null; // 直接返回，避免穿透
    }
    
    // 2. L2缓存查询
    T value = l2Supplier.get();
    if (value != null) {
        return value;
    }
    
    // 3. 分布式锁保护
    if (redissonLock != null) {
        return redissonLock.executeWithLock(key, () -> {
            // 双重检查
            T doubleCheckedValue = l2Supplier.get();
            if (doubleCheckedValue != null) {
                return doubleCheckedValue;
            }
            
            // 4. 数据源加载
            T loadedValue = dataLoader.get();
            if (loadedValue != null) {
                // 添加到布隆过滤器
                bloomFilter.add(key);
            }
            
            return loadedValue;
        });
    }
    
    // 5. 无锁情况下的直接加载
    return dataLoader.get();
}
```

### 2. 缓存雪崩防护 (随机TTL)

#### RandomTtlProtection实现

```java
public class RandomTtlProtection {
    
    public enum JitterStrategy {
        UNIFORM,     // 均匀分布
        GAUSSIAN,    // 高斯分布
        EXPONENTIAL  // 指数分布
    }
    
    private final Duration baseTtl;
    private final Duration jitterRange;
    private final JitterStrategy strategy;
    private final Random random = new ThreadLocalRandom.current();
    
    public Duration calculateTtl() {
        return calculateTtl(baseTtl);
    }
    
    public Duration calculateTtl(Duration originalTtl) {
        if (originalTtl == null || jitterRange == null) {
            return originalTtl;
        }
        
        long baseMillis = originalTtl.toMillis();
        long jitterMillis = jitterRange.toMillis();
        
        long finalTtlMillis = switch (strategy) {
            case UNIFORM -> baseMillis + random.nextLong(-jitterMillis, jitterMillis + 1);
            case GAUSSIAN -> baseMillis + (long) (random.nextGaussian() * jitterMillis / 2);
            case EXPONENTIAL -> baseMillis + (long) (-Math.log(1 - random.nextDouble()) * jitterMillis);
        };
        
        // 确保TTL为正值
        finalTtlMillis = Math.max(finalTtlMillis, 1000); // 最小1秒
        
        return Duration.ofMillis(finalTtlMillis);
    }
}
```

#### 雪崩防护效果

```
原始TTL: 1小时，抖动范围: ±10分钟

缓存失效时间分布：
50分钟  ████░░░░░░░░░░░░░░░░░░  20个key
55分钟  ██████░░░░░░░░░░░░░░░░  30个key  
60分钟  ████████░░░░░░░░░░░░░░  40个key (原始时间)
65分钟  ██████░░░░░░░░░░░░░░░░  30个key
70分钟  ████░░░░░░░░░░░░░░░░░░  20个key

结果：避免了缓存同时失效，分散了数据库压力
```

### 3. 缓存击穿防护 (分布式锁)

#### RedissonLockProtection实现

```java
public class RedissonLockProtection {
    private final RedissonClient redissonClient;
    private final String lockKeyPrefix;
    private final Duration lockTimeout;
    private final Duration waitTimeout;
    private final int maxRetries;
    private final Duration retryDelay;
    
    public <T> T executeWithLock(String lockKey, Supplier<T> supplier) throws LockException {
        String fullLockKey = lockKeyPrefix + lockKey;
        RLock lock = redissonClient.getLock(fullLockKey);
        
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                boolean acquired = lock.tryLock(
                    waitTimeout.toMillis(), 
                    lockTimeout.toMillis(), 
                    TimeUnit.MILLISECONDS
                );
                
                if (acquired) {
                    try {
                        return supplier.get();
                    } finally {
                        if (lock.isHeldByCurrentThread()) {
                            lock.unlock();
                        }
                    }
                } else {
                    if (attempt < maxRetries) {
                        Thread.sleep(retryDelay.toMillis());
                    } else {
                        throw new LockException("Failed to acquire lock after " + maxRetries + " attempts");
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LockException("Lock acquisition interrupted", e);
            }
        }
        
        throw new LockException("Exhausted all retry attempts");
    }
}
```

#### 击穿防护流程

```
高并发访问热点key:
  
  Thread 1: tryLock() ✅ 获得锁
  Thread 2: tryLock() ❌ 等待
  Thread 3: tryLock() ❌ 等待
  Thread N: tryLock() ❌ 等待
  
  Thread 1: 执行数据库查询 → 更新缓存 → unlock()
  Thread 2: tryLock() ✅ 发现缓存已存在 → 直接返回 → unlock()
  Thread 3: tryLock() ✅ 发现缓存已存在 → 直接返回 → unlock()
  
结果：只有第一个线程查询数据库，其他线程复用缓存
```

---

## ⚙️ 配置与使用

### 1. 基础配置

#### Maven依赖

```xml
<dependency>
    <groupId>cc.coderm</groupId>
    <artifactId>cascade-spring-boot-starter</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```

#### 最小配置

```yaml
# application.yml
cascade:
  redisson:
    single-server-config:
      address: "redis://localhost:6379"
```

#### 完整配置示例

```yaml
cascade:
  cache:
    enabled: true
    default-cache-name: "app-cache"
    
    # L1本地缓存配置
    l1:
      maximum-size: 10000
      expire-after-write: PT30M      # 30分钟过期
      expire-after-access: PT15M     # 15分钟无访问过期  
      refresh-after-write: PT10M     # 10分钟后台刷新
      record-stats: true
    
    # L2分布式缓存配置
    l2:
      enabled: true
      key-prefix: "myapp:cache:"
      default-ttl: PT2H              # 2小时过期
      refresh-after-write: PT1H      # 1小时定时刷新
    
    # 分布式同步配置
    sync:
      enabled: true
      topic: "myapp:cache:sync"
    
    # 防护机制配置
    protection:
      enabled: true
      bloom-filter:
        expected-elements: 100000
        false-positive-rate: 0.01
      random-ttl:
        jitter-ratio: 0.1
        base-ttl: PT1H
        jitter-range: PT10M
      distributed-lock:
        timeout: PT30S
        wait-timeout: PT10S
        max-retries: 3
        retry-delay: PT100MS
  
  # Redisson配置
  redisson:
    single-server-config:
      address: "redis://localhost:6379"
      database: 0
      connection-pool-size: 64
      connection-minimum-idle-size: 10
      idle-connection-timeout: 10000
      connect-timeout: 10000
      timeout: 3000
      retry-attempts: 3
      retry-interval: 1500
      
# 监控配置
management:
  endpoints:
    web:
      exposure:
        include: "health,info,metrics,cascadecache"
  metrics:
    export:
      prometheus:
        enabled: true
```

### 2. 编程式使用

#### 基本使用

```java
@Service
public class UserService {
    
    @Autowired
    private RedissonClient redissonClient;
    
    public void example() {
        // 创建用户缓存
        Cache<String, User> userCache = CascadeCacheBuilder
            .<String, User>newBuilder("users")
            .loader(userId -> userRepository.findById(userId))
            .maximumSize(10000)
            .expireAfterWrite(Duration.ofHours(1))
            .refreshAfterWrite(Duration.ofMinutes(30))
            .enableL2Cache(true, redissonClient)
            .l2RefreshAfterWrite(Duration.ofHours(2))
            .enableSync(true)
            .enableProtection(true)
            .bloomFilter(50000L, 0.01)
            .randomTtl(true, Duration.ofHours(1), 0.1)
            .build();
        
        // 基本操作
        User user = userCache.get("user123");                    // 获取
        userCache.put("user123", new User("张三"));              // 存储
        userCache.refresh("user123");                           // 刷新
        userCache.evict("user123");                            // 删除
        
        // 异步操作
        CompletableFuture<User> future = userCache.getAsync("user456");
        CompletableFuture<Void> putFuture = userCache.putAsync("user456", user);
        
        // 批量操作
        Map<String, User> users = userCache.getAll(Set.of("user1", "user2", "user3"));
        userCache.putAll(Map.of("user4", user4, "user5", user5));
        
        // 统计信息
        CacheStats stats = userCache.getStats();
        System.out.println("命中率: " + stats.hitRate());
        System.out.println("加载次数: " + stats.loadCount());
    }
}
```

#### 高级配置

```java
@Configuration
public class CacheConfig {
    
    @Bean
    public Cache<String, Product> productCache(RedissonClient redissonClient) {
        return CascadeCacheBuilder.<String, Product>newBuilder("products")
            // 数据加载器
            .loader(productId -> {
                log.info("Loading product: {}", productId);
                return productService.findById(productId);
            })
            
            // 自定义线程池
            .executor(Executors.newFixedThreadPool(4))
            
            // L1缓存配置
            .maximumSize(20000)
            .expireAfterWrite(Duration.ofMinutes(30))
            .expireAfterAccess(Duration.ofMinutes(15))
            .refreshAfterWrite(Duration.ofMinutes(10))
            
            // L2缓存配置
            .enableL2Cache(true, redissonClient)
            .l2KeyPrefix("myapp:products:")
            .l2DefaultTtl(Duration.ofHours(2))
            .l2RefreshAfterWrite(Duration.ofMinutes(30))
            
            // 防护配置
            .enableProtection(true)
            .bloomFilter(100000L, 0.005)          // 10万预期，0.5%误判率
            .randomTtl(true, Duration.ofMinutes(30), 0.2)  // 20%抖动
            .distributedLock(true, Duration.ofSeconds(30), "product:lock:")
            
            // 监控配置
            .recordStats(true)
            
            .build();
    }
}
```

### 3. 注解式使用

#### Spring Cache注解集成

```java
@Service
public class ProductService {
    
    @Cacheable(value = "products", key = "#id")
    public Product findById(String id) {
        log.info("从数据库加载商品: {}", id);
        return productRepository.findById(id);
    }
    
    @CachePut(value = "products", key = "#product.id")
    public Product save(Product product) {
        Product saved = productRepository.save(product);
        log.info("商品已保存并更新缓存: {}", saved.getId());
        return saved;
    }
    
    @CacheEvict(value = "products", key = "#id")
    public void deleteById(String id) {
        productRepository.deleteById(id);
        log.info("商品已删除并清除缓存: {}", id);
    }
    
    @CacheEvict(value = "products", allEntries = true)
    public void clearAllProducts() {
        log.info("清空所有商品缓存");
    }
}
```

#### 自定义缓存管理器

```java
@Configuration
@EnableCaching
public class CacheManagerConfig {
    
    @Bean
    @Primary
    public CacheManager cacheManager(RedissonClient redissonClient) {
        return new CascadeCacheManager(redissonClient) {
            @Override
            protected Cache<Object, Object> createCache(String name) {
                return CascadeCacheBuilder.<Object, Object>newBuilder(name)
                    .maximumSize(getDefaultMaximumSize())
                    .expireAfterWrite(getDefaultTtl())
                    .enableL2Cache(true, redissonClient)
                    .enableProtection(true)
                    .build();
            }
        };
    }
}
```

---

## 📊 监控与运维

### 1. 指标监控

#### Prometheus指标导出

```java
@Component
public class CascadeCacheMetrics {
    
    private final MeterRegistry meterRegistry;
    private final Timer.Sample cacheOperationTimer;
    
    @EventListener
    public void handleCacheHit(CacheHitEvent event) {
        Counter.builder("cascade_cache_hits_total")
            .tag("cache_name", event.getCacheName())
            .tag("tier", event.getTier().name())
            .register(meterRegistry)
            .increment();
    }
    
    @EventListener
    public void handleCacheMiss(CacheMissEvent event) {
        Counter.builder("cascade_cache_misses_total")
            .tag("cache_name", event.getCacheName())
            .tag("tier", event.getTier().name())
            .register(meterRegistry)
            .increment();
    }
    
    @EventListener
    public void handleCacheLoad(CacheLoadEvent event) {
        Timer.builder("cascade_cache_load_duration_seconds")
            .tag("cache_name", event.getCacheName())
            .tag("success", String.valueOf(event.isSuccess()))
            .register(meterRegistry)
            .record(event.getDuration(), TimeUnit.MILLISECONDS);
    }
}
```

#### 核心监控指标

| 指标类别 | 指标名称 | 说明 | 标签 |
|----------|----------|------|------|
| **命中率** | `cascade_cache_hits_total` | 缓存命中次数 | cache_name, tier |
|  | `cascade_cache_misses_total` | 缓存未命中次数 | cache_name, tier |
| **性能** | `cascade_cache_load_duration_seconds` | 数据加载耗时 | cache_name, success |
|  | `cascade_cache_refresh_duration_seconds` | 缓存刷新耗时 | cache_name, tier |
| **容量** | `cascade_cache_size_current` | 当前缓存大小 | cache_name, tier |
|  | `cascade_cache_evictions_total` | 缓存淘汰次数 | cache_name, tier |
| **防护** | `cascade_cache_protection_activations_total` | 防护机制激活次数 | cache_name, protection_type |
| **同步** | `cascade_cache_sync_events_total` | 同步事件数量 | cache_name, operation |

### 2. 健康检查

#### CascadeCacheHealthIndicator

```java
@Component
public class CascadeCacheHealthIndicator implements HealthIndicator {
    
    private final List<SyncableMultiLevelCascadeCache<?, ?>> caches;
    private final RedissonClient redissonClient;
    
    @Override
    public Health health() {
        Health.Builder builder = Health.up();
        
        try {
            // 检查Redis连接
            checkRedisConnection(builder);
            
            // 检查缓存状态
            checkCacheStatus(builder);
            
            // 检查刷新调度器
            checkRefreshSchedulers(builder);
            
            // 检查同步状态
            checkSyncStatus(builder);
            
        } catch (Exception e) {
            return Health.down(e)
                .withDetail("error", e.getMessage())
                .build();
        }
        
        return builder.build();
    }
    
    private void checkRedisConnection(Health.Builder builder) {
        try {
            redissonClient.getBucket("health-check").trySet("ok", 1, TimeUnit.SECONDS);
            builder.withDetail("redis", "UP");
        } catch (Exception e) {
            builder.withDetail("redis", "DOWN: " + e.getMessage());
        }
    }
    
    private void checkCacheStatus(Health.Builder builder) {
        Map<String, Object> cacheDetails = new HashMap<>();
        
        for (SyncableMultiLevelCascadeCache<?, ?> cache : caches) {
            Map<String, Object> cacheInfo = new HashMap<>();
            
            // L1缓存信息
            CacheStats l1Stats = cache.getL1Stats();
            cacheInfo.put("l1_size", cache.getL1Size());
            cacheInfo.put("l1_hit_rate", l1Stats.hitRate());
            
            // L2缓存信息
            if (cache.hasL2Cache()) {
                CacheStats l2Stats = cache.getL2Stats();
                cacheInfo.put("l2_size", cache.getL2Size());
                cacheInfo.put("l2_hit_rate", l2Stats.hitRate());
            }
            
            // 刷新调度器状态
            if (cache.hasRefreshScheduler()) {
                cacheInfo.put("active_refreshes", cache.getActiveRefreshCount());
            }
            
            cacheDetails.put(cache.getName(), cacheInfo);
        }
        
        builder.withDetail("caches", cacheDetails);
    }
}
```

### 3. 管理端点

#### CascadeCacheEndpoint

```java
@Endpoint(id = "cascadecache")
@Component
public class CascadeCacheEndpoint {
    
    private final CascadeCacheManager cacheManager;
    
    @ReadOperation
    public Map<String, Object> caches() {
        Map<String, Object> result = new HashMap<>();
        
        for (String cacheName : cacheManager.getCacheNames()) {
            SyncableMultiLevelCascadeCache<?, ?> cache = 
                (SyncableMultiLevelCascadeCache<?, ?>) cacheManager.getCache(cacheName);
            
            if (cache != null) {
                result.put(cacheName, buildCacheInfo(cache));
            }
        }
        
        return result;
    }
    
    @ReadOperation
    public Map<String, Object> cache(@Selector String cacheName) {
        SyncableMultiLevelCascadeCache<?, ?> cache = 
            (SyncableMultiLevelCascadeCache<?, ?>) cacheManager.getCache(cacheName);
        
        if (cache == null) {
            return Map.of("error", "Cache not found: " + cacheName);
        }
        
        return buildCacheInfo(cache);
    }
    
    @WriteOperation
    public Map<String, String> evict(@Selector String cacheName, String key) {
        SyncableMultiLevelCascadeCache<?, ?> cache = 
            (SyncableMultiLevelCascadeCache<?, ?>) cacheManager.getCache(cacheName);
        
        if (cache == null) {
            return Map.of("error", "Cache not found: " + cacheName);
        }
        
        cache.evict(key);
        return Map.of("status", "evicted", "cache", cacheName, "key", key);
    }
    
    @WriteOperation
    public Map<String, String> clear(@Selector String cacheName) {
        SyncableMultiLevelCascadeCache<?, ?> cache = 
            (SyncableMultiLevelCascadeCache<?, ?>) cacheManager.getCache(cacheName);
        
        if (cache == null) {
            return Map.of("error", "Cache not found: " + cacheName);
        }
        
        cache.clear();
        return Map.of("status", "cleared", "cache", cacheName);
    }
    
    @WriteOperation
    public Map<String, String> refresh(@Selector String cacheName, String key) {
        SyncableMultiLevelCascadeCache<?, ?> cache = 
            (SyncableMultiLevelCascadeCache<?, ?>) cacheManager.getCache(cacheName);
        
        if (cache == null) {
            return Map.of("error", "Cache not found: " + cacheName);
        }
        
        cache.refresh(key);
        return Map.of("status", "refreshed", "cache", cacheName, "key", key);
    }
    
    private Map<String, Object> buildCacheInfo(SyncableMultiLevelCascadeCache<?, ?> cache) {
        Map<String, Object> info = new HashMap<>();
        
        info.put("name", cache.getName());
        info.put("type", "SyncableMultiLevelCascadeCache");
        
        // 统计信息
        Map<CacheTier, CacheStats> allStats = cache.getAllStats();
        Map<String, Object> statsInfo = new HashMap<>();
        
        for (Map.Entry<CacheTier, CacheStats> entry : allStats.entrySet()) {
            CacheStats stats = entry.getValue();
            Map<String, Object> tierStats = new HashMap<>();
            
            tierStats.put("hit_count", stats.hitCount());
            tierStats.put("miss_count", stats.missCount());
            tierStats.put("hit_rate", stats.hitRate());
            tierStats.put("load_count", stats.loadCount());
            tierStats.put("eviction_count", stats.evictionCount());
            tierStats.put("average_load_penalty", stats.averageLoadPenalty());
            
            statsInfo.put(entry.getKey().name().toLowerCase(), tierStats);
        }
        
        info.put("stats", statsInfo);
        
        // 配置信息
        Map<String, Object> config = new HashMap<>();
        config.put("l1_enabled", true);
        config.put("l2_enabled", cache.hasL2Cache());
        config.put("sync_enabled", cache.isSyncEnabled());
        config.put("protection_enabled", cache.hasProtection());
        config.put("refresh_scheduler_enabled", cache.hasRefreshScheduler());
        
        info.put("config", config);
        
        return info;
    }
}
```

---

## 🏆 最佳实践

### 1. 缓存配置最佳实践

#### 缓存大小配置

```java
// 根据业务场景配置合适的缓存大小
Cache<String, User> userCache = CascadeCacheBuilder.<String, User>newBuilder("users")
    // L1缓存：存储热点数据，大小设为预期热点数据的1.2倍
    .maximumSize(12000)  // 预期1万热点用户 * 1.2
    
    // L2缓存：存储全量数据，考虑Redis内存限制
    .l2DefaultTtl(Duration.ofHours(4))  // 4小时TTL，避免冷数据占用内存
    
    .build();
```

#### 过期时间配置策略

```yaml
# 不同业务数据的TTL配置建议
cascade:
  cache:
    l1:
      # 热点数据：较短过期时间，频繁刷新
      expire-after-write: PT30M      # 30分钟过期
      refresh-after-write: PT10M     # 10分钟刷新
      
    l2:  
      # 温数据：较长过期时间，定期刷新
      default-ttl: PT2H              # 2小时过期
      refresh-after-write: PT30M     # 30分钟刷新

# 业务数据TTL配置示例：
# - 用户信息：1-4小时（变更不频繁）
# - 商品信息：30分钟-2小时（价格库存变化频繁）  
# - 配置数据：1-24小时（基本不变）
# - 统计数据：1-10分钟（实时性要求高）
```

### 2. 性能优化建议

#### 键设计规范

```java
// ❌ 错误的键设计
cache.put("user_info_detail_" + userId + "_with_profile", user);

// ✅ 正确的键设计  
cache.put("user:" + userId, user);           // 简短明确
cache.put("product:price:" + productId, price);  // 结构化命名
cache.put("stats:daily:" + date, dailyStats);    // 层次化组织
```

#### 序列化优化

```java
// 配置高效的序列化器
Cache<String, User> userCache = CascadeCacheBuilder.<String, User>newBuilder("users")
    .enableL2Cache(true, redissonClient)
    // Redisson默认使用高效的序列化，如需自定义：
    .l2KeyPrefix("users:")  // 键前缀避免冲突
    .build();

// 对于大对象，考虑压缩
public class CompressedUser {
    @JsonIgnore 
    private transient String largeDescription; // 忽略大字段
    
    // 或使用压缩注解（如果框架支持）
    @Compressed
    private String content;
}
```

#### 批量操作优化

```java
// ✅ 批量操作，减少网络往返
Map<String, User> users = cache.getAll(userIds);
cache.putAll(userMap);

// ✅ 异步操作，提高并发性能
CompletableFuture<User> future1 = cache.getAsync("user1");
CompletableFuture<User> future2 = cache.getAsync("user2");
CompletableFuture.allOf(future1, future2).join();
```

### 3. 监控运维建议

#### 关键指标监控

```java
// 设置告警阈值
@Component
public class CacheAlertMonitor {
    
    @EventListener
    public void monitorHitRate(CacheStatsEvent event) {
        double hitRate = event.getStats().hitRate();
        
        // L1缓存命中率低于80%告警
        if (event.getTier() == CacheTier.L1 && hitRate < 0.8) {
            alertService.sendAlert("L1缓存命中率过低: " + hitRate);
        }
        
        // 总体命中率低于90%告警  
        if (hitRate < 0.9) {
            alertService.sendAlert("缓存命中率过低: " + hitRate);
        }
    }
    
    @EventListener  
    public void monitorLoadTime(CacheLoadEvent event) {
        // 加载时间超过1秒告警
        if (event.getDuration() > 1000) {
            alertService.sendAlert("缓存加载时间过长: " + event.getDuration() + "ms");
        }
    }
}
```

#### 容量规划

```java
// 定期输出缓存容量报告
@Scheduled(fixedRate = 300000) // 5分钟
public void reportCacheCapacity() {
    for (String cacheName : cacheManager.getCacheNames()) {
        SyncableMultiLevelCascadeCache<?, ?> cache = getCache(cacheName);
        
        long l1Size = cache.getL1Size();
        long l1MaxSize = cache.getL1MaxSize();
        double l1Utilization = (double) l1Size / l1MaxSize;
        
        if (l1Utilization > 0.8) {
            log.warn("缓存 {} L1容量使用率过高: {:.2f}% ({}/{})", 
                cacheName, l1Utilization * 100, l1Size, l1MaxSize);
        }
    }
}
```

### 4. 故障处理建议

#### 缓存降级策略

```java
@Component
public class CacheCircuitBreaker {
    
    private final CircuitBreaker circuitBreaker = CircuitBreaker.create("cache-circuit-breaker");
    
    public <T> T getWithFallback(String key, Supplier<T> cacheSupplier, Supplier<T> fallbackSupplier) {
        return circuitBreaker.executeSupplier(() -> {
            try {
                return cacheSupplier.get();
            } catch (Exception e) {
                log.warn("缓存访问失败，使用降级策略: {}", e.getMessage());
                return fallbackSupplier.get();
            }
        });
    }
}
```

#### 数据一致性保障

```java
@Service
@Transactional
public class UserService {
    
    public User updateUser(User user) {
        try {
            // 1. 更新数据库
            User updated = userRepository.save(user);
            
            // 2. 更新缓存
            userCache.put(user.getId(), updated);
            
            return updated;
        } catch (Exception e) {
            // 3. 异常时清除缓存，确保一致性
            userCache.evict(user.getId());
            throw e;
        }
    }
    
    // 定期数据一致性检查
    @Scheduled(fixedRate = 3600000) // 1小时
    public void checkDataConsistency() {
        // 抽样检查缓存与数据库的一致性
        // 发现不一致时进行修复
    }
}
```

---

## 📝 总结

Cascade Framework通过Spring Boot的自动配置机制，为应用提供了强大的分布式缓存能力：

### 🎯 核心价值

1. **开箱即用**：零配置启动，自动适配Spring Boot生态
2. **高性能**：多级缓存架构，智能数据分层
3. **高可靠**：三重防护机制，保障系统稳定性  
4. **易运维**：丰富的监控指标和管理端点
5. **可扩展**：模块化设计，支持灵活定制

### 🚀 技术亮点

- **自动刷新机制**：L1后台异步 + L2定时调度，保证数据新鲜度
- **分布式同步**：Redis Pub/Sub实现跨节点缓存一致性
- **智能防护**：布隆过滤器、随机TTL、分布式锁三重保护
- **流式API**：Builder模式提供优雅的编程体验
- **监控集成**：原生支持Prometheus/Micrometer指标

### 💡 设计原则

1. **统一门面**：通过单一入口屏蔽复杂性
2. **最佳实践**：内置缓存使用最佳实践
3. **故障友好**：多重降级策略保障服务可用性
4. **运维友好**：完善的监控和管理能力

通过Cascade Framework，开发者可以专注业务逻辑，而无需关心分布式缓存的复杂实现细节，真正实现"简单易用，功能强大"的设计目标。