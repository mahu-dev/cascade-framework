# Cascade Lock 模块设计文档

## 一、模块概述

`cascade-lock` 提供全面的分布式锁解决方案，支持多种锁类型（可重入锁、公平锁、读写锁、红锁等），具备自动续期、死锁检测、锁降级等企业级特性。

## 二、包结构

```
cascade-lock/
└── src/main/java/io/github/cascade/lock/
    ├── CascadeLock.java                  # 锁门面
    ├── CascadeLockManager.java           # 锁管理器
    ├── CascadeLockBuilder.java           # 锁构建器
    │
    ├── api/                              # 锁API
    │   ├── Lock.java                     # 基础锁接口
    │   ├── DistributedLock.java          # 分布式锁接口
    │   ├── ReentrantLock.java            # 可重入锁接口
    │   ├── ReadWriteLock.java            # 读写锁接口
    │   ├── MultiLock.java                # 联锁接口
    │   ├── RedLock.java                  # 红锁接口
    │   ├── Semaphore.java                # 信号量接口
    │   ├── CountDownLatch.java           # 倒计时锁接口
    │   └── FencedLock.java               # 栅栏锁接口
    │
    ├── core/                             # 核心实现
    │   ├── AbstractDistributedLock.java  # 锁基类
    │   ├── RedissonReentrantLock.java    # 可重入锁实现
    │   ├── RedissonFairLock.java         # 公平锁实现
    │   ├── RedissonReadWriteLock.java    # 读写锁实现
    │   ├── RedissonMultiLock.java        # 联锁实现
    │   ├── RedissonRedLock.java          # 红锁实现
    │   ├── RedissonSemaphore.java        # 信号量实现
    │   ├── RedissonCountDownLatch.java   # 倒计时锁实现
    │   └── RedissonFencedLock.java       # 栅栏锁实现
    │
    ├── support/                          # 支持类
    │   ├── LockContext.java              # 锁上下文
    │   ├── LockToken.java                # 锁令牌
    │   ├── LockInfo.java                 # 锁信息
    │   ├── LockHolder.java               # 锁持有者
    │   ├── LockRegistry.java             # 锁注册表
    │   └── LockKey.java                  # 锁键生成
    │
    ├── watchdog/                         # 看门狗机制
    │   ├── WatchDog.java                 # 看门狗接口
    │   ├── DefaultWatchDog.java          # 默认看门狗
    │   ├── WatchDogConfig.java           # 看门狗配置
    │   └── LeaseRenewalTask.java         # 续期任务
    │
    ├── template/                         # 锁模板
    │   ├── LockTemplate.java             # 锁操作模板
    │   ├── LockCallback.java             # 锁回调接口
    │   ├── RetryPolicy.java              # 重试策略
    │   └── BackoffStrategy.java          # 退避策略
    │
    ├── monitor/                          # 监控诊断
    │   ├── LockMonitor.java              # 锁监控器
    │   ├── DeadlockDetector.java         # 死锁检测器
    │   ├── LockMetrics.java              # 锁指标
    │   ├── LockTracer.java               # 锁追踪器
    │   └── LockDiagnostics.java          # 锁诊断
    │
    ├── event/                            # 锁事件
    │   ├── LockEvent.java                # 锁事件基类
    │   ├── LockAcquiredEvent.java        # 获取锁事件
    │   ├── LockReleasedEvent.java        # 释放锁事件
    │   ├── LockTimeoutEvent.java         # 锁超时事件
    │   ├── LockRenewedEvent.java         # 锁续期事件
    │   └── DeadlockDetectedEvent.java    # 死锁检测事件
    │
    ├── exception/                        # 异常处理
    │   ├── LockException.java            # 锁异常基类
    │   ├── LockTimeoutException.java     # 锁超时异常
    │   ├── LockConflictException.java    # 锁冲突异常
    │   ├── DeadlockException.java        # 死锁异常
    │   └── LockExpiredException.java     # 锁过期异常
    │
    └── aspect/                           # AOP支持
        ├── LockAspect.java               # 锁切面
        ├── Locked.java                   # 锁注解
        ├── TryLock.java                  # 尝试锁注解
        └── WithLock.java                 # 带锁执行注解
```

## 三、核心接口定义

### 3.1 CascadeLock - 锁门面

```java
package io.github.cascade.lock;

/**
 * Cascade锁组件门面
 * 提供分布式锁功能的统一入口
 */
@Component
public class CascadeLock implements CascadeComponent {
    
    private final CascadeLockManager lockManager;
    private final CascadeContext context;
    private final LockMonitor lockMonitor;
    private final DeadlockDetector deadlockDetector;
    
    /**
     * 获取或创建锁
     */
    public DistributedLock lock(String name) {
        return lockManager.getLock(name);
    }

/**
 * 死锁报告
 */
@Data
@Builder
public class DeadlockReport {
    private boolean hasDeadlock;
    private List<List<String>> cycles;
    private List<LockInfo> affectedLocks;
    private long timestamp;
    private String resolution;
    
    public static DeadlockReport noDeadlock() {
        return DeadlockReport.builder()
            .hasDeadlock(false)
            .cycles(Collections.emptyList())
            .affectedLocks(Collections.emptyList())
            .timestamp(System.currentTimeMillis())
            .build();
    }
}
```

