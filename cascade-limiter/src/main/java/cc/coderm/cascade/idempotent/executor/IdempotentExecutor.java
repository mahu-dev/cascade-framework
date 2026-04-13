package cc.coderm.cascade.idempotent.executor;

import cc.coderm.cascade.idempotent.exception.IdempotentCommitException;
import cc.coderm.cascade.idempotent.exception.IdempotentConflictException;
import cc.coderm.cascade.idempotent.exception.IdempotentException;
import cc.coderm.cascade.idempotent.exception.IdempotentOwnershipException;
import cc.coderm.cascade.idempotent.metrics.IdempotentMetrics;
import cc.coderm.cascade.idempotent.model.ConflictStrategy;
import cc.coderm.cascade.idempotent.model.IdempotentContext;
import cc.coderm.cascade.idempotent.model.IdempotentRecord;
import cc.coderm.cascade.idempotent.notifier.IdempotentCompletionNotifier;
import cc.coderm.cascade.idempotent.notifier.NoopIdempotentCompletionNotifier;
import cc.coderm.cascade.idempotent.serializer.ResultSerializer;
import cc.coderm.cascade.idempotent.store.IdempotentStore;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 幂等执行器：负责核心流程编排。
 */
@Slf4j
public class IdempotentExecutor {

    private static final long RETRY_ACQUIRE_BACKOFF_INITIAL_MS = 5;
    private static final long RETRY_ACQUIRE_BACKOFF_MAX_MS = 80;
    private static final long RETRY_ACQUIRE_CONTENDED_TIMEOUT_MS = 500;

    private final IdempotentStore store;
    private final MetricsRecorder metrics;
    private final IdempotentCompletionNotifier completionNotifier;
    private final ScheduledExecutorService leaseRenewExecutor;
    private final StateCommitHandler stateCommitHandler;
    private final WaitStrategy waitStrategy;

    public IdempotentExecutor(IdempotentStore store,
                              ResultSerializer serializer,
                              IdempotentMetrics metrics) {
        this(store, serializer, metrics, NoopIdempotentCompletionNotifier.INSTANCE, createLeaseRenewExecutor());
    }

    public IdempotentExecutor(IdempotentStore store,
                              ResultSerializer serializer,
                              IdempotentMetrics metrics,
                              IdempotentCompletionNotifier completionNotifier) {
        this(store, serializer, metrics, completionNotifier, createLeaseRenewExecutor());
    }

    IdempotentExecutor(IdempotentStore store,
                       ResultSerializer serializer,
                       IdempotentMetrics metrics,
                       IdempotentCompletionNotifier completionNotifier,
                       ScheduledExecutorService leaseRenewExecutor) {
        this.store = Objects.requireNonNull(store, "store");
        this.metrics = MetricsRecorder.of(metrics);
        this.completionNotifier = completionNotifier != null
                ? completionNotifier
                : NoopIdempotentCompletionNotifier.INSTANCE;
        this.leaseRenewExecutor = Objects.requireNonNull(leaseRenewExecutor, "leaseRenewExecutor");
        ResultSerializer resolvedSerializer = Objects.requireNonNull(serializer, "serializer");
        this.stateCommitHandler = new StateCommitHandler(this.store, resolvedSerializer);
        this.waitStrategy = new WaitStrategy(this.store, resolvedSerializer, this.completionNotifier);
    }

