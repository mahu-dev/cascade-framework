package cc.coderm.cascade.bloom;

import cc.coderm.cascade.bloom.config.BloomFilterProperties;
import cc.coderm.cascade.bloom.core.BloomFilterManager;
import cc.coderm.cascade.bloom.exception.BloomFilterException;
import cc.coderm.cascade.bloom.exception.BloomFilterNotFoundException;
import cc.coderm.cascade.bloom.impl.RedissonBloomFilterManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * BloomFilterManager remove 方法状态一致性测试
 *
 * @author lionel lionelk@163.com
 * =============================
 * Date: 2026-04-11
 * Time: 00:15:00
 * =============================
 */
@DisplayName("BloomFilterManager remove 方法状态一致性测试")
class BloomFilterManagerRemoveConsistencyTest {

    @Test
    @DisplayName("remove - 成功删除缓存和Redis中的过滤器")
    void shouldRemoveFromBothCacheAndRedis() {
        RedissonClient redissonClient = mock(RedissonClient.class);
        RBloomFilter<String> stringBloomFilter = mock(RBloomFilter.class);

        BloomFilterProperties properties = new BloomFilterProperties();
        RedissonBloomFilterManager manager = new RedissonBloomFilterManager(redissonClient, properties);

        when(redissonClient.<String>getBloomFilter(anyString())).thenReturn(stringBloomFilter);
        when(stringBloomFilter.tryInit(anyLong(), anyDouble())).thenReturn(true);
        when(stringBloomFilter.isExists()).thenReturn(true);

        // 创建并缓存过滤器
        manager.getFilter("test-filter");

        // 删除过滤器
        manager.remove("test-filter");

        // 验证：Redis数据被删除
        verify(stringBloomFilter).delete();
    }

    @Test
    @DisplayName("remove - Redis删除失败时应保持缓存不变并抛出异常")
    void shouldKeepCacheWhenRedisDeleteFails() {
        RedissonClient redissonClient = mock(RedissonClient.class);
        RBloomFilter<String> stringBloomFilter = mock(RBloomFilter.class);

        BloomFilterProperties properties = new BloomFilterProperties();
        RedissonBloomFilterManager manager = new RedissonBloomFilterManager(redissonClient, properties);

        when(redissonClient.<String>getBloomFilter(anyString())).thenReturn(stringBloomFilter);
        when(stringBloomFilter.tryInit(anyLong(), anyDouble())).thenReturn(true);
        when(stringBloomFilter.isExists()).thenReturn(true);
        when(stringBloomFilter.delete()).thenThrow(new RuntimeException("Redis connection lost"));

        // 创建并缓存过滤器
        manager.getFilter("test-filter");

        // 尝试删除
        assertThatThrownBy(() -> manager.remove("test-filter"))
                .isInstanceOf(BloomFilterException.class)
                .hasMessageContaining("Failed to remove bloom filter");

        // 验证：缓存仍存在
        assertThat(manager.listFilterNames()).contains("test-filter");
    }

    @Test
    @DisplayName("remove - 删除不存在的过滤器应抛出异常")
    void shouldThrowExceptionWhenRemovingNonExistentFilter() {
        RedissonClient redissonClient = mock(RedissonClient.class);
        RBloomFilter<String> stringBloomFilter = mock(RBloomFilter.class);

        BloomFilterProperties properties = new BloomFilterProperties();
        RedissonBloomFilterManager manager = new RedissonBloomFilterManager(redissonClient, properties);

        when(redissonClient.<String>getBloomFilter(anyString())).thenReturn(stringBloomFilter);
        when(stringBloomFilter.isExists()).thenReturn(false);

        assertThatThrownBy(() -> manager.remove("non-existent"))
                .isInstanceOf(BloomFilterNotFoundException.class);
    }

    @Test
    @DisplayName("remove - 删除仅存在于Redis的过滤器（无本地缓存）")
    void shouldRemoveFilterFromRedisWhenNotCached() {
        RedissonClient redissonClient = mock(RedissonClient.class);
        RBloomFilter<String> stringBloomFilter = mock(RBloomFilter.class);

        BloomFilterProperties properties = new BloomFilterProperties();
        RedissonBloomFilterManager manager = new RedissonBloomFilterManager(redissonClient, properties);

        when(redissonClient.<String>getBloomFilter(anyString())).thenReturn(stringBloomFilter);
        when(stringBloomFilter.isExists()).thenReturn(true);

        // 直接删除（缓存中不存在）
        manager.remove("redis-only-filter");

        verify(stringBloomFilter).delete();
    }
}