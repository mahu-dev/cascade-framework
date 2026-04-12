package cc.coderm.cascade.idempotent.executor;

import cc.coderm.cascade.idempotent.model.IdempotentRecord;
import cc.coderm.cascade.idempotent.store.IdempotentStore;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;

class LeaseGuardTest {

    @Test
    void shouldNotLoseOwnershipOnTransientContentionWithinTtl() throws Exception {
        RenewScriptStore store = new RenewScriptStore(attempt ->
                attempt <= 2 ? IdempotentStore.RenewResult.CONTENDED : IdempotentStore.RenewResult.RENEWED);
        ScheduledExecutorService renewExecutor = Executors.newSingleThreadScheduledExecutor();
        LeaseGuard guard = LeaseGuard.start("idem:test:key", "owner-1", 200, store, renewExecutor);

        try {
            waitAtLeast(180);
            assertThat(guard.isOwnershipLost()).isFalse();
            assertThat(store.renewCalls()).isGreaterThanOrEqualTo(2);
        } finally {
            guard.close();
            shutdown(renewExecutor);
        }
    }

    @Test
    void shouldLoseOwnershipWhenContentionExceedsTtlWindow() throws Exception {
        RenewScriptStore store = new RenewScriptStore(attempt -> IdempotentStore.RenewResult.CONTENDED);
        ScheduledExecutorService renewExecutor = Executors.newSingleThreadScheduledExecutor();
        LeaseGuard guard = LeaseGuard.start("idem:test:key", "owner-1", 120, store, renewExecutor);

        try {
            waitUntil(guard::isOwnershipLost, 600);
            assertThat(guard.isOwnershipLost()).isTrue();
        } finally {
            guard.close();
            shutdown(renewExecutor);
        }
    }

    @Test
    void shouldLoseOwnershipImmediatelyWhenStoreReportsOwnershipLost() throws Exception {
        RenewScriptStore store = new RenewScriptStore(attempt -> IdempotentStore.RenewResult.OWNERSHIP_LOST);
        ScheduledExecutorService renewExecutor = Executors.newSingleThreadScheduledExecutor();
        LeaseGuard guard = LeaseGuard.start("idem:test:key", "owner-1", 300, store, renewExecutor);

        try {
            waitUntil(guard::isOwnershipLost, 200);
            assertThat(guard.isOwnershipLost()).isTrue();
            assertThat(store.renewCalls()).isGreaterThanOrEqualTo(1);
        } finally {
            guard.close();
            shutdown(renewExecutor);
        }
    }

    private static void waitUntil(BooleanCondition condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
    }

    private static void waitAtLeast(long waitMs) throws InterruptedException {
        Thread.sleep(waitMs);
    }

    private static void shutdown(ScheduledExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        executor.awaitTermination(2, TimeUnit.SECONDS);
    }

    @FunctionalInterface
    private interface BooleanCondition {
        boolean getAsBoolean();
    }

    private static final class RenewScriptStore implements IdempotentStore {
        private final IntFunction<RenewResult> renewScript;
        private final AtomicInteger renewCalls = new AtomicInteger();

        private RenewScriptStore(IntFunction<RenewResult> renewScript) {
            this.renewScript = renewScript;
        }

        private int renewCalls() {
            return renewCalls.get();
        }

        @Override
        public OccupyResult tryOccupy(String key, String scene, long ttlMs, String ownerToken) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean markSucceeded(String key, String ownerToken, String result, String resultType, long ttlMs) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean markFailed(String key, String ownerToken, long ttlMs) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean markUncertain(String key, String ownerToken, String errorReason, long ttlMs) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean deleteIfOwner(String key, String ownerToken) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RenewResult renewProcessing(String key, String ownerToken, long ttlMs) {
            int attempt = renewCalls.incrementAndGet();
            return renewScript.apply(attempt);
        }

        @Override
        public Optional<IdempotentRecord> load(String key) {
            throw new UnsupportedOperationException();
        }
    }
}

