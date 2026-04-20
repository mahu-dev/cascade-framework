package cc.coderm.cascade.bloom.initializer;

import cc.coderm.cascade.bloom.config.BloomFilterProperties;
import cc.coderm.cascade.bloom.core.BloomFilterManager;
import cc.coderm.cascade.bloom.core.CascadeBloomFilter;
import cc.coderm.cascade.bloom.exception.BloomFilterInitException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BloomFilterStartupInitializer 初始化行为测试
 */
class BloomFilterStartupInitializerTest {

    private static final Executor DAEMON_ASYNC_EXECUTOR = command -> {
        Thread thread = new Thread(command, "bloom-init-test");
        thread.setDaemon(true);
        thread.start();
    };

    @Test
    void testWaitForInitializationEnabled_ShouldBlockUntilComplete() {
        BloomFilterProperties properties = new BloomFilterProperties();
        properties.setEnabled(true);
        properties.setWaitForInitialization(true);

        InMemoryBloomFilterManager bloomFilterManager = new InMemoryBloomFilterManager();
        AtomicBoolean initializationCompleted = new AtomicBoolean(false);

        List<BloomFilterInitializer> initializers = List.of(new BloomFilterInitializer() {
            @Override
            public String filterName() {
                return "test-filter";
            }

            @Override
            public void initialize(CascadeBloomFilter<String> filter) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                filter.add("warmup");
                initializationCompleted.set(true);
            }
        });

        BloomFilterStartupInitializer initializer =
                new BloomFilterStartupInitializer(
                        bloomFilterManager,
                        properties,
                        initializers,
                        DAEMON_ASYNC_EXECUTOR
                );

        long startTime = System.currentTimeMillis();
        initializer.onApplicationEvent(null);
        long elapsedTime = System.currentTimeMillis() - startTime;

