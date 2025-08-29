# 缓存层设计统一方案

## 当前问题分析

您观察得很准确！当前的设计确实存在不一致性：

### 现状对比

```java
// RedissonRemoteTier - 继承抽象类
public class RedissonRemoteTier<K, V> extends AbstractCacheTier<K, V> {
    // 基于Redisson的实现
}

// RedisRemoteTier - 实现接口
public class RedisRemoteTier<K, V> implements RemoteTier<K, V> {
    // 基于原生Redis的实现
}
```

### 问题分析

1. **架构不统一** - 一个继承抽象类，一个实现接口
2. **代码重复** - 统计信息、通用方法可能在两个类中重复实现
3. **维护困难** - 两种不同的设计模式增加维护成本
4. **扩展性差** - 新增缓存层实现时不知道该用哪种方式

## 推荐的统一设计方案

### 方案1：统一继承AbstractCacheTier（推荐）

```java
// 统一的抽象基类
public abstract class AbstractCacheTier<K, V> implements Cache<K, V> {
    
    protected final String name;
    protected final CacheTier tier;
    protected final AtomicLong hitCount = new AtomicLong(0);
    protected final AtomicLong missCount = new AtomicLong(0);
    // ... 其他通用字段和方法
    
    protected AbstractCacheTier(String name, CacheTier tier) {
        this.name = name;
        this.tier = tier;
    }
    
    // 通用的统计方法
    protected void recordHit() { hitCount.incrementAndGet(); }
    protected void recordMiss() { missCount.incrementAndGet(); }
    
    // 通用的Cache接口实现
    @Override
    public String getName() { return name; }
    
    @Override
    public CacheStats getStats() { 
        return new CacheTierStats(); 
    }
    
    // 抽象方法 - 子类必须实现
    public abstract V get(K key);
    public abstract void put(K key, V value);
    public abstract void evict(K key);
    public abstract void clear();
    // ... 其他核心方法
}

// RemoteTier接口 - 定义远程缓存特有功能
public interface RemoteTier<K, V> {
    
    // 远程缓存特有方法
    void put(K key, V value, Duration ttl);
    boolean putIfAbsent(K key, V value, Duration ttl);
    Duration getTimeToLive(K key);
    boolean expire(K key, Duration ttl);
    boolean persist(K key);
    Set<K> keys(String pattern);
    boolean isConnected();
    Map<String, Object> getClusterInfo();
    Object eval(String script, List<K> keys, Object... args);
    Pipeline pipeline();
}

// 统一的远程缓存抽象类
public abstract class AbstractRemoteTier<K, V> extends AbstractCacheTier<K, V> 
                                                implements RemoteTier<K, V> {
    
    protected AbstractRemoteTier(String name) {
        super(name, CacheTier.L2);
    }
    
    // 实现一些通用的远程缓存逻辑
    @Override
    public void put(K key, V value) {
        put(key, value, getDefaultTtl());
    }
    
    protected abstract Duration getDefaultTtl();
}

// 具体实现类 - 继承统一的抽象类
public class RedissonRemoteTier<K, V> extends AbstractRemoteTier<K, V> {
    
    private final RedissonClient redissonClient;
    private final Duration defaultTtl;
    
    public RedissonRemoteTier(String name, RedissonClient client, Duration defaultTtl) {
        super(name);
        this.redissonClient = client;
        this.defaultTtl = defaultTtl;
    }
    
    @Override
    protected Duration getDefaultTtl() {
        return defaultTtl;
    }
    
    @Override
    public V get(K key) {
        // Redisson具体实现
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
            recordMiss();
            return null;
        }
    }
    
    @Override
    public void put(K key, V value, Duration ttl) {
        // Redisson具体实现
        try {
            RBucket<V> bucket = redissonClient.getBucket(buildKey(key));
            if (ttl != null) {
                bucket.set(value, ttl);
            } else {
                bucket.set(value);
            }
        } catch (Exception e) {
            throw new RuntimeException("Error putting value", e);
        }
    }
    
    // ... 其他RemoteTier接口的实现
}

public class RedisRemoteTier<K, V> extends AbstractRemoteTier<K, V> {
    
    private final RedisClient redisClient;
    private final Duration defaultTtl;
    
    public RedisRemoteTier(String name, RedisClient client, Duration defaultTtl) {
        super(name);
        this.redisClient = client;
        this.defaultTtl = defaultTtl;
    }
    
    @Override
    protected Duration getDefaultTtl() {
        return defaultTtl;
    }
    
    @Override
    public V get(K key) {
        // 原生Redis具体实现
        try {
            String value = redisClient.get(buildKey(key));
            if (value != null) {
                recordHit();
                return deserialize(value);
            } else {
                recordMiss();
                return null;
            }
        } catch (Exception e) {
            recordMiss();
            return null;
        }
    }
    
    // ... 其他实现
}
```

### 方案2：多级接口设计

```java
// 基础缓存接口
public interface Cache<K, V> {
    V get(K key);
    void put(K key, V value);
    void evict(K key);
    void clear();
    String getName();
    CacheStats getStats();
    // ... 其他基础方法
}

// 本地缓存接口
public interface LocalTier<K, V> extends Cache<K, V> {
    long maximumSize();
    void setMaximumSize(long size);
    Duration expireAfterWrite();
    Duration expireAfterAccess();
}

// 远程缓存接口
public interface RemoteTier<K, V> extends Cache<K, V> {
    void put(K key, V value, Duration ttl);
    Duration getTimeToLive(K key);
    boolean isConnected();
    // ... 远程特有方法
}

// 抽象实现提供通用功能
public abstract class AbstractCache<K, V> implements Cache<K, V> {
    // 通用实现
}

public abstract class AbstractLocalTier<K, V> extends AbstractCache<K, V> 
                                               implements LocalTier<K, V> {
    // 本地缓存通用实现
}

public abstract class AbstractRemoteTier<K, V> extends AbstractCache<K, V> 
                                                implements RemoteTier<K, V> {
    // 远程缓存通用实现
}
```

## 具体修改建议

### 立即修改方案

1. **保持当前RedissonRemoteTier的继承方式**
2. **修改RedisRemoteTier也继承AbstractCacheTier**
3. **让AbstractCacheTier实现Cache接口**

```java
// 修改AbstractCacheTier
public abstract class AbstractCacheTier<K, V> implements Cache<K, V> {
    // 现有实现 + Cache接口方法
}

// 修改RedisRemoteTier
public class RedisRemoteTier<K, V> extends AbstractCacheTier<K, V> 
                                    implements RemoteTier<K, V> {
    
    public RedisRemoteTier(String name, RedisClient client) {
        super(name, CacheTier.L2);  // 调用父类构造器
    }
    
    // 继承AbstractCacheTier的通用功能
    // 实现RemoteTier的特有功能
}
```

### 长期重构方案

1. **统一架构模式** - 所有缓存层都继承AbstractCacheTier
2. **接口分层设计** - Cache <- LocalTier/RemoteTier <- 具体接口
3. **消除代码重复** - 统计、日志、异常处理等通用逻辑抽取到抽象类
4. **提高扩展性** - 新增缓存层时有明确的继承规范

## 实施步骤

1. **第一步**：修改AbstractCacheTier实现Cache接口
2. **第二步**：修改RedisRemoteTier继承AbstractCacheTier  
3. **第三步**：重构RemoteTier接口，去除与Cache重复的方法
4. **第四步**：验证两个实现类的功能一致性
5. **第五步**：更新文档和使用示例

这样统一后，整个缓存层架构会更加清晰、一致，便于维护和扩展。