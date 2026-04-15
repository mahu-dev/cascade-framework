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
