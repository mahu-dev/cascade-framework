# Cascade Cache 使用示例

## 完整示例项目结构

```
demo-project/
├── pom.xml
├── src/main/java/
│   ├── DemoApplication.java
│   ├── config/CacheConfig.java
│   ├── service/UserService.java
│   ├── controller/UserController.java
│   └── model/User.java
└── src/main/resources/
    └── application.yml
```

## 1. Maven依赖配置

```xml
<!-- pom.xml -->
<dependencies>
    <!-- Cascade Spring Boot Starter -->
    <dependency>
        <groupId>cc.coderm</groupId>
        <artifactId>cascade-spring-boot-starter</artifactId>
        <version>1.0-SNAPSHOT</version>
    </dependency>
    
    <!-- Spring Boot Web Starter -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    
    <!-- Spring Boot Data JPA (可选，用于数据库操作) -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>
    
    <!-- H2 Database (测试用) -->
    <dependency>
        <groupId>com.h2database</groupId>
        <artifactId>h2</artifactId>
        <scope>runtime</scope>
    </dependency>
</dependencies>
```

## 2. 配置文件

```yaml
# application.yml
cascade:
  cache:
    enabled: true
    
    # 默认缓存配置
    defaults:
      name: default-cache
      record-stats: true
      
      # L1缓存配置 (本地Caffeine缓存)
      l1:
        enabled: true
        maximum-size: 10000
        expire-after-access: PT2H    # 2小时不访问则过期
        expire-after-write: PT4H     # 4小时强制过期
        record-stats: true
        
      # L2缓存配置 (Redis远程缓存)
      l2:
        enabled: true
        key-prefix: "demo:cache:"
        default-ttl: PT24H           # 24小时TTL
        timeout: PT5S                # 5秒超时
        
    # 同步配置 (多节点缓存同步)
    sync:
      enabled: true
      
    # 特定缓存配置
    caches:
      users:
        name: users
        l1:
          maximum-size: 5000
          expire-after-access: PT1H
        l2:
          key-prefix: "user:"
          default-ttl: PT12H
          
      products:
        name: products
        l1:
          maximum-size: 20000
          expire-after-write: PT30M
        l2:
          key-prefix: "product:"
          default-ttl: PT6H

# Spring Boot配置
spring:
  application:
    name: cascade-cache-demo
    
  # Redis配置
  data:
    redis:
      host: localhost
      port: 6379
      database: 0
      timeout: 5s
      
  # H2数据库配置 (测试用)
  datasource:
    url: jdbc:h2:mem:testdb
    driver-class-name: org.h2.Driver
    username: sa
    password: 
    
  h2:
    console:
      enabled: true
      
  jpa:
    hibernate:
      ddl-auto: create-drop
    show-sql: true

# 日志配置
logging:
  level:
    io.github.cascade: DEBUG
    root: INFO
```

## 3. 启动类

```java
// DemoApplication.java
package com.example.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication
@EnableCaching  // 启用Spring Cache注解支持
public class DemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }
}
```

## 4. 实体类

```java
// model/User.java
package com.example.demo.model;

import jakarta.persistence.*;
import java.io.Serializable;

@Entity
@Table(name = "users")
public class User implements Serializable {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    private String name;
    private String email;
    private Integer age;
    
    // 构造函数
    public User() {}
    
    public User(String name, String email, Integer age) {
        this.name = name;
        this.email = email;
        this.age = age;
    }
    
    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    
    public Integer getAge() { return age; }
    public void setAge(Integer age) { this.age = age; }
    
    @Override
    public String toString() {
        return "User{id=" + id + ", name='" + name + "', email='" + email + "', age=" + age + "}";
    }
}
```

## 5. Service层 (使用Spring Cache注解)

