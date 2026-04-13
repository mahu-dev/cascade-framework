package cc.coderm.demo.service;

import cc.coderm.cascade.idempotent.model.IdempotentRecord;
import cc.coderm.demo.dto.CacheTestDTO;
import cc.coderm.demo.dto.IdempotentTestDTO;
import cc.coderm.demo.dto.LockTestDTO;
import io.github.cascade.cache.v2.api.annotations.CacheEvict;
import io.github.cascade.cache.v2.api.annotations.CachePut;
import io.github.cascade.cache.v2.api.annotations.Cacheable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 测试服务类
 * 提供各种功能测试的业务逻辑
 *
 * @author lionel lionelk@163.com
 * =============================
 * Date: 2026/4/13
 * Time: 14:35
 * =============================
 */
@Service
public class TestService {

    private static final Logger log = LoggerFactory.getLogger(TestService.class);

    private final Map<String, String> dataStore = new ConcurrentHashMap<>();

    private final Map<String, BigDecimal> orderStore = new ConcurrentHashMap<>();

    private final Map<String, Boolean> paymentStore = new ConcurrentHashMap<>();

    private final Map<Long, String> userStore = new ConcurrentHashMap<>();

    private final Map<String, Integer> stockStore = new ConcurrentHashMap<>();

    public TestService() {
        initData();
    }

    private void initData() {
        userStore.put(1L, "张三");
        userStore.put(2L, "李四");
        userStore.put(3L, "王五");
        stockStore.put("SKU:1001", 1000);
        stockStore.put("SKU:1002", 500);
        stockStore.put("SKU:1003", 200);
    }

    // ==================== 缓存测试方法 ====================

    /**
     * 测试基础缓存
     */
    @Cacheable(value = "test:cache", key = "#dto.key")
    public String testBasicCache(CacheTestDTO dto) {
        log.info(">>> 执行基础缓存测试方法，key: {}", dto.getKey());
        simulateDatabaseOperation();
        String value = "Cached:" + dto.getValue();
        dataStore.put(dto.getKey(), value);
        return value;
    }

    /**
     * 测试缓存失效
     */
    @CacheEvict(value = "test:cache", key = "#dto.key")
    public void testCacheEvict(CacheTestDTO dto) {
        log.info(">>> 清除缓存，key: {}", dto.getKey());
        dataStore.remove(dto.getKey());
    }

    /**
     * 测试缓存更新
     */
    @CachePut(value = "test:cache", key = "#dto.key")
    public String testCachePut(CacheTestDTO dto) {
        log.info(">>> 更新缓存，key: {}, newValue: {}", dto.getKey(), dto.getValue());
        String newValue = "Updated:" + dto.getValue();
        dataStore.put(dto.getKey(), newValue);
        return newValue;
    }

    /**
     * 测试条件缓存
     */
    @Cacheable(value = "test:cache:condition", key = "#dto.key", condition = "#dto.enabled")
    public String testConditionCache(CacheTestDTO dto) {
        log.info(">>> 执行条件缓存测试，enabled: {}", dto.getEnabled());
        simulateDatabaseOperation();
        return "Conditional:" + dto.getValue();
    }

    /**
     * 测试带TTL的缓存
     */
    @Cacheable(value = "test:cache:ttl", key = "#dto.key", ttl = 10)
    public String testCacheWithTTL(CacheTestDTO dto) {
        log.info(">>> 执行带TTL的缓存测试，key: {}", dto.getKey());
        simulateDatabaseOperation();
        return "TTL:" + dto.getValue() + ":" + System.currentTimeMillis();
    }

    // ==================== 幂等测试方法 ====================

    /**
     * 测试基础幂等（创建订单）
     */
    public String testBasicIdempotent(IdempotentTestDTO dto) {
        log.info(">>> 创建订单，orderNo: {}", dto.getOrderNo());
        simulateBusinessOperation(500);

        String orderInfo = String.format("订单创建成功 - 订单号: %s, 商品: %d, 数量: %d, 金额: %s",
                dto.getOrderNo(), dto.getProductId(), dto.getQuantity(), dto.getAmount());

        orderStore.put(dto.getOrderNo(), dto.getAmount());
        return orderInfo;
    }

