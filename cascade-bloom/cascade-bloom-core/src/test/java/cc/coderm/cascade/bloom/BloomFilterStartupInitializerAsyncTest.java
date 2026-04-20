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
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class BloomFilterStartupInitializerAsyncTest {

    private static final Executor DAEMON_ASYNC_EXECUTOR = command -> {
        Thread thread = new Thread(command, "bloom-init-async-test");
        thread.setDaemon(true);
        thread.start();
    };

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
                new BloomFilterStartupInitializer(
                        manager,
                        properties,
                        List.of(slowInitializer),
                        DAEMON_ASYNC_EXECUTOR
                );

        long startNanos = System.nanoTime();
        startupInitializer.onApplicationEvent(null);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

        assertThat(elapsedMillis).isLessThan(300);
        assertThat(initializerStarted.await(200, TimeUnit.MILLISECONDS)).isTrue();
        assertThat(initializerFinished.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(manager.getFilter("slow-bloom").mightContain("warmup-done")).isTrue();
    }

    @Test
    @DisplayName("waitForInitialization=false 且初始化失败时，不应阻塞或抛出启动异常")
    void shouldNotFailStartupWhenAsyncInitializerThrows() throws Exception {
        BloomFilterProperties properties = new BloomFilterProperties();
        properties.setEnabled(true);
        properties.setWaitForInitialization(false);

        InMemoryBloomFilterManager manager = new InMemoryBloomFilterManager();
        CountDownLatch started = new CountDownLatch(1);

        BloomFilterInitializer failingInitializer = new BloomFilterInitializer() {
            @Override
            public String filterName() {
                return "broken-bloom";
            }

            @Override
            public void initialize(CascadeBloomFilter<String> filter) {
                started.countDown();
                throw new IllegalStateException("async init failed");
            }
        };

        BloomFilterStartupInitializer startupInitializer =
                new BloomFilterStartupInitializer(
                        manager,
                        properties,
                        List.of(failingInitializer),
                        DAEMON_ASYNC_EXECUTOR
                );

        long startNanos = System.nanoTime();
        startupInitializer.onApplicationEvent(null);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

        assertThat(elapsedMillis).isLessThan(300);
        assertThat(started.await(500, TimeUnit.MILLISECONDS)).isTrue();
    }

    @Test
    @DisplayName("waitForInitialization=false 时预定义过滤器初始化应异步且先于自定义初始化器阶段")
    void shouldInitializePredefinedFiltersAsyncAndBeforeCustomInitializers() throws Exception {
        BloomFilterProperties properties = new BloomFilterProperties();
        properties.setEnabled(true);
        properties.setWaitForInitialization(false);
        BloomFilterProperties.BloomFilterDefinition definition = new BloomFilterProperties.BloomFilterDefinition();
        definition.setName("ordered-bloom");
        definition.setExpectedInsertions(10_000L);
        definition.setFalseProbability(0.01D);
        properties.setFilters(List.of(definition));

        CountDownLatch predefinedStarted = new CountDownLatch(1);
        CountDownLatch predefinedFinished = new CountDownLatch(1);
        CountDownLatch initializerStarted = new CountDownLatch(1);
        AtomicBoolean initializerSawPredefinedReady = new AtomicBoolean(false);

        InMemoryBloomFilterManager manager = new InMemoryBloomFilterManager() {
            @Override
            public <T> CascadeBloomFilter<T> getOrCreate(String name, long expectedInsertions, double falseProbability) {
                predefinedStarted.countDown();
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                CascadeBloomFilter<T> filter = super.getOrCreate(name, expectedInsertions, falseProbability);
                predefinedFinished.countDown();
                return filter;
            }
        };

        BloomFilterInitializer initializer = new BloomFilterInitializer() {
            @Override
            public String filterName() {
                return "ordered-bloom";
            }

            @Override
            public void initialize(CascadeBloomFilter<String> filter) {
                initializerStarted.countDown();
                initializerSawPredefinedReady.set(predefinedFinished.getCount() == 0);
                filter.add("warmup-done");
            }
        };

        BloomFilterStartupInitializer startupInitializer =
                new BloomFilterStartupInitializer(
                        manager,
                        properties,
                        List.of(initializer),
                        DAEMON_ASYNC_EXECUTOR
                );

        long startNanos = System.nanoTime();
        startupInitializer.onApplicationEvent(null);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

        assertThat(elapsedMillis).isLessThan(300);
        assertThat(predefinedStarted.await(300, TimeUnit.MILLISECONDS)).isTrue();
        assertThat(initializerStarted.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(initializerSawPredefinedReady.get()).isTrue();
        assertThat(manager.getFilter("ordered-bloom").mightContain("warmup-done")).isTrue();
    }

    private static class InMemoryBloomFilterManager implements BloomFilterManager {
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
        public boolean existsInRedis(String name) {
            return exists(name);
        }

        @Override
        public void remove(String name) {
            filters.remove(name);
        }

        @Override
        public Set<String> listCachedFilterNames() {
            return filters.keySet();
        }

        @Override
        public Set<String> listRegisteredFilterNames() {
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
