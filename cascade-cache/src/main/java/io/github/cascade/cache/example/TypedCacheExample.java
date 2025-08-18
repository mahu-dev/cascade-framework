package io.github.cascade.cache.example;

import io.github.cascade.cache.api.Cache;
import io.github.cascade.cache.api.CacheLoader;
import io.github.cascade.cache.builder.CascadeCacheBuilder;
import io.github.cascade.cache.manager.SpringBootCacheManager;
import io.github.cascade.cache.util.TypeReference;
import org.springframework.stereotype.Component;

/**
 * 类型化缓存使用示例
 * 演示如何获取真实的K、V类型信息
 *
 * @author cascade
 */
@Component
public class TypedCacheExample {

    /**
     * 示例1：通过Builder显式设置类型
     */
    public void example1() {
        SpringBootCacheManager cacheManager = new SpringBootCacheManager();

        Cache<String, User> cache = new CascadeCacheBuilder<String, User>("userCache", cacheManager)
                .types(String.class, User.class)  // 显式设置类型
                .withLoader(new UserCacheLoader())
                .withRedis()
                .build();

        // 此时getKeyType()返回 "java.lang.String"
        // getValueType()返回 "com.example.User"
    }

    /**
     * 示例2：通过TypeReference捕获类型
     */
    public void example2() {
        // 使用TypeReference捕获复杂的泛型类型
        TypeReference<Cache<String, User>> typeRef = new TypeReference<>() {
        };

        System.out.println("Type: " + typeRef.getTypeName());
        System.out.println("Raw Type: " + typeRef.getRawType());

        // 可以从TypeReference中提取K、V类型
        if (typeRef.isParameterized()) {
            var typeArgs = typeRef.getTypeArguments();
            if (typeArgs.length == 2) {
                System.out.println("Key Type: " + typeArgs[0].getTypeName());
                System.out.println("Value Type: " + typeArgs[1].getTypeName());
            }
        }
    }

    /**
     * 示例3：自动类型匹配的CacheLoader
     */
    @Component
    public static class UserCacheLoader implements CacheLoader<String, User> {
        @Override
        public User load(String key) throws Exception {
            // 模拟从数据库加载用户
            return new User(key, "User " + key);
        }
    }

    /**
     * 示例4：通用CacheLoader with @AutoConfigureLoader
     */
    @Component
    @io.github.cascade.cache.annotation.AutoConfigureLoader
    public static class GenericCacheLoader implements CacheLoader<Object, Object> {
        @Override
        public Object load(Object key) throws Exception {
            // 通用加载逻辑
            return "Loaded: " + key;
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
}