### 3.8 锁监控指标

```java
package io.github.cascade.lock.monitor;

/**
 * 锁监控指标
 */
@Component
public class LockMetrics {
    
    // 计数器
    private final AtomicLong totalAcquisitions = new AtomicLong();
    private final AtomicLong successfulAcquisitions = new AtomicLong();
    private final AtomicLong failedAcquisitions = new AtomicLong();
    private final AtomicLong timeouts = new AtomicLong();
    private final AtomicLong releases = new AtomicLong();
    private final AtomicLong forceUnlocks = new AtomicLong();
    
    // 统计信息
    private final ConcurrentHashMap<String, LockStats> lockStats = new ConcurrentHashMap<>();
    
    /**
     * 记录获取锁
     */
    public void recordAcquisition(String lockName, long waitTime) {
        totalAcquisitions.incrementAndGet();
        successfulAcquisitions.incrementAndGet();
        
        lockStats.compute(lockName, (k, v) -> {
            if (v == null) v = new LockStats(lockName);
            v.recordAcquisition(waitTime);
            return v;
        });
    }
    
    /**
     * 记录获取失败
     */
    public void recordFailure(String lockName) {
        totalAcquisitions.incrementAndGet();
        failedAcquisitions.incrementAndGet();
        
        lockStats.compute(lockName, (k, v) -> {
            if (v == null) v = new LockStats(lockName);
            v.recordFailure();
            return v;
        });
    }
    
    /**
     * 记录超时
     */
    public void recordTimeout(String lockName) {
        timeouts.incrementAndGet();
        
        lockStats.compute(lockName, (k, v) -> {
            if (v == null) v = new LockStats(lockName);
            v.recordTimeout();
            return v;
        });
    }
    
    /**
     * 获取快照
     */
    public MetricsSnapshot snapshot() {
        return MetricsSnapshot.builder()
            .totalAcquisitions(totalAcquisitions.get())
            .successfulAcquisitions(successfulAcquisitions.get())
            .failedAcquisitions(failedAcquisitions.get())
            .timeouts(timeouts.get())
            .releases(releases.get())
            .forceUnlocks(forceUnlocks.get())
            .successRate(getSuccessRate())
            .lockStats(new HashMap<>(lockStats))
            .timestamp(System.currentTimeMillis())
            .build();
    }
    
    /**
     * 获取成功率
     */
    public double getSuccessRate() {
        long total = totalAcquisitions.get();
        if (total == 0) return 0.0;
        return (double) successfulAcquisitions.get() / total;
    }
    
    /**
     * 单个锁的统计信息
     */
    @Data
    public static class LockStats {
        private final String lockName;
        private final AtomicLong acquisitions = new AtomicLong();
        private final AtomicLong failures = new AtomicLong();
        private final AtomicLong timeouts = new AtomicLong();
        private final AtomicLong totalWaitTime = new AtomicLong();
        private final AtomicLong maxWaitTime = new AtomicLong();
        private final AtomicLong minWaitTime = new AtomicLong(Long.MAX_VALUE);
        
        public void recordAcquisition(long waitTime) {
            acquisitions.incrementAndGet();
            totalWaitTime.addAndGet(waitTime);
            updateMaxMin(waitTime);
        }
        
        private void updateMaxMin(long waitTime) {
            long max = maxWaitTime.get();
            while (waitTime > max && !maxWaitTime.compareAndSet(max, waitTime)) {
                max = maxWaitTime.get();
            }
            
            long min = minWaitTime.get();
            while (waitTime < min && !minWaitTime.compareAndSet(min, waitTime)) {
                min = minWaitTime.get();
            }
        }
        
        public double getAverageWaitTime() {
            long count = acquisitions.get();
            return count == 0 ? 0.0 : (double) totalWaitTime.get() / count;
        }
    }
}
```

### 3.9 注解支持

