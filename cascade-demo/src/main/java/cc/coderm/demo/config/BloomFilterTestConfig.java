package cc.coderm.demo.config;

import cc.coderm.cascade.bloom.core.BloomFilterManager;
import cc.coderm.cascade.bloom.core.CascadeBloomFilter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/**
 * 布隆过滤器测试配置
 * 初始化测试用的布隆过滤器
 *
 * @author lionel lionelk@163.com
 * =============================
 * Date: 2026/4/13
 * Time: 14:55
 * =============================
 */
@Slf4j
@Configuration
public class BloomFilterTestConfig implements ApplicationRunner {

    @Lazy
    @Autowired
    private BloomFilterManager bloomFilterManager;

    /**
     * 在应用启动完成后初始化测试用的布隆过滤器
     * 这样可以确保所有自动配置都已经完成
     */
    @Override
    public void run(ApplicationArguments args) throws Exception {
        log.info("=== 开始初始化测试用布隆过滤器 ===");

        try {
            initUserBloomFilter();
            initOrderBloomFilter();
            initProductBloomFilter();

            log.info("=== 布隆过滤器初始化完成 ===");
        } catch (Exception e) {
            log.warn("布隆过滤器初始化失败（可能模块未启用）: {}", e.getMessage());
        }
    }

    /**
     * 初始化用户布隆过滤器
     * 预填充用户ID: 1, 2, 3
     */
    private void initUserBloomFilter() {
        log.info(">>> 初始化用户布隆过滤器: user-bloom");

        CascadeBloomFilter<Object> userFilter = bloomFilterManager.getOrCreate(
                "user-bloom",
                1L,
                0.01
        );

        CascadeBloomFilter<Object> userFilterException = bloomFilterManager.getOrCreate(
                "user-bloom-exception",
                10000L,
                0.01
        );

        CascadeBloomFilter<Object> userFilterWriteback = bloomFilterManager.getOrCreate(
                "user-bloom-writeback",
                10000L,
                0.01
        );

        CascadeBloomFilter<Object> userFilterPerformance = bloomFilterManager.getOrCreate(
                "user-bloom-performance",
                10000L,
                0.01
        );

        Long[] testUserIds = {1L, 2L, 3L};
        for (Long userId : testUserIds) {
            userFilter.add(userId);
            userFilterException.add(userId);
            userFilterWriteback.add(userId);
            userFilterPerformance.add(userId);
            log.info("已添加用户ID到布隆过滤器: {}", userId);
        }

        log.info("用户布隆过滤器初始化完成，预填充用户: 1, 2, 3");
    }

    /**
     * 初始化订单布隆过滤器
     * 预填充一些测试订单
     */
    private void initOrderBloomFilter() {
        log.info(">>> 初始化订单布隆过滤器: order-bloom");

        CascadeBloomFilter<Object> orderFilter = bloomFilterManager.getOrCreate(
                "order-bloom",
                100000L,
                0.01
        );

        String[] testOrders = {
                "1:ORDER001",
                "1:ORDER002",
                "2:ORDER001",
                "3:ORDER001"
        };

        for (String orderKey : testOrders) {
            orderFilter.add(orderKey);
            log.info("已添加订单到布隆过滤器: {}", orderKey);
        }

        log.info("订单布隆过滤器初始化完成");
    }

    /**
     * 初始化商品布隆过滤器
     * 预填充一些测试商品
     */
    private void initProductBloomFilter() {
        log.info(">>> 初始化商品布隆过滤器: product-bloom");

        CascadeBloomFilter<Object> productFilter = bloomFilterManager.getOrCreate(
                "product-bloom",
                100000L,
                0.01
        );

        CascadeBloomFilter<Object> productFilterNoExists = bloomFilterManager.getOrCreate(
                "product-bloom-no-exists",
                100000L,
                0.01
        );

        Long[] testProductIds = {1001L, 1002L, 1003L};
        for (Long productId : testProductIds) {
            productFilter.add(productId);
            productFilterNoExists.add(productId);
            log.info("已添加商品ID到布隆过滤器: {}", productId);
        }

        log.info("商品布隆过滤器初始化完成，预填充商品: 1001, 1002, 1003");
    }
}
