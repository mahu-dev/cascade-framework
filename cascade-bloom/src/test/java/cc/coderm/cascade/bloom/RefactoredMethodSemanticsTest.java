package cc.coderm.cascade.bloom;

import cc.coderm.cascade.bloom.core.BloomFilterManager;
import cc.coderm.cascade.bloom.core.BloomFilterTemplate;
import cc.coderm.cascade.bloom.core.CascadeBloomFilter;
import cc.coderm.cascade.bloom.impl.DefaultBloomFilterTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证重构后的方法语义清晰性
 *
 * @author lionel lionelk@163.com
 * =============================
 * Date: 2026-04-11
 * Time: 00:02:00
 * =============================
 */
@DisplayName("重构验证：方法语义清晰性测试")
class RefactoredMethodSemanticsTest {

    @Test
    @DisplayName("getWithBloomGuard - Bloom miss时不应该执行loader（性能优先）")
    void shouldNotExecuteLoaderOnBloomMiss() {
        InMemoryBloomFilterManager manager = new InMemoryBloomFilterManager();
        DefaultBloomFilterTemplate template = new DefaultBloomFilterTemplate(manager);

        AtomicBoolean loaderExecuted = new AtomicBoolean(false);

        String result = template.getWithBloomGuard(
                "user-bloom",
                "missing-key",
                () -> {
                    loaderExecuted.set(true);
                    return "data";
                },
                "fallback"
        );

        assertThat(result).isEqualTo("fallback");
        assertThat(loaderExecuted.get()).isFalse();
    }

    @Test
    @DisplayName("getWithBloomGuard - Bloom hit时执行loader并写回")
    void shouldExecuteLoaderOnBloomHit() {
        InMemoryBloomFilterManager manager = new InMemoryBloomFilterManager();
        manager.getFilter("user-bloom").add("existing-key");
        DefaultBloomFilterTemplate template = new DefaultBloomFilterTemplate(manager);

        AtomicInteger loaderExecuted = new AtomicInteger(0);

        String result = template.getWithBloomGuard(
                "user-bloom",
                "existing-key",
                () -> {
                    loaderExecuted.incrementAndGet();
                    return "data";
                },
                "fallback"
        );

        assertThat(result).isEqualTo("data");
        assertThat(loaderExecuted.get()).isEqualTo(1);
        assertThat(manager.getFilter("user-bloom").mightContain("existing-key")).isTrue();
    }

    @Test
    @DisplayName("getWithSelfHeal - 总是执行loader（自愈模式）")
    void shouldAlwaysExecuteLoader() {
        InMemoryBloomFilterManager manager = new InMemoryBloomFilterManager();
        DefaultBloomFilterTemplate template = new DefaultBloomFilterTemplate(manager);

        AtomicInteger loaderExecuted = new AtomicInteger(0);

        String result = template.getWithSelfHeal(
                "user-bloom",
                "any-key",
                () -> {
                    loaderExecuted.incrementAndGet();
                    return "data";
                },
                "fallback"
        );

        assertThat(result).isEqualTo("data");
        assertThat(loaderExecuted.get()).isEqualTo(1);
        assertThat(manager.getFilter("user-bloom").mightContain("any-key")).isTrue();
    }

    @Test
    @DisplayName("getWithSelfHeal - Bloom miss但loader命中时自愈")
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
