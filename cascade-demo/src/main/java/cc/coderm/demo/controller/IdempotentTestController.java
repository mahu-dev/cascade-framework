package cc.coderm.demo.controller;

import cc.coderm.cascade.idempotent.annotation.Idempotent;
import cc.coderm.cascade.idempotent.model.ConflictStrategy;
import cc.coderm.demo.dto.IdempotentTestDTO;
import cc.coderm.demo.service.TestService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.TimeUnit;

/**
 * 幂等性测试Controller
 * 测试接口幂等的各种场景
 *
 * @author lionel lionelk@163.com
 * =============================
 * Date: 2026/4/13
 * Time: 14:42
 * =============================
 */
@RestController
@RequestMapping("/api/idempotent/test")
public class IdempotentTestController {

    private static final Logger log = LoggerFactory.getLogger(IdempotentTestController.class);

    @Autowired
    private TestService testService;

    /**
     * 测试基础幂等（创建订单）
     * 验证：相同订单号的重复请求，只处理一次，后续返回第一次结果
     */
    @Idempotent(
            key = "'order:create:' + #dto.orderNo",
            ttl = 1,
            ttlUnit = TimeUnit.HOURS,
            scene = "order-create"
    )
    @PostMapping("/basic")
    public String testBasicIdempotent(@RequestBody IdempotentTestDTO dto) {
        log.info("=== 测试基础幂等（创建订单） ===");
        log.info("请求参数: orderNo={}, productId={}, quantity={}, amount={}",
                dto.getOrderNo(), dto.getProductId(), dto.getQuantity(), dto.getAmount());

        String result = testService.testBasicIdempotent(dto);
        log.info("返回结果: {}", result);
        log.info("提示：使用相同orderNo再次请求，将直接返回本次结果，不会重复创建订单");

        return result;
    }

    /**
     * 测试一次性操作（发送短信）
     * 验证：deleteOnSuccess=true，成功后删除幂等记录，允许重新发送
     */
    @Idempotent(
            key = "'sms:send:' + #dto.phone + ':' + #dto.templateId",
            ttl = 5,
            ttlUnit = TimeUnit.MINUTES,
            scene = "sms-send",
            deleteOnSuccess = true
    )
    @PostMapping("/one-time")
    public String testOneTimeOperation(@RequestBody IdempotentTestDTO dto) {
        log.info("=== 测试一次性操作（发送短信） ===");
        log.info("请求参数: phone={}, templateId={}", dto.getPhone(), dto.getTemplateId());

        String result = testService.testOneTimeOperation(dto);
        log.info("返回结果: {}", result);
        log.info("提示：由于deleteOnSuccess=true，成功后可以再次发送");

        return result;
    }

    /**
     * 测试失败可重试操作（支付）
     * 验证：deleteOnFailure=true，失败后删除幂等记录，允许重试
     */
    @Idempotent(
            key = "'payment:process:' + #dto.paymentId",
            ttl = 30,
            ttlUnit = TimeUnit.MINUTES,
            scene = "payment-process",
            deleteOnFailure = true
    )
    @PostMapping("/retryable")
    public String testRetryableOperation(@RequestBody IdempotentTestDTO dto) {
        log.info("=== 测试失败可重试操作（支付） ===");
        log.info("请求参数: paymentId={}, amount={}, simulateFailure={}",
                dto.getPaymentId(), dto.getAmount(), dto.getSimulateFailure());

        String result = testService.testRetryableOperation(dto);
        log.info("返回结果: {}", result);
        log.info("提示：由于deleteOnFailure=true，失败后可以重试");

        return result;
    }

    /**
     * 测试失败不可重试操作（扣款）
     * 验证：deleteOnFailure=false，失败后锁定，TTL内重复请求直接返回失败
     */
    @Idempotent(
            key = "'deduct:money:' + #dto.paymentId",
            ttl = 1,
            ttlUnit = TimeUnit.HOURS,
            scene = "deduct-money",
            deleteOnFailure = false
    )
    @PostMapping("/non-retryable")
    public String testNonRetryableOperation(@RequestBody IdempotentTestDTO dto) {
        log.info("=== 测试失败不可重试操作（扣款） ===");
        log.info("请求参数: paymentId={}, amount={}, simulateFailure={}",
                dto.getPaymentId(), dto.getAmount(), dto.getSimulateFailure());

        if (Boolean.TRUE.equals(dto.getSimulateFailure())) {
            log.error(">>> 扣款失败，paymentId: {}", dto.getPaymentId());
            throw new RuntimeException("扣款失败: " + dto.getPaymentId());
        }

        String result = String.format("扣款成功 - 支付ID: %s, 金额: %s", dto.getPaymentId(), dto.getAmount());
        log.info("返回结果: {}", result);
        log.info("提示：由于deleteOnFailure=false，失败后TTL内不可重试");

        return result;
    }

