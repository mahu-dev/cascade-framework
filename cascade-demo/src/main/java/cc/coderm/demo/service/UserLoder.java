package cc.coderm.demo.service;

import cc.coderm.demo.model.User;
import io.github.cascade.cache.api.CacheLoader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * @author lionel lionelk@163.com
 * =============================
 * Date: 2025/8/18
 * Time: 09:56
 * =============================
 */
@Service
public class UserLoder implements CacheLoader<String, User> {
    @Autowired
    private UserService userService;

    @Override
    public User load(String key) throws Exception {
        return userService.findById(key);
    }
}
