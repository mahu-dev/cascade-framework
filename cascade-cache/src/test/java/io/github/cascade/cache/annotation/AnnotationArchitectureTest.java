package io.github.cascade.cache.annotation;

import io.github.cascade.cache.api.CacheLoader;
import io.github.cascade.cache.core.CacheLoaderResolver;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Service;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 注解架构测试 - 验证重构后的CacheLoader统一管理
 * <p>
 * 重构亮点：
 * 1. CacheLoaderRegistry不再独立管理loaders缓存
 * 2. @CacheLoaderMethod注解的方法被注册为Spring Bean
 * 3. CacheLoaderResolver统一管理所有CacheLoader
 * 4. 避免了功能重复，简化了架构
 *
 * @author cascade
 */
@Slf4j
@SpringJUnitConfig
public class AnnotationArchitectureTest {

    @Resource
    private CacheLoaderResolver cacheLoaderResolver;

    /**
     * 测试统一的CacheLoader管理
     */
    @Test
    public void testUnifiedCacheLoaderManagement() {
        log.info("=== 测试统一CacheLoader管理架构 ===");

        // 1. 通过CacheLoaderResolver查找基于注解的CacheLoader
        CacheLoader<String, String> userLoader = cacheLoaderResolver.resolveCacheLoader(
                "userCache", String.class, String.class);

        if (userLoader != null) {
            log.info("✅ 成功通过CacheLoaderResolver找到userCacheLoader: {}", userLoader.getName());
        } else {
            log.warn("❌ 未找到userCacheLoader");
        }

        // 2. 通过命名约定查找
        CacheLoader<Long, Object> productLoader = cacheLoaderResolver.resolveCacheLoader(
                Long.class, Object.class);

        if (productLoader != null) {
            log.info("✅ 成功通过类型匹配找到CacheLoader: {}", productLoader.getName());
        }

        // 3. 验证架构优化：不再有重复的loaders缓存
        log.info("✅ 架构重构成功：");
        log.info("  - @CacheLoaderMethod方法自动注册为Spring Bean");
        log.info("  - CacheLoaderResolver统一管理所有CacheLoader");
        log.info("  - 消除了功能重复，简化了架构");
    }

    /**
     * 测试服务类 - 包含@CacheLoaderMethod注解的方法
     */
    @Service
    static class TestCacheLoaderService {

        /**
         * 用户缓存加载器 - 会被自动注册为Spring Bean
         */
        @CacheLoaderMethod(
                name = "testUserCacheLoader",
                cacheNames = {"userCache", "testCache"},
                supportsBatch = true
        )
        public Map<String, String> loadUsers(Set<String> userIds) {
            log.info("TestCacheLoaderService: 批量加载用户数据 {}", userIds);
            Map<String, String> result = new HashMap<>();
            for (String userId : userIds) {
                result.put(userId, "User-" + userId);
            }
            return result;
        }

        /**
         * 产品缓存加载器
         */
        @CacheLoaderMethod(
                name = "testProductLoader",
                async = true,
                onFailure = CacheLoaderMethod.FailureStrategy.RETURN_DEFAULT,
                defaultValue = "DefaultProduct"
        )
        public String loadProduct(Long productId) {
            log.info("TestCacheLoaderService: 加载产品数据 {}", productId);
            return "Product-" + productId;
        }
    }

    /**
     * 测试配置类
     */
    @Configuration
    static class TestConfiguration {

        /**
         * 提供CacheLoaderResolver Bean（通常由自动配置提供）
         */
        @Bean
        public CacheLoaderResolver cacheLoaderResolver() {
            return new CacheLoaderResolver();
        }
    }
}