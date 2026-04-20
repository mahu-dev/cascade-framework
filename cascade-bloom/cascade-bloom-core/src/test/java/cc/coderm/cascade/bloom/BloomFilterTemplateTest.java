package cc.coderm.cascade.bloom;

import cc.coderm.cascade.bloom.core.BloomFilterManager;
import cc.coderm.cascade.bloom.core.BloomFilterTemplate;
import cc.coderm.cascade.bloom.core.CascadeBloomFilter;
import cc.coderm.cascade.bloom.exception.BloomFilterException;
import cc.coderm.cascade.bloom.impl.DefaultBloomFilterTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * BloomFilterTemplate 单元测试
 *
 * @author cascade-framework
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BloomFilterTemplate 单元测试")
class BloomFilterTemplateTest {

    @Mock
    private BloomFilterManager bloomFilterManager;

    @Mock
    @SuppressWarnings("rawtypes")
    private CascadeBloomFilter filter;

    private BloomFilterTemplate template;

    @BeforeEach
    void setUp() {
        template = new DefaultBloomFilterTemplate(bloomFilterManager);
        lenient().when(bloomFilterManager.getFilter(anyString())).thenReturn(filter);
        lenient().when(bloomFilterManager.existsInRedis(anyString())).thenReturn(true);
    }

    @Test
    @DisplayName("add - 代理到过滤器")
    void testAdd() {
        when(filter.add(eq("key1"))).thenReturn(true);

        boolean result = template.add("user-bloom", "key1");

        assertThat(result).isTrue();
        verify(filter).add("key1");
    }

    @Test
    @DisplayName("add - 过滤器名称应自动 trim 规范化")
    void testAddShouldNormalizeFilterName() {
        when(filter.add(eq("key1"))).thenReturn(true);

        boolean result = template.add("  user-bloom  ", "key1");

        assertThat(result).isTrue();
        verify(bloomFilterManager).getFilter("user-bloom");
    }

    @Test
    @DisplayName("add/mightContain - 高频同名调用应复用已绑定过滤器实例")
    void testShouldReuseBoundFilterForHotPath() {
        when(filter.add("k1")).thenReturn(true);
        when(filter.add("k2")).thenReturn(false);
        when(filter.mightContain("k3")).thenReturn(true);

        assertThat(template.add("user-bloom", "k1")).isTrue();
        assertThat(template.add("user-bloom", "k2")).isFalse();
        assertThat(template.mightContain("user-bloom", "k3")).isTrue();

        verify(bloomFilterManager, times(1)).getFilter("user-bloom");
    }

    @Test
    @DisplayName("bind - 应返回可复用的高性能操作句柄")
    void testBindShouldReturnReusableOperations() {
        when(filter.mightContain("key1")).thenReturn(true);

        BloomFilterTemplate.BoundBloomFilterOperations ops = template.bind("  user-bloom  ");
        BloomFilterTemplate.BoundBloomFilterOperations sameOps = template.bind("user-bloom");

        assertThat(ops).isSameAs(sameOps);
        assertThat(ops.filterName()).isEqualTo("user-bloom");
        assertThat(ops.mightContain("key1")).isTrue();
        verify(bloomFilterManager, times(1)).getFilter("user-bloom");
    }

    @Test
    @DisplayName("mightContain - 操作失败时应强一致刷新并重试一次")
    @SuppressWarnings("unchecked")
    void testShouldAutoRefreshAndRetryWhenOperationFails() {
        CascadeBloomFilter<Object> staleFilter = mock(CascadeBloomFilter.class);
        CascadeBloomFilter<Object> freshFilter = mock(CascadeBloomFilter.class);
        when(staleFilter.mightContain("key1")).thenThrow(new RuntimeException("stale"));
        when(freshFilter.mightContain("key1")).thenReturn(true);
        when(bloomFilterManager.getFilter("user-bloom")).thenReturn(staleFilter, freshFilter);
        when(bloomFilterManager.existsInRedis("user-bloom")).thenReturn(false);

        boolean result = template.mightContain("user-bloom", "key1");

        assertThat(result).isTrue();
        verify(bloomFilterManager).existsInRedis("user-bloom");
        verify(bloomFilterManager, times(2)).getFilter("user-bloom");
    }

