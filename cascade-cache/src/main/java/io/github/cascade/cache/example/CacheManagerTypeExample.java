package io.github.cascade.cache.example;

import io.github.cascade.cache.api.Cache;
import io.github.cascade.cache.core.impl.EnhancedDistributedTieredCache;
import io.github.cascade.cache.manager.SpringBootCacheManager;
import io.github.cascade.cache.util.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CacheManager类型保持功能示例
 * 演示如何通过CacheManager创建带类型信息的缓存
 *
 * @author cascade
 */
public class CacheManagerTypeExample {

    private static final Logger log = LoggerFactory.getLogger(CacheManagerTypeExample.class);

    /**
     * 示例1：使用原始getOrCreateCache方法（类型丢失）
     */
    public void example1_TypeLoss() {
        SpringBootCacheManager cacheManager = new SpringBootCacheManager();

        // 这种方式会丢失K、V类型信息
        Cache<String, User> cache = cacheManager.getOrCreateCache("userCache");

        // 检查类型信息
        if (cache instanceof EnhancedDistributedTieredCache<?, ?> enhancedCache) {
            log.info("Cache created - Key type: {}, Value type: {}",
                    enhancedCache.getKeyClass(), enhancedCache.getValueClass());
            // 输出：Key type: null, Value type: null (类型丢失)
        }
    }

    /**
     * 示例2：使用带类型参数的getOrCreateCache方法
     */
    public void example2_ExplicitTypes() {
        SpringBootCacheManager cacheManager = new SpringBootCacheManager();

        // 显式传递类型信息
        Cache<String, User> cache = cacheManager.getOrCreateCache("userCache", String.class, User.class);

        // 检查类型信息
        if (cache instanceof EnhancedDistributedTieredCache<?, ?> enhancedCache) {
            log.info("Cache created - Key type: {}, Value type: {}",
                    enhancedCache.getKeyClass(), enhancedCache.getValueClass());
            // 输出：Key type: class java.lang.String, Value type: class User
        }

        // 现在可以精确匹配CacheLoader
        cache.get("user123"); // 会自动发现匹配的UserCacheLoader
    }

    /**
     * 示例3：使用TypeReference方法
     */
    public void example3_TypeReference() {
        SpringBootCacheManager cacheManager = new SpringBootCacheManager();

        // 使用TypeReference捕获类型信息
        Cache<String, User> cache = cacheManager.getOrCreateCacheWithTypeRef("userCache",
                new TypeReference<>() {
                });

        // 检查类型信息
        if (cache instanceof EnhancedDistributedTieredCache<?, ?> enhancedCache) {
            log.info("Cache created - Key type: {}, Value type: {}",
                    enhancedCache.getKeyClass(), enhancedCache.getValueClass());
            // 输出：Key type: class java.lang.String, Value type: class User
        }
    }

    /**
     * 示例4：复杂泛型类型的处理
     */
    public void example4_ComplexGenericTypes() {
        SpringBootCacheManager cacheManager = new SpringBootCacheManager();

        // 处理复杂的泛型类型，比如List<User>
        Cache<String, java.util.List<User>> cache = cacheManager.getOrCreateCache(
                "userListCache",
                String.class,
                (Class<java.util.List<User>>) (Class<?>) java.util.List.class  // 需要强制转换
        );

        if (cache instanceof EnhancedDistributedTieredCache<?, ?> enhancedCache) {
            log.info("Complex type cache - Key type: {}, Value type: {}",
                    enhancedCache.getKeyClass(), enhancedCache.getValueClass());
        }
    }

    /**
     * 示例5：工厂方法模式
     */
    public static class TypedCacheFactory {
        private final SpringBootCacheManager cacheManager;

        public TypedCacheFactory(SpringBootCacheManager cacheManager) {
            this.cacheManager = cacheManager;
        }

        public Cache<String, User> getUserCache() {
            return cacheManager.getOrCreateCache("userCache", String.class, User.class);
        }

        public Cache<Long, Product> getProductCache() {
            return cacheManager.getOrCreateCache("productCache", Long.class, Product.class);
        }

        public <K, V> Cache<K, V> createTypedCache(String cacheName, Class<K> keyType, Class<V> valueType) {
            return cacheManager.getOrCreateCache(cacheName, keyType, valueType);
        }
    }

    /**
     * 用户实体类
     */
    public static class User {
        private String id;
        private String name;

        public User(String id, String name) {
            this.id = id;
            this.name = name;
        }

        // getters and setters
        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    /**
     * 产品实体类
     */
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
        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public Double getPrice() {
            return price;
        }

        public void setPrice(Double price) {
            this.price = price;
        }
    }
}