package cc.coderm.cascade.bloom;

import cc.coderm.cascade.bloom.config.BloomFilterProperties;
import cc.coderm.cascade.bloom.core.CascadeBloomFilter;
import cc.coderm.cascade.bloom.exception.BloomFilterException;
import cc.coderm.cascade.bloom.impl.RedissonBloomFilterManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * cascade-bloom 核心单元测试
 *
 * @author cascade-framework
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("cascade-bloom 单元测试")
class BloomFilterTest {

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RBloomFilter<String> stringBloomFilter;

    private RedissonBloomFilterManager manager;
    private BloomFilterProperties properties;

    @BeforeEach
    void setUp() {
        properties = new BloomFilterProperties();
        properties.setKeyPrefix("test:bloom:");
        properties.setDefaultExpectedInsertions(100_000L);
        properties.setDefaultFalseProbability(0.03);

        manager = new RedissonBloomFilterManager(redissonClient, properties);

        when(redissonClient.<String>getBloomFilter(anyString())).thenReturn((RBloomFilter) stringBloomFilter);
        when(stringBloomFilter.tryInit(anyLong(), anyDouble())).thenReturn(true);
    }

    // =========================================================================
    // BloomFilterManager 测试
    // =========================================================================

    @Test
    @DisplayName("getFilter - 使用默认配置创建过滤器")
    void testGetFilterWithDefaultConfig() {
        CascadeBloomFilter<Object> filter = manager.getFilter("user-bloom");

        assertThat(filter).isNotNull();
        assertThat(filter.getName()).isEqualTo("user-bloom");
        verify(redissonClient).getBloomFilter("test:bloom:user-bloom");
        verify(stringBloomFilter).tryInit(100_000L, 0.03);
    }

    @Test
    @DisplayName("getOrCreate - 自定义容量和误判率")
    void testGetOrCreate() {
        CascadeBloomFilter<Object> filter = manager.getOrCreate("order-bloom", 5_000_000L, 0.01);

        assertThat(filter).isNotNull();
        assertThat(filter.getName()).isEqualTo("order-bloom");
        assertThat(filter.getExpectedInsertions()).isEqualTo(5_000_000L);
        assertThat(filter.getFalseProbability()).isEqualTo(0.01);
        verify(stringBloomFilter).tryInit(5_000_000L, 0.01);
    }

    @Test
    @DisplayName("getOrCreate - 相同名称返回同一实例（本地缓存）")
    void testGetOrCreateReturnsSameInstance() {
        CascadeBloomFilter<Object> filter1 = manager.getFilter("user-bloom");
        CascadeBloomFilter<Object> filter2 = manager.getFilter("user-bloom");

        assertThat(filter1).isSameAs(filter2);
        // tryInit 只被调用一次（本地缓存命中）
        verify(stringBloomFilter, times(1)).tryInit(anyLong(), anyDouble());
    }

    @Test
    @DisplayName("getOrCreate - 参数校验：name 为空")
    void testGetOrCreateWithBlankName() {
        assertThatThrownBy(() -> manager.getOrCreate("", 100_000L, 0.03))
                .isInstanceOf(BloomFilterException.class)
                .hasMessageContaining("name must not be blank");
    }

    @Test
    @DisplayName("getOrCreate - 参数校验：expectedInsertions 非正数")
    void testGetOrCreateWithInvalidExpectedInsertions() {
        assertThatThrownBy(() -> manager.getOrCreate("test", 0L, 0.03))
                .isInstanceOf(BloomFilterException.class)
                .hasMessageContaining("expectedInsertions must be positive");
    }

    @Test
    @DisplayName("getOrCreate - 参数校验：falseProbability 越界")
    void testGetOrCreateWithInvalidFalseProbability() {
        assertThatThrownBy(() -> manager.getOrCreate("test", 100_000L, 1.5))
                .isInstanceOf(BloomFilterException.class)
                .hasMessageContaining("falseProbability must be in range");
    }

    @Test
    @DisplayName("listFilterNames - 返回已注册过滤器列表")
    void testListFilterNames() {
        manager.getFilter("filter-a");
        manager.getFilter("filter-b");

        Set<String> names = manager.listFilterNames();
        assertThat(names).containsExactlyInAnyOrder("filter-a", "filter-b");
    }

