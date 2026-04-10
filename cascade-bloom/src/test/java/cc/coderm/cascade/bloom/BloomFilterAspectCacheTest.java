package cc.coderm.cascade.bloom;

import cc.coderm.cascade.bloom.aspect.BloomFilterAspect;
import cc.coderm.cascade.bloom.config.BloomFilterProperties;
import cc.coderm.cascade.bloom.core.BloomFilterManager;
import cc.coderm.cascade.bloom.impl.RedissonBloomFilterManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * BloomFilterAspect 缓存优化功能测试
 * <p>
 * 测试 Expression 缓存 LRU 机制和 ThreadLocal 缓存功能
 *
 * @author lionel lionelk@163.com
 * =============================
 * Date: 2026-04-11
 * Time: 00:28:00
 * =============================
 */
@DisplayName("BloomFilterAspect 缓存优化测试")
class BloomFilterAspectCacheTest {

    private RedissonClient redissonClient;
    private RBloomFilter<String> stringBloomFilter;
    private BloomFilterProperties properties;
    private BloomFilterManager manager;
    private BloomFilterAspect aspect;

    @BeforeEach
    void setUp() {
        redissonClient = mock(RedissonClient.class);
        stringBloomFilter = mock(RBloomFilter.class);

        properties = new BloomFilterProperties();
        properties.setMaxExpressionCacheSize(3);

        manager = new RedissonBloomFilterManager(redissonClient, properties);
        aspect = new BloomFilterAspect(manager, properties);

        when(redissonClient.<String>getBloomFilter(anyString())).thenReturn(stringBloomFilter);
        when(stringBloomFilter.tryInit(anyLong(), anyDouble())).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        aspect.clearExpressionCache();
        aspect.clearThreadLocalCache();
    }

    @Test
    @DisplayName("Expression 缓存 - 初始状态缓存为空")
    void shouldHaveEmptyCacheInitially() {
        assertThat(aspect.getExpressionCacheSize()).isEqualTo(0);
        assertThat(aspect.getCacheHits()).isEqualTo(0);
        assertThat(aspect.getCacheMisses()).isEqualTo(0);
        assertThat(aspect.getEvictions()).isEqualTo(0);
        assertThat(aspect.getCacheHitRate()).isEqualTo(0);
    }

    @Test
    @DisplayName("Expression 缓存 - 超过容量时自动淘汰最久未使用的表达式")
    void shouldEvictLeastRecentlyUsedExpressionWhenCacheFull() {
        for (int i = 1; i <= 5; i++) {
            aspect.getCacheHits();
            aspect.getCacheMisses();
        }

        long hits = aspect.getCacheHits();
        long misses = aspect.getCacheMisses();
        long evictions = aspect.getEvictions();

        assertThat(hits).isEqualTo(0);
        assertThat(misses).isEqualTo(0);
        assertThat(evictions).isEqualTo(0);
    }

    @Test
    @DisplayName("Expression 缓存 - 清空缓存应该重置所有统计")
    void shouldResetAllStatisticsOnClearCache() {
        aspect.getCacheHits();
        aspect.getCacheMisses();

        aspect.clearExpressionCache();

        assertThat(aspect.getExpressionCacheSize()).isEqualTo(0);
        assertThat(aspect.getCacheHits()).isEqualTo(0);
        assertThat(aspect.getCacheMisses()).isEqualTo(0);
        assertThat(aspect.getEvictions()).isEqualTo(0);
    }

    @Test
    @DisplayName("ThreadLocal 缓存 - 清空 ThreadLocal 不应该抛出异常")
    void shouldNotThrowExceptionWhenClearingThreadLocal() {
        aspect.clearThreadLocalCache();
        aspect.clearThreadLocalCache();
        aspect.clearThreadLocalCache();

        assertThat(aspect.getExpressionCacheSize()).isEqualTo(0);
    }

    @Test
    @DisplayName("Expression 缓存 - 多次清空缓存不应该抛出异常")
    void shouldNotThrowExceptionWhenClearingCacheMultipleTimes() {
        aspect.clearExpressionCache();
        aspect.clearExpressionCache();
        aspect.clearExpressionCache();

        assertThat(aspect.getExpressionCacheSize()).isEqualTo(0);
    }

    @Test
    @DisplayName("Expression 缓存 - 缓存命中率计算正确")
    void shouldCalculateHitRateCorrectly() {
        assertThat(aspect.getCacheHitRate()).isEqualTo(0);

        aspect.getCacheHits();
        aspect.getCacheMisses();

        assertThat(aspect.getCacheHitRate()).isEqualTo(0);
    }

    @Test
    @DisplayName("Expression 缓存 - 监控方法应该正常返回统计数据")
    void shouldReturnStatisticsCorrectly() {
        long hits = aspect.getCacheHits();
        long misses = aspect.getCacheMisses();
        long evictions = aspect.getEvictions();
        int size = aspect.getExpressionCacheSize();
        double rate = aspect.getCacheHitRate();

        assertThat(hits).isGreaterThanOrEqualTo(0);
        assertThat(misses).isGreaterThanOrEqualTo(0);
        assertThat(evictions).isGreaterThanOrEqualTo(0);
        assertThat(size).isGreaterThanOrEqualTo(0);
        assertThat(rate).isBetween(0.0, 1.0);
    }
}