package cc.coderm.cascade.bloom;

import cc.coderm.cascade.bloom.annotation.BloomFilter;
import cc.coderm.cascade.bloom.aspect.BloomFilterAspect;
import cc.coderm.cascade.bloom.config.BloomFilterProperties;
import cc.coderm.cascade.bloom.core.BloomFilterManager;
import cc.coderm.cascade.bloom.core.CascadeBloomFilter;
import cc.coderm.cascade.bloom.exception.BloomFilterException;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BloomFilterAspectAutoRefreshTest {

    private AnnotationConfigApplicationContext context;
    private AnnotationTestService service;
    private InMemoryBloomFilterManager manager;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigApplicationContext(TestConfig.class);
        service = context.getBean(AnnotationTestService.class);
        manager = context.getBean(InMemoryBloomFilterManager.class);
    }

    @AfterEach
    void tearDown() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    @DisplayName("默认注解路径：Bloom miss 时应短路 fallback，不触发方法调用")
    void shouldShortCircuitOnMissByDefault() {
        String value = service.strictGet("u-1");

        assertThat(value).isEqualTo("fallback");
        assertThat(service.getStrictCalls()).isEqualTo(0);
        assertThat(manager.getFilter("strict-bloom").mightContain("u-1")).isFalse();
    }

    @Test
    @DisplayName("开启 autoRefreshOnAbsent：Bloom miss 但源存在时应返回真实值并回填")
    void shouldRefreshAndWriteBackOnAbsentWhenEnabled() {
        String value = service.refreshableGet("u-2");

        assertThat(value).isEqualTo("db-u-2");
        assertThat(service.getRefreshableCalls()).isEqualTo(1);
        assertThat(manager.getFilter("refresh-bloom").mightContain("u-2")).isTrue();
    }

    @Test
    @DisplayName("开启 autoRefreshOnAbsent：Bloom miss 且源不存在时应返回 fallback")
    void shouldReturnFallbackWhenRefreshFindsNothing() {
        String value = service.refreshableGet("missing");

        assertThat(value).isEqualTo("fallback");
        assertThat(service.getRefreshableCalls()).isEqualTo(1);
        assertThat(manager.getFilter("refresh-bloom").mightContain("missing")).isFalse();
    }

    @Test
    @DisplayName("writeBackOnSuccess=false：即使源存在也不应回填")
    void shouldSkipWriteBackWhenDisabled() {
        String value = service.refreshNoWriteBack("u-3");

        assertThat(value).isEqualTo("db-u-3");
        assertThat(service.getNoWriteBackCalls()).isEqualTo(1);
        assertThat(manager.getFilter("no-write-back-bloom").mightContain("u-3")).isFalse();
    }

    @Test
    @DisplayName("开启 autoRefreshOnAbsent + throwOnAbsent：源不存在时应抛异常")
    void shouldThrowWhenRefreshMissesAndThrowEnabled() {
        assertThatThrownBy(() -> service.throwingGet("u-404"))
                .isInstanceOf(BloomFilterException.class)
                .hasMessage("NOT_FOUND");
        assertThat(service.getThrowingCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("key 表达式解析结果为 null 时应快速失败，不再降级为空字符串")
    void shouldFailFastWhenResolvedKeyIsNull() {
        assertThatThrownBy(() -> service.strictGet(null))
                .isInstanceOf(BloomFilterException.class)
                .hasMessageContaining("resolved to null");
        assertThat(service.getStrictCalls()).isEqualTo(0);
    }

    @Test
    @DisplayName("兼容模式下 key 解析失败且 args[0]=null 时也应快速失败")
    void shouldFailFastWhenFallbackArgIsNullInCompatMode() {
        AnnotationConfigApplicationContext compatContext = new AnnotationConfigApplicationContext(CompatConfig.class);
        try {
            CompatAnnotationTestService compatService = compatContext.getBean(CompatAnnotationTestService.class);
            assertThatThrownBy(() -> compatService.invalidExpression(null))
                    .isInstanceOf(BloomFilterException.class)
                    .hasMessageContaining("resolved to null")
                    .hasMessageContaining("args[0]");
            assertThat(compatService.getCalls()).isEqualTo(0);
        } finally {
            compatContext.close();
        }
    }

    @Configuration
    @EnableAspectJAutoProxy
    static class TestConfig {

        @Bean
        InMemoryBloomFilterManager bloomFilterManager() {
            return new InMemoryBloomFilterManager();
        }

        @Bean
        BloomFilterAspect bloomFilterAspect(BloomFilterManager bloomFilterManager) {
            return new BloomFilterAspect(bloomFilterManager, new BloomFilterProperties());
        }

        @Bean
        AnnotationTestService annotationTestService() {
            return new AnnotationTestService();
        }
    }

    @Configuration
    @EnableAspectJAutoProxy
    static class CompatConfig {

        @Bean
        InMemoryBloomFilterManager bloomFilterManager() {
            return new InMemoryBloomFilterManager();
        }

        @Bean
        BloomFilterAspect bloomFilterAspect(BloomFilterManager bloomFilterManager) {
            BloomFilterProperties properties = new BloomFilterProperties();
            properties.setStrictSpEL(false);
            return new BloomFilterAspect(bloomFilterManager, properties);
        }

        @Bean
        CompatAnnotationTestService compatAnnotationTestService() {
            return new CompatAnnotationTestService();
        }
    }

    static class AnnotationTestService {
        final AtomicInteger strictCalls = new AtomicInteger();
        final AtomicInteger refreshableCalls = new AtomicInteger();
        final AtomicInteger noWriteBackCalls = new AtomicInteger();
        final AtomicInteger throwingCalls = new AtomicInteger();

        @BloomFilter(name = "strict-bloom", key = "#id", fallbackValue = "'fallback'")
        public String strictGet(String id) {
            strictCalls.incrementAndGet();
            return "db-" + id;
        }

        @BloomFilter(name = "refresh-bloom", key = "#id", fallbackValue = "'fallback'", autoRefreshOnAbsent = true)
        public String refreshableGet(String id) {
            refreshableCalls.incrementAndGet();
            if ("missing".equals(id)) {
                return null;
            }
            return "db-" + id;
        }

        @BloomFilter(name = "no-write-back-bloom",
                key = "#id",
                fallbackValue = "'fallback'",
                autoRefreshOnAbsent = true,
                writeBackOnSuccess = false)
        public String refreshNoWriteBack(String id) {
            noWriteBackCalls.incrementAndGet();
            return "db-" + id;
        }

        @BloomFilter(name = "throw-bloom", key = "#id", autoRefreshOnAbsent = true, throwOnAbsent = true, message = "NOT_FOUND")
        public String throwingGet(String id) {
            throwingCalls.incrementAndGet();
            return null;
        }

        public int getStrictCalls() {
            return strictCalls.get();
        }

        public int getRefreshableCalls() {
            return refreshableCalls.get();
        }

        public int getNoWriteBackCalls() {
            return noWriteBackCalls.get();
        }

        public int getThrowingCalls() {
            return throwingCalls.get();
        }
    }

    static class CompatAnnotationTestService {
        final AtomicInteger calls = new AtomicInteger();

        @BloomFilter(name = "compat-bloom", key = "#id +", fallbackValue = "'fallback'")
        public String invalidExpression(String id) {
            calls.incrementAndGet();
            return "db-" + id;
        }

        public int getCalls() {
            return calls.get();
        }
    }

    static class InMemoryBloomFilterManager implements BloomFilterManager {
        private final ConcurrentHashMap<String, CascadeBloomFilter<Object>> filters = new ConcurrentHashMap<>();

        @SuppressWarnings("unchecked")
        @Override
        public <T> CascadeBloomFilter<T> getFilter(String name) {
            return (CascadeBloomFilter<T>) filters.computeIfAbsent(name, ignored -> new InMemoryBloomFilter(name));
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
            ConcurrentHashMap<Object, Boolean> result = new ConcurrentHashMap<>();
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