    /**
     * 执行幂等保护的业务逻辑。
     *
     * @param context  幂等上下文
     * @param business 业务方法回调
     * @return 业务结果（首次执行）或缓存结果（幂等命中）
     */
    public Object execute(IdempotentContext context, IdempotentBusiness business) throws Throwable {
        validateContext(context);
        long waitDeadline = calculateWaitDeadline(context);
        long contendedRetryDeadline = calculateContendedRetryDeadline(context, waitDeadline);
        long retryBackoffMs = RETRY_ACQUIRE_BACKOFF_INITIAL_MS;

        while (true) {
            String key = context.getIdempotentKey();
            String ownerToken = UUID.randomUUID().toString();

            IdempotentStore.OccupyResult occupyResult =
                    store.tryOccupy(key, context.getScene(), context.getTtlMs(), ownerToken);

            if (occupyResult.occupied()) {
                ProcessingCompletionSignal signal = waitStrategy.beginProcessingSignal(key);
                try {
                    return executeFirstTime(context, business, ownerToken);
                } finally {
                    waitStrategy.completeProcessingSignal(key, signal);
                }
            }

            if (occupyResult.contended()) {
                if (System.currentTimeMillis() >= contendedRetryDeadline) {
                    throw new IdempotentException(
                            "Unable to determine occupancy for idempotent key [" + key + "] due transient store contention.",
                            null);
                }
                waitBeforeRetry(contendedRetryDeadline, retryBackoffMs);
                retryBackoffMs = Math.min(RETRY_ACQUIRE_BACKOFF_MAX_MS, retryBackoffMs << 1);
                continue;
            }

            ExistingRecordDecision decision = handleExistingRecord(context, occupyResult.existingRecord(), waitDeadline);
            if (!decision.shouldRetryAcquire()) {
                return decision.value();
            }

            waitBeforeRetry(waitDeadline, retryBackoffMs);
            retryBackoffMs = Math.min(RETRY_ACQUIRE_BACKOFF_MAX_MS, retryBackoffMs << 1);
        }
    }

    private static void validateContext(IdempotentContext context) {
        if (context.getTtlMs() <= 0) {
            throw new IllegalArgumentException("Idempotent ttlMs must be > 0");
        }
        if (context.getWaitTimeoutMs() < 0) {
            throw new IllegalArgumentException("Idempotent waitTimeoutMs must be >= 0");
        }
    }

    private Object executeFirstTime(IdempotentContext context,
                                    IdempotentBusiness business,
                                    String ownerToken) throws Throwable {
        String key = context.getIdempotentKey();
        metrics.recordMiss(context.getScene());

        LeaseGuard leaseGuard = LeaseGuard.start(key, ownerToken, context.getTtlMs(), store, leaseRenewExecutor);
        try {
            Object result;
            try {
                result = business.call();
            } catch (Exception businessEx) {
                stateCommitHandler.commitFailure(context, ownerToken, leaseGuard, businessEx);
                metrics.recordFailure(context.getScene());
                throw businessEx;
            } catch (Error fatal) {
                // Fatal errors must fail-fast. Do not try to commit failed state on broken JVM runtime.
                throw fatal;
            }

            try {
                stateCommitHandler.commitSuccess(context, ownerToken, result, leaseGuard);
                metrics.recordSuccess(context.getScene());
                return result;
            } catch (IdempotentOwnershipException | IdempotentCommitException ex) {
                metrics.recordFailure(context.getScene());
                throw ex;
            }
        } finally {
            leaseGuard.close();
        }
    }

    private ExistingRecordDecision handleExistingRecord(IdempotentContext context,
                                                        IdempotentRecord existingRecord,
                                                        long waitDeadline) throws Throwable {
        String key = context.getIdempotentKey();
        if (existingRecord == null || existingRecord.getState() == null) {
            throw new IdempotentException("Idempotent key [" + key + "] exists but state is missing.", existingRecord);
        }

        return switch (existingRecord.getState()) {
            case SUCCEEDED -> {
                log.debug("[Idempotent] key={} HIT (SUCCEEDED), replaying cached result.", key);
                metrics.recordHit(context.getScene());
                yield ExistingRecordDecision.completed(waitStrategy.replayResult(existingRecord, context));
            }
            case FAILED -> {
                log.debug("[Idempotent] key={} HIT (FAILED), deleteOnFailure={}.", key, context.isDeleteOnFailure());
                metrics.recordHit(context.getScene());
                throw new IdempotentException(
                        "Previous execution of key [" + key + "] has failed.", existingRecord);
            }
            case UNCERTAIN -> {
                metrics.recordHit(context.getScene());
                throw new IdempotentCommitException(
                        "Previous execution of key [" + key + "] is in UNCERTAIN state, "
                                + "duplicate execution is blocked.", existingRecord);
            }
            case PROCESSING -> handleProcessing(context, existingRecord, waitDeadline);
        };
    }

