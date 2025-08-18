package io.github.cascade.cache.example;

import io.github.cascade.cache.api.Cache;
import io.github.cascade.cache.builder.CascadeCacheBuilder;
import io.github.cascade.cache.manager.SpringBootCacheManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * 优雅的缓存使用方式示例
 * 展示如何不用 TypeReference 匿名类就能优雅地创建类型化缓存
 * 
 * @author cascade
 */
public class ElegantCacheUsageExample {
    
    private static final Logger log = LoggerFactory.getLogger(ElegantCacheUsageExample.class);
    private final SpringBootCacheManager cacheManager = new SpringBootCacheManager();
    
    /**
     * 方式1：CascadeCacheBuilder静态方法 (推荐)
     */
    public void method1_CascadeBuilderStatic() {
        // 替代：cacheManager.getOrCreateCacheWithTypeRef("userCache", new TypeReference<>() {})
        
        // 通用静态方法
        Cache<String, User> userCache = CascadeCacheBuilder.create("userCache", cacheManager, String.class, User.class).build();
        
        // 常用类型的快捷静态方法
        Cache<String, User> userCache2 = CascadeCacheBuilder.stringKey("userCache2", cacheManager, User.class).build();
        Cache<Long, Product> productCache = CascadeCacheBuilder.longKey("productCache", cacheManager, Product.class).build();
        Cache<Integer, Order> orderCache = CascadeCacheBuilder.intKey("orderCache", cacheManager, Order.class).build();
        
        // 最快速的创建方式
        Cache<String, User> userCache3 = CascadeCacheBuilder.quickStringCache("userCache3", cacheManager, User.class);
        
        log.info("Created caches using CascadeCacheBuilder static methods");
    }
    
    /**
     * 方式2：流式Builder模式 - 统一在CascadeCacheBuilder中
     */
    public void method2_FluentBuilder() {
        // 设置默认CacheManager
        CascadeCacheBuilder.setDefaultCacheManager(cacheManager);
        
        // 流式构建 - 现在使用统一的CascadeCacheBuilder
        Cache<String, User> userCache = CascadeCacheBuilder.named("userCache")
                .withStringKey(User.class)
                .build();
        
        Cache<Long, Product> productCache = CascadeCacheBuilder.named("productCache")
                .withLongKey(Product.class)
                .build();
        
        Cache<String, Order> orderCache = CascadeCacheBuilder.named("orderCache")
                .withTypes(String.class, Order.class)
                .build(cacheManager); // 也可以显式指定CacheManager
        
        log.info("Created caches using unified fluent builder pattern");
    }
    
    /**
     * 方式3：智能类型推断 (实验性)
     */
    public void method3_SmartInference() {
        // 智能推断会尝试从方法签名和缓存名称推断类型
        Cache<String, User> userCache = cacheManager.smartCache("userCache");
        Cache<Long, Product> productCache = cacheManager.smartCache("productCache");
        
        // 对于复杂场景，推断可能失败，会退回到普通创建方式
        Cache<String, Order> orderCache = cacheManager.smartCache("orderCache");
        
        log.info("Created caches using smart type inference");
    }
    
    /**
     * 方式4：工厂模式封装
     */
    public static class CacheFactory {
        private final SpringBootCacheManager cacheManager;
        
        public CacheFactory(SpringBootCacheManager cacheManager) {
            this.cacheManager = cacheManager;
        }
        
        // 预定义的缓存获取方法
        public Cache<String, User> users() {
            return cacheManager.stringCache("users", User.class);
        }
        
        public Cache<Long, Product> products() {
            return cacheManager.longCache("products", Product.class);
        }
        
        public Cache<String, Order> orders() {
            return cacheManager.stringCache("orders", Order.class);
        }
        
        // 通用方法
        public <K, V> Cache<K, V> get(String name, Class<K> keyType, Class<V> valueType) {
            return cacheManager.cache(name, keyType, valueType);
        }
    }
    
    public void method4_FactoryPattern() {
        CacheFactory factory = new CacheFactory(cacheManager);
        
        // 非常简洁的使用方式
        Cache<String, User> userCache = factory.users();
        Cache<Long, Product> productCache = factory.products();
        Cache<String, Order> orderCache = factory.orders();
        
        log.info("Created caches using factory pattern");
    }
    
