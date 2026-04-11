package cc.coderm.cascade.bloom;

import cc.coderm.cascade.bloom.core.BloomFilterManager;
import cc.coderm.cascade.bloom.core.CascadeBloomFilter;
import cc.coderm.cascade.bloom.impl.DefaultBloomFilterTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class BloomFilterTemplateWriteBackSelfHealTest {

    @Test
    @DisplayName("getWithSelfHeal - Bloom miss + loader 命中：应返回真实数据并回填布隆过滤器")
    void shouldSelfHealWhenBloomMissButLoaderHits() {
        InMemoryBloomFilterManager manager = new InMemoryBloomFilterManager();
        DefaultBloomFilterTemplate template = new DefaultBloomFilterTemplate(manager);

        String result = template.getWithSelfHeal(
                "user-bloom",
                "user:1001",
                () -> "user-data",
                "fallback"
        );

        assertThat(result).isEqualTo("user-data");
        assertThat(manager.getFilter("user-bloom").mightContain("user:1001")).isTrue();
    }

    @Test
    @DisplayName("getWithSelfHeal - Bloom miss + loader 未命中：应返回 fallback 且不回填")
    void shouldReturnFallbackWhenBloomMissAndLoaderMisses() {
        InMemoryBloomFilterManager manager = new InMemoryBloomFilterManager();
        DefaultBloomFilterTemplate template = new DefaultBloomFilterTemplate(manager);

        String result = template.getWithSelfHeal(
                "user-bloom",
                "user:404",
                () -> null,
                "fallback"
        );

        assertThat(result).isEqualTo("fallback");
        assertThat(manager.getFilter("user-bloom").mightContain("user:404")).isFalse();
    }

    @Test
    @DisplayName("getWithSelfHeal - Bloom hit + loader 未命中（误判）：返回 fallback")
    void shouldReturnFallbackWhenBloomHitsButLoaderMisses() {
        InMemoryBloomFilterManager manager = new InMemoryBloomFilterManager();
        manager.getFilter("user-bloom").add("user:1002");
        DefaultBloomFilterTemplate template = new DefaultBloomFilterTemplate(manager);

        String result = template.getWithSelfHeal(
                "user-bloom",
                "user:1002",
                () -> null,
                "fallback"
        );

        assertThat(result).isEqualTo("fallback");
    }

    @Test
    @DisplayName("getWithBloomGuard - Bloom hit + loader 命中：应返回数据并回填")
    void shouldWriteBackWhenBloomHitsAndLoaderHits() {
        InMemoryBloomFilterManager manager = new InMemoryBloomFilterManager();
        manager.getFilter("user-bloom").add("user:1003");
        DefaultBloomFilterTemplate template = new DefaultBloomFilterTemplate(manager);

        String result = template.getWithBloomGuard(
                "user-bloom",
                "user:1003",
                () -> "user-data",
                "fallback"
        );

        assertThat(result).isEqualTo("user-data");
    }

    @Test
    @DisplayName("getWithBloomGuard - Bloom miss：应直接返回 fallback，不执行 loader")
    void shouldReturnFallbackWithoutExecutingLoader() {
        InMemoryBloomFilterManager manager = new InMemoryBloomFilterManager();
        DefaultBloomFilterTemplate template = new DefaultBloomFilterTemplate(manager);

        String result = template.getWithBloomGuard(
                "user-bloom",
                "user:9999",
                () -> {
                    throw new RuntimeException("loader should not be called");
                },
                "fallback"
        );

        assertThat(result).isEqualTo("fallback");
        assertThat(manager.getFilter("user-bloom").mightContain("user:9999")).isFalse();
    }

    @Test
    @DisplayName("Template add 应统一按字符串 key 写入")
    void shouldCanonicalizeKeyWhenAdding() {
        InMemoryBloomFilterManager manager = new InMemoryBloomFilterManager();
        DefaultBloomFilterTemplate template = new DefaultBloomFilterTemplate(manager);

        template.add("user-bloom", 1001L);

        assertThat(manager.getFilter("user-bloom").mightContain("1001")).isTrue();
        assertThat(manager.getFilter("user-bloom").mightContain(1001L)).isFalse();
    }

    @Test
    @DisplayName("Template mightContain 应统一按字符串 key 查询")
    void shouldCanonicalizeKeyWhenCheckingContainment() {
        InMemoryBloomFilterManager manager = new InMemoryBloomFilterManager();
        manager.getFilter("user-bloom").add("1002");
        DefaultBloomFilterTemplate template = new DefaultBloomFilterTemplate(manager);

        assertThat(template.mightContain("user-bloom", 1002L)).isTrue();
    }

    private static final class InMemoryBloomFilterManager implements BloomFilterManager {
        private final ConcurrentHashMap<String, CascadeBloomFilter<Object>> filters = new ConcurrentHashMap<>();

        @SuppressWarnings("unchecked")
        @Override
        public <T> CascadeBloomFilter<T> getFilter(String name) {
            return (CascadeBloomFilter<T>) filters.computeIfAbsent(name, ignored -> new InMemoryBloomFilter());
        }

        @Override
        public <T> CascadeBloomFilter<T> getOrCreate(String name, long expectedInsertions, double falseProbability) {
            return getFilter(name);
        }

        @Override
        public boolean exists(String name) {
            return filters.containsKey(name);
        }

        @Override
        public void remove(String name) {
            filters.remove(name);
        }

        @Override
        public Set<String> listFilterNames() {
            return filters.keySet();
        }
    }

    private static final class InMemoryBloomFilter implements CascadeBloomFilter<Object> {
        private final Set<Object> values = ConcurrentHashMap.newKeySet();

        @Override
        public boolean add(Object value) {
            return values.add(value);
        }

        @Override
        public void addAll(Collection<Object> values) {
            this.values.addAll(values);
        }

        @Override
        public boolean mightContain(Object value) {
            return values.contains(value);
        }

        @Override
        public Map<Object, Boolean> mightContainAll(Collection<Object> values) {
            ConcurrentHashMap<Object, Boolean> result = new ConcurrentHashMap<>();
            values.forEach(v -> result.put(v, mightContain(v)));
            return result;
        }

        @Override
        public String getName() {
            return "in-memory";
        }

        @Override
        public long getExpectedInsertions() {
            return 0;
        }

        @Override
        public double getFalseProbability() {
            return 0;
        }

        @Override
        public long count() {
            return values.size();
        }

        @Override
        public boolean isExists() {
            return true;
        }

        @Override
        public void delete() {
            values.clear();
        }
    }
}