    private ExistingRecordDecision handleProcessing(IdempotentContext context,
                                                    IdempotentRecord processingRecord,
                                                    long waitDeadline) throws Throwable {
        String key = context.getIdempotentKey();
        metrics.recordConflict(context.getScene());

        return switch (context.getConflictStrategy()) {
            case FAIL_FAST -> {
                log.debug("[Idempotent] key={} CONFLICT, strategy=FAIL_FAST.", key);
                throw new IdempotentConflictException(key, processingRecord);
            }
            case FALLBACK -> {
                log.debug("[Idempotent] key={} CONFLICT, strategy=FALLBACK.", key);
                throw new IdempotentConflictException(key, processingRecord);
            }
            case WAIT -> {
                log.debug("[Idempotent] key={} CONFLICT, strategy=WAIT, timeout={}ms.",
                        key, context.getWaitTimeoutMs());
                try {
                    yield ExistingRecordDecision.completed(waitStrategy.waitForCompletion(context, waitDeadline));
                } catch (WaitStrategy.RetryAcquireSignal ignored) {
                    yield ExistingRecordDecision.retry();
                }
            }
        };
    }

    private static long calculateWaitDeadline(IdempotentContext context) {
        if (context.getConflictStrategy() != ConflictStrategy.WAIT) {
            return 0L;
        }
        long now = System.currentTimeMillis();
        long safeDelta = Math.min(context.getWaitTimeoutMs(), Long.MAX_VALUE - now);
        return now + safeDelta;
    }

    private static long calculateContendedRetryDeadline(IdempotentContext context, long waitDeadline) {
        if (context.getConflictStrategy() == ConflictStrategy.WAIT) {
            return waitDeadline;
        }
        long now = System.currentTimeMillis();
        long safeDelta = Math.min(RETRY_ACQUIRE_CONTENDED_TIMEOUT_MS, Long.MAX_VALUE - now);
        return now + safeDelta;
    }

    private static void waitBeforeRetry(long waitDeadline, long backoffMs) throws InterruptedException {
        if (waitDeadline <= 0) {
            return;
        }
        long remaining = waitDeadline - System.currentTimeMillis();
        if (remaining <= 0) {
            return;
        }
        Thread.sleep(Math.min(backoffMs, remaining));
    }

    private static ScheduledExecutorService createLeaseRenewExecutor() {
        ScheduledThreadPoolExecutor executor = (ScheduledThreadPoolExecutor) Executors.newScheduledThreadPool(
                Math.max(2, Runtime.getRuntime().availableProcessors() / 2),
                new IdempotentRenewThreadFactory());
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return executor;
    }

    @PreDestroy
    public void shutdown() {
        try {
            completionNotifier.close();
        } catch (Exception ex) {
            log.debug("[Idempotent] close completion notifier failed: {}", ex.getMessage());
        }
        leaseRenewExecutor.shutdown();
        try {
            if (!leaseRenewExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                leaseRenewExecutor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            leaseRenewExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static final class IdempotentRenewThreadFactory implements ThreadFactory {
        private static final AtomicInteger INDEX = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable r) {
            Thread thread = new Thread(r);
            thread.setDaemon(true);
            thread.setName("cascade-idempotent-renew-" + INDEX.getAndIncrement());
            return thread;
        }
    }

    private record ExistingRecordDecision(boolean shouldRetryAcquire, Object value) {
        private static ExistingRecordDecision completed(Object value) {
            return new ExistingRecordDecision(false, value);
        }

        private static ExistingRecordDecision retry() {
            return new ExistingRecordDecision(true, null);
        }
    }

    private static final class MetricsRecorder {
        private final IdempotentMetrics delegate;

        private MetricsRecorder(IdempotentMetrics delegate) {
            this.delegate = delegate;
        }

        private static MetricsRecorder of(IdempotentMetrics metrics) {
            return new MetricsRecorder(metrics);
        }

        private void recordHit(String scene) {
            if (delegate != null) {
                delegate.recordHit(scene);
            }
        }

        private void recordMiss(String scene) {
            if (delegate != null) {
                delegate.recordMiss(scene);
            }
        }

        private void recordConflict(String scene) {
            if (delegate != null) {
                delegate.recordConflict(scene);
            }
        }

        private void recordSuccess(String scene) {
            if (delegate != null) {
                delegate.recordSuccess(scene);
            }
        }

        private void recordFailure(String scene) {
            if (delegate != null) {
                delegate.recordFailure(scene);
            }
        }
    }
}
