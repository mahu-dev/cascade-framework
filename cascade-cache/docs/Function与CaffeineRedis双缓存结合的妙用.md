

#  Function 与 Caffeine/Redis 双缓存结合的妙用

在做高并发系统时，单层缓存总是非常常见的：

  * • 只用 Redis，频繁访问下会产生网络开销，RT 受限； 
  * • 只用 Caffeine（本地缓存），数据一致性与容量都成问题。 

于是，  ** 双缓存  ** （L1 本地 + L2 Redis）成为很多系统的标配。  
但常见的实现模式往往是这样的：


​    
    public String getValue(String key) {  
        String value = caffeine.getIfPresent(key);  
        if (value != null) return value;  
      
        value = redis.get(key);  
        if (value != null) {  
            caffeine.put(key, value);  
            return value;  
        }  
      
        value = db.query(key);  
        if (value != null) {  
            redis.set(key, value);  
            caffeine.put(key, value);  
        }  
        return value;  
    }

这段代码逻辑没问题，但问题是  ** 重复、难以扩展  ** 。如果以后换成三层缓存，或者加载来源有变化，就得大改。

我的做法是：用  ** Function 把加载逻辑抽象掉  ** ，形成可装配的缓存链。

* * *

##  1\. Function 驱动的缓存链

我定义了一个通用的函数接口：


​    
    Function<String, String> cachePipeline;

然后把三步逻辑拆成函数：


​    
    Function<String, String> loadFromCaffeine = key -> caffeine.getIfPresent(key);  
    Function<String, String> loadFromRedis = key -> redis.get(key);  
    Function<String, String> loadFromDb = key -> db.query(key);

再加上“写回”的 Consumer 装饰：


​    
    Function<String, String> withCaffeineWriteBack(Function<String, String> f) {  
        return key -> {  
            String val = f.apply(key);  
            if (val != null) caffeine.put(key, val);  
            return val;  
        };  
    }  
      
    Function<String, String> withRedisWriteBack(Function<String, String> f) {  
        return key -> {  
            String val = f.apply(key);  
            if (val != null) redis.set(key, val);  
            return val;  
        };  
    }

然后拼装：


​    
    cachePipeline = loadFromCaffeine  
            .orElse(withCaffeineWriteBack(loadFromRedis))  
            .orElse(withRedisWriteBack(loadFromDb));

这里的  ` orElse  ` 是我自定义的函数组合器（类似 Optional），保证前一个为 null 时才执行后一个。

* * *

##  2\. 自定义函数组合器：orElse

Java 标准库没有  ` Function.orElse  ` ，所以我自己写了个：


​    
    static <T, R> Function<T, R> orElse(Function<T, R> first, Function<T, R> second) {  
        return t -> {  
            R r = first.apply(t);  
            return r != null ? r : second.apply(t);  
        };  
    }

用法：


​    
    Function<String, String> pipeline =  
            orElse(loadFromCaffeine,  
            orElse(withCaffeineWriteBack(loadFromRedis),  
            withRedisWriteBack(loadFromDb)));

这样，缓存层次关系完全由函数组合决定，而不是硬编码在 if-else 里。  
新增一层缓存？只要加一个  ` orElse  ` 就行。

* * *

##  3\. 动态替换与可测试性

这种函数化的实现有一个天然优势：  ** 替换方便  ** 。  
在单测里，我不需要启动 Redis，只要替换掉对应 Function：


​    
    Function<String, String> mockRedis = key -> null; // 模拟无数据

在压测环境里，我甚至能把  ` loadFromDb  ` 替换成一个延迟 200ms 的函数，用来模拟慢查询。  
这让双缓存的测试维度一下丰富了很多，而不必依赖真实环境。

* * *

##  4\. 常见扩展：防击穿与异步写回

我在生产环境里踩过一个坑：  
如果 Redis 和 Caffeine 同时失效，所有请求都会打到 DB，瞬间把数据库打挂。  
解决方案其实也能通过 Function 装饰来实现。

###  防击穿（带锁）


​    
    Function<String, String> withLock(Function<String, String> f) {  
        return key -> {  
            synchronized (("LOCK_" + key).intern()) {  
                return f.apply(key);  
            }  
        };  
    }

###  异步写回

有时我们不想在主流程里同步写回 Redis，可以写个包装器：


​    
    Function<String, String> withAsyncRedisWriteBack(Function<String, String> f) {  
        return key -> {  
            String val = f.apply(key);  
            if (val != null) {  
                CompletableFuture.runAsync(() -> redis.set(key, val));  
            }  
            return val;  
        };  
    }

这两种能力，都是通过函数化包装轻松插入的，不需要改核心逻辑。

* * *

##  5\. 实际收益

在项目里我用了半年，真实感受到几个好处：

  * •  ** 结构清晰  ** ：双缓存逻辑是函数链，不再是 if-else 嵌套。 
  * •  ** 扩展性高  ** ：加一层缓存、换数据源，只需改组合，不动主流程。 
  * •  ** 测试友好  ** ：mock Function 即可，不依赖真实 Redis/DB。 
  * •  ** 灵活的横切能力  ** ：重试、防击穿、异步写回都能通过包装器实现。 

这比传统写法更贴近“函数式管道”的思路，代码既简洁又强大。

* * *

##  总结

我一直觉得，双缓存这类场景很适合用  ** 函数组合  ** 重构。  
传统方式往往靠设计模式（装饰器、责任链）解决，但在 Java 8+ 的世界里，用 Function 就能写出更直观、更易装配的代码。

一句话总结：  
** 当你用 Function 来描述缓存操作时，双缓存就不再是“硬逻辑”，而是一条可随时拼装的流水线。  **






