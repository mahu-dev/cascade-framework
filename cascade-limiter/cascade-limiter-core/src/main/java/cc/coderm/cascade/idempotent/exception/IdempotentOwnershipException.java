package cc.coderm.cascade.idempotent.exception;

import cc.coderm.cascade.idempotent.model.IdempotentRecord;

/**
 * 幂等key执行所有权丢失异常。
 *
 * <p>可能的原因：
 * <ul>
 *   <li>续期失败超过TTL，key已过期</li>
 *   <li>其他实例强占key</li>
 * </ul>
 *
 * <p><strong>重要</strong>：业务逻辑可能已经执行完成（如数据库已提交），
 * 但无法确认幂等状态。调用方<strong>不应自动重试</strong>，需要人工介入或
 * 通过其他渠道确认业务状态。
 */
public class IdempotentOwnershipException extends IdempotentException {

    public IdempotentOwnershipException(String message, IdempotentRecord record) {
        super(message, record);
    }

    public IdempotentOwnershipException(String message, IdempotentRecord record, Throwable cause) {
        super(message, record, cause);
    }
}