        assertTrue(initializationCompleted.get(), "初始化应该已完成");
        assertTrue(elapsedTime >= 100, "应该等待初始化完成，耗时至少100ms");
        assertTrue(bloomFilterManager.getFilter("test-filter").mightContain("warmup"));
    }

    @Test
    void testWaitForInitializationDisabled_ShouldNotBlock() throws Exception {
        BloomFilterProperties properties = new BloomFilterProperties();
        properties.setEnabled(true);
        properties.setWaitForInitialization(false);

        InMemoryBloomFilterManager bloomFilterManager = new InMemoryBloomFilterManager();
        AtomicBoolean initializationStarted = new AtomicBoolean(false);

        List<BloomFilterInitializer> initializers = List.of(new BloomFilterInitializer() {
            @Override
            public String filterName() {
                return "test-filter";
            }

            @Override
            public void initialize(CascadeBloomFilter<String> filter) {
                initializationStarted.set(true);
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
        });

        BloomFilterStartupInitializer initializer =
                new BloomFilterStartupInitializer(
                        bloomFilterManager,
                        properties,
                        initializers,
                        DAEMON_ASYNC_EXECUTOR
                );

        long startTime = System.currentTimeMillis();
        initializer.onApplicationEvent(null);
        long elapsedTime = System.currentTimeMillis() - startTime;

        assertTrue(elapsedTime < 200, "不应该等待初始化完成，应该快速返回");
        Thread.sleep(100);
        assertTrue(initializationStarted.get(), "初始化应该已启动（异步）");
    }

    @Test
    void testDisabledModule_ShouldSkipInitialization() {
        BloomFilterProperties properties = new BloomFilterProperties();
        properties.setEnabled(false);

        InMemoryBloomFilterManager bloomFilterManager = new InMemoryBloomFilterManager();
        AtomicBoolean initialized = new AtomicBoolean(false);

        List<BloomFilterInitializer> initializers = List.of(new BloomFilterInitializer() {
            @Override
            public String filterName() {
                return "error-filter";
            }

            @Override
            public void initialize(CascadeBloomFilter<String> filter) {
                initialized.set(true);
            }
        });

        BloomFilterStartupInitializer initializer =
                new BloomFilterStartupInitializer(
                        bloomFilterManager,
                        properties,
                        initializers,
                        DAEMON_ASYNC_EXECUTOR
                );

        initializer.onApplicationEvent(null);
        assertFalse(initialized.get(), "模块关闭时不应执行任何初始化器");
        assertTrue(bloomFilterManager.listCachedFilterNames().isEmpty(), "模块关闭时不应访问任何过滤器");
    }

    @Test
    void testEmptyInitializers_ShouldHandleGracefully() {
        BloomFilterProperties properties = new BloomFilterProperties();
        properties.setEnabled(true);
        properties.setWaitForInitialization(true);

        InMemoryBloomFilterManager bloomFilterManager = new InMemoryBloomFilterManager();
        BloomFilterStartupInitializer initializer =
                new BloomFilterStartupInitializer(
                        bloomFilterManager,
                        properties,
                        List.of(),
                        DAEMON_ASYNC_EXECUTOR
                );

        assertDoesNotThrow(() -> initializer.onApplicationEvent(null));
    }

    @Test
    void testWaitForInitializationEnabled_ShouldFailFastWhenInitializerFailed() {
        BloomFilterProperties properties = new BloomFilterProperties();
        properties.setEnabled(true);
        properties.setWaitForInitialization(true);

        InMemoryBloomFilterManager bloomFilterManager = new InMemoryBloomFilterManager();
        List<BloomFilterInitializer> initializers = List.of(
                new BloomFilterInitializer() {
                    @Override
                    public String filterName() {
                        return "ok-filter";
                    }

                    @Override
                    public void initialize(CascadeBloomFilter<String> filter) {
                        filter.add("ok");
                    }
                },
                new BloomFilterInitializer() {
                    @Override
                    public String filterName() {
                        return "broken-filter";
                    }

                    @Override
                    public void initialize(CascadeBloomFilter<String> filter) {
                        throw new IllegalStateException("boom");
                    }
                }
        );

        BloomFilterStartupInitializer initializer =
                new BloomFilterStartupInitializer(
                        bloomFilterManager,
                        properties,
                        initializers,
                        DAEMON_ASYNC_EXECUTOR
                );

        BloomFilterInitException exception =
                assertThrows(BloomFilterInitException.class, () -> initializer.onApplicationEvent(null));

        assertTrue(exception.getMessage().contains("failedTasks=1"));
        assertTrue(exception.getMessage().contains("initializer:broken-filter"));
        assertTrue(bloomFilterManager.getFilter("ok-filter").mightContain("ok"));
    }

    @Test
    void testInitializerFilterName_ShouldAllowTrimmedMatchForPredefinedFilter() {
        BloomFilterProperties properties = new BloomFilterProperties();
        properties.setEnabled(true);
        properties.setWaitForInitialization(true);
        BloomFilterProperties.BloomFilterDefinition definition = new BloomFilterProperties.BloomFilterDefinition();
        definition.setName("ordered-bloom");
        definition.setExpectedInsertions(1000L);
        definition.setFalseProbability(0.03D);
        properties.setFilters(List.of(definition));

        InMemoryBloomFilterManager bloomFilterManager = new InMemoryBloomFilterManager();

        List<BloomFilterInitializer> initializers = List.of(new BloomFilterInitializer() {
            @Override
            public String filterName() {
                return "  ordered-bloom  ";
            }

            @Override
            public void initialize(CascadeBloomFilter<String> filter) {
                filter.add("warmup");
            }
        });

        BloomFilterStartupInitializer initializer =
                new BloomFilterStartupInitializer(
                        bloomFilterManager,
                        properties,
                        initializers,
                        DAEMON_ASYNC_EXECUTOR
                );

        assertDoesNotThrow(() -> initializer.onApplicationEvent(null));
        assertTrue(bloomFilterManager.getFilter("ordered-bloom").mightContain("warmup"));
    }

    @Test
    void testInitializerFilterName_ShouldFailFastOnCaseMismatchWithPredefinedFilter() {
        BloomFilterProperties properties = new BloomFilterProperties();
        properties.setEnabled(true);
        properties.setWaitForInitialization(true);
        BloomFilterProperties.BloomFilterDefinition definition = new BloomFilterProperties.BloomFilterDefinition();
        definition.setName("user-bloom");
        definition.setExpectedInsertions(1000L);
        definition.setFalseProbability(0.03D);
        properties.setFilters(List.of(definition));

        InMemoryBloomFilterManager bloomFilterManager = new InMemoryBloomFilterManager();
        AtomicBoolean initializerCalled = new AtomicBoolean(false);
        List<BloomFilterInitializer> initializers = List.of(new BloomFilterInitializer() {
            @Override
            public String filterName() {
                return "USER-BLOOM";
            }

            @Override
            public void initialize(CascadeBloomFilter<String> filter) {
                initializerCalled.set(true);
            }
        });

        BloomFilterStartupInitializer initializer =
                new BloomFilterStartupInitializer(
                        bloomFilterManager,
                        properties,
                        initializers,
                        DAEMON_ASYNC_EXECUTOR
                );

        BloomFilterInitException exception =
                assertThrows(BloomFilterInitException.class, () -> initializer.onApplicationEvent(null));

        assertFalse(initializerCalled.get(), "大小写不匹配时不应执行初始化逻辑");
        assertTrue(bloomFilterManager.listCachedFilterNames().contains("user-bloom"),
                "预定义过滤器应按配置名称创建");
        assertFalse(bloomFilterManager.listCachedFilterNames().contains("USER-BLOOM"),
                "大小写不匹配时不应创建错误名称过滤器");
        assertTrue(exception.getMessage().contains("initializer:USER-BLOOM"));
        assertTrue(exception.getCause() instanceof IllegalStateException);
        assertTrue(exception.getCause().getMessage().contains("must exactly match predefined filter name"));
        assertTrue(exception.getCause().getMessage().contains("initializerName=[USER-BLOOM]"));
        assertTrue(exception.getCause().getMessage().contains("predefinedName=[user-bloom]"));
    }

    @Test
    void testWaitForInitializationEnabled_ShouldTimeoutInsteadOfBlockingForever() throws Exception {
        BloomFilterProperties properties = new BloomFilterProperties();
        properties.setEnabled(true);
        properties.setWaitForInitialization(true);
        properties.setInitializationWaitTimeout(Duration.ofMillis(120));

        InMemoryBloomFilterManager bloomFilterManager = new InMemoryBloomFilterManager();
        CountDownLatch initializerStarted = new CountDownLatch(1);

        List<BloomFilterInitializer> initializers = List.of(new BloomFilterInitializer() {
            @Override
            public String filterName() {
                return "timeout-filter";
            }

            @Override
            public void initialize(CascadeBloomFilter<String> filter) {
                initializerStarted.countDown();
                try {
                    Thread.sleep(10_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });

        BloomFilterStartupInitializer initializer =
                new BloomFilterStartupInitializer(
                        bloomFilterManager,
                        properties,
                        initializers,
                        DAEMON_ASYNC_EXECUTOR
                );

        long startNanos = System.nanoTime();
        BloomFilterInitException exception =
                assertThrows(BloomFilterInitException.class, () -> initializer.onApplicationEvent(null));
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

        assertTrue(initializerStarted.await(200, TimeUnit.MILLISECONDS), "初始化任务应该被提交执行");
        assertTrue(elapsedMillis < 2_000, "应在超时后快速失败，避免永久阻塞");
        assertTrue(exception.getMessage().contains("timed out after"));
        assertTrue(exception.getMessage().contains("initialization-wait-timeout"));
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
