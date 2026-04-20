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
import org.redisson.api.BatchResult;
import org.redisson.api.RBatch;
import org.redisson.api.RBitSetAsync;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RFuture;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.Codec;
import org.redisson.client.protocol.Encoder;

import io.netty.buffer.Unpooled;

import java.nio.charset.StandardCharsets;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

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
    @Mock
    private RBatch batch;
    @Mock
    private RBitSetAsync bitSet;
    @Mock
    private BatchResult<?> batchResult;
    @Mock
    private Codec codec;
    @Mock
    private Encoder valueEncoder;
    @Mock
    private RSet<String> registrySet;

    private RedissonBloomFilterManager manager;
    private BloomFilterProperties properties;
    private Set<String> registeredFilterNames;

    @BeforeEach
    void setUp() throws Exception {
        properties = new BloomFilterProperties();
        properties.setKeyPrefix("test:bloom:");
        properties.setDefaultExpectedInsertions(100_000L);
        properties.setDefaultFalseProbability(0.03);
        registeredFilterNames = new HashSet<>();

        manager = new RedissonBloomFilterManager(redissonClient, properties);

        when(redissonClient.<String>getBloomFilter(anyString())).thenReturn((RBloomFilter) stringBloomFilter);
        when(redissonClient.<String>getSet(anyString())).thenReturn(registrySet);
        when(redissonClient.createBatch(any())).thenReturn(batch);
        when(registrySet.add(anyString())).thenAnswer(invocation -> registeredFilterNames.add(invocation.getArgument(0)));
        when(registrySet.remove(anyString())).thenAnswer(invocation -> registeredFilterNames.remove(invocation.getArgument(0)));
        when(registrySet.contains(anyString())).thenAnswer(invocation -> registeredFilterNames.contains(invocation.getArgument(0)));
        when(registrySet.readAll()).thenAnswer(invocation -> new HashSet<>(registeredFilterNames));
        when(batch.getBitSet(anyString())).thenReturn(bitSet);
        when(batch.execute()).thenReturn((BatchResult) batchResult);
        when(bitSet.getAsync(anyLong())).thenAnswer(invocation -> completedBooleanFuture(true));
        when(stringBloomFilter.tryInit(anyLong(), anyDouble())).thenReturn(true);
        when(stringBloomFilter.isExists()).thenReturn(true);
        when(stringBloomFilter.getCodec()).thenReturn(codec);
        when(stringBloomFilter.getName()).thenReturn("test:bloom:user-bloom");
        when(codec.getValueEncoder()).thenReturn(valueEncoder);
        doAnswer(invocation -> {
            String value = (String) invocation.getArgument(0);
            return Unpooled.copiedBuffer(value, StandardCharsets.UTF_8);
        }).when(valueEncoder).encode(any());
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
    @DisplayName("getFilter - 探测窗口内命中缓存不应远程探测 Redis exists")
    void testGetFilterShouldNotProbeRedisWithinProbeWindow() {
        properties.setCacheExistenceProbeIntervalMillis(60_000L);
        manager = new RedissonBloomFilterManager(redissonClient, properties);

        manager.getFilter("user-bloom");
        clearInvocations(stringBloomFilter);

        CascadeBloomFilter<Object> cachedFilter = manager.getFilter("user-bloom");

        assertThat(cachedFilter).isNotNull();
        verify(stringBloomFilter, never()).isExists();
        verify(stringBloomFilter, never()).tryInit(anyLong(), anyDouble());
    }

    @Test
    @DisplayName("getFilter - 命中自定义配置缓存时不应被默认参数拒绝")
    void testGetFilterShouldNotRejectCustomConfiguredCachedFilter() {
        CascadeBloomFilter<Object> customFilter = manager.getOrCreate("user-bloom", 5_000_000L, 0.01);

        CascadeBloomFilter<Object> byNameFilter = manager.getFilter("user-bloom");

        assertThat(byNameFilter).isSameAs(customFilter);
        assertThat(byNameFilter.getExpectedInsertions()).isEqualTo(5_000_000L);
        assertThat(byNameFilter.getFalseProbability()).isEqualTo(0.01D);
        verify(stringBloomFilter, times(1)).tryInit(5_000_000L, 0.01);
    }

    @Test
    @DisplayName("getOrCreate - 本地缓存命中但参数不一致时应快速失败")
    void testGetOrCreateShouldFailFastWhenCachedConfigMismatch() {
        manager.getOrCreate("user-bloom", 100_000L, 0.03);

        assertThatThrownBy(() -> manager.getOrCreate("user-bloom", 200_000L, 0.01))
                .isInstanceOf(BloomFilterException.class)
                .hasMessageContaining("configuration mismatch")
                .hasMessageContaining("source=cache");

        verify(redissonClient, times(1)).getBloomFilter("test:bloom:user-bloom");
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
    @DisplayName("getOrCreate - 过滤器名称应自动 trim 规范化")
    void testGetOrCreateShouldNormalizeFilterName() {
        CascadeBloomFilter<Object> filter = manager.getOrCreate("  normalized-bloom  ", 100_000L, 0.03);

        assertThat(filter.getName()).isEqualTo("normalized-bloom");
        verify(redissonClient).getBloomFilter("test:bloom:normalized-bloom");
    }

    @Test
    @DisplayName("listCachedFilterNames - 返回本地缓存过滤器列表")
    void testListFilterNames() {
        manager.getFilter("filter-a");
        manager.getFilter("filter-b");

        Set<String> names = manager.listCachedFilterNames();
        assertThat(names).containsExactlyInAnyOrder("filter-a", "filter-b");
    }

    @Test
    @DisplayName("listRegisteredFilterNames - 应返回 Redis 持久注册集合并随删除更新")
    void testListRegisteredFilterNames() {
        manager.getFilter("filter-a");
        manager.getFilter("filter-b");

        assertThat(manager.listRegisteredFilterNames())
                .containsExactlyInAnyOrder("filter-a", "filter-b");

        manager.remove("filter-a");

        assertThat(manager.listRegisteredFilterNames())
                .containsExactlyInAnyOrder("filter-b");
    }

    @Test
    @DisplayName("exists - 本地缓存未命中且 Redis 不存在时应返回 false（周期探测）")
    void testExistsShouldReturnFalseWhenCacheMissAndRedisMissing() {
        when(stringBloomFilter.isExists()).thenReturn(false);

        boolean exists = manager.exists("user-bloom");

        assertThat(exists).isFalse();
        assertThat(manager.listCachedFilterNames()).doesNotContain("user-bloom");
        verify(stringBloomFilter).isExists();
        verify(registrySet).remove("user-bloom");
    }

    @Test
    @DisplayName("exists - 本地缓存命中且探测窗口内应直接返回 true（与 getFilter 一致）")
    void testExistsShouldReturnCachedTruthWithinProbeWindow() {
        manager.getFilter("user-bloom");
        clearInvocations(stringBloomFilter, registrySet);

        boolean exists = manager.exists("user-bloom");

        assertThat(exists).isTrue();
        verify(stringBloomFilter, never()).isExists();
        verify(registrySet, never()).contains(anyString());
    }

    @Test
    @DisplayName("exists - 本地缓存未命中时应按探测窗口周期性探测 Redis Bloom")
    void testExistsShouldReturnTrueWhenCacheMissAndRedisExists() {
        when(stringBloomFilter.isExists()).thenReturn(true);

        boolean exists = manager.exists("user-bloom");

        assertThat(exists).isTrue();
        verify(stringBloomFilter).isExists();
        verify(registrySet, never()).contains(anyString());
    }

    @Test
    @DisplayName("existsInRedis - 每次应强一致探测 Redis 并回写最终一致快照")
    void testExistsInRedisShouldProbeRedisStrongly() {
        when(stringBloomFilter.isExists()).thenReturn(true, false);

        boolean first = manager.existsInRedis("user-bloom");
        boolean second = manager.existsInRedis("user-bloom");

        assertThat(first).isTrue();
        assertThat(second).isFalse();
        assertThat(manager.exists("user-bloom")).isFalse();
        verify(stringBloomFilter, times(2)).isExists();
    }

    @Test
    @DisplayName("getFilter - 本地缓存命中但 Redis 已失效时应驱逐并重建过滤器实例")
    void testGetFilterShouldRecreateWhenCachedInstanceIsStale() {
        properties.setCacheExistenceProbeIntervalMillis(0L);
        manager = new RedissonBloomFilterManager(redissonClient, properties);

        CascadeBloomFilter<Object> first = manager.getFilter("user-bloom");
        when(stringBloomFilter.isExists()).thenReturn(false);

        CascadeBloomFilter<Object> second = manager.getFilter("user-bloom");

        assertThat(second).isNotSameAs(first);
        assertThat(manager.listCachedFilterNames()).contains("user-bloom");
        verify(stringBloomFilter, atLeastOnce()).isExists();
        verify(stringBloomFilter, times(2)).tryInit(anyLong(), anyDouble());
    }

    @Test
    @DisplayName("getFilter - Redis 存在性探测超时时应降级为 UNKNOWN 并返回本地缓存")
    void testGetFilterShouldReturnCachedFilterWhenExistenceProbeTimesOut() throws Exception {
        properties.setCacheExistenceProbeIntervalMillis(0L);
        properties.setCacheExistenceProbeTimeoutMillis(10L);
        manager = new RedissonBloomFilterManager(redissonClient, properties);

        CascadeBloomFilter<Object> first = manager.getFilter("user-bloom");
        clearInvocations(stringBloomFilter);

        RFuture<Boolean> timeoutFuture = mock(RFuture.class);
        when(timeoutFuture.get(10L, TimeUnit.MILLISECONDS)).thenThrow(new TimeoutException("redis slow"));
        when(timeoutFuture.cancel(true)).thenReturn(true);
        when(stringBloomFilter.isExistsAsync()).thenReturn(timeoutFuture);

        CascadeBloomFilter<Object> second = manager.getFilter("user-bloom");

        assertThat(second).isSameAs(first);
        verify(stringBloomFilter).isExistsAsync();
        verify(timeoutFuture).cancel(true);
        verify(stringBloomFilter, never()).tryInit(anyLong(), anyDouble());
    }

    @Test
    @DisplayName("getFilter - 缓存校验期间并发重建时单次调用最多探测一次 Redis exists")
    void testGetFilterShouldProbeAtMostOnceWhenCacheIsRebuiltDuringValidation() throws Exception {
        properties.setCacheExistenceProbeIntervalMillis(0L);
        manager = new RedissonBloomFilterManager(redissonClient, properties);

        CascadeBloomFilter<Object> initial = manager.getFilter("user-bloom");
        assertThat(initial).isNotNull();
        clearInvocations(stringBloomFilter, registrySet);

        CountDownLatch unregisterEntered = new CountDownLatch(1);
        CountDownLatch allowUnregister = new CountDownLatch(1);
        AtomicBoolean blockOnce = new AtomicBoolean(true);

        when(stringBloomFilter.isExists()).thenReturn(false, true);
        when(registrySet.remove(eq("user-bloom"))).thenAnswer(invocation -> {
            if (blockOnce.compareAndSet(true, false)) {
                unregisterEntered.countDown();
                if (!allowUnregister.await(2, TimeUnit.SECONDS)) {
                    throw new AssertionError("Timed out while waiting to release unregister");
                }
            }
            return registeredFilterNames.remove("user-bloom");
        });

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<CascadeBloomFilter<Object>> validatingCall = executor.submit(() -> manager.getFilter("user-bloom"));
            assertThat(unregisterEntered.await(1, TimeUnit.SECONDS)).isTrue();

            Future<CascadeBloomFilter<Object>> recreatingCall = executor.submit(() -> manager.getFilter("user-bloom"));
            CascadeBloomFilter<Object> recreated = recreatingCall.get(3, TimeUnit.SECONDS);
            assertThat(recreated).isNotNull();

            allowUnregister.countDown();
            CascadeBloomFilter<Object> validated = validatingCall.get(3, TimeUnit.SECONDS);
            assertThat(validated).isSameAs(recreated);
        } finally {
            allowUnregister.countDown();
            executor.shutdownNow();
            executor.awaitTermination(1, TimeUnit.SECONDS);
        }

        verify(stringBloomFilter, times(1)).isExists();
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
        int hashIterations = calculateHashIterations(properties.getDefaultExpectedInsertions(),
                calculateBitSize(properties.getDefaultExpectedInsertions(), properties.getDefaultFalseProbability()));
        AtomicLong readCounter = new AtomicLong(0L);
        when(bitSet.getAsync(anyLong())).thenAnswer(invocation -> {
            long readOrder = readCounter.getAndIncrement();
            int valueIndex = (int) (readOrder / hashIterations);
            return completedBooleanFuture(valueIndex != 1);
        });

        CascadeBloomFilter<Object> filter = manager.getFilter("user-bloom");
        List<String> keys = Arrays.asList("k1", "k2", "k3");
        Map<Object, Boolean> result = filter.mightContainAll((List) keys);

        assertThat(result).containsEntry("k1", true)
                .containsEntry("k2", false)
                .containsEntry("k3", true);
        verify(redissonClient).createBatch(any());
        verify(batch).execute();
        verify(bitSet, atLeastOnce()).getAsync(anyLong());
        verify(stringBloomFilter, never()).contains(anyString());
    }

    private static long calculateBitSize(long expectedInsertions, double falseProbability) {
        double logOfTwo = Math.log(2D);
        return (long) (-expectedInsertions * Math.log(falseProbability) / (logOfTwo * logOfTwo));
    }

    private static int calculateHashIterations(long expectedInsertions, long bitSize) {
        return Math.max(1, (int) Math.round((double) bitSize / expectedInsertions * Math.log(2D)));
    }

    @SuppressWarnings("unchecked")
    private static RFuture<Boolean> completedBooleanFuture(boolean value) {
        RFuture<Boolean> future = mock(RFuture.class);
        when(future.join()).thenReturn(value);
        when(future.getNow()).thenReturn(value);
        return future;
    }

    @Test
    @DisplayName("addAll - 使用 pipeline 批量写入")
    void testAddAll() {
        List<String> values = Arrays.asList("id:1", "id:2", "id:3");

        CascadeBloomFilter<Object> filter = manager.getFilter("user-bloom");
        filter.addAll((List) values);

        verify(redissonClient).createBatch(any());
        verify(batch).execute();
        verify(bitSet, atLeastOnce()).setAsync(anyLong());
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
