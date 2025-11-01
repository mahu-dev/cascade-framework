package cc.coderm.demo.controller;

import cc.coderm.demo.model.User;
import cc.coderm.demo.service.UserService;
import io.github.cascade.cache.api.Cache;
import io.github.cascade.cache.api.CacheManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;
import java.util.function.Function;

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
    @SuppressWarnings("rawtypes")
    private CacheManager cascadeCacheManager;


    // ==================== 自动CacheLoader发现功能测试端点 ====================

    @GetMapping("/test1")
    public void test1(@RequestParam("id") String id) {
        Cache<String, User> users = cascadeCacheManager.getOrCreateCache("users", String.class, User.class);
        Optional<User> user = users.get(id);
        if (user.isPresent()) {
            log.info(">>> 缓存命中，用户: {}", user.get());
        } else {
            log.info(">>> 缓存未命中，用户不存在");
        }

    }

    /**
     * 测试自动CacheLoader发现功能
     */
    @GetMapping("/test/auto-loader/{id}")
    public User testAutoLoader(@PathVariable String id) {
        long start = System.currentTimeMillis();
        User user = userService.findByIdWithAutoLoader(id);
        long end = System.currentTimeMillis();
        log.info("自动CacheLoader测试 - 查询耗时: {}ms", (end - start));
        return user;
    }

    /**
     * 测试自动刷新功能 - 先清空缓存确保缓存未命中
     */
    @GetMapping("/test/refresh")
    public User testRefresh(@RequestParam("id") String id) {
        log.info("=== 测试自动刷新功能 ===");

        // 先清空这个键的缓存，确保缓存未命中
        Cache<String, User> cache = cascadeCacheManager.getCache("users");
        if (cache != null) {
            cache.evict(id);
            log.info("已清空缓存键: {}", id);
        }

        long start = System.currentTimeMillis();

        // 使用实际的用户ID，而不是生成新的ID
        User user = userService.findByIdWithAutoLoader(id);

        long end = System.currentTimeMillis();
        log.info("自动刷新测试 - 查询耗时: {}ms，等待10秒观察刷新日志...", (end - start));
        return user;
    }

    /**
     * 测试显式指定CacheLoader功能
     */
    @GetMapping("/test/explicit-loader/{id}")
    public User testExplicitLoader(@PathVariable String id) {
        long start = System.currentTimeMillis();
        User user = userService.findByIdWithExplicitLoader(id);
        long end = System.currentTimeMillis();
        log.info("显式CacheLoader测试 - 查询耗时: {}ms", (end - start));
        return user;
    }

    /**
     * 测试无CacheLoader情况
     */
    @GetMapping("/test/no-loader/{id}")
    public User testNoLoader(@PathVariable String id) {
        long start = System.currentTimeMillis();
        User user = userService.findByIdNoLoader(id);
        long end = System.currentTimeMillis();
        log.info("无CacheLoader测试 - 查询耗时: {}ms", (end - start));
        return user;
    }

    /**
     * 测试编程式缓存创建与自动CacheLoader发现
     */
    @GetMapping("/test/programmatic/{id}")
    public User testProgrammaticCache(@PathVariable String id) {
        // 创建一个新的缓存，看看是否能自动发现CacheLoader
//        Cache<String, User> cache = cascadeCacheManager.getOrCreateCache(
//                "programmatic-cache", String.class, User.class
//        );
//        User user = cache.getOrLoad(id, userId -> {
//            // 如果缓存中没有，则从数据库加载
//            return userService.loadUserDirectFromDatabase(userId);
//        });

//        Cache<String, User> users = cascadeCacheManager.getOrCreateAutoRefreshCache("users", String.class, User.class);
        Cache<String, User> users = cascadeCacheManager.getOrCreateDistributedAutoRefreshCache("users", String.class, User.class);

        User user = users.getOrLoad(id, userId -> {
            return userService.loadUserDirectFromDatabase(userId);
        });

        System.out.println("已创建缓存: " + users);
        long start = System.currentTimeMillis();

        long end = System.currentTimeMillis();
        log.info("编程式缓存+自动CacheLoader测试 - 查询耗时: {}ms", (end - start));
        return user;
    }

    /**
     * 方案1：手动管理刷新器（需要手动添加键和启动）
     * 这个方案需要手动管理刷新器，对用户不够透明
     */
    @GetMapping("/test/manual-refresh-setup/{id}")
    public User testManualRefreshSetup(@PathVariable String id) {
        log.info("=== 方案1：手动管理刷新器 ===");

        // 步骤1：获取或创建缓存，并提供加载器
        @SuppressWarnings("unchecked")
        Cache<String, User> cache = cascadeCacheManager.getOrCreateCache(
                "users-with-refresh",
                String.class,
                User.class,
                (Function<String, User>) userId -> {
                    log.info(">>> 加载器被调用，从数据库加载用户: {}", userId);
                    return userService.loadUserDirectFromDatabase(userId);
                }
        );

        // 步骤2：获取或创建缓存刷新器
        @SuppressWarnings("unchecked")
        var refresher = cascadeCacheManager.getOrCreateCacheRefresher("users-with-refresh");

        // 步骤3：动态添加需要刷新的键
        if (refresher != null && !refresher.getMonitoredKeys().contains(id)) {
            refresher.addKey(id, 60L);  // 每60秒刷新一次
            log.info(">>> 已将键 [{}] 添加到刷新监控列表", id);
        }

        // 步骤4：确保刷新器已启动
        if (refresher != null && !refresher.isRunning()) {
            refresher.start();
            log.info(">>> 刷新器已启动");
        }

        // 步骤5：正常使用缓存
        long start = System.currentTimeMillis();
        User user = cache.get(id).orElse(null);
        long end = System.currentTimeMillis();

        log.info(">>> 查询耗时: {}ms，监控键数: {}",
                (end - start), refresher != null ? refresher.getMonitoredKeys().size() : 0);

        return user;
    }


    /**
     * 测试批量预热缓存并启用定时刷新
     * 适用场景：应用启动时预热热点数据
     */
    @PostMapping("/test/preheat")
    @SuppressWarnings("unchecked")
    public String preheatCache(@RequestBody String[] userIds) {
        log.info("=== 开始预热缓存并启用定时刷新 ===");

        // 1. 获取缓存和刷新器
        Cache<String, User> cache = cascadeCacheManager.getOrCreateCache(
                "users-with-refresh",
                String.class,
                User.class,
                (Function<String, User>) uid -> userService.loadUserDirectFromDatabase(uid)
        );
        var refresher = cascadeCacheManager.getOrCreateCacheRefresher("users-with-refresh");

        // 2. 预热：批量加载数据到缓存
        int preheated = 0;
        for (String userId : userIds) {
            User user = cache.getOrLoad(userId, uid -> userService.loadUserDirectFromDatabase(uid));
            if (user != null) {
                // 3. 将热点数据添加到定时刷新列表
                refresher.addKey(userId, 120);  // 每2分钟刷新一次
                preheated++;
            }
        }

        // 4. 启动刷新器
        if (!refresher.isRunning()) {
            refresher.start();
            log.info(">>> 刷新器已启动");
        }

        String result = String.format("预热完成！成功预热 %d 个用户，已启用定时刷新", preheated);
        log.info(">>> {}", result);
        return result;
    }

    /**
     * 手动触发特定键的刷新（支持异步）
     */
    @PostMapping("/test/manual-refresh/{id}")
    public String manualRefresh(@PathVariable String id) {
        var refresher = cascadeCacheManager.getOrCreateCacheRefresher("users-with-refresh");

        // 手动异步刷新
        refresher.refresh(id).thenAccept(user -> {
            log.info(">>> 手动刷新完成，用户: {}", user);
        }).exceptionally(ex -> {
            log.error(">>> 刷新失败", ex);
            return null;
        });

        return "已触发异步刷新: " + id;
    }

    /**
     * 停止刷新器（应用关闭时调用）
     */
    @PostMapping("/test/stop-refresh")
    public String stopRefresh() {
        var refresher = cascadeCacheManager.getOrCreateCacheRefresher("users-with-refresh");
        refresher.stop();
        log.info(">>> 刷新器已停止");
        return "刷新器已停止";
    }

}
