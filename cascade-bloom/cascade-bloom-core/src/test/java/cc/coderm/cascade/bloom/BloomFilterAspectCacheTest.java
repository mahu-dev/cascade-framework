package cc.coderm.cascade.bloom;

import cc.coderm.cascade.bloom.annotation.BloomFilter;
import cc.coderm.cascade.bloom.aspect.BloomFilterAspect;
import cc.coderm.cascade.bloom.config.BloomFilterProperties;
import cc.coderm.cascade.bloom.core.BloomFilterManager;
import cc.coderm.cascade.bloom.core.CascadeBloomFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("BloomFilterAspect 表达式缓存测试")
class BloomFilterAspectCacheTest {

    private AnnotationConfigApplicationContext context;
    private BloomFilterAspect aspect;
    private CacheExpressionService service;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigApplicationContext(TestConfig.class);
        aspect = context.getBean(BloomFilterAspect.class);
        service = context.getBean(CacheExpressionService.class);
    }

    @AfterEach
    void tearDown() {
        if (aspect != null) {
            aspect.clearExpressionCache();
        }
        if (context != null) {
            context.close();
        }
    }

    @Test
    @DisplayName("初始状态：表达式缓存与统计均为 0")
    void shouldHaveZeroCacheStatsInitially() {
        assertThat(aspect.getExpressionCacheSize()).isZero();
        assertThat(aspect.getCacheHits()).isZero();
        assertThat(aspect.getCacheMisses()).isZero();
        assertThat(aspect.getEvictions()).isZero();
        assertThat(aspect.getCacheHitRate()).isZero();
    }

    @Test
    @DisplayName("重复调用相同 SpEL：应产生 miss + hit")
    void shouldTrackMissAndHitForRepeatedExpression() {
        assertThat(service.byId("u-1")).isNull();
        assertThat(service.byId("u-2")).isNull();

        assertThat(aspect.getExpressionCacheSize()).isEqualTo(1);
        assertThat(aspect.getCacheMisses()).isEqualTo(1);
        assertThat(aspect.getCacheHits()).isEqualTo(1);
        assertThat(aspect.getEvictions()).isZero();
        assertThat(aspect.getCacheHitRate()).isEqualTo(0.5D);
    }

    @Test
    @DisplayName("超过容量时应按 LRU 淘汰表达式")
    void shouldEvictExpressionByLruWhenCacheFull() {
        assertThat(service.byId("u-1")).isNull();       // miss: #id
        assertThat(service.byPrefixA("u-1")).isNull();  // miss: 'A:' + #id
        assertThat(service.byId("u-2")).isNull();       // hit: #id

        assertThat(service.byPrefixB("u-1")).isNull();  // miss + eviction (evict A)
        assertThat(aspect.getExpressionCacheSize()).isEqualTo(2);
        assertThat(aspect.getCacheMisses()).isEqualTo(3);
        assertThat(aspect.getCacheHits()).isEqualTo(1);
        assertThat(aspect.getEvictions()).isEqualTo(1);

        assertThat(service.byPrefixA("u-3")).isNull();  // A was evicted -> miss + eviction
        assertThat(aspect.getExpressionCacheSize()).isEqualTo(2);
        assertThat(aspect.getCacheMisses()).isEqualTo(4);
        assertThat(aspect.getCacheHits()).isEqualTo(1);
        assertThat(aspect.getEvictions()).isEqualTo(2);
    }

    @Test
    @DisplayName("clearExpressionCache 应重置缓存与统计")
    void shouldResetCacheAndStatsOnClearExpressionCache() {
        assertThat(service.byId("u-1")).isNull();
        assertThat(service.byId("u-2")).isNull();
        assertThat(aspect.getCacheMisses()).isEqualTo(1);
        assertThat(aspect.getCacheHits()).isEqualTo(1);

        aspect.clearExpressionCache();

        assertThat(aspect.getExpressionCacheSize()).isZero();
        assertThat(aspect.getCacheHits()).isZero();
        assertThat(aspect.getCacheMisses()).isZero();
        assertThat(aspect.getEvictions()).isZero();
        assertThat(aspect.getCacheHitRate()).isZero();
    }

    @Test
    @DisplayName("并发解析同一 SpEL 表达式应线程安全且缓存大小稳定")
    void shouldBeThreadSafeUnderConcurrentExpressionReads() throws InterruptedException {
        int threadCount = 8;
        int callsPerThread = 30;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CompletableFuture<?>[] futures = new CompletableFuture<?>[threadCount];

        try {
            for (int i = 0; i < threadCount; i++) {
                final int worker = i;
                futures[i] = CompletableFuture.runAsync(() -> {
                    awaitStartSignal(start);
                    for (int j = 0; j < callsPerThread; j++) {
                        assertThat(service.byId("u-" + worker + "-" + j)).isNull();
                    }
                }, executor);
            }

            start.countDown();
            CompletableFuture.allOf(futures).join();

            assertThat(aspect.getExpressionCacheSize()).isEqualTo(1);
            assertThat(aspect.getCacheMisses()).isEqualTo(1);
            assertThat(aspect.getCacheHits()).isEqualTo((long) threadCount * callsPerThread - 1);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(1, TimeUnit.SECONDS);
        }
    }

    private static void awaitStartSignal(CountDownLatch start) {
        try {
            start.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting start signal", e);
        }
    }

    @Configuration
    @EnableAspectJAutoProxy
    static class TestConfig {

        @Bean
        BloomFilterProperties bloomFilterProperties() {
            BloomFilterProperties properties = new BloomFilterProperties();
            properties.setMaxExpressionCacheSize(2);
            properties.setStrictSpEL(true);
            return properties;
        }

        @Bean
        InMemoryBloomFilterManager bloomFilterManager() {
            return new InMemoryBloomFilterManager();
        }

        @Bean
        BloomFilterAspect bloomFilterAspect(BloomFilterManager bloomFilterManager,
                                            BloomFilterProperties properties) {
            return new BloomFilterAspect(bloomFilterManager, properties);
        }

        @Bean
        CacheExpressionService cacheExpressionService() {
            return new CacheExpressionService();
        }
    }

    static class CacheExpressionService {

        @BloomFilter(name = "cache-bloom", key = "#id")
        public String byId(String id) {
            return "db-" + id;
        }

        @BloomFilter(name = "cache-bloom", key = "'A:' + #id")
        public String byPrefixA(String id) {
            return "db-A-" + id;
        }

        @BloomFilter(name = "cache-bloom", key = "'B:' + #id")
        public String byPrefixB(String id) {
            return "db-B-" + id;
        }
    }

    static class InMemoryBloomFilterManager implements BloomFilterManager {
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

    static class InMemoryBloomFilter implements CascadeBloomFilter<Object> {
        private final String name;
        private final Set<Object> values = ConcurrentHashMap.newKeySet();

        InMemoryBloomFilter(String name) {
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
