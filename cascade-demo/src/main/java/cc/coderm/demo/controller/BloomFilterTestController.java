package cc.coderm.demo.controller;

import cc.coderm.cascade.bloom.annotation.BloomFilter;
import cc.coderm.demo.dto.BloomFilterTestDTO;
import cc.coderm.demo.service.TestService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 布隆过滤器测试Controller
 * 测试布隆过滤器的各种场景
 *
 * @author lionel lionelk@163.com
 * =============================
 * Date: 2026/4/13
 * Time: 14:48
 * =============================
 */

@RestController
@RequestMapping("/api/bloom/test")
public class BloomFilterTestController {

    private static final Logger log = LoggerFactory.getLogger(BloomFilterTestController.class);

    @Autowired
    private TestService testService;

    /**
     * 测试基础布隆过滤（查询用户）
     * 验证：用户不存在时返回null，存在时返回用户信息
     */
    @BloomFilter(
            name = "user-bloom",
            key = "#userId",  // 使用智能解析：直接访问DTO属性
            fallbackValue = "null",
            throwOnAbsent = false,
            writeBackOnSuccess = true
    )
    @PostMapping("/basic")
    public String testBasicBloomFilter(@RequestBody BloomFilterTestDTO dto) {
        log.info("=== 测试基础布隆过滤（查询用户） ===");
        log.info("请求参数: userId={}", dto.getUserId());

        String result = testService.testBasicBloomFilter(dto.getUserId());
        if (result == null) {
            log.info("返回结果: 用户不存在（被布隆过滤器拦截）");
            return "用户不存在 - userId: " + dto.getUserId();
        }

        log.info("返回结果: {}", result);
        return result;
    }

    /**
     * 测试不存在时抛异常
     * 验证：用户不存在时直接抛异常，不执行方法
     */
    @BloomFilter(
            name = "user-bloom-exception",
            key = "#userId",
            throwOnAbsent = true,
            message = "用户不存在，请检查用户ID"
    )
    @PostMapping("/exception")
    public String testExceptionOnAbsent(@RequestBody BloomFilterTestDTO dto) {
        log.info("=== 测试不存在时抛异常 ===");
        log.info("请求参数: userId={}", dto.getUserId());

        String result = testService.testBasicBloomFilter(dto.getUserId());
        log.info("返回结果: {}", result);

        return result;
    }

    /**
     * 测试复合key布隆过滤（查询订单）
     * 验证：使用SpEL表达式组合多个参数作为key
     */
    @BloomFilter(
            name = "order-bloom",
            key = "#dto.userId + ':' + #dto.orderId",
            writeBackOnSuccess = true
    )
    @PostMapping("/composite")
    public String testCompositeKey(@RequestBody BloomFilterTestDTO dto) {
        log.info("=== 测试复合key布隆过滤（查询订单） ===");
        log.info("请求参数: userId={}, orderId={}", dto.getUserId(), dto.getOrderId());

        String result = testService.testCompositeBloomFilter(dto.getUserId(), dto.getOrderId());
        log.info("返回结果: {}", result);
        log.info("提示：复合key为 userId:orderId");

        return result;
    }

    /**
     * 测试自动回填功能
     * 验证：查询成功后自动将key回填到布隆过滤器
     */
    @BloomFilter(
            name = "user-bloom-writeback",
            key = "#userId",
            writeBackOnSuccess = true,
            autoRefreshOnAbsent = false
    )
    @PostMapping("/writeback")
    public String testWriteBack(@RequestBody BloomFilterTestDTO dto) {
        log.info("=== 测试自动回填功能 ===");
        log.info("请求参数: userId={}, username={}", dto.getUserId(), dto.getUsername());

        if (dto.getUsername() != null && !dto.getUsername().isEmpty()) {
            String result = testService.initBloomFilterData(dto.getUserId(), dto.getUsername());
            log.info("初始化数据: {}", result);
            return result;
        }

        String result = testService.testBasicBloomFilter(dto.getUserId());
        if (result == null) {
            log.info("返回结果: 用户不存在");
            return "用户不存在 - userId: " + dto.getUserId();
        }

        log.info("返回结果: {}", result);
        log.info("提示：查询成功后key已自动回填到布隆过滤器");

        return result;
    }

