package cc.coderm.cascade.idempotent.executor;

import cc.coderm.cascade.idempotent.exception.IdempotentCommitException;
import cc.coderm.cascade.idempotent.exception.IdempotentConflictException;
import cc.coderm.cascade.idempotent.exception.IdempotentException;
import cc.coderm.cascade.idempotent.model.IdempotentContext;
import cc.coderm.cascade.idempotent.model.IdempotentRecord;
import cc.coderm.cascade.idempotent.notifier.IdempotentCompletionNotifier;
import cc.coderm.cascade.idempotent.serializer.ResultSerializer;
import cc.coderm.cascade.idempotent.store.IdempotentStore;
import lombok.extern.slf4j.Slf4j;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * WAIT 冲突策略处理器。
 *
 * <p>策略优先级：本地信号 -> 跨实例信号 -> 退避轮询。
 */
@Slf4j
final class WaitStrategy {

    private static final long WAIT_POLL_INITIAL_MS = 20;
    private static final long WAIT_POLL_MAX_MS = 250;

    private final IdempotentStore store;
    private final ResultSerializer serializer;
    private final IdempotentCompletionNotifier completionNotifier;
    private final ConcurrentMap<String, ProcessingCompletionSignal> processingSignals = new ConcurrentHashMap<>();

    WaitStrategy(IdempotentStore store,
                 ResultSerializer serializer,
                 IdempotentCompletionNotifier completionNotifier) {
        this.store = Objects.requireNonNull(store, "store");
        this.serializer = Objects.requireNonNull(serializer, "serializer");
        this.completionNotifier = Objects.requireNonNull(completionNotifier, "completionNotifier");
    }

    ProcessingCompletionSignal beginProcessingSignal(String key) {
        ProcessingCompletionSignal signal = new ProcessingCompletionSignal();
        ProcessingCompletionSignal previous = processingSignals.put(key, signal);
        if (previous != null) {
            previous.complete(null);
            log.warn("[Idempotent] key={} replacing stale local processing signal.", key);
        }
        return signal;
    }

    void completeProcessingSignal(String key, ProcessingCompletionSignal signal) {
        IdempotentRecord terminalRecord = null;
        try {
            terminalRecord = store.load(key).orElse(null);
        } catch (RuntimeException ex) {
            log.debug("[Idempotent] key={} failed to load terminal record for local waiters: {}",
                    key, ex.getMessage());
        } finally {
            signal.complete(terminalRecord);
            processingSignals.remove(key, signal);
            publishCompletionSignal(key, terminalRecord);
        }
    }

    Object waitForCompletion(IdempotentContext context, long deadline) throws InterruptedException {
        String key = context.getIdempotentKey();
        if (System.currentTimeMillis() >= deadline) {
            throw new IdempotentConflictException(key, store.load(key).orElse(null));
        }

        LocalWaitResult localWaitResult = awaitLocalProcessingCompletion(key, deadline);
        if (localWaitResult.completed()) {
            TerminalResolution resolution = resolveTerminalState(context, localWaitResult.terminalRecord());
            if (resolution.resolved()) {
                return resolution.value();
            }
            throw RetryAcquireSignal.INSTANCE;
        }

        IdempotentCompletionNotifier.Subscription crossSubscription = subscribeCompletionSignal(key);
        try {
            long pollDelayMs = WAIT_POLL_INITIAL_MS;
            while (System.currentTimeMillis() < deadline) {
                long remainingMs = deadline - System.currentTimeMillis();
                if (remainingMs <= 0) {
                    break;
                }

                long waitMs = Math.min(pollDelayMs, remainingMs);
                boolean crossSignaled;
                if (crossSubscription != null) {
                    crossSignaled = awaitCrossSignal(crossSubscription.completion(), waitMs);
                } else {
                    Thread.sleep(waitMs);
                    crossSignaled = false;
                }

                Optional<IdempotentRecord> opt = store.load(key);
                if (opt.isEmpty() && (crossSignaled || crossSubscription == null)) {
                    throw RetryAcquireSignal.INSTANCE;
                }

                TerminalResolution resolution = resolveTerminalState(context, opt.orElse(null));
                if (resolution.resolved()) {
                    return resolution.value();
                }

                if (crossSignaled && crossSubscription != null) {
                    crossSubscription.close();
                    crossSubscription = subscribeCompletionSignal(key);
                    pollDelayMs = WAIT_POLL_INITIAL_MS;
                    continue;
                }
                pollDelayMs = Math.min(WAIT_POLL_MAX_MS, pollDelayMs << 1);
            }
        } finally {
            if (crossSubscription != null) {
                crossSubscription.close();
            }
        }

        throw new IdempotentConflictException(key, store.load(key).orElse(null));
    }