```java
package io.github.cascade.lock.aspect;

/**
 * 分布式锁注解
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface WithLock {
    /**
     * 锁名称，支持SpEL表达式
     */
    String value() default "";
    
    /**
     * 锁名称（同value）
     */
    String name() default "";
    
    /**
     * 锁键，支持SpEL表达式
     */
    String key() default "";
    
    /**
     * 等待时间
     */
    long waitTime() default 10;
    
    /**
     * 租期
     */
    long leaseTime() default 30;
    
    /**
     * 时间单位
     */
    TimeUnit timeUnit() default TimeUnit.SECONDS;
    
    /**
     * 锁类型
     */
    LockType type() default LockType.REENTRANT;
    
    /**
     * 是否公平锁
     */
    boolean fair() default false;
    
    /**
     * 获取锁失败时的处理
     */
    FailureStrategy onFailure() default FailureStrategy.THROW_EXCEPTION;
}

/**
 * 尝试获取锁注解
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface TryLock {
    String value() default "";
    String name() default "";
    String key() default "";
    long timeout() default 0;
    TimeUnit timeUnit() default TimeUnit.SECONDS;
    String fallbackMethod() default "";
}

/**
 * 锁切面
 */
@Aspect
@Component
@Slf4j
public class LockAspect {
    
    private final CascadeLock cascadeLock;
    private final ExpressionParser parser = new SpelExpressionParser();
    
    @Around("@annotation(withLock)")
    public Object aroundWithLock(ProceedingJoinPoint joinPoint, WithLock withLock) throws Throwable {
        String lockName = resolveLockName(joinPoint, withLock);
        
        DistributedLock lock = cascadeLock.builder(lockName)
            .type(withLock.type())
            .waitTime(Duration.of(withLock.waitTime(), toChronoUnit(withLock.timeUnit())))
            .leaseTime(Duration.of(withLock.leaseTime(), toChronoUnit(withLock.timeUnit())))
            .fair(withLock.fair())
            .build();
        
        boolean acquired = false;
        try {
            acquired = lock.tryLock(withLock.waitTime(), withLock.leaseTime(), withLock.timeUnit());
            
            if (acquired) {
                return joinPoint.proceed();
            } else {
                return handleLockFailure(joinPoint, withLock);
            }
        } finally {
            if (acquired) {
                lock.unlock();
            }
        }
    }
    
    @Around("@annotation(tryLock)")
    public Object aroundTryLock(ProceedingJoinPoint joinPoint, TryLock tryLock) throws Throwable {
        String lockName = resolveLockName(joinPoint, tryLock);
        
        return cascadeLock.template()
            .lock(lockName)
            .waitTime(Duration.of(tryLock.timeout(), toChronoUnit(tryLock.timeUnit())))
            .tryExecute(() -> {
                try {
                    return joinPoint.proceed();
                } catch (Throwable e) {
                    throw new RuntimeException(e);
                }
            })
            .orElseGet(() -> {
                if (StringUtils.isNotBlank(tryLock.fallbackMethod())) {
                    return invokeFallbackMethod(joinPoint, tryLock.fallbackMethod());
                }
                return null;
            });
    }
    
    private String resolveLockName(ProceedingJoinPoint joinPoint, Object annotation) {
        // 解析SpEL表达式生成锁名称
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        Object[] args = joinPoint.getArgs();
        
        String name = "";
        String key = "";
        
        if (annotation instanceof WithLock) {
            WithLock withLock = (WithLock) annotation;
            name = StringUtils.isNotBlank(withLock.name()) ? withLock.name() : withLock.value();
            key = withLock.key();
        } else if (annotation instanceof TryLock) {
            TryLock tryLock = (TryLock) annotation;
            name = StringUtils.isNotBlank(tryLock.name()) ? tryLock.name() : tryLock.value();
            key = tryLock.key();
        }
        
        if (StringUtils.isBlank(name)) {
            name = method.getDeclaringClass().getSimpleName() + "." + method.getName();
        }
        
        if (StringUtils.isNotBlank(key)) {
            EvaluationContext context = new StandardEvaluationContext();
            for (int i = 0; i < args.length; i++) {
                context.setVariable("p" + i, args[i]);
            }
            
            Expression expression = parser.parseExpression(key);
            Object keyValue = expression.getValue(context);
            name = name + ":" + keyValue;
        }
        
        return name;
    }
}
```

## 四、高级特性

### 4.1 红锁实现

```java
package io.github.cascade.lock.core;

/**
 * 红锁实现 - 提供更高的可靠性
 */
public class RedissonRedLock implements RedLock {
    
    private final List<RLock> locks;
    private final int quorum;
    
    public RedissonRedLock(List<RLock> locks) {
        this.locks = locks;
        this.quorum = locks.size() / 2 + 1;
    }
    
    @Override
    public boolean tryLock(long waitTime, long leaseTime, TimeUnit unit) throws InterruptedException {
        long start = System.currentTimeMillis();
        long waitMillis = unit.toMillis(waitTime);
        List<RLock> acquiredLocks = new ArrayList<>();
        
        try {
            for (RLock lock : locks) {
                long remainingTime = waitMillis - (System.currentTimeMillis() - start);
                if (remainingTime <= 0) {
                    break;
                }
                
                if (lock.tryLock(remainingTime, leaseTime, TimeUnit.MILLISECONDS)) {
                    acquiredLocks.add(lock);
                }
                
                if (acquiredLocks.size() >= quorum) {
                    return true;
                }
            }
            
            // 未达到法定数量，释放已获取的锁
            releaseAll(acquiredLocks);
            return false;
            
        } catch (Exception e) {
            releaseAll(acquiredLocks);
            throw e;
        }
    }
    
    @Override
    public void unlock() {
        releaseAll(locks);
    }
    
    private void releaseAll(List<RLock> locks) {
        for (RLock lock : locks) {
            try {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            } catch (Exception e) {
                log.error("Failed to release lock", e);
            }
        }
    }
}
```

### 4.2 分段锁实现

