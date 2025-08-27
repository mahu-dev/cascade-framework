package cc.coderm.demo.service;

import cc.coderm.demo.model.User;
import io.github.cascade.cache.annotation.CascadeCachePut;
import io.github.cascade.cache.annotation.CascadeCacheRefresh;
import io.github.cascade.cache.annotation.CascadeCacheable;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheConfig;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author lionel lionelk@163.com
 * =============================
 * Date: 2025/8/14
 * Time: 11:27
 * =============================
 */
@Slf4j
@Service
@CacheConfig(cacheNames = "users")  // 指定默认缓存名称
public class UserService {

    // 模拟数据库
    private final Map<String, User> userDatabase = new ConcurrentHashMap<>();
    private Long nextId = 1L;

    // 初始化一些测试数据
    public UserService() {
        userDatabase.put("1", new User(1L, "张三", "zhangsan@example.com", 25));
        userDatabase.put("2", new User(2L, "李四", "lisi@example.com", 30));
        userDatabase.put("3", new User(3L, "王五", "wangwu@example.com", 28));
        nextId = 4L;
    }

    /**
     * 根据ID查询用户 - 会被缓存
     */
    @CascadeCacheable(
            value = "users",
            key = "#id",
            enableL1 = true,
            enableL2 = true,
            ttl = "PT30M",
            loader = "userCacheLoader"
    )
    @CascadeCacheRefresh(
            value = "users",
            refreshInterval = 10,  // 10分钟 = 600秒
            loader = "userCacheLoader"
    )
    public User findById(String id) {
        log.info(">>> 从数据库查询用户(缓存方法): {}", id);
        return loadUserDirectFromDatabase(id);
    }

    /**
     * 直接从数据库加载用户（不经过缓存）
     * 供CacheLoader使用，避免循环调用
     */
    public User loadUserDirectFromDatabase(String id) {
        log.info(">>> 直接从数据库加载用户: {}", id);

        // 模拟数据库查询延迟
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        User user = userDatabase.get(id);
        log.info("<<< 从数据库返回用户: {}", user);
        return user;
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
        userDatabase.put(user.getId().toString(), user);
        System.out.println(">>> 创建用户: " + user);
        return user;
    }

    /**
     * 更新用户 - 清除对应缓存
     */
    @CacheEvict(key = "'all'")
    public User updateUser(User user) {
        userDatabase.put(user.getId().toString(), user);
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


    /**
     * 演示编程式缓存创建的新API
     */
    public void demonstrateNewCacheAPI() {
        // 注意：这个方法仅用于演示，实际使用中应通过依赖注入获取CacheManager
        // 新的API使用方式：
        // cacheManager.cacheBuilder("customCache")
        //     .maximumSize(5000)
        //     .expireAfterWrite(Duration.ofMinutes(30))
        //     .enableL2Cache(true, redissonClient)
        //     .enableProtection(true)
        //     .build();
    }

    @CascadeCachePut(value = "users", key = "#id")
    public User updateById(String id) {
        return userDatabase.get(id);
    }
}
