package cc.coderm.cascade.bloom;

import cc.coderm.cascade.bloom.util.BloomFilterKeyUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("BloomFilterKeyUtil 数组键标准化测试")
class BloomFilterKeyUtilTest {

    @Test
    @DisplayName("toKey - 基本类型数组应按内容稳定序列化")
    void shouldSerializePrimitiveArrayByContent() {
        int[] left = {1, 2, 3};
        int[] right = {1, 2, 3};

        assertThat(BloomFilterKeyUtil.toKey(left)).isEqualTo("[1,2,3]");
        assertThat(BloomFilterKeyUtil.toKey(right)).isEqualTo("[1,2,3]");
    }

    @Test
    @DisplayName("toKey - 对象数组与嵌套数组应按内容稳定序列化")
    void shouldSerializeNestedObjectArrayByContent() {
        Object[] left = {"u-1", new long[]{7L, 8L}, null};
        Object[] right = {"u-1", new long[]{7L, 8L}, null};

        assertThat(BloomFilterKeyUtil.toKey(left)).isEqualTo("[u-1,[7,8],null]");
        assertThat(BloomFilterKeyUtil.toKey(right)).isEqualTo("[u-1,[7,8],null]");
    }

    @Test
    @DisplayName("toKey - 数组内容不同应产生不同 key")
    void shouldDifferentiateArrayContent() {
        assertThat(BloomFilterKeyUtil.toKey(new String[]{"a", "b"}))
                .isNotEqualTo(BloomFilterKeyUtil.toKey(new String[]{"a", "c"}));
    }

    @Test
    @DisplayName("buildKey - 数组片段应复用 toKey 标准化规则")
    void shouldBuildCompositeKeyWithArrayPart() {
        String key1 = BloomFilterKeyUtil.buildKey("user", new int[]{10, 20}, "order");
        String key2 = BloomFilterKeyUtil.buildKey("user", new int[]{10, 20}, "order");

        assertThat(key1).isEqualTo("user:[10,20]:order");
        assertThat(key2).isEqualTo("user:[10,20]:order");
    }

    @Test
    @DisplayName("toKey - 循环数组引用应快速失败")
    void shouldFailFastOnCyclicArrayReference() {
        Object[] cyclic = new Object[1];
        cyclic[0] = cyclic;

        assertThatThrownBy(() -> BloomFilterKeyUtil.toKey(cyclic))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cyclic array reference");
    }
}