```java
package io.github.cascade.lock.core;

/**
 * 分段锁 - 提高并发性能
 */
public class SegmentedLock<K> {
    
    private final int segments;
    private final DistributedLock[] locks;
    private final Function<K, Integer> hashFunction;
    
    public SegmentedLock(String baseName, int segments) {
        this.segments = segments;
        this.locks = new DistributedLock[segments];
        this.hashFunction = key -> Math.abs(key.hashCode() % segments);
        
        for (int i = 0; i < segments; i++) {
            locks[i] = new RedissonReentrantLock(baseName + ":" + i);
        }
    }
    
    public void lock(K key) {
        int segment = hashFunction.apply(key);
        locks[segment].lock();
    }
    
    public boolean tryLock(K key, long time, TimeUnit unit) throws InterruptedException {
        int segment = hashFunction.apply(key);
        return locks[segment].tryLock(time, unit);
    }
    
    public void unlock(K key) {
        int segment = hashFunction.apply(key);
        locks[segment].unlock();
    }
    
    public <T> T executeWithLock(K key, Supplier<T> action) {
        lock(key);
        try {
            return action.get();
        } finally {
            unlock(key);
        }
    }
}
```

## 五、使用示例

### 5.1 基础使用

```java
@Service
public class OrderService {
    
    @Autowired
    private Cascade cascade;
    
    public void processOrder(Long orderId) {
        // 方式1：使用锁模板
        cascade.lock().template()
            .lock("order:" + orderId)
            .waitTime(Duration.ofSeconds(5))
            .execute(() -> {
                // 处理订单逻辑
                Order order = orderRepository.findById(orderId);
                order.setStatus(OrderStatus.PROCESSING);
                orderRepository.save(order);
                return order;
            });
        
        // 方式2：手动管理锁
        DistributedLock lock = cascade.lock().lock("order:" + orderId);
        try {
            if (lock.tryLock(5, 30, TimeUnit.SECONDS)) {
                try {
                    // 处理逻辑
                } finally {
                    lock.unlock();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    // 方式3：使用注解
    @WithLock(name = "order", key = "#orderId", waitTime = 5)
    public void updateOrder(Long orderId, OrderUpdateDto dto) {
        // 自动加锁的业务逻辑
    }
    
    @TryLock(name = "inventory", key = "#skuId", timeout = 3, fallbackMethod = "handleLockTimeout")
    public boolean decreaseInventory(Long skuId, int quantity) {
        // 减库存逻辑
        return true;
    }
    
    public boolean handleLockTimeout(Long skuId, int quantity) {
        log.warn("Failed to acquire lock for SKU: {}", skuId);
        return false;
    }
}
```

### 5.2 高级使用

```java
@Component
public class AdvancedLockExample {
    
    @Autowired
    private CascadeLock cascadeLock;
    
    public void advancedUsage() {
        // 1. 红锁
        RedLock redLock = cascadeLock.redLock("lock1", "lock2", "lock3");
        if (redLock.tryLock(5, 30, TimeUnit.SECONDS)) {
            try {
                // 高可靠性操作
            } finally {
                redLock.unlock();
            }
        }
        
        // 2. 读写锁
        ReadWriteLock rwLock = cascadeLock.readWriteLock("data");
        
        // 读操作
        rwLock.readLock().lock();
        try {
            // 读取数据
        } finally {
            rwLock.readLock().unlock();
        }
        
        // 写操作
        rwLock.writeLock().lock();
        try {
            // 修改数据
        } finally {
            rwLock.writeLock().unlock();
        }
        
        // 3. 信号量
        Semaphore semaphore = cascadeLock.semaphore("resource", 10);
        try {
            semaphore.acquire(3);
            // 使用资源
        } finally {
            semaphore.release(3);
        }
        
        // 4. 分段锁
        SegmentedLock<String> segmentedLock = new SegmentedLock<>("user", 16);
        segmentedLock.executeWithLock(userId, () -> {
            // 按用户ID分段的操作
            return userService.updateUser(userId);
        });
    }
}
```

## 六、配置说明

