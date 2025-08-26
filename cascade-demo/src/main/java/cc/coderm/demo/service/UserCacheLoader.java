package cc.coderm.demo.service;

import cc.coderm.demo.model.User;
import io.github.cascade.cache.api.CacheLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 用户缓存加载器
 * 演示自动CacheLoader发现机制
 */
@Service
public class UserCacheLoader implements CacheLoader<String, User> {

    private static final Logger log = LoggerFactory.getLogger(UserCacheLoader.class);

    @Autowired
    private UserService userService;

    @Override
    public User load(String userId) throws Exception {
        log.info("Loading user directly from database: {}", userId);
        
        // 模拟数据库查询延迟
        Thread.sleep(100);
        
        // 直接从数据库加载，避免循环调用
        User user = userService.loadUserDirectFromDatabase(userId);
        if (user == null) {
            log.warn("User not found in database: {}", userId);
            return null;
        }
        
        log.info("Successfully loaded user from database: {}", user);
        return user;
    }

    @Override
    public Map<String, User> loadAll(Set<String> userIds) throws Exception {
        log.info("Batch loading users: {}", userIds);
        
        return userIds.stream()
            .collect(Collectors.toMap(
                id -> id,
                id -> {
                    try {
                        return load(id);
                    } catch (Exception e) {
                        log.error("Failed to load user: {}", id, e);
                        return null;
                    }
                }
            ))
            .entrySet()
            .stream()
            .filter(entry -> entry.getValue() != null)
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}