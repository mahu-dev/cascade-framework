package cc.coderm.cascade.bloom;

import cc.coderm.cascade.bloom.config.BloomFilterProperties;
import cc.coderm.cascade.bloom.core.CascadeBloomFilter;
import cc.coderm.cascade.bloom.impl.RedissonBloomFilterManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RedissonBloomFilterManager 并发创建行为测试")
class RedissonBloomFilterManagerConcurrencyTest {

    @Test
    @DisplayName("不同 key 的过滤器创建应并行执行，不应被全局锁串行化")
    void shouldCreateDifferentFiltersInParallel() throws InterruptedException {
        CountDownLatch tryInitEntered = new CountDownLatch(2);
        CountDownLatch releaseTryInit = new CountDownLatch(1);
        AtomicInteger activeInitCalls = new AtomicInteger(0);
        AtomicInteger maxConcurrentInitCalls = new AtomicInteger(0);
        RedissonClient redissonClient = createRedissonClientProxy(() -> createBloomFilterProxy(
                () -> {
                    int active = activeInitCalls.incrementAndGet();
                    maxConcurrentInitCalls.accumulateAndGet(active, Math::max);
                    tryInitEntered.countDown();
                    try {
                        releaseTryInit.await(3, TimeUnit.SECONDS);
                    } finally {
                        activeInitCalls.decrementAndGet();
                    }
                    return true;
                }
        ));

        BloomFilterProperties properties = new BloomFilterProperties();
        RedissonBloomFilterManager manager = new RedissonBloomFilterManager(redissonClient, properties);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<CascadeBloomFilter<?>> f1 = CompletableFuture.supplyAsync(
                    () -> manager.getFilter("parallel-a"), executor);
            CompletableFuture<CascadeBloomFilter<?>> f2 = CompletableFuture.supplyAsync(
                    () -> manager.getFilter("parallel-b"), executor);

            boolean bothEntered = tryInitEntered.await(1, TimeUnit.SECONDS);
            releaseTryInit.countDown();

            CascadeBloomFilter<?> filter1 = f1.join();
            CascadeBloomFilter<?> filter2 = f2.join();

            assertThat(bothEntered).isTrue();
            assertThat(filter1).isNotNull();
            assertThat(filter2).isNotNull();
            assertThat(maxConcurrentInitCalls.get()).isGreaterThanOrEqualTo(2);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(1, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("同 key 并发请求应只触发一次 Redis 初始化")
    void shouldDeduplicateConcurrentCreationForSameKey() throws InterruptedException {
        CountDownLatch firstInitEntered = new CountDownLatch(1);
        CountDownLatch releaseTryInit = new CountDownLatch(1);
        AtomicInteger getBloomFilterCalls = new AtomicInteger(0);
        AtomicInteger tryInitCalls = new AtomicInteger(0);
        RedissonClient redissonClient = createRedissonClientProxy(() -> {
            getBloomFilterCalls.incrementAndGet();
            return createBloomFilterProxy(() -> {
                tryInitCalls.incrementAndGet();
                firstInitEntered.countDown();
                releaseTryInit.await(3, TimeUnit.SECONDS);
                return true;
            });
        });

        BloomFilterProperties properties = new BloomFilterProperties();
        RedissonBloomFilterManager manager = new RedissonBloomFilterManager(redissonClient, properties);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<CascadeBloomFilter<?>> f1 = CompletableFuture.supplyAsync(
                    () -> manager.getFilter("same-key"), executor);
            CompletableFuture<CascadeBloomFilter<?>> f2 = CompletableFuture.supplyAsync(
                    () -> manager.getFilter("same-key"), executor);

            assertThat(firstInitEntered.await(1, TimeUnit.SECONDS)).isTrue();
            releaseTryInit.countDown();

            CascadeBloomFilter<?> filter1 = f1.join();
            CascadeBloomFilter<?> filter2 = f2.join();

            assertThat(filter1).isSameAs(filter2);
            assertThat(getBloomFilterCalls.get()).isEqualTo(1);
            assertThat(tryInitCalls.get()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(1, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("remove 与 in-flight get 并发时，新世代 get 不应卡在旧世代创建上")
    void shouldAllowNewEpochCreationAfterRemoveWhenOldCreationStillInFlight() throws InterruptedException {
        CountDownLatch firstInitEntered = new CountDownLatch(1);
        CountDownLatch secondInitEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstInit = new CountDownLatch(1);
        CountDownLatch releaseSecondInit = new CountDownLatch(1);
        AtomicInteger tryInitCalls = new AtomicInteger(0);
        AtomicInteger deleteCalls = new AtomicInteger(0);

        RedissonClient redissonClient = createRedissonClientProxy(() -> createBloomFilterProxy(() -> {
            int current = tryInitCalls.incrementAndGet();
            if (current == 1) {
                firstInitEntered.countDown();
                releaseFirstInit.await(3, TimeUnit.SECONDS);
                return true;
            }
            if (current == 2) {
                secondInitEntered.countDown();
                releaseSecondInit.await(3, TimeUnit.SECONDS);
                return true;
            }
            return true;
        }, deleteCalls));

        BloomFilterProperties properties = new BloomFilterProperties();
        RedissonBloomFilterManager manager = new RedissonBloomFilterManager(redissonClient, properties);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<CascadeBloomFilter<?>> firstGet = CompletableFuture.supplyAsync(
                    () -> manager.getFilter("epoch-race"), executor);

            assertThat(firstInitEntered.await(1, TimeUnit.SECONDS)).isTrue();

            manager.remove("epoch-race");

            CompletableFuture<CascadeBloomFilter<?>> secondGet = CompletableFuture.supplyAsync(
                    () -> manager.getFilter("epoch-race"), executor);

            assertThat(secondInitEntered.await(1, TimeUnit.SECONDS)).isTrue();

            releaseSecondInit.countDown();
            releaseFirstInit.countDown();

            CascadeBloomFilter<?> firstResult = firstGet.join();
            CascadeBloomFilter<?> secondResult = secondGet.join();

            assertThat(firstResult).isSameAs(secondResult);
            assertThat(tryInitCalls.get()).isEqualTo(2);
            assertThat(deleteCalls.get()).isGreaterThanOrEqualTo(1);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(1, TimeUnit.SECONDS);
        }
    }

    @SuppressWarnings("unchecked")
    private static RedissonClient createRedissonClientProxy(BloomFilterSupplier bloomFilterSupplier) {
        InvocationHandler handler = (proxy, method, args) -> {
            if ("getBloomFilter".equals(method.getName())) {
                return bloomFilterSupplier.get();
            }
            if ("toString".equals(method.getName())) {
                return "RedissonClientProxy";
            }
            if ("hashCode".equals(method.getName())) {
                return System.identityHashCode(proxy);
            }
            if ("equals".equals(method.getName())) {
                return proxy == args[0];
            }
            return defaultValue(method);
        };
        return (RedissonClient) Proxy.newProxyInstance(
                RedissonClient.class.getClassLoader(),
                new Class<?>[]{RedissonClient.class},
                handler
        );
    }

    @SuppressWarnings("unchecked")
    private static RBloomFilter<String> createBloomFilterProxy(TryInitSupplier tryInitSupplier) {
        return createBloomFilterProxy(tryInitSupplier, new AtomicInteger(0));
    }

    @SuppressWarnings("unchecked")
    private static RBloomFilter<String> createBloomFilterProxy(TryInitSupplier tryInitSupplier,
                                                                AtomicInteger deleteCalls) {
        InvocationHandler handler = (proxy, method, args) -> {
            String name = method.getName();
            if ("tryInit".equals(name)) {
                return tryInitSupplier.get();
            }
            if ("getExpectedInsertions".equals(name)) {
                return 0L;
            }
            if ("getFalseProbability".equals(name)) {
                return 0D;
            }
            if ("contains".equals(name) || "add".equals(name) || "isExists".equals(name) || "delete".equals(name)) {
                if ("delete".equals(name)) {
                    deleteCalls.incrementAndGet();
                }
                return true;
            }
            if ("count".equals(name)) {
                return 0L;
            }
            if ("toString".equals(name)) {
                return "RBloomFilterProxy";
            }
            if ("hashCode".equals(name)) {
                return System.identityHashCode(proxy);
            }
            if ("equals".equals(name)) {
                return proxy == args[0];
            }
            return defaultValue(method);
        };
        return (RBloomFilter<String>) Proxy.newProxyInstance(
                RBloomFilter.class.getClassLoader(),
                new Class<?>[]{RBloomFilter.class},
                handler
        );
    }

    private static Object defaultValue(Method method) {
        Class<?> returnType = method.getReturnType();
        if (!returnType.isPrimitive()) {
            return null;
        }
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == byte.class) {
            return (byte) 0;
        }
        if (returnType == short.class) {
            return (short) 0;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == float.class) {
            return 0F;
        }
        if (returnType == double.class) {
            return 0D;
        }
        if (returnType == char.class) {
            return '\0';
        }
        throw new IllegalStateException("Unsupported primitive return type: " + returnType.getName());
    }

    @FunctionalInterface
    private interface BloomFilterSupplier {
        RBloomFilter<String> get();
    }

    @FunctionalInterface
    private interface TryInitSupplier {
        boolean get() throws InterruptedException;
    }
}
