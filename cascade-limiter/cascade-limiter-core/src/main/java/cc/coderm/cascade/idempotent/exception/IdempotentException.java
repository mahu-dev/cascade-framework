package cc.coderm.cascade.idempotent.exception;

import cc.coderm.cascade.idempotent.model.IdempotentRecord;
import lombok.Getter;

/**
 * 幂等异常基类
 *
 * @author lionel lionelk@163.com
 * =============================
 * Date: 2026/4/12
 * Time: 04:58
 * =============================
 */

@Getter
public class IdempotentException extends RuntimeException {

    private final IdempotentRecord record;

    public IdempotentException(String message, IdempotentRecord record) {
        super(message);
        this.record = record;
    }

    public IdempotentException(String message, IdempotentRecord record, Throwable cause) {
        super(message, cause);
        this.record = record;
    }
}