    /**
     * 测试自动刷新功能
     * 验证：布隆过滤器判断miss时，执行真实探测并自动回填
     */
    @BloomFilter(
            name = "product-bloom",
            key = "#productId",
            writeBackOnSuccess = true,
            autoRefreshOnAbsent = true
    )
    @PostMapping("/refresh")
    public String testAutoRefresh(@RequestBody BloomFilterTestDTO dto) {
        log.info("=== 测试自动刷新功能 ===");
        log.info("请求参数: productId={}", dto.getProductId());

        String result = testService.testProductBloomFilter(dto.getProductId());
        log.info("返回结果: {}", result);
        log.info("提示：autoRefreshOnAbsent=true，布隆过滤器miss时会执行真实探测");

        return result;
    }

    /**
     * 测试商品查询（不存在的商品）
     * 验证：商品不存在时被布隆过滤器拦截
     */
    @BloomFilter(
            name = "product-bloom-no-exists",
            key = "#productId",
            fallbackValue = "null",
            throwOnAbsent = false,
            writeBackOnSuccess = true
    )
    @PostMapping("/product-not-exists")
    public String testProductNotExists(@RequestBody BloomFilterTestDTO dto) {
        log.info("=== 测试商品查询（不存在的商品） ===");
        log.info("请求参数: productId={}", dto.getProductId());

        String result = testService.testProductBloomFilter(dto.getProductId());
        if (result == null) {
            log.info("返回结果: 商品不存在（被布隆过滤器拦截）");
            return "商品不存在 - productId: " + dto.getProductId();
        }

        log.info("返回结果: {}", result);
        return result;
    }

    /**
     * 初始化布隆过滤器数据
     * 验证：预先添加数据到布隆过滤器
     */
    @PostMapping("/init")
    public String initBloomData(@RequestBody BloomFilterTestDTO dto) {
        log.info("=== 初始化布隆过滤器数据 ===");
        log.info("请求参数: userId={}, username={}", dto.getUserId(), dto.getUsername());

        String result = testService.initBloomFilterData(dto.getUserId(), dto.getUsername());
        log.info("初始化结果: {}", result);
        log.info("提示：数据已添加到存储，下次查询会自动回填到布隆过滤器");

        return result;
    }

    /**
     * 测试布隆过滤器性能
     * 验证：大量查询场景下的性能表现
     */
    @BloomFilter(
            name = "user-bloom-performance",
            key = "#userId",
            writeBackOnSuccess = false
    )
    @PostMapping("/performance")
    public String testPerformance(@RequestBody BloomFilterTestDTO dto) {
        log.info("=== 测试布隆过滤器性能 ===");
        log.info("请求参数: userId={}", dto.getUserId());

        long startTime = System.currentTimeMillis();
        String result = testService.testBasicBloomFilter(dto.getUserId());
        long endTime = System.currentTimeMillis();

        long duration = endTime - startTime;
        log.info("查询耗时: {}ms", duration);

        if (result == null) {
            return String.format("用户不存在 - userId: %d, 耗时: %dms（被布隆过滤器快速拦截）",
                    dto.getUserId(), duration);
        }

        return result + String.format(", 耗时: %dms", duration);
    }

    /**
     * 测试多种数据类型作为key
     * 验证：支持Long、String等多种数据类型
     */
    @BloomFilter(
            name = "mixed-type-bloom",
            key = "#dto.orderId",
            writeBackOnSuccess = true
    )
    @PostMapping("/mixed-type")
    public String testMixedType(@RequestBody BloomFilterTestDTO dto) {
        log.info("=== 测试多种数据类型作为key ===");
        log.info("请求参数: orderId={}", dto.getOrderId());

        String result = testService.testCompositeBloomFilter(dto.getUserId(), dto.getOrderId());
        log.info("返回结果: {}", result);
        log.info("提示：orderId为String类型，使用SpEL表达式 #dto.orderId 作为key");

        return result;
    }
}