    Object replayResult(IdempotentRecord terminalRecord, IdempotentContext context) {
        String json = terminalRecord.getResult();
        if (json == null || serializer.nullPlaceholder().equals(json)) {
            return null;
        }
        return serializer.deserialize(json, context.getReturnType());
    }

    private LocalWaitResult awaitLocalProcessingCompletion(String key, long deadline) throws InterruptedException {
        ProcessingCompletionSignal signal = processingSignals.get(key);
        if (signal == null) {
            return LocalWaitResult.notCompleted();
        }
        long remainingMs = deadline - System.currentTimeMillis();
        if (remainingMs <= 0) {
            return LocalWaitResult.notCompleted();
        }
        if (!signal.await(remainingMs)) {
            return LocalWaitResult.notCompleted();
        }
        return LocalWaitResult.completed(signal.getTerminalRecord());
    }

    private TerminalResolution resolveTerminalState(IdempotentContext context, IdempotentRecord current) {
        if (current == null || current.getState() == null) {
            return TerminalResolution.unresolved();
        }
        String key = context.getIdempotentKey();
        if (current.isSucceeded()) {
            log.debug("[Idempotent] key={} wait completed, replaying result.", key);
            return TerminalResolution.resolved(replayResult(current, context));
        }
        if (current.isFailed()) {
            throw new IdempotentException(
                    "Previous execution of key [" + key + "] has failed.", current);
        }
        if (current.isUncertain()) {
            throw new IdempotentCommitException(
                    "Previous execution of key [" + key + "] is in UNCERTAIN state, "
                            + "duplicate execution is blocked.", current);
        }
        return TerminalResolution.unresolved();
    }

    private void publishCompletionSignal(String key, IdempotentRecord terminalRecord) {
        if (!completionNotifier.isEnabled()) {
            return;
        }
        if (terminalRecord != null && terminalRecord.isProcessing()) {
            return;
        }
        try {
            completionNotifier.publish(key);
        } catch (RuntimeException ex) {
            log.debug("[Idempotent] key={} failed to publish cross-instance completion signal: {}",
                    key, ex.getMessage());
        }
    }

    private IdempotentCompletionNotifier.Subscription subscribeCompletionSignal(String key) {
        if (!completionNotifier.isEnabled()) {
            return null;
        }
        try {
            return completionNotifier.subscribe(key);
        } catch (RuntimeException ex) {
            log.debug("[Idempotent] key={} failed to subscribe cross-instance completion signal: {}",
                    key, ex.getMessage());
            return null;
        }
    }

    private static boolean awaitCrossSignal(CompletableFuture<Void> completion, long waitMs)
            throws InterruptedException {
        if (completion.isDone()) {
            return true;
        }
        try {
            completion.get(waitMs, TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException ex) {
            return false;
        } catch (ExecutionException ex) {
            return true;
        }
    }

    private record LocalWaitResult(boolean completed, IdempotentRecord terminalRecord) {
        private static LocalWaitResult notCompleted() {
            return new LocalWaitResult(false, null);
        }

        private static LocalWaitResult completed(IdempotentRecord terminalRecord) {
            return new LocalWaitResult(true, terminalRecord);
        }
    }

    private record TerminalResolution(boolean resolved, Object value) {
        private static TerminalResolution unresolved() {
            return new TerminalResolution(false, null);
        }

        private static TerminalResolution resolved(Object value) {
            return new TerminalResolution(true, value);
        }
    }

    static final class RetryAcquireSignal extends RuntimeException {
        private static final RetryAcquireSignal INSTANCE = new RetryAcquireSignal();

        private RetryAcquireSignal() {
            super(null, null, false, false);
        }
    }
}
