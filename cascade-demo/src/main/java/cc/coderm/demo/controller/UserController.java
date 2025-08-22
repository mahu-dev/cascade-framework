package cc.coderm.demo.controller;

import cc.coderm.demo.model.User;
import cc.coderm.demo.service.UserService;
import io.github.cascade.cache.api.Cache;
import io.github.cascade.cache.api.CacheStats;
import io.github.cascade.cache.manager.CascadeCacheManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * @author lionel lionelk@163.com
 * =============================
 * Date: 2025/8/14
 * Time: 11:29
 * =============================
 */


@RestController
@RequestMapping("/api/users")
public class UserController {

    private static final Logger log = LoggerFactory.getLogger(UserController.class);

    @Autowired
    private UserService userService;


    @Autowired
    private CascadeCacheManager cascadeCacheManager;


    @GetMapping("test1")
    public void test() {
        System.out.println("CascadeCacheManager: " + cascadeCacheManager);

        // 检查RedissonClient是否正确配置
        if (cascadeCacheManager.getRedissonClient() != null) {
            org.redisson.api.RedissonClient redissonClient = cascadeCacheManager.getRedissonClient();
            System.out.println("RedissonClient配置: " + redissonClient.getConfig());
        } else {
            System.out.println("RedissonClient未配置");
        }

        Cache<String, User> test = cascadeCacheManager.getOrCreateCache("user", User.class);

        User user = new User();
        user.setId(1L);
        user.setName("王五");
        user.setEmail("wangwu@123.com");
        user.setAge(1);

        test.put("1", user);

    }

    @GetMapping("test2")
    public void test2(@RequestParam("id") String id) {
        Cache<String, User> test = cascadeCacheManager.getOrCreateCache("user", User.class);
        System.out.println(test.getOrLoad(id));

    }


    @GetMapping("test4")
    public String test4(@RequestParam("cacheName") String cacheName) {
        Cache<Object, Object> cache = cascadeCacheManager.getCache(cacheName);
        System.out.println(cache.toString());
        return cache.toString();
    }

    @GetMapping("test5")
    public User test5(@RequestParam("cacheName") String cacheName) {
        Cache<Long, User> user = cascadeCacheManager.newCache("user", Long.class, User.class);
        user.put(1000L, new User(1000L, "张三", "zhangsan@123.com", 188));
        User user1 = user.get(1000L);
        return user1;
    }

    @GetMapping("test6")
    public void test6() {
        Cache<Long, User> user = cascadeCacheManager.newCache("user", Long.class, User.class);
        long size = user.size();
        System.out.println("缓存大小: " + size);
        CacheStats stats = user.getStats();
        System.out.println("缓存统计信息: " + stats.toString());

    }


    /**
     * 获取用户 - 测试缓存效果
     */
    @GetMapping("/{id}")
    public User getUser(@PathVariable Long id) {
        long start = System.currentTimeMillis();
        User user = userService.findById(id.toString());
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


}
