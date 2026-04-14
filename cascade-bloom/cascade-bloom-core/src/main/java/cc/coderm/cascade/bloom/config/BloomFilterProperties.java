package cc.coderm.cascade.bloom.config;

import lombok.Data;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.List;

/**
 * 布隆过滤器配置属性
 *
 * <pre>
 * cascade:
 *   bloom:
 *     enabled: true
 *     key-prefix: "cascade:bloom:"
 *     default-expected-insertions: 1000000
 *     default-false-probability: 0.03
 *     max-cache-size: 1000
 *     filters:
 *       - name: user-bloom
 *         expected-insertions: 5000000
 *         false-probability: 0.01
 * </pre>
 *
 * @author lionel lionelk@163.com
 * =============================
 * Date: 2025-01-10
 * Time: 16:00:00
 * =============================
 */
@Data
@Validated
@ConfigurationProperties(prefix = "cascade.bloom")
public class BloomFilterProperties implements InitializingBean {

    /**
     * 是否启用布隆过滤器模块，默认 true
     */
    private boolean enabled = true;

    /**
     * Redis key 前缀，默认 "cascade:bloom:"
     */
    private String keyPrefix = "cascade:bloom:";

    /**
     * 全局默认预期容量（元素数量），默认 100 万
     */
    private long defaultExpectedInsertions = 1_000_000L;

    /**
     * 全局默认误判率，默认 3%（0.03）
     */
    private double defaultFalseProbability = 0.03;

    /**
     * SpEL 表达式解析严格模式
     * <p>
     * true：解析失败直接抛出异常（推荐生产环境）
     * false：解析失败降级到 args[0]（兼容模式）
     */
    private boolean strictSpEL = true;

    /**
     * 是否等待布隆过滤器初始化完成
     * <p>
     * true：应用启动后等待所有初始化器完成，确保数据一致性（推荐生产环境）
     * false：异步执行初始化，应用快速启动但可能导致缓存穿透
     */
    private boolean waitForInitialization = true;

    /**
     * 本地缓存最大容量（过滤器数量）
     * <p>
     * 当缓存中过滤器数量超过此值时，使用 LRU 策略自动淘汰最久未使用的过滤器。
     * 默认 1000，适用于大多数场景。如果需要缓存更多过滤器，可以适当增加此值。
     * <p>
     * 注意：此限制只影响本地缓存，不影响 Redis 中的数据。
     * 被淘汰的过滤器再次访问时会重新创建并加入缓存。
     */
    private int maxCacheSize = 1000;

    /**
     * SpEL 表达式缓存最大容量
     * <p>
     * 当缓存中 SpEL 表达式数量超过此值时，使用 LRU 策略自动淘汰最久未使用的表达式。
     * 默认 500，适用于大多数场景。如果使用了大量不同的 SpEL 表达式，可以适当增加此值。
     * <p>
     * 注意：此限制只影响表达式编译缓存，不影响功能正确性。
     * 被淘汰的表达式再次使用时会重新解析并加入缓存。
     */
    private int maxExpressionCacheSize = 500;

    /**
     * 预定义过滤器列表，在 Spring 启动时自动创建
     */
    private List<BloomFilterDefinition> filters = new ArrayList<>();

    /**
     * 配置验证
     */
    @Override
    public void afterPropertiesSet() {
        if (keyPrefix == null || keyPrefix.trim().isEmpty()) {
            throw new IllegalStateException("[cascade-bloom] keyPrefix must not be blank");
        }
        if (defaultExpectedInsertions <= 0) {
            throw new IllegalStateException("[cascade-bloom] defaultExpectedInsertions must be positive");
        }
        if (defaultFalseProbability <= 0 || defaultFalseProbability >= 1) {
            throw new IllegalStateException("[cascade-bloom] defaultFalseProbability must be in range (0, 1)");
        }
        if (maxCacheSize <= 0) {
            throw new IllegalStateException("[cascade-bloom] maxCacheSize must be positive");
        }
        if (maxExpressionCacheSize <= 0) {
            throw new IllegalStateException("[cascade-bloom] maxExpressionCacheSize must be positive");
        }
    }

    // -------------------------------------------------------------------------
    // 内部类：单个过滤器定义
    // -------------------------------------------------------------------------

    /**
     * 单个布隆过滤器的定义配置
     */
    @Data
    public static class BloomFilterDefinition {

        /**
         * 过滤器名称（必填），最终 Redis key = keyPrefix + name
         */
        private String name;

        /**
         * 预期元素数量，若未设置则使用全局默认值
         */
        private Long expectedInsertions;

        /**
         * 期望误判率，若未设置则使用全局默认值
         */
        private Double falseProbability;
    }
}
