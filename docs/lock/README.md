# Cascade Lock - Redisson 分布式锁工具类

这个模块提供了简单实用的 Redisson 分布式锁工具类，让您更方便地使用 Redisson 的锁功能。

## 核心工具类

- **RedissonLockUtils** - 分布式锁工具类
- **RedissonSemaphoreUtils** - 信号量工具类  
- **RedissonCountDownLatchUtils** - 倒计时锁存器工具类

## 使用示例

### 1. 分布式锁 (RedissonLockUtils)

#### 基本使用
```java
// 执行带锁的操作
String result = RedissonLockUtils.withLock(redissonClient, "my-lock", () -> {
    // 业务逻辑
    return doSomething();
});

// 执行带锁的操作（无返回值）
RedissonLockUtils.withLock(redissonClient, "my-lock", () -> {
    // 业务逻辑
    doSomething();
});
```

#### 带超时的锁
```java
// 带等待超时的锁
String result = RedissonLockUtils.withLock(redissonClient, "my-lock", 
    10, TimeUnit.SECONDS, () -> {
        return doSomething();
    });

// 带等待超时和租期的锁
RedissonLockUtils.withLock(redissonClient, "my-lock", 
    10, 30, TimeUnit.SECONDS, () -> {
        doSomething();
    });
```

#### 公平锁
```java
// 使用公平锁（FIFO顺序）
RedissonLockUtils.withFairLock(redissonClient, "fair-lock", () -> {
    doSomething();
});
```

#### 读写锁
```java
// 使用读锁
String data = RedissonLockUtils.withReadLock(redissonClient, "rw-lock", () -> {
    return readData();
});

// 使用写锁
RedissonLockUtils.withWriteLock(redissonClient, "rw-lock", () -> {
    writeData();
});
```

#### 多锁
```java
// 同时获取多个锁
String[] lockNames = {"lock1", "lock2", "lock3"};
RedissonLockUtils.withMultiLock(redissonClient, lockNames, () -> {
    // 需要多个锁保护的操作
    doSomethingWithMultipleResources();
});
```

#### 红锁 (RedLock)
```java
// 使用红锁（多个 Redis 实例）
RedissonClient[] clients = {client1, client2, client3};
RedissonLockUtils.withRedLock(clients, "red-lock", () -> {
    doSomething();
});
```

#### 尝试获取锁
```java
// 尝试获取锁，失败时执行替代逻辑
String result = RedissonLockUtils.tryLock(redissonClient, "my-lock", 
    () -> {
        // 获取锁成功时的操作
        return doSomething();
    }, 
    () -> {
        // 获取锁失败时的操作
        return getDefaultValue();
    });
```

#### 锁状态查询
```java
// 检查锁是否被持有
boolean locked = RedissonLockUtils.isLocked(redissonClient, "my-lock");

// 获取锁的剩余时间
long ttl = RedissonLockUtils.remainTimeToLive(redissonClient, "my-lock");

// 强制释放锁
boolean unlocked = RedissonLockUtils.forceUnlock(redissonClient, "my-lock");
```

### 2. 信号量 (RedissonSemaphoreUtils)

#### 基本使用
```java
// 执行带信号量的操作
String result = RedissonSemaphoreUtils.withSemaphore(redissonClient, 
    "my-semaphore", 5, () -> {
        // 最多5个线程同时执行
        return doSomething();
    });

// 指定获取的许可数
RedissonSemaphoreUtils.withSemaphore(redissonClient, 
    "my-semaphore", 10, 2, () -> {
        // 总共10个许可，这里获取2个
        doSomething();
    });
```

#### 带超时的信号量
```java
// 带超时的信号量
RedissonSemaphoreUtils.withSemaphore(redissonClient, 
    "my-semaphore", 5, 1, 10, TimeUnit.SECONDS, () -> {
        return doSomething();
    });
```

#### 尝试获取信号量
```java
// 尝试获取信号量，失败时执行替代逻辑
String result = RedissonSemaphoreUtils.trySemaphore(redissonClient, 
    "my-semaphore", 5, 1,
    () -> {
        // 获取许可成功
        return doSomething();
    }, 
    () -> {
        // 获取许可失败
        return "服务繁忙，请稍后重试";
    });
```

#### 可过期许可信号量
```java
// 使用可过期许可的信号量
RedissonSemaphoreUtils.withPermitExpirableSemaphore(redissonClient, 
    "expirable-semaphore", 5, 30, TimeUnit.SECONDS, () -> {
        // 许可30秒后自动过期
        return doSomething();
    });
```

