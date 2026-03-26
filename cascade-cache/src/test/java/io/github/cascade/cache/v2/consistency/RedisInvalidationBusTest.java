package io.github.cascade.cache.v2.consistency;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cascade.cache.v2.observability.CacheMetricsCollector;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisInvalidationBusTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void shouldRetryAndCountDeadLetterWhenPublishKeepsFailing() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CacheMetricsCollector metrics = CacheMetricsCollector.create("user", registry);
        FakeRedisHarness harness = new FakeRedisHarness();
        harness.topic("topic:user").publishFailure = new IllegalStateException("boom");

        RedisInvalidationBus<String> bus = new RedisInvalidationBus<>(
                harness.client(),
                "topic:",
                metrics,
                new RedisInvalidationBus.PublishOptions(false, 1, 8, 3, 0)
        );
        bus.start();
        try {
            CompletionException error = assertThrows(
                    CompletionException.class,
                    () -> bus.publishInvalidation("user", "k1", 1L, "node-A").join()
            );
            assertTrue(error.getCause() instanceof IllegalStateException);
            assertEquals(3, harness.topic("topic:user").publishCalls.get(), "应按配置进行重试");
            assertEquals(2.0, counterValue(registry, "cascade.cache.sync.publish.retry"));
            assertEquals(1.0, counterValue(registry, "cascade.cache.sync.publish.fail"));
            assertEquals(1.0, counterValue(registry, "cascade.cache.sync.deadletter"));
        } finally {
            bus.stop();
            registry.close();
        }
    }

    @Test
    void shouldCountConsumeFailureWhenHandlerThrows() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CacheMetricsCollector metrics = CacheMetricsCollector.create("user", registry);
        FakeRedisHarness harness = new FakeRedisHarness();
        FakeTopicState state = harness.topic("topic:user");

        RedisInvalidationBus<String> bus = new RedisInvalidationBus<>(
                harness.client(),
                "topic:",
                metrics,
                new RedisInvalidationBus.PublishOptions(false, 1, 8, 1, 0)
        );
        bus.subscribe("user", event -> {
            throw new IllegalStateException("consume-fail");
        });
        bus.start();
        try {
            String payload = OBJECT_MAPPER.writeValueAsString(
                    InvalidationEvent.invalidate("user", "k1", 2L, "node-B")
            );
            state.dispatch("topic:user", payload);
            assertEquals(1.0, counterValue(registry, "cascade.cache.sync.consume.fail"));
        } finally {
            bus.stop();
            registry.close();
        }
    }

    @Test
    void shouldCountRejectAndDeadLetterWhenPublishQueueIsFull() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CacheMetricsCollector metrics = CacheMetricsCollector.create("user", registry);
        FakeRedisHarness harness = new FakeRedisHarness();
        FakeTopicState state = harness.topic("topic:user");
        state.publishBlockLatch = new CountDownLatch(1);

        RedisInvalidationBus<String> bus = new RedisInvalidationBus<>(
                harness.client(),
                "topic:",
                metrics,
                new RedisInvalidationBus.PublishOptions(true, 1, 1, 1, 0)
        );
        bus.start();
        try {
            var f1 = bus.publishInvalidation("user", "k1", 1L, "node-A");
            var f2 = bus.publishInvalidation("user", "k2", 2L, "node-A");
            CompletionException error = assertThrows(
                    CompletionException.class,
                    () -> bus.publishInvalidation("user", "k3", 3L, "node-A").join()
            );
            assertTrue(error.getCause() instanceof RuntimeException);

            state.publishBlockLatch.countDown();
            f1.join();
            f2.join();

            assertEquals(1.0, counterValue(registry, "cascade.cache.sync.publish.reject"));
            assertEquals(1.0, counterValue(registry, "cascade.cache.sync.deadletter"));
        } finally {
            bus.stop();
            registry.close();
        }
    }

    @Test
    void shouldKeepPojoKeyTypeWhenConsumingPublishedEvent() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CacheMetricsCollector metrics = CacheMetricsCollector.create("user", registry);
        FakeRedisHarness harness = new FakeRedisHarness();
        AtomicReference<Object> consumedKey = new AtomicReference<>();

        RedisInvalidationBus<Object> bus = new RedisInvalidationBus<>(
                harness.client(),
                "topic:",
                metrics,
                new RedisInvalidationBus.PublishOptions(false, 1, 8, 1, 0)
        );
        bus.subscribe("user", event -> consumedKey.set(event.getKey()));
        bus.start();
        try {
            CompositeKey key = new CompositeKey("u1", 3);
            bus.publishInvalidation("user", key, 1L, "node-A").join();

            Object keyObj = consumedKey.get();
            assertTrue(keyObj instanceof CompositeKey);
            assertEquals(key, keyObj);
        } finally {
            bus.stop();
            registry.close();
        }
    }

    private static double counterValue(SimpleMeterRegistry registry, String name) {
        var meter = registry.find(name).counter();
        return meter == null ? 0.0 : meter.count();
    }

    private static final class FakeRedisHarness {
        private final Map<String, FakeTopicState> topics = new ConcurrentHashMap<>();
        private final RedissonClient client = (RedissonClient) Proxy.newProxyInstance(
                RedissonClient.class.getClassLoader(),
                new Class<?>[]{RedissonClient.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getTopic" -> {
                        String topicName = String.valueOf(args[0]);
                        yield topic(topicName).asTopicProxy();
                    }
                    case "toString" -> "FakeRedissonClient";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == (args != null && args.length > 0 ? args[0] : null);
                    default -> throw new UnsupportedOperationException("Unsupported RedissonClient method: " + method.getName());
                }
        );

        private RedissonClient client() {
            return client;
        }

        private FakeTopicState topic(String topicName) {
            return topics.computeIfAbsent(topicName, ignored -> new FakeTopicState());
        }
    }

    private static final class FakeTopicState {
        private final AtomicInteger publishCalls = new AtomicInteger(0);
        private volatile RuntimeException publishFailure;
        private volatile CountDownLatch publishBlockLatch;
        private volatile Object listener;

        private final RTopic topicProxy = (RTopic) Proxy.newProxyInstance(
                RTopic.class.getClassLoader(),
                new Class<?>[]{RTopic.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "publish" -> {
                        publishCalls.incrementAndGet();
                        CountDownLatch latch = publishBlockLatch;
                        if (latch != null) {
                            latch.await(2, TimeUnit.SECONDS);
                        }
                        if (publishFailure != null) {
                            throw publishFailure;
                        }
                        invokeListener("fake-topic", String.valueOf(args[0]));
                        yield 1L;
                    }
                    case "addListener" -> {
                        listener = args[1];
                        yield 1;
                    }
                    case "removeListener" -> true;
                    case "toString" -> "FakeRTopic";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == (args != null && args.length > 0 ? args[0] : null);
                    default -> throw new UnsupportedOperationException("Unsupported RTopic method: " + method.getName());
                }
        );

        private RTopic asTopicProxy() {
            return topicProxy;
        }

        private void dispatch(String channel, String payload) throws Exception {
            invokeListener(channel, payload);
        }

        private void invokeListener(String channel, String payload) throws Exception {
            Object currentListener = listener;
            if (currentListener == null) {
                return;
            }
            Method onMessage = null;
            for (Method method : currentListener.getClass().getMethods()) {
                if ("onMessage".equals(method.getName()) && method.getParameterCount() == 2) {
                    onMessage = method;
                    break;
                }
            }
            if (onMessage == null) {
                throw new IllegalStateException("onMessage method not found");
            }
            onMessage.invoke(currentListener, channel, payload);
        }
    }

    private record CompositeKey(String id, int shard) {
    }
}