    @Test
    @DisplayName("mightContain - 并发故障刷新应合并为单次远程重载")
    @SuppressWarnings("unchecked")
    void testShouldCoalesceConcurrentReloadIntoSingleFlight() throws Exception {
        CascadeBloomFilter<Object> staleFilter = mock(CascadeBloomFilter.class);
        CascadeBloomFilter<Object> freshFilter = mock(CascadeBloomFilter.class);
        CountDownLatch bothWorkersReachedStale = new CountDownLatch(2);

        when(staleFilter.mightContain("key1")).thenAnswer(invocation -> {
            bothWorkersReachedStale.countDown();
            if (!bothWorkersReachedStale.await(2, TimeUnit.SECONDS)) {
                throw new AssertionError("both workers should hit stale filter before reload");
            }
            throw new RuntimeException("stale");
        });
        when(freshFilter.mightContain("key1")).thenReturn(true);
        when(bloomFilterManager.getFilter("user-bloom")).thenReturn(staleFilter, freshFilter, freshFilter);
        when(bloomFilterManager.existsInRedis("user-bloom")).thenReturn(false);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(() -> template.mightContain("user-bloom", "key1"));
            Future<Boolean> second = executor.submit(() -> template.mightContain("user-bloom", "key1"));

            assertThat(first.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(second.get(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdownNow();
        }

        verify(bloomFilterManager, times(2)).getFilter("user-bloom");
        verify(bloomFilterManager, times(1)).existsInRedis("user-bloom");
    }

    @Test
    @DisplayName("mightContain - 代理到过滤器")
    void testMightContain() {
        when(filter.mightContain(eq("key1"))).thenReturn(true);

        boolean result = template.mightContain("user-bloom", "key1");

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("getWithBloomGuard - 布隆过滤器确认不存在时直接返回 fallback，不执行 loader")
    void testGetWithBloomGuardMiss() {
        when(filter.mightContain(eq("missing-key"))).thenReturn(false);

        String result = template.getWithBloomGuard(
                "user-bloom",
                "missing-key",
                () -> {
                    throw new RuntimeException("loader should not be called");
                },
                "fallback"
        );

        assertThat(result).isEqualTo("fallback");
        verify(filter, never()).add(any());
    }

    @Test
    @DisplayName("getWithBloomGuard - 布隆过滤器可能存在时执行 loader")
    void testGetWithBloomGuardHit() {
        when(filter.mightContain(eq("existing-key"))).thenReturn(true);

        String result = template.getWithBloomGuard(
                "user-bloom",
                "existing-key",
                () -> "loaded-value",
                "fallback"
        );

        assertThat(result).isEqualTo("loaded-value");
    }

    @Test
    @DisplayName("getWithBloomGuard - key 为 null 时应 fail-fast")
    void testGetWithBloomGuardShouldFailFastWhenKeyIsNull() {
        assertThatThrownBy(() -> template.getWithBloomGuard("user-bloom", null, () -> "v", "fallback"))
                .isInstanceOf(BloomFilterException.class)
                .hasMessageContaining("key must not be null");
        verifyNoInteractions(filter);
    }

    @Test
    @DisplayName("getWithBloomGuard - loader 为 null 时应 fail-fast")
    void testGetWithBloomGuardShouldFailFastWhenLoaderIsNull() {
        assertThatThrownBy(() -> template.getWithBloomGuard("user-bloom", "k1", null, "fallback"))
                .isInstanceOf(BloomFilterException.class)
                .hasMessageContaining("loader must not be null");
        verifyNoInteractions(bloomFilterManager);
        verifyNoInteractions(filter);
    }

    @Test
    @DisplayName("getWithBloomGuard - loader 返回非 null 时自动写回过滤器")
    @SuppressWarnings("unchecked")
    void testGetWithBloomGuardWriteBack() {
        when(filter.mightContain(eq("key1"))).thenReturn(true);

        String result = template.getWithBloomGuard(
                "user-bloom",
                "key1",
                () -> "data",
                null
        );

        assertThat(result).isEqualTo("data");
        verify(filter).add("key1");
    }

    @Test
    @DisplayName("getWithBloomGuard - loader 返回 null 时不写回过滤器")
    void testGetWithBloomGuardNoWriteBackOnNull() {
        when(filter.mightContain(eq("key1"))).thenReturn(true);

        String result = template.getWithBloomGuard(
                "user-bloom",
                "key1",
                () -> null,
                "fallback"
        );

        assertThat(result).isNull();
        verify(filter, never()).add(any());
    }

    @Test
    @DisplayName("getWithBloomGuard - loader 返回 Optional.empty 时不写回过滤器")
    void testGetWithBloomGuardNoWriteBackOnOptionalEmpty() {
        when(filter.mightContain(eq("key1"))).thenReturn(true);

        Optional<String> result = template.getWithBloomGuard(
                "user-bloom",
                "key1",
                Optional::<String>empty,
                Optional.of("fallback")
        );

        assertThat(result).isEmpty();
        verify(filter, never()).add(any());
    }

    @Test
    @DisplayName("getWithBloomGuard - loader 返回 CompletableFuture 时应在异步完成后基于 payload 决定写回")
    void testGetWithBloomGuardShouldWriteBackAfterAsyncLoaderCompletes() {
        when(filter.mightContain(eq("key1"))).thenReturn(true);

        CompletableFuture<String> result = template.getWithBloomGuard(
                "user-bloom",
                "key1",
                () -> CompletableFuture.completedFuture("data"),
                CompletableFuture.completedFuture("fallback")
        );

        assertThat(result.join()).isEqualTo("data");
        verify(filter).add("key1");
    }

    @Test
    @DisplayName("getWithBloomGuard - loader 返回 CompletableFuture.completedFuture(null) 时不写回")
    void testGetWithBloomGuardShouldNotWriteBackWhenAsyncPayloadIsNull() {
        when(filter.mightContain(eq("key1"))).thenReturn(true);

        CompletableFuture<String> result = template.getWithBloomGuard(
                "user-bloom",
                "key1",
                () -> CompletableFuture.completedFuture(null),
                CompletableFuture.completedFuture("fallback")
        );

        assertThat(result.join()).isNull();
        verify(filter, never()).add(any());
    }

    @Test
    @DisplayName("getWithSelfHeal - 布隆过滤器 miss 但 loader 命中，应返回数据并写回")
    @SuppressWarnings("unchecked")
    void testGetWithSelfHealMissButLoaderHits() {
        when(filter.mightContain(eq("key1"))).thenReturn(false);

        String result = template.getWithSelfHeal(
                "user-bloom",
                "key1",
                () -> "data",
                "fallback"
        );

        assertThat(result).isEqualTo("data");
        verify(filter).add("key1");
    }

    @Test
    @DisplayName("getWithSelfHeal - 布隆过滤器 miss 且 loader 未命中，应返回 fallback")
    void testGetWithSelfHealMissAndLoaderMisses() {
        when(filter.mightContain(eq("key1"))).thenReturn(false);

        String result = template.getWithSelfHeal(
                "user-bloom",
                "key1",
                () -> null,
                "fallback"
        );

        assertThat(result).isEqualTo("fallback");
        verify(filter, never()).add(any());
    }

    @Test
    @DisplayName("getWithSelfHeal - loader 返回 Optional.empty 时应返回 fallback 且不写回")
    void testGetWithSelfHealNoWriteBackOnOptionalEmpty() {
        when(filter.mightContain(eq("key1"))).thenReturn(false);
        Optional<String> fallback = Optional.of("fallback");

        Optional<String> result = template.getWithSelfHeal(
                "user-bloom",
                "key1",
                Optional::<String>empty,
                fallback
        );

        assertThat(result).isEqualTo(fallback);
        verify(filter, never()).add(any());
    }

    @Test
    @DisplayName("getWithSelfHeal - loader 返回 CompletableFuture.completedFuture(null) 时应返回异步 fallback")
    void testGetWithSelfHealShouldFallbackWhenAsyncPayloadIsNull() {
        when(filter.mightContain(eq("key1"))).thenReturn(true);
        CompletableFuture<String> fallback = CompletableFuture.completedFuture("fallback");

        CompletableFuture<String> result = template.getWithSelfHeal(
                "user-bloom",
                "key1",
                () -> CompletableFuture.completedFuture(null),
                fallback
        );

        assertThat(result.join()).isEqualTo("fallback");
        verify(filter, never()).add(any());
    }

    @Test
    @DisplayName("getWithSelfHeal - 布隆过滤器 hit 且 loader 命中，应返回数据并写回")
    @SuppressWarnings("unchecked")
    void testGetWithSelfHealHitAndLoaderHits() {
        when(filter.mightContain(eq("key1"))).thenReturn(true);

        String result = template.getWithSelfHeal(
                "user-bloom",
                "key1",
                () -> "data",
                "fallback"
        );

        assertThat(result).isEqualTo("data");
        verify(filter).add("key1");
    }

    @Test
    @DisplayName("getWithSelfHeal - 布隆过滤器 hit 但 loader 未命中（误判），应返回 fallback")
    void testGetWithSelfHealHitButLoaderMisses() {
        when(filter.mightContain(eq("key1"))).thenReturn(true);

        String result = template.getWithSelfHeal(
                "user-bloom",
                "key1",
                () -> null,
                "fallback"
        );

        assertThat(result).isEqualTo("fallback");
        verify(filter, never()).add(any());
    }

    @Test
    @DisplayName("getWithSelfHeal - loader 为 null 时应 fail-fast")
    void testGetWithSelfHealShouldFailFastWhenLoaderIsNull() {
        assertThatThrownBy(() -> template.getWithSelfHeal("user-bloom", "k1", null, "fallback"))
                .isInstanceOf(BloomFilterException.class)
                .hasMessageContaining("loader must not be null");
        verifyNoInteractions(filter);
    }
}