```java
// service/UserService.java
package com.example.demo.service;

import com.example.demo.model.User;
import org.springframework.cache.annotation.*;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
@CacheConfig(cacheNames = "users")  // 指定默认缓存名称
public class UserService {
    
    // 模拟数据库
    private final Map<Long, User> userDatabase = new ConcurrentHashMap<>();
    private Long nextId = 1L;
    
    // 初始化一些测试数据
    public UserService() {
        userDatabase.put(1L, new User("张三", "zhangsan@example.com", 25));
        userDatabase.put(2L, new User("李四", "lisi@example.com", 30));
        userDatabase.put(3L, new User("王五", "wangwu@example.com", 28));
        nextId = 4L;
    }
    
    /**
     * 根据ID查询用户 - 会被缓存
     */
    @Cacheable(key = "#id")
    public User findById(Long id) {
        System.out.println(">>> 从数据库查询用户: " + id);
        // 模拟数据库查询延迟
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return userDatabase.get(id);
    }
    
    /**
     * 查询所有用户 - 会被缓存
     */
    @Cacheable(key = "'all'")
    public List<User> findAll() {
        System.out.println(">>> 从数据库查询所有用户");
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return new ArrayList<>(userDatabase.values());
    }
    
    /**
     * 根据名称查询用户 - 自定义缓存条件
     */
    @Cacheable(key = "#name", condition = "#name.length() > 2")
    public List<User> findByName(String name) {
        System.out.println(">>> 从数据库根据名称查询用户: " + name);
        return userDatabase.values().stream()
                .filter(user -> user.getName().contains(name))
                .toList();
    }
    
    /**
     * 创建用户 - 清除相关缓存
     */
    @CacheEvict(key = "'all'")
    public User createUser(User user) {
        user.setId(nextId++);
        userDatabase.put(user.getId(), user);
        System.out.println(">>> 创建用户: " + user);
        return user;
    }
    
    /**
     * 更新用户 - 清除对应缓存
     */
    @CacheEvict(key = "#user.id")
    @CacheEvict(key = "'all'")
    public User updateUser(User user) {
        userDatabase.put(user.getId(), user);
        System.out.println(">>> 更新用户: " + user);
        return user;
    }
    
    /**
     * 删除用户 - 清除多个缓存
     */
    @Caching(evict = {
        @CacheEvict(key = "#id"),
        @CacheEvict(key = "'all'")
    })
    public boolean deleteUser(Long id) {
        User removed = userDatabase.remove(id);
        System.out.println(">>> 删除用户: " + id);
        return removed != null;
    }
    
    /**
     * 清除所有用户缓存
     */
    @CacheEvict(allEntries = true)
    public void clearAllCache() {
        System.out.println(">>> 清除所有用户缓存");
    }
}
```

## 6. Controller层

```java
// controller/UserController.java
package com.example.demo.controller;

import com.example.demo.model.User;
import com.example.demo.service.UserService;
import io.github.cascade.autoconfigure.CascadeCacheManager;
import io.github.cascade.cache.api.Cache;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/users")
public class UserController {
    
    @Autowired
    private UserService userService;
    
    @Autowired
    private CascadeCacheManager cacheManager;
    
    /**
     * 获取用户 - 测试缓存效果
     */
    @GetMapping("/{id}")
    public User getUser(@PathVariable Long id) {
        long start = System.currentTimeMillis();
        User user = userService.findById(id);
        long end = System.currentTimeMillis();
        System.out.println("查询耗时: " + (end - start) + "ms");
        return user;
    }
    
    /**
     * 获取所有用户
     */
    @GetMapping
    public List<User> getAllUsers() {
        long start = System.currentTimeMillis();
        List<User> users = userService.findAll();
        long end = System.currentTimeMillis();
        System.out.println("查询所有用户耗时: " + (end - start) + "ms");
        return users;
    }
    
    /**
     * 根据名称搜索用户
     */
    @GetMapping("/search")
    public List<User> searchUsers(@RequestParam String name) {
        return userService.findByName(name);
    }
    
    /**
     * 创建用户
     */
    @PostMapping
    public User createUser(@RequestBody User user) {
        return userService.createUser(user);
    }
    
    /**
     * 更新用户
     */
    @PutMapping("/{id}")
    public User updateUser(@PathVariable Long id, @RequestBody User user) {
        user.setId(id);
        return userService.updateUser(user);
    }
    
    /**
     * 删除用户
     */
    @DeleteMapping("/{id}")
    public boolean deleteUser(@PathVariable Long id) {
        return userService.deleteUser(id);
    }
    
    /**
     * 清除所有缓存
     */
    @PostMapping("/cache/clear")
    public String clearCache() {
        userService.clearAllCache();
        return "缓存已清除";
    }
    
    /**
     * 直接使用CascadeCacheManager API
     */
    @PostMapping("/cache/direct")
    public String directCacheOperation() {
        // 获取缓存实例
        Cache<String, String> cache = cacheManager.getCache("test");
        
        // 设置缓存
        cache.put("direct-key", "direct-value");
        
        // 获取缓存
        String value = cache.get("direct-key");
        
        return "直接缓存操作结果: " + value;
    }
    
    /**
     * 获取缓存统计信息
     */
    @GetMapping("/cache/stats")
    public String getCacheStats() {
        StringBuilder stats = new StringBuilder();
        
        for (String cacheName : cacheManager.getCacheNames()) {
            stats.append("缓存: ").append(cacheName).append("\n");
            // 这里可以添加具体的统计信息获取逻辑
        }
        
        return stats.toString();
    }
}
```

