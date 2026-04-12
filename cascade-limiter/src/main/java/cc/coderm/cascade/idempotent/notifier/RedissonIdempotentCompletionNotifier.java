package cc.coderm.cascade.idempotent.notifier;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 基于 Redis Pub/Sub 的跨实例完成通知实现。
 */
@Slf4j
public class RedissonIdempotentCompletionNotifier implements IdempotentCompletionNotifier {

    private final RTopic topic;
    private final int listenerId;
    private final ConcurrentMap<String, Set<CompletableFuture<Void>>> waiters = new ConcurrentHashMap<>();

    public RedissonIdempotentCompletionNotifier(RedissonClient redissonClient, String topicName) {
        this.topic = redissonClient.getTopic(topicName, StringCodec.INSTANCE);
        this.listenerId = this.topic.addListener(String.class, (channel, key) -> notifyWaiters(key));
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public Subscription subscribe(String key) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        waiters.computeIfAbsent(key, ignored -> ConcurrentHashMap.newKeySet()).add(future);
        return new RedissonSubscription(key, future);
    }

    @Override
    public void publish(String key) {
        try {
            topic.publish(key);
        } catch (RuntimeException ex) {
            log.debug("[Idempotent] publish completion event failed, key={}, reason={}", key, ex.getMessage());
        }
    }

    @Override
    public void close() {
        try {
            topic.removeListener(listenerId);
        } catch (RuntimeException ex) {
            log.debug("[Idempotent] remove completion listener failed: {}", ex.getMessage());
        }
        waiters.forEach((key, futures) -> futures.forEach(future -> future.complete(null)));
        waiters.clear();
    }

    private void notifyWaiters(String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        Set<CompletableFuture<Void>> futures = waiters.remove(key);
        if (futures == null || futures.isEmpty()) {
            return;
        }
        for (CompletableFuture<Void> future : futures) {
            future.complete(null);
        }
    }

    private final class RedissonSubscription implements Subscription {
        private final String key;
        private final CompletableFuture<Void> future;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private RedissonSubscription(String key, CompletableFuture<Void> future) {
            this.key = key;
            this.future = future;
        }

        @Override
        public CompletableFuture<Void> completion() {
            return future;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            waiters.computeIfPresent(key, (ignored, futures) -> {
                futures.remove(future);
                return futures.isEmpty() ? null : futures;
            });
        }
    }
}
