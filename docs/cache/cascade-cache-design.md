# Cascade Cache 模块设计文档

## 一、模块概述

`cascade-cache` 是基于 Redisson 深度封装的高性能多级缓存解决方案，以 Spring Boot 3 Starter 的方式提供企业级缓存服务。支持 L1（Caffeine）+ L2（Redis）两级缓存架构，具备智能加载、缓存同步、预热、防护等核心特性。

### 核心特性

- **多级缓存**：L1（Caffeine 本地缓存）+ L2（Redis 分布式缓存）
- **缓存同步**：基于 Redis Pub/Sub 实现多节点缓存一致性
- **智能加载**：支持 CacheLoader、批量加载、异步加载
- **缓存预热**：支持数据库、文件等多种数据源预热策略
- **缓存防护**：集成布隆过滤器防穿透、TTL随机化防雪崩、分布式锁防击穿
- **监控统计**：完善的缓存指标监控和性能统计
- **Spring Boot 集成**：开箱即用的自动配置和注解支持

## 二、包结构

```

## 三、核心架构设计

### 3.1 整体架构

```
┌─────────────────────────────────────────────────────────────┐
│                    Application Layer                        │
├─────────────────────────────────────────────────────────────┤
│                  Cache Facade (CascadeCache)               │
├─────────────────────────────────────────────────────────────┤
│  Cache Manager  │  Protection  │  Sync Manager │  Warmer   │
├─────────────────────────────────────────────────────────────┤
│                    Multi-Tier Cache                        │
│  ┌─────────────┐              ┌─────────────────────────┐   │
│  │ L1 (Local)  │              │    L2 (Distributed)     │   │
│  │  Caffeine   │ ◄─────────► │       Redisson           │   │
│  │   Cache     │              │      Redis Cache        │   │
│  └─────────────┘              └─────────────────────────┘   │
├─────────────────────────────────────────────────────────────┤
│                      Event & Monitoring                    │
└─────────────────────────────────────────────────────────────┘
```

### 3.2 多级缓存架构

#### L1 缓存（本地缓存）
- **实现**：基于 Caffeine 的高性能本地缓存
- **特点**：超低延迟访问，JVM 内存存储
- **容量**：可配置最大条目数和内存大小
- **淘汰策略**：支持 LRU、LFU、FIFO 等策略

#### L2 缓存（分布式缓存）
- **实现**：基于 Redisson 的 Redis 分布式缓存
- **特点**：集群共享，持久化存储
- **容量**：基于 Redis 配置，支持大容量存储
- **高可用**：支持 Redis 集群、哨兵模式

#### 缓存读取流程
```
1. 查询 L1 缓存
   ├─ 命中 → 返回数据
   └─ 未命中 → 查询 L2 缓存
       ├─ 命中 → 回填 L1 → 返回数据
       └─ 未命中 → 执行 CacheLoader → 回填 L1&L2 → 返回数据
```

## 四、核心功能设计

### 4.1 缓存同步机制

#### Redis Pub/Sub 同步
```java
// 同步消息结构
public class SyncMessage {
    private String cacheKey;        // 缓存键
    private SyncOperation operation; // 操作类型：PUT/EVICT/CLEAR
    private Object value;           // 缓存值（PUT操作）
    private long timestamp;         // 时间戳
    private String nodeId;          // 节点标识
}

// 同步策略
public enum SyncStrategy {
    IMMEDIATE,    // 立即同步
    BATCH,        // 批量同步
    ASYNC,        // 异步同步
    EVENTUAL      // 最终一致性
}
```

#### 同步流程
1. **写入操作**：更新本地缓存 → 发布同步消息 → 其他节点接收消息 → 更新本地缓存
2. **冲突解决**：基于时间戳的最后写入胜利（LWW）策略
3. **网络分区**：支持分区容错，网络恢复后自动同步

### 4.2 智能加载机制

#### CacheLoader 支持
```java
public interface CacheLoader<K, V> {
    V load(K key) throws Exception;
    Map<K, V> loadAll(Iterable<? extends K> keys) throws Exception;
}

