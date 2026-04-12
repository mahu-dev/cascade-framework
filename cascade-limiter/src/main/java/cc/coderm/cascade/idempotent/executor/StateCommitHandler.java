package cc.coderm.cascade.idempotent.executor;

import cc.coderm.cascade.idempotent.exception.IdempotentCommitException;
import cc.coderm.cascade.idempotent.exception.IdempotentOwnershipException;
import cc.coderm.cascade.idempotent.model.IdempotentContext;
import cc.coderm.cascade.idempotent.model.IdempotentRecord;
import cc.coderm.cascade.idempotent.model.IdempotentState;
import cc.coderm.cascade.idempotent.serializer.ResultSerializer;
import cc.coderm.cascade.idempotent.store.IdempotentStore;
import lombok.extern.slf4j.Slf4j;

import java.util.Objects;

/**
 * 幂等状态提交处理器，负责成功/失败/不确定状态流转。
 */
@Slf4j
final class StateCommitHandler {

    private final IdempotentStore store;
    private final ResultSerializer serializer;

    StateCommitHandler(IdempotentStore store, ResultSerializer serializer) {
        this.store = Objects.requireNonNull(store, "store");
        this.serializer = Objects.requireNonNull(serializer, "serializer");
    }

    void commitSuccess(IdempotentContext context,
                       String ownerToken,
                       Object result,
                       LeaseGuard leaseGuard) {
        String key = context.getIdempotentKey();
        if (leaseGuard.isOwnershipLost()) {
            throw ownershipLost(context, null);
        }

        if (context.isDeleteOnSuccess()) {
            boolean deleted = store.deleteIfOwner(key, ownerToken);
            if (!deleted) {
                throw ownershipLost(context, null);
            }
            log.debug("[Idempotent] key={} deleteOnSuccess, key removed.", key);
            return;
        }

        String serialized;
        String typeName = result != null ? result.getClass().getName() : null;
        try {
            serialized = serializer.serialize(result);
        } catch (Exception ex) {
            throw markUncertainAndBuildCommitException(context, ownerToken, leaseGuard, ex);
        }

        try {
            boolean committed = store.markSucceeded(key, ownerToken, serialized, typeName, context.getTtlMs());
            if (!committed) {
                throw ownershipLost(context, null);
            }
            log.debug("[Idempotent] key={} marked SUCCEEDED.", key);
        } catch (IdempotentOwnershipException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw markUncertainAndBuildCommitException(context, ownerToken, leaseGuard, ex);
        }
    }

    void commitFailure(IdempotentContext context,
                       String ownerToken,
                       LeaseGuard leaseGuard,
                       Throwable businessEx) {
        String key = context.getIdempotentKey();
        if (leaseGuard.isOwnershipLost()) {
            throw ownershipLost(context, businessEx);
        }

        boolean committed;
        if (context.isDeleteOnFailure()) {
            committed = store.deleteIfOwner(key, ownerToken);
            log.debug("[Idempotent] key={} deleteOnFailure, key removed for retry.", key);
        } else {
            committed = store.markFailed(key, ownerToken, context.getTtlMs());
            log.debug("[Idempotent] key={} marked FAILED.", key);
        }

        if (!committed) {
            throw ownershipLost(context, businessEx);
        }
    }

    private IdempotentCommitException markUncertainAndBuildCommitException(IdempotentContext context,
                                                                           String ownerToken,
                                                                           LeaseGuard leaseGuard,
                                                                           Throwable cause) {
        String key = context.getIdempotentKey();
        if (leaseGuard.isOwnershipLost()) {
            throw ownershipLost(context, cause);
        }

        String reason = buildCommitFailureReason(cause);
        boolean marked;
        try {
            marked = store.markUncertain(key, ownerToken, reason, context.getTtlMs());
        } catch (RuntimeException markEx) {
            markEx.addSuppressed(cause);
            String message = "Idempotent key [" + key + "] business executed but commit failed, "
                    + "and failed to mark UNCERTAIN.";
            return new IdempotentCommitException(message, store.load(key).orElse(null), markEx);
        }

        if (!marked) {
            throw ownershipLost(context, cause);
        }

        IdempotentRecord latest = store.load(key).orElse(IdempotentRecord.builder()
                .state(IdempotentState.UNCERTAIN)
                .scene(context.getScene())
                .error(reason)
                .build());
        String message = "Idempotent key [" + key + "] business executed but commit failed. "
                + "State marked as UNCERTAIN to prevent duplicate side effects.";
        return new IdempotentCommitException(message, latest, cause);
    }

    private IdempotentOwnershipException ownershipLost(IdempotentContext context, Throwable cause) {
        String key = context.getIdempotentKey();
        IdempotentRecord latest = store.load(key).orElse(null);
        String message = "Idempotent ownership lost for key [" + key
                + "], execution result cannot be committed safely.";
        return new IdempotentOwnershipException(message, latest, cause);
    }

    private static String buildCommitFailureReason(Throwable cause) {
        String type = cause.getClass().getSimpleName();
        String message = cause.getMessage();
        if (message == null || message.isBlank()) {
            return type;
        }
        return type + ": " + message;
    }
}

