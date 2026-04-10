package cc.coderm.cascade.bloom.core;

import java.util.Collection;
import java.util.Map;

/**
 * 布隆过滤器核心接口
 * <p>
 * 封装单个布隆过滤器实例的所有操作，屏蔽底层 Redisson 细节。
 * <p>
 * 布隆过滤器特性说明：
 * <ul>
 *   <li>{@code mightContain} 返回 {@code false} 表示元素<strong>一定不存在</strong></li>
 *   <li>{@code mightContain} 返回 {@code true} 表示元素<strong>可能存在</strong>（存在误判率）</li>
 *   <li>过滤器容量和误判率在初始化后<strong>不可更改</strong></li>
 * </ul>
 * <p>
 * <strong>类型参数使用说明</strong>：
 * 虽然 {@code <T>} 支持任意类型，但框架内部会统一按 {@code toString()} 标准化为字符串存储。
 * 为避免类型转换和序列化问题，强烈建议使用 {@code String} 类型作为元素类型。
 * <ul>
 *   <li><strong>推荐用法</strong>：{@code CascadeBloomFilter<String>}</li>
 *   <li>如需使用其他类型（如 {@code Long}），请确保类型安全并理解可能的风险</li>
 *   <li>所有元素最终都会被转换为 {@code String} 存储，key 标准化由 {@link cc.coderm.cascade.bloom.util.BloomFilterKeyUtil} 处理</li>
 * </ul>
 *
 * <p>推荐用法示例：
 * <pre>{@code
 * // ✅ 推荐：使用 String 类型
 * CascadeBloomFilter<String> filter = bloomFilterManager.getFilter("user-bloom");
 * filter.add("12345");
 * filter.mightContain("12345");
 *
 * // ⚠️ 谨慎使用：其他类型需要确保类型安全
 * CascadeBloomFilter<Long> longFilter = bloomFilterManager.getFilter("order-bloom");
 * longFilter.add(12345L);  // 会被转换为 "12345" 存储
 * }</pre>
 *
 * @param <T> 元素类型（推荐使用 {@code String}）
 * @author lionel lionelk@163.com
 * =============================
 * Date: 2025-01-10
 * Time: 16:00:00
 * =============================
 */
public interface CascadeBloomFilter<T> {

    /**
     * 添加元素到布隆过滤器
     *
     * @param value 待添加元素（不可为 null）
     * @return {@code true} 表示元素首次被添加；{@code false} 表示可能已存在
     */
    boolean add(T value);

    /**
     * 批量添加元素
     *
     * @param values 待添加元素集合（不可为 null，元素不可为 null）
     */
    void addAll(Collection<T> values);

    /**
     * 判断元素是否可能存在于布隆过滤器中
     *
     * @param value 待判断元素（不可为 null）
     * @return {@code false} 表示元素<strong>一定不存在</strong>；
     * {@code true} 表示元素<strong>可能存在</strong>
     */
    boolean mightContain(T value);

    /**
     * 批量判断元素是否可能存在
     *
     * @param values 待判断元素集合
     * @return Map，key 为元素，value 为是否可能存在
     */
    Map<T, Boolean> mightContainAll(Collection<T> values);

    /**
     * 获取过滤器名称（对应 Redis key）
     *
     * @return 过滤器名称
     */
    String getName();

    /**
     * 获取预期容量（初始化时指定）
     *
     * @return 预期元素数量
     */
    long getExpectedInsertions();

    /**
     * 获取预期误判率（初始化时指定）
     *
     * @return 误判率，范围 (0, 1)
     */
    double getFalseProbability();

    /**
     * 获取当前已插入的元素数量
     *
     * @return 元素计数
     */
    long count();

    /**
     * 判断过滤器在 Redis 中是否已存在
     *
     * @return {@code true} 表示已存在
     */
    boolean isExists();

    /**
     * 删除整个布隆过滤器（从 Redis 中移除对应 key）
     * <p>
     * <strong>谨慎使用</strong>：删除后所有数据丢失，需要重新初始化
     */
    void delete();
}