    /**
     * 测试并发冲突等待策略
     * 验证：相同key的并发请求，后续请求等待前一个请求完成
     */
    @Idempotent(
            key = "'concurrent:wait:' + #dto.paymentId",
            ttl = 10,
            ttlUnit = TimeUnit.MINUTES,
            scene = "concurrent-wait",
            conflictStrategy = ConflictStrategy.WAIT,
            waitTimeoutMs = 10000
    )
    @PostMapping("/wait")
    public String testWaitStrategy(@RequestBody IdempotentTestDTO dto) {
        log.info("=== 测试并发冲突等待策略 ===");
        log.info("请求参数: paymentId={}", dto.getPaymentId());

        String result = testService.testConflictWithFallback(dto);
        log.info("返回结果: {}", result);
        log.info("提示：并发请求会等待前一个请求完成（最多等待10秒）");

        return result;
    }

    /**
     * 测试并发冲突降级策略
     * 验证：相同key的并发请求，后续请求执行fallback方法
     */
    @Idempotent(
            key = "'concurrent:fallback:' + #dto.paymentId",
            ttl = 10,
            ttlUnit = TimeUnit.MINUTES,
            scene = "concurrent-fallback",
            conflictStrategy = ConflictStrategy.FALLBACK,
            fallbackMethod = "payFallback"
    )
    @PostMapping("/fallback")
    public String testFallbackStrategy(@RequestBody IdempotentTestDTO dto) {
        log.info("=== 测试并发冲突降级策略 ===");
        log.info("请求参数: paymentId={}", dto.getPaymentId());

        String result = testService.testConflictWithFallback(dto);
        log.info("返回结果: {}", result);
        log.info("提示：并发请求会直接返回降级结果，不会等待");

        return result;
    }

    /**
     * 测试自定义提示消息
     * 验证：重复请求时返回自定义提示
     */
    @Idempotent(
            key = "'custom:message:' + #dto.orderNo",
            ttl = 30,
            ttlUnit = TimeUnit.MINUTES,
            scene = "custom-message",
            message = "订单正在处理中，请勿重复提交，订单号: "
    )
    @PostMapping("/custom-message")
    public String testCustomMessage(@RequestBody IdempotentTestDTO dto) {
        log.info("=== 测试自定义提示消息 ===");
        log.info("请求参数: orderNo={}", dto.getOrderNo());

        String result = testService.testBasicIdempotent(dto);
        log.info("返回结果: {}", result);
        log.info("提示：重复请求时会返回自定义消息");

        return result;
    }

    /**
     * 测试长TTL幂等
     * 验证：幂等记录保存24小时，适用于重要业务
     */
    @Idempotent(
            key = "'long:ttl:' + #dto.orderNo",
            ttl = 24,
            ttlUnit = TimeUnit.HOURS,
            scene = "important-order"
    )
    @PostMapping("/long-ttl")
    public String testLongTTL(@RequestBody IdempotentTestDTO dto) {
        log.info("=== 测试长TTL幂等 ===");
        log.info("请求参数: orderNo={}", dto.getOrderNo());

        String result = testService.testBasicIdempotent(dto);
        log.info("返回结果: {}", result);
        log.info("提示：幂等记录保存24小时");

        return result;
    }

    /**
     * 测试短TTL幂等
     * 验证：幂等记录保存1分钟，适用于高频操作
     */
    @Idempotent(
            key = "'short:ttl:' + #dto.phone + ':' + #dto.templateId",
            ttl = 1,
            ttlUnit = TimeUnit.MINUTES,
            scene = "high-frequency"
    )
    @PostMapping("/short-ttl")
    public String testShortTTL(@RequestBody IdempotentTestDTO dto) {
        log.info("=== 测试短TTL幂等 ===");
        log.info("请求参数: phone={}, templateId={}", dto.getPhone(), dto.getTemplateId());

        String result = testService.testOneTimeOperation(dto);
        log.info("返回结果: {}", result);
        log.info("提示：幂等记录仅保存1分钟");

        return result;
    }
}
