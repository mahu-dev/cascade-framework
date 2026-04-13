package cc.coderm.demo.controller;

import cc.coderm.demo.dto.LockTestDTO;
import cc.coderm.demo.service.TestService;
import io.github.cascade.lock.annotation.DistributedLock;
import io.github.cascade.lock.enums.LockStrategy;
import io.github.cascade.lock.enums.LockType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.TimeUnit;

/**
 * 分布式锁测试Controller
 * 测试各种分布式锁的场景
 *
 * @author lionel lionelk@163.com
 * =============================
 * Date: 2026/4/13
 * Time: 14:45
 * =============================
 */

@RestController
@RequestMapping("/api/lock/test")
public class LockTestController {

    private static final Logger log = LoggerFactory.getLogger(LockTestController.class);

    @Autowired
    private TestService testService;

    /**
     * 测试可重入锁（库存扣减）
     * 验证：同一线程可多次获取同一把锁
     */
    @DistributedLock(
            keys = "'stock:deduct:' + #dto.skuId",
            lockType = LockType.REENTRANT,
            waitTime = 5000,
            leaseTime = -1,
            timeUnit = TimeUnit.MILLISECONDS,
            strategy = LockStrategy.FAIL_FAST,
            message = "库存扣减操作繁忙，请稍后重试"
    )
    @PostMapping("/reentrant")
    public String testReentrantLock(@RequestBody LockTestDTO dto) {
        log.info("=== 测试可重入锁（库存扣减） ===");
        log.info("请求参数: skuId={}, quantity={}", dto.getSkuId(), dto.getQuantity());

        String result = testService.testReentrantLock(dto);
        log.info("返回结果: {}", result);
        log.info("提示：同一订单号可以多次进入（可重入），不同订单号需要排队等待");

        return result;
    }

    /**
     * 测试公平锁（订单处理）
     * 验证：按照请求顺序获取锁，先到先得
     */
    @DistributedLock(
            keys = "'order:process:' + #dto.orderId",
            lockType = LockType.FAIR,
            waitTime = 10000,
            leaseTime = 30,
            timeUnit = TimeUnit.SECONDS,
            strategy = LockStrategy.FAIL_FAST,
            message = "订单处理繁忙，请稍后重试"
    )
    @PostMapping("/fair")
    public String testFairLock(@RequestBody LockTestDTO dto) {
        log.info("=== 测试公平锁（订单处理） ===");
        log.info("请求参数: orderId={}, userId={}", dto.getOrderId(), dto.getUserId());

        String result = testService.testFairLock(dto);
        log.info("返回结果: {}", result);
        log.info("提示：公平锁保证先到先得，按请求顺序处理");

        return result;
    }

    /**
     * 测试读锁（读取库存）
     * 验证：多个读锁可以共存，与写锁互斥
     */
    @DistributedLock(
            keys = "'stock:read:' + #dto.skuId",
            lockType = LockType.READ,
            waitTime = 5000,
            leaseTime = 10,
            timeUnit = TimeUnit.SECONDS,
            strategy = LockStrategy.FAIL_FAST,
            message = "读取库存信息繁忙，请稍后重试"
    )
    @PostMapping("/read")
    public String testReadLock(@RequestBody LockTestDTO dto) {
        log.info("=== 测试读锁（读取库存） ===");
        log.info("请求参数: skuId={}", dto.getSkuId());

        String result = testService.testReadLock(dto);
        log.info("返回结果: {}", result);
        log.info("提示：多个读请求可以并发执行，提高读取性能");

        return result;
    }

    /**
     * 测试写锁（更新库存）
     * 验证：写锁与读锁、写锁都互斥
     */
    @DistributedLock(
            keys = "'stock:write:' + #dto.skuId",
            lockType = LockType.WRITE,
            waitTime = 5000,
            leaseTime = 10,
            timeUnit = TimeUnit.SECONDS,
            strategy = LockStrategy.FAIL_FAST,
            message = "更新库存操作繁忙，请稍后重试"
    )
    @PostMapping("/write")
    public String testWriteLock(@RequestBody LockTestDTO dto) {
        log.info("=== 测试写锁（更新库存） ===");
        log.info("请求参数: skuId={}, quantity={}", dto.getSkuId(), dto.getQuantity());

        String result = testService.testWriteLock(dto);
        log.info("返回结果: {}", result);
        log.info("提示：写锁独占，与其他读锁和写锁都互斥");

        return result;
    }

    /**
     * 测试联锁（同时锁定订单和库存）
     * 验证：多个资源同时加锁，要么全部成功，要么全部失败
     */
    @DistributedLock(
            keys = {
                    "'order:create:' + #dto.orderId",
                    "'stock:reserve:' + #dto.skuId"
            },
            lockType = LockType.MULTI,
            waitTime = 5000,
            leaseTime = 30,
            timeUnit = TimeUnit.SECONDS,
            strategy = LockStrategy.FAIL_FAST,
            message = "下单失败，订单或库存被占用"
    )
    @PostMapping("/multi")
    public String testMultiLock(@RequestBody LockTestDTO dto) {
        log.info("=== 测试联锁（同时锁定订单和库存） ===");
        log.info("请求参数: orderId={}, skuId={}, quantity={}",
                dto.getOrderId(), dto.getSkuId(), dto.getQuantity());

        String result = testService.testMultiLock(dto);
        log.info("返回结果: {}", result);
        log.info("提示：联锁同时锁定多个资源，保证原子性");

        return result;
    }

