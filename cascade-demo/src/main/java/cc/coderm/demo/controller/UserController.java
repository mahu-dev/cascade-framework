package cc.coderm.demo.controller;

import cc.coderm.demo.model.User;
import cc.coderm.demo.service.UserService;
import io.github.cascade.cache.api.Cache;
import io.github.cascade.cache.manager.SpringBootCacheManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
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
    private SpringBootCacheManager springBootCacheManager;

    // 注入自动发现的缓存
    @Autowired
    @Qualifier("userCache")
    private Cache<String, User> userCache;


    @GetMapping("test1")
    public void test() {
        System.out.println("SpringBootCacheManager: " + springBootCacheManager);

        // 检查RedissonClient是否正确配置
        if (springBootCacheManager.getRedissonClient() != null) {
            org.redisson.api.RedissonClient redissonClient = springBootCacheManager.getRedissonClient();
            System.out.println("RedissonClient配置: " + redissonClient.getConfig());
        } else {
            System.out.println("RedissonClient未配置");
        }

        Cache<Object, Object> test = springBootCacheManager.getOrCreateCache("test");
        test.put("test", "test111");

    }

    @GetMapping("test2")
    public void test2() {

//        Cache<Object, Object> test = springBootCacheManager.getOrCreateCache("test");
        // 使用TypeReference捕获类型信息
//        Cache<String, User> cache = springBootCacheManager.getOrCreateCacheWithTypeRef("userCache", new TypeReference<>() {
//        });
        Cache<String, User> test = springBootCacheManager.getOrCreateCache("test", User.class);


        System.out.println(test.getOrLoad("test"));

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


    /**
     * 测试自动CacheLoader功能
     */
    @GetMapping("/cache/auto-load/{userId}")
    public User testAutoLoad(@PathVariable String userId) {
        log.info("Testing auto CacheLoader for user: {}", userId);

        // 使用自动发现CacheLoader的缓存
        long start = System.currentTimeMillis();
        User user = userCache.get(userId); // 这里会自动从UserCacheLoader加载如果缓存未命中
        long end = System.currentTimeMillis();

        log.info("Cache get operation took: {}ms", end - start);
        log.info("Cache stats: {}", userCache.getStats());

        return user;
    }

    /**
     * 测试批量自动加载
     */
    @GetMapping("/cache/auto-load-batch")
    public List<User> testBatchAutoLoad(@RequestParam("ids") List<String> userIds) {
        log.info("Testing batch auto CacheLoader for users: {}", userIds);

        long start = System.currentTimeMillis();
        var usersMap = userCache.getAll(java.util.Set.copyOf(userIds));
        long end = System.currentTimeMillis();

        log.info("Batch cache get operation took: {}ms", end - start);
        log.info("Loaded {} users from cache", usersMap.size());

        return usersMap.values().stream().toList();
    }

    /**
     * 检查自动发现状态
     */
    @GetMapping("/cache/discovery-status")
    public String checkDiscoveryStatus() {
        StringBuilder status = new StringBuilder();
        status.append("=== CacheLoader Auto-Discovery Status ===\n");

        try {
            // 检查userCache是否有CacheLoader
            status.append("UserCache CacheLoader: ");
            if (userCache instanceof io.github.cascade.cache.core.unified.UnifiedCache) {
                var unifiedCache = (io.github.cascade.cache.core.unified.UnifiedCache<String, User>) userCache;
                if (unifiedCache.getLoader() != null) {
                    status.append("✅ Found - ").append(unifiedCache.getLoader().getClass().getSimpleName()).append("\n");
                } else {
                    status.append("❌ Not Found\n");
                }
            } else {
                status.append("⚠️ Not UnifiedCache instance\n");
            }

            status.append("Cache Stats: ").append(userCache.getStats()).append("\n");

        } catch (Exception e) {
            status.append("❌ Error: ").append(e.getMessage()).append("\n");
        }

        return status.toString();
    }
}