```yaml
cascade:
  lock:
    # 默认配置
    defaults:
      wait-time: 10s
      lease-time: 30s
      auto-renew: true
      fair: false
      
    # 看门狗配置
    watchdog:
      enabled: true
      renewal-interval: 10s  # 续期间隔（lease-time的1/3）
      
    # 死锁检测
    deadlock:
      detection-enabled: true
      check-interval: 30s
      auto-resolve: false
      resolution-strategy: VICTIM_SELECTION  # VICTIM_SELECTION, TIMEOUT_BASED
      
    # 监控配置
    monitoring:
      enabled: true
      metrics-enabled: true
      trace-enabled: false
      slow-lock-threshold: 1s
      
    # 锁定义
    locks:
      - name: orderLock
        type: REENTRANT
        wait-time: 5s
        lease-time: 30s
        fair: false
        
      - name: inventoryLock
        type: FAIR
        wait-time: 3s
        lease-time: 10s
        
      - name: distributedSemaphore
        type: SEMAPHORE
        permits: 100
        
    # 重试策略
    retry:
      enabled: true
      max-attempts: 3
      delay: 100ms
      max-delay: 5s
      multiplier: 2.0
      
    # 线程池配置
    thread-pool:
      core-size: 10
      max-size: 50
      queue-capacity: 1000
      keep-alive: 60s
```
    
    /**
     * 构建新的锁
     */
    public CascadeLockBuilder builder(String name) {
        return new CascadeLockBuilder(name, this);
    }
    
    /**
     * 获取公平锁
     */
    public DistributedLock fairLock(String name) {
        return lockManager.getFairLock(name);
    }
    
    /**
     * 获取读写锁
     */
    public ReadWriteLock readWriteLock(String name) {
        return lockManager.getReadWriteLock(name);
    }
    
    /**
     * 获取红锁
     */
    public RedLock redLock(String... names) {
        return lockManager.getRedLock(names);
    }
    
    /**
     * 获取信号量
     */
    public Semaphore semaphore(String name, int permits) {
        return lockManager.getSemaphore(name, permits);
    }
    
    /**
     * 获取倒计时锁
     */
    public CountDownLatch countDownLatch(String name, int count) {
        return lockManager.getCountDownLatch(name, count);
    }
    
    /**
     * 创建锁模板
     */
    public LockTemplate template() {
        return new LockTemplate(this);
    }
    
    /**
     * 带锁执行
     */
    public <T> T executeWithLock(String lockName, Supplier<T> action) {
        return template()
            .lock(lockName)
            .execute(action);
    }
    
    /**
     * 带锁执行（无返回值）
     */
    public void runWithLock(String lockName, Runnable action) {
        template()
            .lock(lockName)
            .run(action);
    }
    
    /**
     * 尝试带锁执行
     */
    public <T> Optional<T> tryExecuteWithLock(String lockName, 
                                               Duration timeout, 
                                               Supplier<T> action) {
        return template()
            .lock(lockName)
            .waitTime(timeout)
            .tryExecute(action);
    }
    
    /**
     * 获取所有锁信息
     */
    public Collection<LockInfo> getAllLocks() {
        return lockManager.getAllLockInfo();
    }
    
    /**
     * 强制释放锁
     */
    public boolean forceUnlock(String name) {
        return lockManager.forceUnlock(name);
    }
    
    /**
     * 检测死锁
     */
    public DeadlockReport detectDeadlocks() {
        return deadlockDetector.detect();
    }
    
    /**
     * 获取锁指标
     */
    public LockMetrics getMetrics() {
        return lockMonitor.getMetrics();
    }
    
    @Override
    public ComponentType getType() {
        return ComponentType.LOCK;
    }
    
    @Override
    public HealthStatus health() {
        return HealthStatus.builder()
            .status(lockManager.isHealthy() ? Status.UP : Status.DOWN)
            .withDetail("totalLocks", getAllLocks().size())
            .withDetail("deadlocks", detectDeadlocks().hasDeadlock())
            .withDetail("metrics", getMetrics())
            .build();
    }
}
```

### 3.2 DistributedLock - 分布式锁接口

```java
package io.github.cascade.lock.api;

/**
 * 分布式锁接口
 */
public interface DistributedLock extends Lock {
    
    /**
     * 尝试获取锁
     */
    boolean tryLock();
    
    /**
     * 尝试获取锁（带超时）
     */
    boolean tryLock(long time, TimeUnit unit) throws InterruptedException;
    
    /**
     * 尝试获取锁（带等待时间和租期）
     */
    boolean tryLock(long waitTime, long leaseTime, TimeUnit unit) throws InterruptedException;
    
    /**
     * 异步尝试获取锁
     */
    CompletableFuture<Boolean> tryLockAsync();
    
    /**
     * 异步尝试获取锁（带超时）
     */
    CompletableFuture<Boolean> tryLockAsync(long time, TimeUnit unit);
    
    /**
     * 获取锁（阻塞）
     */
    void lock();
    
    /**
     * 获取锁（带租期）
     */
    void lock(long leaseTime, TimeUnit unit);
    
    /**
     * 异步获取锁
     */
    CompletableFuture<Void> lockAsync();
    
    /**
     * 异步获取锁（带租期）
     */
    CompletableFuture<Void> lockAsync(long leaseTime, TimeUnit unit);
    
    /**
     * 释放锁
     */
    void unlock();
    
    /**
     * 异步释放锁
     */
    CompletableFuture<Void> unlockAsync();
    
    /**
     * 强制释放锁
     */
    boolean forceUnlock();
    
    /**
     * 是否被当前线程持有
     */
    boolean isHeldByCurrentThread();
    
    /**
     * 是否被任何线程持有
     */
    boolean isLocked();
    
    /**
     * 获取持有计数（可重入）
     */
    int getHoldCount();
    
    /**
     * 获取剩余存活时间
     */
    long remainTimeToLive();
    
    /**
     * 获取锁名称
     */
    String getName();
    
    /**
     * 获取锁信息
     */
    LockInfo getInfo();
    
    /**
     * 新建条件变量
     */
    Condition newCondition();
}
```

### 3.3 CascadeLockBuilder - 锁构建器

```java
package io.github.cascade.lock;

/**
 * 锁构建器 - 流式API
 */
public class CascadeLockBuilder {
    
    private final String name;
    private final CascadeLock parent;
    