    /**
     * 测试红锁（高可用场景）
     * 验证：在多个Redis节点上加锁，提高可靠性
     */
    @DistributedLock(
            keys = {
                    "'resource:node1:' + #dto.resourceId",
                    "'resource:node2:' + #dto.resourceId",
                    "'resource:node3:' + #dto.resourceId"
            },
            lockType = LockType.RED,
            waitTime = 3000,
            leaseTime = 10,
            timeUnit = TimeUnit.SECONDS,
            strategy = LockStrategy.FAIL_FAST,
            message = "获取资源失败，请稍后重试"
    )
    @PostMapping("/redlock")
    public String testRedLock(@RequestBody LockTestDTO dto) {
        log.info("=== 测试红锁（高可用场景） ===");
        log.info("请求参数: resourceId={}", dto.getResourceId());

        String result = testService.testFailFast(dto);
        log.info("返回结果: {}", result);
        log.info("提示：红锁在多个节点加锁，提高分布式环境下的可靠性");

        return result;
    }

    /**
     * 测试快速失败策略
     * 验证：获取锁失败立即返回，不等待
     */
    @DistributedLock(
            keys = "'fail:fast:' + #dto.resourceId",
            lockType = LockType.REENTRANT,
            waitTime = 0,
            leaseTime = 10,
            timeUnit = TimeUnit.SECONDS,
            strategy = LockStrategy.FAIL_FAST,
            message = "资源被占用，已快速返回"
    )
    @PostMapping("/fail-fast")
    public String testFailFast(@RequestBody LockTestDTO dto) {
        log.info("=== 测试快速失败策略 ===");
        log.info("请求参数: resourceId={}", dto.getResourceId());

        String result = testService.testFailFast(dto);
        log.info("返回结果: {}", result);
        log.info("提示：waitTime=0，获取锁失败立即返回，不等待");

        return result;
    }

    /**
     * 测试看门狗自动续期
     * 验证：leaseTime=-1时，看门狗自动续期，防止业务未执行完锁就过期
     */
    @DistributedLock(
            keys = "'watchdog:' + #dto.resourceId",
            lockType = LockType.REENTRANT,
            waitTime = 5000,
            leaseTime = -1,
            timeUnit = TimeUnit.MILLISECONDS,
            strategy = LockStrategy.FAIL_FAST,
            message = "获取锁失败，请稍后重试"
    )
    @PostMapping("/watchdog")
    public String testWatchdog(@RequestBody LockTestDTO dto) {
        log.info("=== 测试看门狗自动续期 ===");
        log.info("请求参数: resourceId={}", dto.getResourceId());

        if (dto.getExecutionTime() == null) {
            dto.setExecutionTime(30000L);
        }

        String result = testService.testFailFast(dto);
        log.info("返回结果: {}", result);
        log.info("提示：leaseTime=-1，看门狗会自动续期，适合执行时间不确定的业务");

        return result;
    }

    /**
     * 测试自定义锁等待时间
     * 验证：可以自定义等待锁的最长时间
     */
    @DistributedLock(
            keys = "'custom:wait:' + #dto.resourceId",
            lockType = LockType.REENTRANT,
            waitTime = 15000,
            leaseTime = 10,
            timeUnit = TimeUnit.SECONDS,
            strategy = LockStrategy.FAIL_FAST,
            message = "等待超时，获取锁失败"
    )
    @PostMapping("/custom-wait")
    public String testCustomWaitTime(@RequestBody LockTestDTO dto) {
        log.info("=== 测试自定义锁等待时间 ===");
        log.info("请求参数: resourceId={}", dto.getResourceId());

        String result = testService.testFailFast(dto);
        log.info("返回结果: {}", result);
        log.info("提示：waitTime=15秒，最多等待15秒获取锁");

        return result;
    }

    /**
     * 测试锁持有时间
     * 验证：可以设置锁的自动释放时间
     */
    @DistributedLock(
            keys = "'custom:lease:' + #dto.resourceId",
            lockType = LockType.REENTRANT,
            waitTime = 5000,
            leaseTime = 5,
            timeUnit = TimeUnit.SECONDS,
            strategy = LockStrategy.FAIL_FAST,
            message = "获取锁失败，请稍后重试"
    )
    @PostMapping("/custom-lease")
    public String testCustomLeaseTime(@RequestBody LockTestDTO dto) {
        log.info("=== 测试锁持有时间 ===");
        log.info("请求参数: resourceId={}", dto.getResourceId());

        String result = testService.testFailFast(dto);
        log.info("返回结果: {}", result);
        log.info("提示：leaseTime=5秒，5秒后锁自动释放");

        return result;
    }
}