## 7. 运行和测试

### 启动应用

```bash
mvn spring-boot:run
```

### 测试API

```bash
# 1. 首次查询用户 (会从数据库查询，较慢)
curl http://localhost:8080/api/users/1

# 2. 再次查询相同用户 (从缓存获取，很快)
curl http://localhost:8080/api/users/1

# 3. 查询所有用户
curl http://localhost:8080/api/users

# 4. 创建新用户 (会清除相关缓存)
curl -X POST http://localhost:8080/api/users \
  -H "Content-Type: application/json" \
  -d '{"name":"赵六","email":"zhaoliu@example.com","age":35}'

# 5. 更新用户 (会清除对应缓存)
curl -X PUT http://localhost:8080/api/users/1 \
  -H "Content-Type: application/json" \
  -d '{"name":"张三(更新)","email":"zhangsan-new@example.com","age":26}'

# 6. 直接缓存操作
curl -X POST http://localhost:8080/api/users/cache/direct

# 7. 清除所有缓存
curl -X POST http://localhost:8080/api/users/cache/clear
```

## 8. 高级配置示例

```java
// config/CacheConfig.java
package com.example.demo.config;

import io.github.cascade.autoconfigure.CascadeCacheManager;
import io.github.cascade.cache.api.Cache;
import io.github.cascade.cache.api.CacheLoader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CacheConfig {
    
    @Autowired
    private CascadeCacheManager cacheManager;
    
    /**
     * 自定义缓存加载器
     */
    @Bean
    public CacheLoader<String, String> configCacheLoader() {
        return new CacheLoader<String, String>() {
            @Override
            public String load(String key) throws Exception {
                // 从配置文件或数据库加载配置
                System.out.println("加载配置: " + key);
                return "config-value-for-" + key;
            }
        };
    }
    
    /**
     * 初始化自定义缓存
     */
    @Bean
    public Cache<String, String> configCache() {
        Cache<String, String> cache = cacheManager.getCache("config");
        
        // 预热一些常用配置
        cache.put("app.name", "Cascade Demo");
        cache.put("app.version", "1.0.0");
        
        return cache;
    }
}
```

## 9. 监控和调试

```yaml
# 启用监控端点
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,caches
  endpoint:
    health:
      show-details: always

# 详细日志配置
logging:
  level:
    io.github.cascade.cache: DEBUG
    io.github.cascade.autoconfigure: DEBUG
    com.example.demo: DEBUG
```

### 访问监控端点

```bash
# 健康检查
curl http://localhost:8080/actuator/health

# 缓存信息
curl http://localhost:8080/actuator/caches

# 应用指标
curl http://localhost:8080/actuator/metrics
```

## 预期效果

1. **首次查询**: 耗时1-2秒（模拟数据库查询）
2. **缓存命中**: 耗时1-10毫秒（从内存获取）
3. **自动失效**: 按配置的TTL自动清理
4. **分布式同步**: 多节点间缓存自动同步
5. **降级保护**: Redis不可用时自动降级到本地缓存