    /**
     * 方式5：函数式接口 (进一步简化)
     */
    @FunctionalInterface
    public interface CacheProvider<K, V> {
        Cache<K, V> provide(String cacheName);
        
        static <V> CacheProvider<String, V> stringKey(SpringBootCacheManager manager, Class<V> valueType) {
            return cacheName -> manager.stringCache(cacheName, valueType);
        }
        
        static <V> CacheProvider<Long, V> longKey(SpringBootCacheManager manager, Class<V> valueType) {
            return cacheName -> manager.longCache(cacheName, valueType);
        }
    }
    
    public void method5_FunctionalInterface() {
        // 创建类型化的提供者
        CacheProvider<String, User> userProvider = CacheProvider.stringKey(cacheManager, User.class);
        CacheProvider<Long, Product> productProvider = CacheProvider.longKey(cacheManager, Product.class);
        
        // 使用提供者创建缓存
        Cache<String, User> userCache = userProvider.provide("userCache");
        Cache<Long, Product> productCache = productProvider.provide("productCache");
        
        log.info("Created caches using functional interface");
    }
    
    /**
     * 方式6：统一Builder的高级配置 - 展示从简单到复杂的无缝切换
     */
    public void method6_UnifiedBuilderAdvanced() {
        // 简单快速创建
        Cache<String, User> simpleCache = CascadeCacheBuilder
                .stringKey("simpleUserCache", cacheManager, User.class)
                .build();
        
        // 需要详细配置时，直接链式调用
        Cache<String, User> configuredCache = CascadeCacheBuilder
                .stringKey("configuredUserCache", cacheManager, User.class)
                .withRedis() // 启用Redis L2缓存
                .withProtection() // 启用防护机制
                .bloomFilter(10000, 0.01) // 配置布隆过滤器
                .maximumSize(5000) // 设置L1缓存大小
                .expireAfterWrite(Duration.ofMinutes(30)) // 设置过期时间
                .build();
        
        // 流式构建后转为详细配置
        Cache<Long, Product> advancedCache = CascadeCacheBuilder.named("productCache")
                .withLongKey(Product.class)
                .configure(cacheManager) // 转为详细配置模式
                .withRedis()
                .enableSync(true) // 启用同步
                .l1MaximumSize(1000)
                .l2DefaultTtl(Duration.ofHours(2))
                .randomTtl(true, 0.1) // 防止缓存雪崩
                .build();
        
        log.info("Created caches with unified builder - from simple to advanced");
    }
    
    /**
     * 性能对比示例
     */
    public void performanceComparison() {
        long start, end;
        
        // 原始方式（类型丢失）
        start = System.nanoTime();
        Cache<String, User> cache1 = cacheManager.getOrCreateCache("test1");
        end = System.nanoTime();
        log.info("Original method: {} ns", end - start);
        
        // 新的简洁方式
        start = System.nanoTime();
        Cache<String, User> cache2 = cacheManager.stringCache("test2", User.class);
        end = System.nanoTime();
        log.info("New elegant method: {} ns", end - start);
        
        // 统一Builder方式
        start = System.nanoTime();
        Cache<String, User> cache3 = CascadeCacheBuilder.named("test3")
                .withStringKey(User.class)
                .build(cacheManager);
        end = System.nanoTime();
        log.info("Unified Builder method: {} ns", end - start);
    }
    
    // 示例实体类
    public static class User {
        private String id;
        private String name;
        
        public User(String id, String name) {
            this.id = id;
            this.name = name;
        }
        
        // getters and setters
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }
    
    public static class Product {
        private Long id;
        private String name;
        private Double price;
        
        public Product(Long id, String name, Double price) {
            this.id = id;
            this.name = name;
            this.price = price;
        }
        
        // getters and setters
        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public Double getPrice() { return price; }
        public void setPrice(Double price) { this.price = price; }
    }
    
    public static class Order {
        private String orderId;
        private String userId;
        private Double amount;
        
        public Order(String orderId, String userId, Double amount) {
            this.orderId = orderId;
            this.userId = userId;
            this.amount = amount;
        }
        
        // getters and setters
        public String getOrderId() { return orderId; }
        public void setOrderId(String orderId) { this.orderId = orderId; }
        public String getUserId() { return userId; }
        public void setUserId(String userId) { this.userId = userId; }
        public Double getAmount() { return amount; }
        public void setAmount(Double amount) { this.amount = amount; }
    }
}