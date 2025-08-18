package cc.coderm.demo.controller;

import cc.coderm.demo.model.User;
import cc.coderm.demo.service.UserService;
import io.github.cascade.autoconfigure.CascadeAutoConfiguration.CascadeBuilderFactory;
import io.github.cascade.cache.api.Cache;
import io.github.cascade.cache.manager.SpringBootCacheManager;
import io.github.cascade.cache.util.TypeReference;
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

    @Autowired
    private UserService userService;

    @Autowired
    private CascadeBuilderFactory builderFactory;

    @Autowired
    private SpringBootCacheManager springBootCacheManager;


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
        Cache<String, User> cache = springBootCacheManager.getOrCreateCacheWithTypeRef("userCache",
                new TypeReference<>() {
                });


        System.out.println(cache.get("test"));

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
     * 直接使用CascadeBuilderFactory - 演示新的统一缓存创建方式
     */
    @RequestMapping("/cache/direct")
    public String directCacheOperation() {
        // 使用新的统一API创建缓存 - Builder模式
        Cache<String, String> cache = builderFactory.<String, String>createBuilder("test")
                .maximumSize(1000)
                .expireAfterWrite(java.time.Duration.ofMinutes(30))
                .build();


        // 设置缓存
        cache.put("direct-key", "direct-value-" + System.currentTimeMillis());

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

        // 通过builderFactory获取cacheManager来获取缓存统计
        for (String cacheName : builderFactory.getCacheManager().getCacheNames()) {
            stats.append("缓存: ").append(cacheName).append("\n");
            // 这里可以添加具体的统计信息获取逻辑
        }

        return stats.toString();
    }
}