// 异步加载器
public interface AsyncCacheLoader<K, V> {
    CompletableFuture<V> asyncLoad(K key);
    CompletableFuture<Map<K, V>> asyncLoadAll(Iterable<? extends K> keys);
}
```

#### 加载策略
- **同步加载**：阻塞式加载，适用于实时性要求高的场景
- **异步加载**：非阻塞式加载，提升系统吞吐量
- **批量加载**：一次性加载多个键值，减少数据库访问
- **预刷新加载**：在过期前提前刷新，避免缓存穿透

### 4.3 缓存预热机制

#### 预热策略
```java
public interface WarmupStrategy {
    void warmup(Cache cache, WarmupConfig config);
}

// 预热配置
public class WarmupConfig {
    private WarmupSource source;      // 数据源类型
    private String sourceConfig;      // 数据源配置
    private int batchSize;           // 批量大小
    private int threadPoolSize;      // 线程池大小
    private Duration timeout;        // 超时时间
}
```

#### 支持的数据源
1. **数据库预热**：从数据库查询热点数据进行预热
2. **文件预热**：从配置文件或数据文件加载预热数据
3. **API预热**：调用外部API获取预热数据
4. **定时预热**：基于 Cron 表达式的定时预热任务

### 4.4 缓存防护机制

#### 缓存穿透防护（布隆过滤器）
```java
public class BloomFilterProtection implements CacheProtection {
    private BloomFilter<String> bloomFilter;
    
    @Override
    public boolean shouldLoad(String key) {
        return bloomFilter.mightContain(key);
    }
    
    @Override
    public void recordKey(String key) {
        bloomFilter.put(key);
    }
}
```

#### 缓存雪崩防护（随机TTL）
```java
public class RandomTtlProtection implements CacheProtection {
    private final Random random = new Random();
    
    @Override
    public Duration adjustTtl(Duration originalTtl) {
        // 在原TTL基础上增加随机时间（±20%）
        long randomOffset = (long) (originalTtl.toMillis() * 0.2 * random.nextGaussian());
        return Duration.ofMillis(originalTtl.toMillis() + randomOffset);
    }
}
```

#### 缓存击穿防护（分布式锁）
```java
public class DistributedLockProtection implements CacheProtection {
    private final RedissonClient redissonClient;
    
    @Override
    public <T> T loadWithLock(String key, Supplier<T> loader) {
        RLock lock = redissonClient.getLock("cache:lock:" + key);
        try {
            if (lock.tryLock(5, TimeUnit.SECONDS)) {
                // 双重检查，避免重复加载
                T cached = getFromCache(key);
                if (cached != null) {
                    return cached;
                }
                return loader.get();
            }
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
        throw new CacheException("Failed to acquire lock for key: " + key);
    }
}
```

## 五、Spring Boot 集成

### 5.1 自动配置

```java
@Configuration
@EnableConfigurationProperties(CascadeCacheProperties.class)
@ConditionalOnClass({RedissonClient.class, Cache.class})
public class CascadeCacheAutoConfiguration {
    
    @Bean
    @ConditionalOnMissingBean
    public CascadeCacheManager cacheManager(
            CascadeCacheProperties properties,
            RedissonClient redissonClient) {
        return CascadeCacheBuilder.newBuilder()
                .redissonClient(redissonClient)
                .localCacheConfig(properties.getLocal())
                .remoteCacheConfig(properties.getRemote())
                .syncConfig(properties.getSync())
                .protectionConfig(properties.getProtection())
                .build();
    }
}
```

### 5.2 配置属性

```yaml
cascade:
  cache:
    # 本地缓存配置
    local:
      maximum-size: 10000
      expire-after-write: 30m
      expire-after-access: 10m
      refresh-after-write: 5m
    
    # 远程缓存配置
    remote:
      default-ttl: 1h
      max-idle-time: 30m
      key-prefix: "cascade:"
    