    /**
     * 测试一次性操作（发送短信）
     */
    public String testOneTimeOperation(IdempotentTestDTO dto) {
        log.info(">>> 发送短信，phone: {}, template: {}", dto.getPhone(), dto.getTemplateId());
        simulateBusinessOperation(200);

        String result = String.format("短信发送成功 - 手机: %s, 模板: %s, 时间: %d",
                dto.getPhone(), dto.getTemplateId(), System.currentTimeMillis());
        return result;
    }

    /**
     * 测试失败可重试操作（支付）
     */
    public String testRetryableOperation(IdempotentTestDTO dto) {
        log.info(">>> 处理支付，paymentId: {}, simulateFailure: {}", dto.getPaymentId(), dto.getSimulateFailure());

        if (Boolean.TRUE.equals(dto.getSimulateFailure())) {
            log.warn(">>> 支付失败，paymentId: {}", dto.getPaymentId());
            throw new RuntimeException("支付处理失败: " + dto.getPaymentId());
        }

        simulateBusinessOperation(800);
        String result = String.format("支付成功 - 支付ID: %s, 金额: %s, 时间: %d",
                dto.getPaymentId(), dto.getAmount(), System.currentTimeMillis());

        paymentStore.put(dto.getPaymentId(), true);
        return result;
    }

    /**
     * 测试并发冲突时的降级方法
     */
    public String testConflictWithFallback(IdempotentTestDTO dto) {
        log.info(">>> 处理高并发请求，paymentId: {}", dto.getPaymentId());
        simulateBusinessOperation(1000);

        String result = String.format("高并发处理完成 - 支付ID: %s, 时间: %d",
                dto.getPaymentId(), System.currentTimeMillis());

        paymentStore.put(dto.getPaymentId(), true);
        return result;
    }

    /**
     * 幂等降级方法
     */
    public String payFallback(IdempotentTestDTO dto, IdempotentRecord record) {
        log.info(">>> 执行降级方法，paymentId: {}, record: {}", dto.getPaymentId(), record);
        return String.format("请求处理中，请稍后查询 - 支付ID: %s, 状态: %s",
                dto.getPaymentId(), record.getState());
    }

    // ==================== 分布式锁测试方法 ====================

    /**
     * 测试可重入锁（库存扣减）
     */
    public String testReentrantLock(LockTestDTO dto) {
        log.info(">>> 扣减库存，skuId: {}, quantity: {}", dto.getSkuId(), dto.getQuantity());
        long executionTime = dto.getExecutionTime() != null ? dto.getExecutionTime() : 500L;
        simulateBusinessOperation(executionTime);

        String stockKey = "SKU:" + dto.getSkuId();
        int currentStock = stockStore.getOrDefault(stockKey, 0);
        int quantity = dto.getQuantity();
        int newStock = currentStock - quantity;
        stockStore.put(stockKey, newStock);

        log.info(">>> 库存扣减完成 - SKU: {}, 原库存: {}, 扣减: {}, 新库存: {}",
                dto.getSkuId(), currentStock, quantity, newStock);

        return String.format("库存扣减成功 - SKU: %s, 原库存: %d, 扣减: %d, 新库存: %d",
                dto.getSkuId(), currentStock, quantity, newStock);
    }

    /**
     * 测试公平锁（订单处理）
     */
    public String testFairLock(LockTestDTO dto) {
        log.info(">>> 处理订单，orderId: {}", dto.getOrderId());
        Long executionTime = dto.getExecutionTime() != null ? dto.getExecutionTime() : 800L;
        simulateBusinessOperation(executionTime);

        String orderInfo = String.format("订单处理完成 - 订单ID: %d, 用户: %d, 时间: %d",
                dto.getOrderId(), dto.getUserId(), System.currentTimeMillis());
        return orderInfo;
    }

