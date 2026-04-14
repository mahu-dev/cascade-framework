package cc.coderm.cascade.idempotent.exception;

import cc.coderm.cascade.idempotent.model.IdempotentRecord;

/**
 * 幂等结果提交失败异常（业务已执行完成，但状态/结果未能可靠提交）。
 */
public class IdempotentCommitException extends IdempotentException {

    public IdempotentCommitException(String message, IdempotentRecord record) {
        super(message, record);
    }

    public IdempotentCommitException(String message, IdempotentRecord record, Throwable cause) {
        super(message, record, cause);
    }
}
