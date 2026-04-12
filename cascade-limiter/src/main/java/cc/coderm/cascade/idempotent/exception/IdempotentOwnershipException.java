package cc.coderm.cascade.idempotent.exception;

import cc.coderm.cascade.idempotent.model.IdempotentRecord;

/**
 * 幂等 key 执行所有权丢失异常。
 */
public class IdempotentOwnershipException extends IdempotentException {

    public IdempotentOwnershipException(String message, IdempotentRecord record) {
        super(message, record);
    }

    public IdempotentOwnershipException(String message, IdempotentRecord record, Throwable cause) {
        super(message, record, cause);
    }
}