    @Test
    @DisplayName("exists - 本地缓存命中但 Redis 不存在时应返回 false 并清理本地缓存")
    void testExistsShouldEvictStaleCacheWhenRedisMissing() {
        manager.getFilter("user-bloom");
        assertThat(manager.listFilterNames()).contains("user-bloom");

        when(stringBloomFilter.isExists()).thenReturn(false);

        boolean exists = manager.exists("user-bloom");

        assertThat(exists).isFalse();
        assertThat(manager.listFilterNames()).doesNotContain("user-bloom");
        verify(stringBloomFilter).isExists();
    }

    @Test
    @DisplayName("exists - 本地缓存命中且 Redis 存在时应返回 true 并保留缓存")
    void testExistsShouldReturnTrueWhenRedisExists() {
        manager.getFilter("user-bloom");
        when(stringBloomFilter.isExists()).thenReturn(true);

        boolean exists = manager.exists("user-bloom");

        assertThat(exists).isTrue();
        assertThat(manager.listFilterNames()).contains("user-bloom");
        verify(stringBloomFilter).isExists();
    }

    // =========================================================================
    // CascadeBloomFilter 操作测试
    // =========================================================================

    @Test
    @DisplayName("add - 添加元素")
    void testAdd() {
        when(stringBloomFilter.add(eq("user:1001"))).thenReturn(true);

        CascadeBloomFilter<Object> filter = manager.getFilter("user-bloom");
        boolean result = filter.add("user:1001");

        assertThat(result).isTrue();
        verify(stringBloomFilter).add("user:1001");
    }

    @Test
    @DisplayName("add - 元素为 null 抛出异常")
    void testAddNullThrows() {
        CascadeBloomFilter<Object> filter = manager.getFilter("user-bloom");
        assertThatThrownBy(() -> filter.add(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("mightContain - 元素不存在返回 false")
    void testMightContainReturnsFalse() {
        when(stringBloomFilter.contains(eq("unknown-key"))).thenReturn(false);

        CascadeBloomFilter<Object> filter = manager.getFilter("user-bloom");
        boolean result = filter.mightContain("unknown-key");

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("mightContain - 元素可能存在返回 true")
    void testMightContainReturnsTrue() {
        when(stringBloomFilter.contains(eq("user:1001"))).thenReturn(true);

        CascadeBloomFilter<Object> filter = manager.getFilter("user-bloom");
        boolean result = filter.mightContain("user:1001");

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("mightContainAll - 批量判断")
    void testMightContainAll() {
        when(stringBloomFilter.contains(eq("k1"))).thenReturn(true);
        when(stringBloomFilter.contains(eq("k2"))).thenReturn(false);
        when(stringBloomFilter.contains(eq("k3"))).thenReturn(true);

        CascadeBloomFilter<Object> filter = manager.getFilter("user-bloom");
        List<String> keys = Arrays.asList("k1", "k2", "k3");
        Map<Object, Boolean> result = filter.mightContainAll((List) keys);

        assertThat(result).containsEntry("k1", true)
                .containsEntry("k2", false)
                .containsEntry("k3", true);
    }

    @Test
    @DisplayName("addAll - 使用 Redisson 批量接口添加")
    void testAddAll() {
        List<String> values = Arrays.asList("id:1", "id:2", "id:3");
        when(stringBloomFilter.add(values)).thenReturn(3L);

        CascadeBloomFilter<Object> filter = manager.getFilter("user-bloom");
        filter.addAll((List) values);

        verify(stringBloomFilter).add(values);
        verify(stringBloomFilter, never()).add(anyString());
    }

    @Test
    @DisplayName("count - 返回当前元素数量")
    void testCount() {
        when(stringBloomFilter.count()).thenReturn(42L);

        CascadeBloomFilter<Object> filter = manager.getFilter("user-bloom");
        assertThat(filter.count()).isEqualTo(42L);
    }

    @Test
    @DisplayName("isExists - 过滤器存在检测")
    void testIsExists() {
        when(stringBloomFilter.isExists()).thenReturn(true);

        CascadeBloomFilter<Object> filter = manager.getFilter("user-bloom");
        assertThat(filter.isExists()).isTrue();
    }

    @Test
    @DisplayName("delete - 删除过滤器")
    void testDelete() {
        CascadeBloomFilter<Object> filter = manager.getFilter("user-bloom");
        filter.delete();

        verify(stringBloomFilter).delete();
    }
}