    /**
     * 测试读锁
     */
    public String testReadLock(LockTestDTO dto) {
        log.info(">>> 读取库存信息，skuId: {}", dto.getSkuId());
        Long executionTime = dto.getExecutionTime() != null ? dto.getExecutionTime() : 300L;
        simulateBusinessOperation(executionTime);

        String stockKey = "SKU:" + dto.getSkuId();
        Integer stock = stockStore.getOrDefault(stockKey, 0);

        return String.format("读取库存 - SKU: %s, 当前库存: %d", dto.getSkuId(), stock);
    }

    /**
     * 测试写锁
     */
    public String testWriteLock(LockTestDTO dto) {
        log.info(">>> 更新库存，skuId: {}, quantity: {}", dto.getSkuId(), dto.getQuantity());
        Long executionTime = dto.getExecutionTime() != null ? dto.getExecutionTime() : 600L;
        simulateBusinessOperation(executionTime);

        String stockKey = "SKU:" + dto.getSkuId();
        stockStore.put(stockKey, dto.getQuantity());

        return String.format("更新库存成功 - SKU: %s, 新库存: %d", dto.getSkuId(), dto.getQuantity());
    }

    /**
     * 测试联锁（订单+库存）
     */
    public String testMultiLock(LockTestDTO dto) {
        log.info(">>> 下单并扣库存，orderId: {}, skuId: {}", dto.getOrderId(), dto.getSkuId());
        Long executionTime = dto.getExecutionTime() != null ? dto.getExecutionTime() : 1000L;
        simulateBusinessOperation(executionTime);

        String stockKey = "SKU:" + dto.getSkuId();
        Integer currentStock = stockStore.getOrDefault(stockKey, 0);
        stockStore.put(stockKey, currentStock - dto.getQuantity());

        String result = String.format("下单成功 - 订单ID: %d, SKU: %s, 扣减: %d, 剩余: %d",
                dto.getOrderId(), dto.getSkuId(), dto.getQuantity(), currentStock - dto.getQuantity());

        return result;
    }

    /**
     * 测试获取锁失败时的快速失败策略
     */
    public String testFailFast(LockTestDTO dto) {
        log.info(">>> 尝试获取锁处理业务，resourceId: {}", dto.getResourceId());
        simulateBusinessOperation(500);

        return String.format("业务处理成功 - 资源: %s, 时间: %d",
                dto.getResourceId(), System.currentTimeMillis());
    }

    // ==================== 布隆过滤器测试方法 ====================

    /**
     * 测试基础布隆过滤（查询用户）
     */
    public String testBasicBloomFilter(Long userId) {
        log.info(">>> 查询用户，userId: {}", userId);
        simulateDatabaseOperation();

        String username = userStore.get(userId);
        if (username == null) {
            log.warn(">>> 用户不存在，userId: {}", userId);
            return null;
        }

        return String.format("用户信息 - ID: %d, 姓名: %s", userId, username);
    }

    /**
     * 测试复合key布隆过滤（查询订单）
     */
    public String testCompositeBloomFilter(Long userId, String orderId) {
        log.info(">>> 查询订单，userId: {}, orderId: {}", userId, orderId);
        simulateDatabaseOperation();

        String orderInfo = String.format("订单信息 - 用户: %d, 订单: %s, 状态: 已支付", userId, orderId);
        return orderInfo;
    }

    /**
     * 测试布隆过滤器初始化（添加用户）
     */
    public String initBloomFilterData(Long userId, String username) {
        log.info(">>> 初始化布隆过滤器数据，userId: {}, username: {}", userId, username);
        userStore.put(userId, username);
        return String.format("添加用户成功 - ID: %d, 姓名: %s", userId, username);
    }

    /**
     * 测试查询商品
     */
    public String testProductBloomFilter(Long productId) {
        log.info(">>> 查询商品，productId: {}", productId);
        simulateDatabaseOperation();

        String productInfo = String.format("商品信息 - ID: %d, 名称: 商品%d, 库存: %d",
                productId, productId, ThreadLocalRandom.current().nextInt(1000));

        return productInfo;
    }

    // ==================== 辅助方法 ====================

    private void simulateDatabaseOperation() {
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void simulateBusinessOperation(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