    # 同步配置
    sync:
      enabled: true
      strategy: IMMEDIATE
      topic: "cascade:cache:sync"
      batch-size: 100
    
    # 防护配置
    protection:
      bloom-filter:
        enabled: true
        expected-insertions: 1000000
        false-positive-probability: 0.01
      
      random-ttl:
        enabled: true
        variance-percentage: 20
      
      distributed-lock:
        enabled: true
        wait-time: 5s
        lease-time: 30s
    
    # 预热配置
    warmup:
      enabled: true
      strategies:
        - type: DATABASE
          config: "SELECT id, data FROM hot_data"
          batch-size: 1000
        - type: FILE
          config: "classpath:cache-warmup.json"
```

### 5.3 注解支持

```java
@CascadeCacheable(
    value = "userCache",
    key = "#userId",
    condition = "#userId > 0",
    unless = "#result == null"
)
public User getUserById(Long userId) {
    return userRepository.findById(userId);
}

@CascadeCacheEvict(
    value = "userCache",
    key = "#user.id",
    beforeInvocation = false
)
public void updateUser(User user) {
    userRepository.save(user);
}

@CascadeCachePut(
    value = "userCache",
    key = "#result.id",
    condition = "#result != null"
)
public User createUser(User user) {
    return userRepository.save(user);
}
```

## 六、监控与统计

### 6.1 缓存指标

```java
public class CacheMetrics {
    // 命中率统计
    private final AtomicLong hitCount = new AtomicLong();
    private final AtomicLong missCount = new AtomicLong();
    
    // 加载统计
    private final AtomicLong loadCount = new AtomicLong();
    private final AtomicLong loadTime = new AtomicLong();
    
    // 淘汰统计
    private final AtomicLong evictionCount = new AtomicLong();
    
    // 大小统计
    private volatile long estimatedSize;
    
    public double hitRate() {
        long hits = hitCount.get();
        long total = hits + missCount.get();
        return total == 0 ? 1.0 : (double) hits / total;
    }
    
    public double averageLoadTime() {
        long loads = loadCount.get();
        return loads == 0 ? 0.0 : (double) loadTime.get() / loads;
    }
}
```

### 6.2 Micrometer 集成

```java
@Component
public class CacheMetricsCollector {
    
    @EventListener
    public void handleCacheHit(CacheHitEvent event) {
        Metrics.counter("cascade.cache.hits", 
                "cache", event.getCacheName(),
                "tier", event.getTier().name())
               .increment();
    }
    
    @EventListener
    public void handleCacheMiss(CacheMissEvent event) {
        Metrics.counter("cascade.cache.misses",
                "cache", event.getCacheName(),
                "tier", event.getTier().name())
               .increment();
    }
    
    @Scheduled(fixedRate = 30000)
    public void recordCacheSize() {
        cacheManager.getCacheNames().forEach(cacheName -> {
            Cache cache = cacheManager.getCache(cacheName);
            Metrics.gauge("cascade.cache.size",
                    Tags.of("cache", cacheName),
                    cache.estimatedSize());
        });
    }
}
```

## 七、使用示例

### 7.1 基本使用

```java
@Service
public class UserService {
    
    @Autowired
    private CascadeCacheManager cacheManager;
    
    public User getUser(Long userId) {
        Cache<Long, User> userCache = cacheManager.getCache("users");
        return userCache.get(userId, this::loadUserFromDB);
    }
    
