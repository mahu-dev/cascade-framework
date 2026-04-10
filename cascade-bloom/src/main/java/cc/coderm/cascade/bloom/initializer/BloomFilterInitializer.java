package cc.coderm.cascade.bloom.initializer;

import cc.coderm.cascade.bloom.core.BloomFilterManager;
import cc.coderm.cascade.bloom.core.CascadeBloomFilter;

/**
 * 布隆过滤器初始化器接口
 * <p>
 * 实现此接口并注册为 Spring Bean，可在 Spring 容器就绪后自动执行初始化逻辑，
 * 例如从数据库批量加载数据预热布隆过滤器。
 *
 * <p>使用示例：
 * <pre>{@code
 * @Component
 * public class UserBloomFilterInitializer implements BloomFilterInitializer {
 *
 *     @Autowired
 *     private UserRepository userRepository;
 *
 *     @Override
 *     public String filterName() {
 *         return "user-bloom";
 *     }
 *
 *     @Override
 *     public void initialize(CascadeBloomFilter<String> filter) {
 *         List<Long> ids = userRepository.findAllIds();
 *         ids.forEach(id -> filter.add(String.valueOf(id)));
 *         log.info("user-bloom initialized with {} elements", ids.size());
 *     }
 * }
 * }</pre>
 *
 * @author lionel lionelk@163.com
 * =============================
 * Date: 2025-01-10
 * Time: 16:00:00
 * =============================
 */
public interface BloomFilterInitializer {

    /**
     * 返回此初始化器对应的布隆过滤器名称
     * <p>
     * 对应 {@code cascade.bloom.filters[].name} 中配置的名称，
     * 或通过 {@link BloomFilterManager#getOrCreate} 创建的名称。
     *
     * @return 过滤器名称
     */
    String filterName();

    /**
     * 执行初始化逻辑
     * <p>
     * 在 Spring 容器完全就绪（{@code ApplicationReadyEvent}）后调用。
     * 若过滤器已包含数据（Redis 中已存在），建议实现幂等逻辑（跳过或增量写入）。
     *
     * @param filter 对应名称的布隆过滤器实例（已完成 tryInit）
     */
    void initialize(CascadeBloomFilter<String> filter);
}