package cc.coderm.demo.config;

import cc.coderm.demo.model.User;
import io.github.cascade.cache.api.Cache;
import io.github.cascade.cache.core.unified.UnifiedCacheBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 缓存配置类
 * 演示自动CacheLoader发现机制
 */
@Configuration
public class CacheConfiguration {

    private static final Logger log = LoggerFactory.getLogger(CacheConfiguration.class);

    /**
     * 用户缓存 - 自动发现UserCacheLoader
     */
    @Bean
    public Cache<String, User> userCache() {
        log.info("Creating userCache with auto-discovery enabled");
        
        return UnifiedCacheBuilder.stringCache("userCache", User.class)
            .enableL1(true)
            .maximumSize(1000)
            .expireAfterWrite(Duration.ofMinutes(10))
            .recordStats(true)
            .autoDiscoverLoader(true) // 显式启用自动发现
            .build(); // 这里会自动发现并配置UserCacheLoader
    }

    /**
     * 用户简单缓存 - 命名约定发现
     * 因为缓存名叫"user"，会自动查找userLoader或UserLoader
     */
    @Bean
    public Cache<String, User> userSimpleCache() {
        log.info("Creating user cache with naming convention discovery");
        
        return UnifiedCacheBuilder.stringCache("user", User.class)
            .enableL1(true)
            .maximumSize(500)
            .expireAfterWrite(Duration.ofMinutes(5))
            .build(); // 按命名约定自动发现
    }
}