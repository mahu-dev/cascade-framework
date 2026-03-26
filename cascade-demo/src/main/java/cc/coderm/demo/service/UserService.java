package cc.coderm.demo.service;

import cc.coderm.demo.model.User;
import io.github.cascade.cache.v2.api.annotations.CacheEvict;
import io.github.cascade.cache.v2.api.annotations.CachePut;
import io.github.cascade.cache.v2.api.annotations.Cacheable;
import lombok.extern.slf4j.Slf4j;
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
//@CacheConfig(cacheNames = "users")  // 指定默认缓存名称
public class UserService {

    // 模拟数据库
    private final Map<String, User> userDatabase = new ConcurrentHashMap<>();

    // 初始化一些测试数据
    public UserService() {
        userDatabase.put("1", new User(1L, "张三", "zhangsan@example.com", 25));
        userDatabase.put("2", new User(2L, "李四", "lisi@example.com", 30));
        userDatabase.put("3", new User(3L, "王五", "wangwu@example.com", 28));
    }

    /**
     * 根据ID查询用户 - 使用显式指定的CacheLoader
     */
    @Cacheable(value = "users", key = "#id")
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
        log.info(">>> 从数据库查询所有用户");
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
        log.info(">>> 从数据库根据名称查询用户: {}", name);
        return userDatabase.values().stream()
                .filter(user -> user.getName().contains(name))
                .toList();
    }

    /**
     * 清除所有用户缓存
     */
    @CacheEvict(allEntries = true)
    public void clearAllCache() {
        log.info(">>> 清除所有用户缓存");
    }

    @CachePut(value = "users", key = "#id")
    public User updateById(String id) {
        User user = userDatabase.get(id);
        log.debug(">>> 更新用户: {}", user);
        return user;
    }

    @CacheEvict(value = "users", key = "#id")
    public void deleteById(String id) {
        userDatabase.remove(id);
    }

    // ==================== 自动CacheLoader发现功能演示 ====================

    /**
     * 演示自动CacheLoader发现功能
     * 不需要显式指定loader，会自动发现UserCacheLoader实现
     */
    @Cacheable(value = "users", key = "#id", enableRefresh = true, refreshInterval = 10)
    public User findByIdWithAutoLoader(String id) {
        log.info(">>> 使用自动发现的CacheLoader查询用户: {}", id);
        // 如果缓存未命中且找到了匹配的CacheLoader，这个方法可能不会被调用
        // 因为自动发现的UserCacheLoader会被使用
        return loadUserDirectFromDatabase(id);
    }

    /**
     * 演示显式指定loader（传统方式）
     */
    @Cacheable(value = "users-explicit", key = "#id")
    public User findByIdWithExplicitLoader(String id) {
        log.info(">>> 使用显式指定的CacheLoader查询用户: {}", id);
        return loadUserDirectFromDatabase(id);
    }

    /**
     * 演示没有CacheLoader的情况
     * 当缓存未命中时会调用这个方法
     */
    @Cacheable(value = "users", key = "#id")
    public User findByIdNoLoader(String id) {
        log.info(">>> 没有CacheLoader时的查询用户: {}", id);
        return loadUserDirectFromDatabase(id);
    }
}
