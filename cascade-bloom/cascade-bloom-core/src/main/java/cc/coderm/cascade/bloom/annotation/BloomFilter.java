package cc.coderm.cascade.bloom.annotation;

import java.lang.annotation.*;

/**
 * 布隆过滤器方法级拦截注解
 * <p>
 * 标注在方法上，在方法执行前自动用布隆过滤器做前置过滤：
 * <ul>
 *   <li>若布隆过滤器判断 key <strong>一定不存在</strong>，则直接返回 {@link #fallbackValue()} 或抛出异常</li>
 *   <li>若布隆过滤器判断 key <strong>可能存在</strong>，则继续执行原始方法</li>
 * </ul>
 *
 * <p>使用示例：
 * <pre>{@code
 * // 示例 1：不存在时返回 null
 * @BloomFilter(name = "user-bloom", key = "#userId")
 * public User getUserById(Long userId) { ... }
 *
 * // 示例 2：不存在时抛出异常
 * @BloomFilter(name = "user-bloom", key = "#userId", throwOnAbsent = true, message = "用户不存在")
 * public User getUserById(Long userId) { ... }
 *
 * // 示例 3：复合 key（SpEL 表达式）
 * @BloomFilter(name = "order-bloom", key = "#userId + ':' + #orderId")
 * public Order getOrder(Long userId, Long orderId) { ... }
 * }</pre>
 *
 * @author lionel lionelk@163.com
 * =============================
 * Date: 2025-01-10
 * Time: 16:00:00
 * =============================
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface BloomFilter {

    /**
     * 过滤器名称（必填），对应 {@code BloomFilterManager} 中注册的名称
     */
    String name();

    /**
     * 从方法参数提取过滤 key 的 SpEL 表达式
     * <p>
     * 默认取第一个方法参数的 {@code toString()}：{@code "#args[0]"}
     * - "#id"        : 使用参数id作为键
     * - "#user.id"   : 使用参数user的id属性作为键
     * 支持使用参数名（需开启 {@code -parameters} 编译选项）：{@code "#userId"}
     */
    String key() default "#args[0]";

    /**
     * 布隆过滤器判断"一定不存在"时的返回值（SpEL 表达式或字面量）
     * <p>
     * 默认返回 {@code null}（字面量 "null" 会被解析为 Java null）。
     * <p>
     * 与 {@link #throwOnAbsent()} 二选一，{@code throwOnAbsent=true} 时此属性无效。
     */
    String fallbackValue() default "null";

    /**
     * 布隆过滤器判断"一定不存在"时是否抛出异常
     * <p>
     * 默认 {@code false}，设为 {@code true} 后将抛出 {@link cc.coderm.cascade.bloom.exception.BloomFilterException}
     */
    boolean throwOnAbsent() default false;

    /**
     * 抛出异常时的错误信息（{@link #throwOnAbsent()} 为 {@code true} 时生效）
     */
    String message() default "The requested resource does not exist";

    /**
     * 查询成功（方法返回非 null）时是否自动回填 key 到布隆过滤器
     * <p>
     * 默认 {@code true}，建议保持开启，用于弥补预热遗漏或跨节点写入延迟。
     */
    boolean writeBackOnSuccess() default true;

    /**
     * 布隆过滤器判断 miss 时，是否执行原方法进行一次真实探测（自动刷新）
     * <p>
     * 默认 {@code false}（保持布隆前置拦截语义）。
     * <p>
     * 开启后：
     * <ul>
     *   <li>若原方法返回非 null，会自动回填 key（受 {@link #writeBackOnSuccess()} 控制）</li>
     *   <li>若原方法返回 null，则按 {@link #throwOnAbsent()} / {@link #fallbackValue()} 处理</li>
     * </ul>
     */
    boolean autoRefreshOnAbsent() default false;
}
