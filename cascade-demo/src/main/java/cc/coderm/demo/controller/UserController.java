package cc.coderm.demo.controller;

import cc.coderm.demo.model.User;
import cc.coderm.demo.service.UserService;
import io.github.cascade.cache.simple.Cache;
import io.github.cascade.cache.simple.CacheManager;
import io.github.cascade.cache.simple.CacheManagerImpl;
import io.github.cascade.cache.simple.TieredCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

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
        Cache<String, User> cache = cascadeCacheManager.getOrCreateCache(
                "programmatic-cache", String.class, User.class
        );

        long start = System.currentTimeMillis();
        User user = cache.getOrLoad(id, userId -> {
            // 如果缓存中没有，则从数据库加载
            return userService.loadUserDirectFromDatabase(userId);
        });
        long end = System.currentTimeMillis();
        log.info("编程式缓存+自动CacheLoader测试 - 查询耗时: {}ms", (end - start));
        return user;
    }

    /**
     * 获取CacheLoaderResolver的统计信息
     */
    @GetMapping("/test/resolver-stats")
    public Object getResolverStats() {
        if (cascadeCacheManager instanceof CacheManagerImpl cacheManagerImpl) {
            if (cacheManagerImpl.getCacheLoaderResolver() != null) {
                return cacheManagerImpl.getCacheLoaderResolver().getStats();
            }
        }
        return "CacheLoaderResolver未设置";
    }

    /**
     * 测试缓存统计信息
     */
    @GetMapping("/test/cache-stats/{cacheName}")
    public String getCacheStats(@PathVariable String cacheName) {
        @SuppressWarnings("unchecked")
        Cache<String, User> cache = cascadeCacheManager.getCache(cacheName);
        if (cache != null) {
            if (cache instanceof TieredCache) {
                @SuppressWarnings("unchecked")
                TieredCache<String, User> tieredCache = (TieredCache<String, User>) cache;
                return "缓存 " + cacheName + " 统计信息: " + tieredCache.getStats();
            } else {
                return "缓存 " + cacheName + " 基本信息: 名称=" + cache.getName() + ", 大小=" + cache.size();
            }
        }
        return "缓存 " + cacheName + " 不存在";
    }

    /**
     * 获取缓存管理器统计信息
     */
    @GetMapping("/test/manager-stats")
    public Object getManagerStats() {
        if (cascadeCacheManager instanceof CacheManagerImpl cacheManagerImpl) {
            return cacheManagerImpl.getStats();
        }
        return "统计信息不可用";
    }

}