    // 配置参数
    private LockType type = LockType.REENTRANT;
    private Duration waitTime = Duration.ofSeconds(10);
    private Duration leaseTime = Duration.ofSeconds(30);
    private boolean fair = false;
    private boolean autoRenew = true;
    private RetryPolicy retryPolicy;
    private BackoffStrategy backoffStrategy;
    private List<LockEventListener> listeners = new ArrayList<>();
    
    /**
     * 设置锁类型
     */
    public CascadeLockBuilder type(LockType type) {
        this.type = type;
        return this;
    }
    
    /**
     * 设置等待时间
     */
    public CascadeLockBuilder waitTime(Duration waitTime) {
        this.waitTime = waitTime;
        return this;
    }
    
    /**
     * 设置租期
     */
    public CascadeLockBuilder leaseTime(Duration leaseTime) {
        this.leaseTime = leaseTime;
        return this;
    }
    
    /**
     * 启用公平锁
     */
    public CascadeLockBuilder fair() {
        this.fair = true;
        return this;
    }
    
    /**
     * 禁用自动续期
     */
    public CascadeLockBuilder noAutoRenew() {
        this.autoRenew = false;
        return this;
    }
    
    /**
     * 设置重试策略
     */
    public CascadeLockBuilder retry(Consumer<RetryPolicy> configurer) {
        this.retryPolicy = new RetryPolicy();
        configurer.accept(this.retryPolicy);
        return this;
    }
    
    /**
     * 设置退避策略
     */
    public CascadeLockBuilder backoff(BackoffStrategy strategy) {
        this.backoffStrategy = strategy;
        return this;
    }
    
    /**
     * 添加事件监听器
     */
    public CascadeLockBuilder onAcquired(Consumer<LockAcquiredEvent> listener) {
        this.listeners.add(new LockEventListener() {
            @Override
            public void onEvent(LockEvent event) {
                if (event instanceof LockAcquiredEvent) {
                    listener.accept((LockAcquiredEvent) event);
                }
            }
        });
        return this;
    }
    
    /**
     * 构建锁
     */
    public DistributedLock build() {
        LockConfig config = buildConfig();
        DistributedLock lock = createLock(config);
        
        // 注册到管理器
        parent.getLockManager().register(name, lock);
        
        return lock;
    }
    
