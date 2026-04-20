package cc.coderm.cascade.bloom;

import cc.coderm.cascade.bloom.config.BloomFilterProperties;
import cc.coderm.cascade.bloom.core.BloomFilterManager;
import cc.coderm.cascade.bloom.core.CascadeBloomFilter;
import cc.coderm.cascade.bloom.impl.RedissonBloomFilterManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;

import java.util.HashSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * BloomFilterManager LRU 缓存功能测试
 *
 * @author lionel lionelk@163.com
 * =============================
 * Date: 2026-04-11
 * Time: 00:17:00
 * =============================
 */
@DisplayName("BloomFilterManager LRU 缓存功能测试")
class BloomFilterManagerLRUCacheTest {

    @Test
    @DisplayName("LRU 缓存 - 超过容量时自动淘汰最久未使用的过滤器")
    void shouldEvictLeastRecentlyUsedFilterWhenCacheFull() {
        RedissonClient redissonClient = mock(RedissonClient.class);
        RBloomFilter<String> stringBloomFilter = mock(RBloomFilter.class);
        mockRegistry(redissonClient);

        BloomFilterProperties properties = new BloomFilterProperties();
        properties.setMaxCacheSize(3);

        RedissonBloomFilterManager manager = new RedissonBloomFilterManager(redissonClient, properties);

        when(redissonClient.<String>getBloomFilter(anyString())).thenReturn(stringBloomFilter);
        when(stringBloomFilter.tryInit(anyLong(), anyDouble())).thenReturn(true);
        when(stringBloomFilter.isExists()).thenReturn(true);

        manager.getFilter("filter-1");
        manager.getFilter("filter-2");
        manager.getFilter("filter-3");

        assertThat(manager.getCacheSize()).isEqualTo(3);
        assertThat(manager.listCachedFilterNames()).containsExactlyInAnyOrder("filter-1", "filter-2", "filter-3");

        manager.getFilter("filter-4");

        assertThat(manager.getCacheSize()).isEqualTo(3);
        assertThat(manager.listCachedFilterNames()).containsExactlyInAnyOrder("filter-2", "filter-3", "filter-4");
    }

    @Test
    @DisplayName("LRU 缓存 - 访问已缓存的过滤器更新访问时间")
    void shouldUpdateAccessTimeWhenAccessingCachedFilter() {
        RedissonClient redissonClient = mock(RedissonClient.class);
        RBloomFilter<String> stringBloomFilter = mock(RBloomFilter.class);
        mockRegistry(redissonClient);

        BloomFilterProperties properties = new BloomFilterProperties();
        properties.setMaxCacheSize(3);

        RedissonBloomFilterManager manager = new RedissonBloomFilterManager(redissonClient, properties);

        when(redissonClient.<String>getBloomFilter(anyString())).thenReturn(stringBloomFilter);
        when(stringBloomFilter.tryInit(anyLong(), anyDouble())).thenReturn(true);
        when(stringBloomFilter.isExists()).thenReturn(true);

        manager.getFilter("filter-1");
        manager.getFilter("filter-2");
        manager.getFilter("filter-3");

        manager.getFilter("filter-1");

        manager.getFilter("filter-4");

        assertThat(manager.listCachedFilterNames()).containsExactlyInAnyOrder("filter-3", "filter-1", "filter-4");
    }

    @Test
    @DisplayName("LRU 缓存 - 统计信息正确记录")
    void shouldTrackStatisticsCorrectly() {
        RedissonClient redissonClient = mock(RedissonClient.class);
        RBloomFilter<String> stringBloomFilter = mock(RBloomFilter.class);
        mockRegistry(redissonClient);

        BloomFilterProperties properties = new BloomFilterProperties();
        properties.setMaxCacheSize(2);

        RedissonBloomFilterManager manager = new RedissonBloomFilterManager(redissonClient, properties);

        when(redissonClient.<String>getBloomFilter(anyString())).thenReturn(stringBloomFilter);
        when(stringBloomFilter.tryInit(anyLong(), anyDouble())).thenReturn(true);
        when(stringBloomFilter.isExists()).thenReturn(true);

        manager.getFilter("filter-1");
        manager.getFilter("filter-2");

        manager.getFilter("filter-1");

        manager.getFilter("filter-3");

        assertThat(manager.getCacheHits()).isEqualTo(1);
        assertThat(manager.getCacheMisses()).isEqualTo(3);
        assertThat(manager.getEvictions()).isEqualTo(1);
        assertThat(manager.getCacheHitRate()).isGreaterThan(0);
    }

    @Test
    @DisplayName("LRU 缓存 - clearCache 清空所有缓存")
    void shouldClearAllCache() {
        RedissonClient redissonClient = mock(RedissonClient.class);
        RBloomFilter<String> stringBloomFilter = mock(RBloomFilter.class);
        mockRegistry(redissonClient);

        BloomFilterProperties properties = new BloomFilterProperties();
        RedissonBloomFilterManager manager = new RedissonBloomFilterManager(redissonClient, properties);

        when(redissonClient.<String>getBloomFilter(anyString())).thenReturn(stringBloomFilter);
        when(stringBloomFilter.tryInit(anyLong(), anyDouble())).thenReturn(true);
        when(stringBloomFilter.isExists()).thenReturn(true);

        manager.getFilter("filter-1");
        manager.getFilter("filter-2");

        assertThat(manager.getCacheSize()).isEqualTo(2);

        manager.clearCache();

        assertThat(manager.getCacheSize()).isEqualTo(0);
    }

    @Test
    @DisplayName("LRU 缓存 - 被淘汰的过滤器重新访问时重新创建")
    void shouldRecreateEvictedFilterOnNextAccess() {
        RedissonClient redissonClient = mock(RedissonClient.class);
        RBloomFilter<String> stringBloomFilter = mock(RBloomFilter.class);
        mockRegistry(redissonClient);

        BloomFilterProperties properties = new BloomFilterProperties();
        properties.setMaxCacheSize(2);

        RedissonBloomFilterManager manager = new RedissonBloomFilterManager(redissonClient, properties);

        when(redissonClient.<String>getBloomFilter(anyString())).thenReturn(stringBloomFilter);
        when(stringBloomFilter.tryInit(anyLong(), anyDouble())).thenReturn(true);
        when(stringBloomFilter.isExists()).thenReturn(true);

        manager.getFilter("filter-1");
        manager.getFilter("filter-2");
        manager.getFilter("filter-3");

        assertThat(manager.listCachedFilterNames()).doesNotContain("filter-1");

        CascadeBloomFilter<String> filter = manager.getFilter("filter-1");

        assertThat(filter).isNotNull();
        assertThat(manager.listCachedFilterNames()).contains("filter-1");
    }

    private static void mockRegistry(RedissonClient redissonClient) {
        @SuppressWarnings("unchecked")
        RSet<String> registrySet = mock(RSet.class);
        when(redissonClient.<String>getSet(anyString())).thenReturn(registrySet);
        when(registrySet.add(anyString())).thenReturn(true);
        when(registrySet.remove(anyString())).thenReturn(true);
        when(registrySet.readAll()).thenReturn(new HashSet<>());
    }
}
