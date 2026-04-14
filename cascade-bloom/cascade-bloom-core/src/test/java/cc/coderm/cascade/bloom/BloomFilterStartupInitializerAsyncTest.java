package cc.coderm.cascade.bloom;

import cc.coderm.cascade.bloom.config.BloomFilterProperties;
import cc.coderm.cascade.bloom.core.BloomFilterManager;
import cc.coderm.cascade.bloom.core.CascadeBloomFilter;
import cc.coderm.cascade.bloom.initializer.BloomFilterInitializer;
import cc.coderm.cascade.bloom.initializer.BloomFilterStartupInitializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class BloomFilterStartupInitializerAsyncTest {

    @Test
    @DisplayName("runInitializers 应异步提交，不阻塞 ApplicationReady 线程")
    void shouldNotBlockApplicationReadyThread() throws Exception {
        BloomFilterProperties properties = new BloomFilterProperties();
        properties.setEnabled(true);
        properties.setWaitForInitialization(false);  // 明确设置为异步模式

        InMemoryBloomFilterManager manager = new InMemoryBloomFilterManager();
        CountDownLatch initializerStarted = new CountDownLatch(1);
        CountDownLatch initializerFinished = new CountDownLatch(1);

        BloomFilterInitializer slowInitializer = new BloomFilterInitializer() {
            @Override
            public String filterName() {
                return "slow-bloom";
            }

            @Override
            public void initialize(CascadeBloomFilter<String> filter) {
                initializerStarted.countDown();
                try {
                    Thread.sleep(800);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                filter.add("warmup-done");
                initializerFinished.countDown();
            }
        };

        BloomFilterStartupInitializer startupInitializer =
                new BloomFilterStartupInitializer(manager, properties, List.of(slowInitializer));

        long startNanos = System.nanoTime();
        startupInitializer.onApplicationEvent(null);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

        assertThat(elapsedMillis).isLessThan(300);
        assertThat(initializerStarted.await(200, TimeUnit.MILLISECONDS)).isTrue();
        assertThat(initializerFinished.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(manager.getFilter("slow-bloom").mightContain("warmup-done")).isTrue();
    }

    private static final class InMemoryBloomFilterManager implements BloomFilterManager {
        private final ConcurrentHashMap<String, CascadeBloomFilter<Object>> filters = new ConcurrentHashMap<>();

        @SuppressWarnings("unchecked")
        @Override
        public <T> CascadeBloomFilter<T> getFilter(String name) {
            return (CascadeBloomFilter<T>) filters.computeIfAbsent(name, InMemoryBloomFilter::new);
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
        private final String name;
        private final Set<Object> values = ConcurrentHashMap.newKeySet();

        private InMemoryBloomFilter(String name) {
            this.name = name;
        }

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
            Map<Object, Boolean> result = new ConcurrentHashMap<>();
            values.forEach(v -> result.put(v, mightContain(v)));
            return result;
        }

        @Override
        public String getName() {
            return name;
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