    /**
     * 构建并执行
     */
    public <T> T execute(Supplier<T> action) {
        DistributedLock lock = build();
        try {
            lock.lock();
            return action.get();
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * 构建并尝试执行
     */
    public <T> Optional<T> tryExecute(Supplier<T> action) {
        DistributedLock lock = build();
        try {
            if (lock.tryLock(waitTime.toMillis(), TimeUnit.MILLISECONDS)) {
                try {
                    return Optional.of(action.get());
                } finally {
                    lock.unlock();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return Optional.empty();
    }
}
```

### 3.4 锁模板

```java
package io.github.cascade.lock.template;

/**
 * 锁操作模板
 * 简化锁的使用，自动处理获取和释放
 */
public class LockTemplate {
    
    private final CascadeLock cascadeLock;
    private String lockName;
    private Duration waitTime = Duration.ofSeconds(10);
    private Duration leaseTime = Duration.ofSeconds(30);
    private RetryPolicy retryPolicy;
    private boolean propagateException = true;
    
    /**
     * 指定锁名称
     */
    public LockTemplate lock(String name) {
        this.lockName = name;
        return this;
    }
    
    /**
     * 设置等待时间
     */
    public LockTemplate waitTime(Duration duration) {
        this.waitTime = duration;
        return this;
    }
    
    /**
     * 设置租期
     */
    public LockTemplate leaseTime(Duration duration) {
        this.leaseTime = duration;
        return this;
    }
    
    /**
     * 设置重试策略
     */
    public LockTemplate retry(RetryPolicy policy) {
        this.retryPolicy = policy;
        return this;
    }
    
    /**
     * 执行操作（有返回值）
     */
    public <T> T execute(Supplier<T> action) {
        return execute(new LockCallback<T>() {
            @Override
            public T doInLock() throws Exception {
                return action.get();
            }
        });
    }
    
    /**
     * 执行操作（无返回值）
     */
    public void run(Runnable action) {
        execute(new LockCallback<Void>() {
            @Override
            public Void doInLock() throws Exception {
                action.run();
                return null;
            }
        });
    }
    
    /**
     * 尝试执行操作
     */
    public <T> Optional<T> tryExecute(Supplier<T> action) {
        DistributedLock lock = getLock();
        try {
            if (lock.tryLock(waitTime.toMillis(), leaseTime.toMillis(), TimeUnit.MILLISECONDS)) {
                try {
                    return Optional.of(action.get());
                } finally {
                    lock.unlock();
                }
            }
        } catch (Exception e) {
            handleException(e);
        }
        return Optional.empty();
    }
    
    /**
     * 执行操作（带回调）
     */
    public <T> T execute(LockCallback<T> callback) {
        DistributedLock lock = getLock();
        int attempts = 0;
        Exception lastException = null;
        
        while (shouldRetry(attempts, lastException)) {
            try {
                lock.lock(leaseTime.toMillis(), TimeUnit.MILLISECONDS);
                try {
                    return callback.doInLock();
                } finally {
                    lock.unlock();
                }
            } catch (Exception e) {
                lastException = e;
                attempts++;
                if (retryPolicy != null && retryPolicy.shouldRetry(attempts, e)) {
                    sleep(retryPolicy.getDelay(attempts));
                } else {
                    handleException(e);
                    break;
                }
            }
        }
        
        throw new LockException("Failed to execute with lock after " + attempts + " attempts", lastException);
    }
    
    private DistributedLock getLock() {
        return cascadeLock.lock(lockName);
    }
    
    private boolean shouldRetry(int attempts, Exception exception) {
        return retryPolicy != null && retryPolicy.shouldRetry(attempts, exception);
    }
    
    private void handleException(Exception e) {
        if (propagateException) {
            if (e instanceof RuntimeException) {
                throw (RuntimeException) e;
            }
            throw new LockException("Lock operation failed", e);
        }
    }
}

/**
 * 锁回调接口
 */
public interface LockCallback<T> {
    T doInLock() throws Exception;
}
```

### 3.5 锁实现类

```java
package io.github.cascade.lock.core;

/**
 * 基于Redisson的可重入锁实现
 */
public class RedissonReentrantLock extends AbstractDistributedLock {
    
    private final RLock redissonLock;
    private final WatchDog watchDog;
    private final LockConfig config;
    private final AtomicReference<Thread> holder = new AtomicReference<>();
    private final AtomicInteger holdCount = new AtomicInteger(0);
    
    @Override
    public boolean tryLock() {
        return tryLock(0, config.getLeaseTime(), TimeUnit.MILLISECONDS);
    }
    
    @Override
    public boolean tryLock(long time, TimeUnit unit) throws InterruptedException {
        return tryLock(time, config.getLeaseTime(), unit);
    }
    
    @Override
    public boolean tryLock(long waitTime, long leaseTime, TimeUnit unit) throws InterruptedException {
        Thread current = Thread.currentThread();
        
        // 可重入检查
        if (isHeldByCurrentThread()) {
            holdCount.incrementAndGet();
            return true;
        }
        
        boolean acquired = redissonLock.tryLock(waitTime, leaseTime, unit);
        
        if (acquired) {
            holder.set(current);
            holdCount.set(1);
            
            // 启动看门狗
            if (config.isAutoRenew() && leaseTime > 0) {
                watchDog.start(this, leaseTime, unit);
            }
            
            // 发布事件
            publishEvent(new LockAcquiredEvent(getName(), current.getName()));
            
            // 记录指标
            metricsCollector.recordAcquisition(getName(), waitTime);
        } else {
            metricsCollector.recordFailure(getName());
        }
        
        return acquired;
    }
    
    @Override
    public void lock() {
        lock(config.getLeaseTime(), TimeUnit.MILLISECONDS);
    }
    
    @Override
    public void lock(long leaseTime, TimeUnit unit) {
        Thread current = Thread.currentThread();
        
        // 可重入检查
        if (isHeldByCurrentThread()) {
            holdCount.incrementAndGet();
            return;
        }
        
        redissonLock.lock(leaseTime, unit);
        holder.set(current);
        holdCount.set(1);
        
        // 启动看门狗
        if (config.isAutoRenew() && leaseTime > 0) {
            watchDog.start(this, leaseTime, unit);
        }
        
        publishEvent(new LockAcquiredEvent(getName(), current.getName()));
        metricsCollector.recordAcquisition(getName(), 0);
    }
    
    @Override
    public void unlock() {
        if (!isHeldByCurrentThread()) {
            throw new IllegalMonitorStateException("Lock not held by current thread");
        }
        
        int count = holdCount.decrementAndGet();
        if (count == 0) {
            try {
                redissonLock.unlock();
                holder.set(null);
                
                // 停止看门狗
                if (config.isAutoRenew()) {
                    watchDog.stop(this);
                }
                
                publishEvent(new LockReleasedEvent(getName(), Thread.currentThread().getName()));
                metricsCollector.recordRelease(getName());
            } catch (Exception e) {
                holdCount.incrementAndGet(); // 恢复计数
                throw new LockException("Failed to unlock", e);
            }
        }
    }
    
    @Override
    public boolean isHeldByCurrentThread() {
        return Thread.currentThread().equals(holder.get());
    }
    
    @Override
    public boolean isLocked() {
        return redissonLock.isLocked();
    }
    
    @Override
    public int getHoldCount() {
        return isHeldByCurrentThread() ? holdCount.get() : 0;
    }
    
    @Override
    public long remainTimeToLive() {
        return redissonLock.remainTimeToLive();
    }
    
    @Override
    public LockInfo getInfo() {
        return LockInfo.builder()
            .name(getName())
            .type(LockType.REENTRANT)
            .locked(isLocked())
            .holder(holder.get() != null ? holder.get().getName() : null)
            .holdCount(holdCount.get())
            .remainingTime(remainTimeToLive())
            .waitingThreads(getQueueLength())
            .fair(false)
            .build();
    }
}
```

### 3.6 看门狗机制

```java
package io.github.cascade.lock.watchdog;

/**
 * 看门狗实现 - 自动续期
 */
@Component
public class DefaultWatchDog implements WatchDog {
    
    private final ScheduledExecutorService scheduler;
    private final Map<String, ScheduledFuture<?>> renewalTasks = new ConcurrentHashMap<>();
    private final WatchDogConfig config;
    
    @Override
    public void start(DistributedLock lock, long leaseTime, TimeUnit unit) {
        String lockName = lock.getName();
        
        // 计算续期间隔（租期的1/3）
        long renewalInterval = unit.toMillis(leaseTime) / 3;
        
        ScheduledFuture<?> task = scheduler.scheduleAtFixedRate(() -> {
            try {
                if (lock.isHeldByCurrentThread()) {
                    // 续期
                    boolean renewed = lock.tryLock(0, leaseTime, unit);
                    if (renewed) {
                        log.debug("Lock {} renewed for {} {}", lockName, leaseTime, unit);
                        publishEvent(new LockRenewedEvent(lockName));
                    } else {
                        log.warn("Failed to renew lock {}", lockName);
                        stop(lock);
                    }
                } else {
                    // 锁已释放，停止续期
                    stop(lock);
                }
            } catch (Exception e) {
                log.error("Error renewing lock {}", lockName, e);
                stop(lock);
            }
        }, renewalInterval, renewalInterval, TimeUnit.MILLISECONDS);
        
        renewalTasks.put(lockName, task);
    }
    
    @Override
    public void stop(DistributedLock lock) {
        String lockName = lock.getName();
        ScheduledFuture<?> task = renewalTasks.remove(lockName);
        if (task != null) {
            task.cancel(false);
            log.debug("WatchDog stopped for lock {}", lockName);
        }
    }
    
    @Override
    public void stopAll() {
        renewalTasks.values().forEach(task -> task.cancel(false));
        renewalTasks.clear();
    }
    
    @Override
    public boolean isWatching(String lockName) {
        return renewalTasks.containsKey(lockName);
    }
    
    @Override
    public int getWatchingCount() {
        return renewalTasks.size();
    }
}
```

### 3.7 死锁检测

```java
package io.github.cascade.lock.monitor;

/**
 * 死锁检测器
 */
@Component
public class DeadlockDetector {
    
    private final CascadeLockManager lockManager;
    private final ScheduledExecutorService scheduler;
    private final Duration checkInterval = Duration.ofSeconds(30);
    
    @PostConstruct
    public void start() {
        scheduler.scheduleAtFixedRate(this::detectAndReport, 
            checkInterval.toMillis(), 
            checkInterval.toMillis(), 
            TimeUnit.MILLISECONDS);
    }
    
    /**
     * 检测死锁
     */
    public DeadlockReport detect() {
        Map<String, LockInfo> locks = lockManager.getAllLockInfo()
            .stream()
            .collect(Collectors.toMap(LockInfo::getName, Function.identity()));
        
        // 构建等待图
        DirectedGraph<String> waitGraph = buildWaitGraph(locks);
        
        // 检测循环依赖
        List<List<String>> cycles = findCycles(waitGraph);
        
        if (!cycles.isEmpty()) {
            return DeadlockReport.builder()
                .hasDeadlock(true)
                .cycles(cycles)
                .affectedLocks(extractAffectedLocks(cycles, locks))
                .timestamp(System.currentTimeMillis())
                .build();
        }
        
        return DeadlockReport.noDeadlock();
    }
    
    /**
     * 检测并报告
     */
    private void detectAndReport() {
        try {
            DeadlockReport report = detect();
            if (report.hasDeadlock()) {
                log.error("Deadlock detected: {}", report);
                publishEvent(new DeadlockDetectedEvent(report));
                
                // 可选：自动解决死锁
                if (config.isAutoResolveDeadlock()) {
                    resolveDeadlock(report);
                }
            }
        } catch (Exception e) {
            log.error("Error during deadlock detection", e);
        }
    }
    
    /**
     * 构建等待图
     */
    private DirectedGraph<String> buildWaitGraph(Map<String, LockInfo> locks) {
        DirectedGraph<String> graph = new DirectedGraph<>();
        
        for (LockInfo lock : locks.values()) {
            if (lock.isLocked() && lock.hasWaitingThreads()) {
                for (String waitingThread : lock.getWaitingThreads()) {
                    // 查找等待线程持有的其他锁
                    locks.values().stream()
                        .filter(l -> waitingThread.equals(l.getHolder()))
                        .forEach(l -> graph.addEdge(lock.getName(), l.getName()));
                }
            }
        }
        
        return graph;
    }
    
    /**
     * 解决死锁
     */
    private void resolveDeadlock(DeadlockReport report) {
        // 选择牺牲者（例如：持有锁时间最长的）
        LockInfo victim = report.getAffectedLocks().stream()
            .min(Comparator.comparing(LockInfo::getAcquiredTime))
            .orElse(null);
        
        if (victim != null) {
            log.warn("Resolving deadlock by releasing lock: {}", victim.getName());
            lockManager.forceUnlock(victim.getName());
        }
    }
}