#### 信号量管理
```java
// 获取可用许可数
int available = RedissonSemaphoreUtils.getAvailablePermits(redissonClient, "my-semaphore");

// 设置许可数
boolean success = RedissonSemaphoreUtils.trySetPermits(redissonClient, "my-semaphore", 10);

// 增加许可数
RedissonSemaphoreUtils.addPermits(redissonClient, "my-semaphore", 5);

// 减少许可数（通过获取但不释放的方式）
RedissonSemaphoreUtils.reducePermits(redissonClient, "my-semaphore", 2);
```

### 3. 倒计时锁存器 (RedissonCountDownLatchUtils)

#### 基本使用
```java
// 等待倒计时完成
RedissonCountDownLatchUtils.await(redissonClient, "my-latch", 5);

// 倒计时减一
RedissonCountDownLatchUtils.countDown(redissonClient, "my-latch");

// 倒计时减指定数量
RedissonCountDownLatchUtils.countDown(redissonClient, "my-latch", 2);
```

#### 带超时的等待
```java
// 等待倒计时完成（带超时）
boolean completed = RedissonCountDownLatchUtils.await(redissonClient, 
    "my-latch", 5, 30, TimeUnit.SECONDS);

if (completed) {
    System.out.println("倒计时完成");
} else {
    System.out.println("超时了");
}
```

#### 等待并执行
```java
// 等待倒计时完成后执行操作
String result = RedissonCountDownLatchUtils.awaitAndExecute(redissonClient, 
    "my-latch", 3, () -> {
        return "所有任务完成！";
    });

// 带超时的等待并执行
RedissonCountDownLatchUtils.awaitAndExecute(redissonClient, 
    "my-latch", 3, 30, TimeUnit.SECONDS,
    () -> {
        System.out.println("倒计时完成");
    }, 
    () -> {
        System.out.println("超时了");
    });
```

#### 状态查询和管理
```java
// 获取当前计数
long count = RedissonCountDownLatchUtils.getCount(redissonClient, "my-latch");

// 检查是否已完成
boolean completed = RedissonCountDownLatchUtils.isCompleted(redissonClient, "my-latch");

// 重置倒计时锁存器
boolean reset = RedissonCountDownLatchUtils.reset(redissonClient, "my-latch", 5);

// 强制完成（设置计数为0）
int released = RedissonCountDownLatchUtils.forceCountDown(redissonClient, "my-latch");

// 获取状态信息
String status = RedissonCountDownLatchUtils.getStatus(redissonClient, "my-latch");
```

## 完整使用示例

### 场景：订单处理系统

```java
@Service
public class OrderProcessingService {
    
    @Autowired
    private RedissonClient redissonClient;
    
    // 使用分布式锁确保订单处理的唯一性
    public void processOrder(String orderId) {
        String lockName = "order-lock:" + orderId;
        
        RedissonLockUtils.withLock(redissonClient, lockName, 10, TimeUnit.SECONDS, () -> {
            // 处理订单逻辑
            doProcessOrder(orderId);
        });
    }
    
    // 使用信号量限制并发处理数量
    public String handleRequest() {
        return RedissonSemaphoreUtils.withSemaphore(redissonClient, 
            "request-limit", 100, () -> {
                // 最多100个并发请求
                return processRequest();
            });
    }
    
    // 使用倒计时锁存器等待批量任务完成
    public void waitForBatchTasks(List<String> taskIds) {
        String latchName = "batch-tasks";
        int taskCount = taskIds.size();
        
        // 启动所有任务
        for (String taskId : taskIds) {
            CompletableFuture.runAsync(() -> {
                try {
                    processTask(taskId);
                } finally {
                    // 任务完成后倒计时减一
                    RedissonCountDownLatchUtils.countDown(redissonClient, latchName);
                }
            });
        }
        
        // 等待所有任务完成
        RedissonCountDownLatchUtils.awaitAndExecute(redissonClient, 
            latchName, taskCount, () -> {
                System.out.println("所有批量任务完成！");
            });
    }
}
```

## 特性

✅ **简单易用** - 提供静态工具方法，无需复杂配置  
✅ **函数式编程** - 支持 Lambda 表达式和方法引用  
✅ **自动资源管理** - 自动获取和释放锁，防止资源泄漏  
✅ **完整的异常处理** - 统一的异常处理和日志记录  
✅ **灵活的超时控制** - 支持等待超时和租期设置  
✅ **全面的锁类型** - 支持可重入锁、公平锁、读写锁、多锁、红锁等  
✅ **信号量支持** - 普通信号量和可过期许可信号量  
✅ **协调原语** - 倒计时锁存器用于线程间协调

这些工具类直接封装了 Redisson 的 API，让您能够更方便地在项目中使用分布式锁、信号量和同步原语。