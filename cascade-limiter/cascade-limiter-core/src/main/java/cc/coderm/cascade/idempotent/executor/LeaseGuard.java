package cc.coderm.cascade.idempotent.executor;

import cc.coderm.cascade.idempotent.store.IdempotentStore;
import lombok.extern.slf4j.Slf4j;

import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 幂等租约守护，自动续期防止业务执行期间 key 过期。
 */
@Slf4j
final class LeaseGuard implements AutoCloseable {

    private static final long MIN_RENEW_INTERVAL_MS = 50;
    private static final long MAX_RENEW_INTERVAL_MS = 5_000;

    private final String key;
    private final String ownerToken;
    private final long ttlMs;
    private final long ttlNanos;
    private final IdempotentStore store;
    private final AtomicBoolean ownershipLost = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final ScheduledFuture<?> renewFuture;
    private volatile long lastRenewSuccessNanos;

    private LeaseGuard(String key,
                       String ownerToken,
                       long ttlMs,
                       IdempotentStore store,
                       ScheduledExecutorService renewExecutor,
                       long renewIntervalMs) {
        this.key = Objects.requireNonNull(key, "key");
        this.ownerToken = Objects.requireNonNull(ownerToken, "ownerToken");
        this.ttlMs = ttlMs;
        this.ttlNanos = toSaturatedNanos(ttlMs);
        this.store = Objects.requireNonNull(store, "store");
        this.lastRenewSuccessNanos = System.nanoTime();
        this.renewFuture = renewExecutor.scheduleWithFixedDelay(
                this::renewOnce, renewIntervalMs, renewIntervalMs, TimeUnit.MILLISECONDS);
    }

    static LeaseGuard start(String key,
                            String ownerToken,
                            long ttlMs,
                            IdempotentStore store,
                            ScheduledExecutorService renewExecutor) {
        long intervalMs = computeRenewIntervalMs(ttlMs);
        return new LeaseGuard(key, ownerToken, ttlMs, store, renewExecutor, intervalMs);
    }

    static long computeRenewIntervalMs(long ttlMs) {
        long suggested = ttlMs / 3;
        if (suggested < MIN_RENEW_INTERVAL_MS) {
            return MIN_RENEW_INTERVAL_MS;
        }
        return Math.min(suggested, MAX_RENEW_INTERVAL_MS);
    }

    private void renewOnce() {
        if (closed.get() || ownershipLost.get()) {
            return;
        }
        long nowNanos = System.nanoTime();
        try {
            IdempotentStore.RenewResult renewResult = store.renewProcessing(key, ownerToken, ttlMs);
            if (renewResult == IdempotentStore.RenewResult.RENEWED) {
                lastRenewSuccessNanos = nowNanos;
                return;
            }
            if (renewResult == IdempotentStore.RenewResult.OWNERSHIP_LOST) {
                markOwnershipLost("[Idempotent] key={} owner lost during lease renew.", null);
                return;
            }
            handleContentionOrTransientFailure(nowNanos, null);
        } catch (RuntimeException ex) {
            handleContentionOrTransientFailure(nowNanos, ex);
        }
    }

    private void handleContentionOrTransientFailure(long nowNanos, RuntimeException cause) {
        if (isRenewWindowExceeded(nowNanos)) {
            markOwnershipLost("[Idempotent] key={} renew unavailable for > ttl, ownership considered lost.", cause);
            return;
        }
        if (cause != null) {
            log.warn("[Idempotent] key={} renew failed transiently: {}", key, cause.getMessage());
        } else {
            log.debug("[Idempotent] key={} renew contended, retry in next cycle.", key);
        }
    }

    private void markOwnershipLost(String message, RuntimeException cause) {
        if (!ownershipLost.compareAndSet(false, true)) {
            return;
        }
        if (cause == null) {
            log.error(message, key);
        } else {
            log.error(message, key, cause);
        }
    }

    boolean isOwnershipLost() {
        return ownershipLost.get();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            renewFuture.cancel(false);
        }
    }

    private boolean isRenewWindowExceeded(long nowNanos) {
        long elapsed = nowNanos - lastRenewSuccessNanos;
        return elapsed >= ttlNanos;
    }

    private static long toSaturatedNanos(long ttlMs) {
        if (ttlMs <= 0) {
            return 0;
        }
        long nanosPerMs = 1_000_000L;
        if (ttlMs >= Long.MAX_VALUE / nanosPerMs) {
            return Long.MAX_VALUE;
        }
        return ttlMs * nanosPerMs;
    }
}
