package cc.coderm.cascade.idempotent.exception;

import cc.coderm.cascade.idempotent.model.IdempotentRecord;

/**
 * 并发冲突异常：同一 key 正在 PROCESSING，且策略为 FAIL_FAST 或等待超时时抛出。
 *
 * @author lionel lionelk@163.com
 * =============================
 * Date: 2026/4/12
 * Time: 04:58
 * =============================
 */

public class IdempotentConflictException extends IdempotentException {

    public IdempotentConflictException(String key, IdempotentRecord record) {
        super("Idempotent key [" + key + "] is currently being processed by another request.", record);
    }
}