    private User loadUserFromDB(Long userId) {
        return userRepository.findById(userId).orElse(null);
    }
}
```

### 7.2 批量操作

```java
public Map<Long, User> getUsers(Set<Long> userIds) {
    Cache<Long, User> userCache = cacheManager.getCache("users");
    return userCache.getAll(userIds, this::loadUsersFromDB);
}

private Map<Long, User> loadUsersFromDB(Set<Long> userIds) {
    return userRepository.findAllById(userIds)
            .stream()
            .collect(Collectors.toMap(User::getId, Function.identity()));
}
```

### 7.3 异步操作

```java
public CompletableFuture<User> getUserAsync(Long userId) {
    AsyncCache<Long, User> userCache = cacheManager.getAsyncCache("users");
    return userCache.get(userId, this::loadUserFromDBAsync);
}

private CompletableFuture<User> loadUserFromDBAsync(Long userId) {
    return CompletableFuture.supplyAsync(() -> 
        userRepository.findById(userId).orElse(null));
}
```

## 八、性能优化

### 8.1 内存优化
- **对象池化**：重用缓存条目对象，减少GC压力
- **压缩存储**：支持数据压缩，节省内存空间
- **弱引用**：L1缓存支持弱引用，自动释放不常用数据

### 8.2 网络优化
- **批量操作**：合并多个缓存操作，减少网络往返
- **管道化**：使用 Redis Pipeline 提升吞吐量
- **连接池**：复用 Redis 连接，避免频繁建连

### 8.3 序列化优化
- **高效序列化**：支持 Kryo、Protobuf 等高效序列化方案
- **类型感知**：根据数据类型选择最优序列化策略
- **压缩传输**：网络传输时自动压缩数据

## 九、最佳实践

### 9.1 缓存设计原则
1. **合理设置TTL**：根据数据更新频率设置合适的过期时间
2. **控制缓存大小**：避免缓存占用过多内存影响应用性能
3. **选择合适的Key**：使用有意义且唯一的缓存键
4. **避免大对象**：缓存对象不宜过大，影响序列化性能

### 9.2 性能调优建议
1. **监控命中率**：保持较高的缓存命中率（>80%）
2. **合理分层**：热点数据放L1，大容量数据放L2
3. **预热策略**：应用启动时预热核心数据
4. **定期清理**：清理过期和无效的缓存数据

### 9.3 故障处理
1. **降级策略**：缓存不可用时自动降级到数据源
2. **熔断保护**：防止缓存故障影响整个系统
3. **监控告警**：及时发现和处理缓存异常
4. **数据一致性**：确保缓存与数据源的数据一致性
cascade-cache/
└── src/main/java/io/github/cascade/cache/
    ├── CascadeCache.java                 # 缓存门面
    ├── CascadeCacheManager.java          # 缓存管理器
    ├── CascadeCacheBuilder.java          # 缓存构建器
    │
    ├── api/                              # 缓存API
    │   ├── Cache.java                    # 基础缓存接口
    │   ├── TieredCache.java              # 多级缓存接口
    │   ├── AsyncCache.java               # 异步缓存接口
    │   ├── LoadingCache.java             # 自动加载缓存
    │   ├── CacheLoader.java              # 缓存加载器
    │   ├── CacheWriter.java              # 缓存写入器
    │   └── EvictionPolicy.java           # 淘汰策略
    │
    ├── core/                             # 核心实现
    │   ├── AbstractCache.java            # 缓存基类
    │   ├── DefaultTieredCache.java       # 默认多级缓存
    │   ├── LocalCache.java               # 本地缓存
    │   ├── RemoteCache.java              # 远程缓存
    │   ├── CacheProxy.java               # 缓存代理
    │   └── CacheEntry.java               # 缓存条目
    │
    ├── tier/                             # 缓存层级
    │   ├── Tier.java                     # 层级接口
    │   ├── LocalTier.java                # 本地层
    │   ├── RemoteTier.java               # 远程层
    │   ├── TierConfig.java               # 层级配置
    │   └── TierChain.java                # 层级链
    │
    ├── sync/                             # 缓存同步
    │   ├── CacheSynchronizer.java        # 同步器接口
    │   ├── RedisPubSubSynchronizer.java  # Redis发布订阅同步
    │   ├── RedisStreamSynchronizer.java  # Redis Stream同步
    │   ├── SyncMessage.java              # 同步消息
    │   ├── SyncStrategy.java             # 同步策略
    │   └── ConflictResolver.java         # 冲突解决器
    │
    ├── loader/                           # 加载机制
    │   ├── CacheLoaderAdapter.java       # 加载器适配器
    │   ├── BatchCacheLoader.java         # 批量加载器
    │   ├── AsyncCacheLoader.java         # 异步加载器
    │   ├── RefreshAheadLoader.java       # 预刷新加载器
    │   └── LoaderChain.java              # 加载器链
    │
    ├── policy/                           # 缓存策略
    │   ├── EvictionStrategy.java         # 淘汰策略
    │   ├── RefreshStrategy.java          # 刷新策略
    │   ├── ExpiryPolicy.java             # 过期策略
    │   ├── impl/
    │   │   ├── LRUEviction.java          # LRU淘汰
    │   │   ├── LFUEviction.java          # LFU淘汰
    │   │   ├── FIFOEviction.java         # FIFO淘汰
    │   │   ├── TimeBasedExpiry.java      # 基于时间过期
    │   │   └── TouchedExpiry.java        # 访问过期
    │   └── PolicyChain.java              # 策略链
    │
    ├── warmer/                           # 缓存预热
    │   ├── CacheWarmer.java              # 预热器接口
    │   ├── ScheduledWarmer.java          # 定时预热
    │   ├── DataSourceWarmer.java         # 数据源预热
    │   ├── FileBasedWarmer.java          # 文件预热
    │   └── WarmupStrategy.java           # 预热策略
    │
    ├── stats/                            # 统计监控
    │   ├── CacheStats.java               # 缓存统计
    │   ├── StatsCollector.java           # 统计收集器
    │   ├── HitRateCalculator.java        # 命中率计算
    │   ├── CacheMetrics.java             # 缓存指标
    │   └── StatsSnapshot.java            # 统计快照
    │
    ├── event/                            # 缓存事件
    │   ├── CacheEvent.java               # 缓存事件基类
    │   ├── CacheEventListener.java       # 事件监听器
    │   ├── CacheEventPublisher.java      # 事件发布器
    │   ├── events/
    │   │   ├── CacheHitEvent.java         # 缓存命中事件
    │   │   ├── CacheMissEvent.java        # 缓存未命中事件
    │   │   ├── CacheEvictionEvent.java    # 缓存淘汰事件
    │   │   └── CacheLoadEvent.java        # 缓存加载事件
    │   └── EventBus.java                 # 事件总线
    │
    ├── protection/                       # 缓存防护
    │   ├── CacheProtection.java          # 防护接口
    │   ├── BloomFilterProtection.java    # 布隆过滤器防穿透
    │   ├── DistributedLockProtection.java # 分布式锁防击穿
    │   ├── RandomTtlProtection.java      # 随机TTL防雪崩
    │   ├── CircuitBreakerProtection.java # 熔断器保护
    │   └── ProtectionChain.java          # 防护链
    │
    ├── serialization/                    # 序列化
    │   ├── CacheSerializer.java          # 序列化接口
    │   ├── JsonSerializer.java           # JSON序列化
    │   ├── KryoSerializer.java           # Kryo序列化
    │   ├── ProtobufSerializer.java       # Protobuf序列化
    │   └── SerializerFactory.java        # 序列化工厂
    │
    ├── config/                           # 配置管理
    │   ├── CacheConfig.java              # 缓存配置
    │   ├── TierConfig.java               # 层级配置
    │   ├── SyncConfig.java               # 同步配置
    │   ├── ProtectionConfig.java         # 防护配置
    │   └── ConfigValidator.java          # 配置验证器
    │
    └── spring/                           # Spring集成
        ├── CacheAutoConfiguration.java   # 自动配置
        ├── CacheProperties.java          # 配置属性
        ├── CacheAnnotationProcessor.java # 注解处理器
        ├── annotations/
        │   ├── CascadeCacheable.java      # 缓存注解
        │   ├── CascadeCacheEvict.java     # 缓存清除注解
        │   ├── CascadeCachePut.java       # 缓存更新注解
        │   └── EnableCascadeCache.java    # 启用缓存注解
        └── aspect/
            ├── CacheAspect.java           # 缓存切面
            └── CacheInterceptor.java      # 缓存拦截器
```
    