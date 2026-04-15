package cc.coderm.cascade.bloom.core;

import java.util.function.Supplier;

/**
 * 布隆过滤器操作模板
 * <p>
 * 提供面向业务的高阶操作，封装常用的布隆过滤器使用模式。
 * 类比 Spring 的 {@code RedisTemplate}，简化重复的样板代码。
 *
 * @author lionel lionelk@163.com
 * =============================
 * Date: 2025-01-10
 * Time: 16:00:00
 * =============================
 */
public interface BloomFilterTemplate {

    /**
     * 添加元素到指定过滤器
     *
     * @param filterName 过滤器名称
     * @param value      待添加元素
     * @return {@code true} 表示首次添加
     */
    boolean add(String filterName, Object value);

    /**
     * 判断元素是否可能存在于指定过滤器
     *
     * @param filterName 过滤器名称
     * @param value      待判断元素
     * @return {@code false} 表示一定不存在，{@code true} 表示可能存在
     */
    boolean mightContain(String filterName, Object value);

    /**
     * 防缓存穿透核心方法（布隆过滤器守卫模式）
     * <p>
     * 执行逻辑：
     * <ol>
     *   <li>先用布隆过滤器判断 key 是否可能存在</li>
     *   <li>如果<strong>一定不存在</strong>，直接返回 {@code fallback}，不执行 loader</li>
     *   <li>如果<strong>可能存在</strong>，执行 {@code loader} 加载数据并返回</li>
     *   <li>如果 loader 返回非 null 数据，自动写回布隆过滤器</li>
     * </ol>
     * <p>
     * <strong>异常语义</strong>：若 {@code loader} 抛出异常，异常会直接向上抛出（不吞异常）。
     * 出现异常时不会执行写回逻辑。
     *
     * <p>使用示例：
     * <pre>{@code
     * User user = bloomFilterTemplate.getWithBloomGuard(
     *     "user-bloom",
     *     String.valueOf(userId),
     *     () -> userService.loadFromDb(userId),
     *     null
     * );
     * }</pre>
     *
     * @param filterName 过滤器名称
     * @param key        查询 key（字符串形式）
     * @param loader     数据加载器（布隆过滤器判断可能存在时执行）
     * @param fallback   布隆过滤器判断一定不存在时的默认返回值
     * @param <T>        返回类型
     * @return loader 的结果或 fallback 值
     */
    <T> T getWithBloomGuard(String filterName, String key, Supplier<T> loader, T fallback);

    /**
     * 布隆过滤器自愈模式（总是执行 loader 并写回）
     * <p>
     * 执行逻辑：
     * <ol>
     *   <li><strong>总是执行</strong> {@code loader} 加载数据（无论布隆过滤器结果如何）</li>
     *   <li>若 loader 返回非 null 数据，自动写回布隆过滤器并返回</li>
     *   <li>若 loader 返回 null，返回 {@code fallback}</li>
     * </ol>
     * <p>
     * <strong>异常语义</strong>：若 {@code loader} 抛出异常，异常会直接向上抛出（不吞异常）。
     * 出现异常时不会执行写回逻辑。
     * <p>
     * <strong>适用场景</strong>：
     * <ul>
     *   <li>修复布隆过滤器误判（数据存在但布隆过滤器判断不存在）</li>
     *   <li>布隆过滤器预热不完整，需要逐步补全</li>
     *   <li>可以容忍查询数据库的性能开销</li>
     * </ul>
     *
     * <p>使用示例：
     * <pre>{@code
     * // 即使布隆过滤器判断不存在，也会查询数据库
     * // 如果查到数据，自动写回布隆过滤器（自愈）
     * User user = bloomFilterTemplate.getWithSelfHeal(
     *     "user-bloom",
     *     String.valueOf(userId),
     *     () -> userService.loadFromDb(userId),
     *     null
     * );
     * }</pre>
     *
     * <p><strong>性能提示</strong>：此方法会执行 loader，可能影响性能。
     * 如果对性能敏感且布隆过滤器预热完整，建议使用 {@link #getWithBloomGuard}。
     *
     * @param filterName 过滤器名称
     * @param key        查询 key
     * @param loader     数据加载器（总是执行）
     * @param fallback   loader 返回 null 时的默认返回值
     * @param <T>        返回类型
     * @return loader 的结果或 fallback 值
     */
    <T> T getWithSelfHeal(String filterName, String key, Supplier<T> loader, T fallback);
}
