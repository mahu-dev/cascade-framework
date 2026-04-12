package cc.coderm.cascade.idempotent.model;

import lombok.Builder;
import lombok.Getter;

import java.lang.reflect.Method;
import java.lang.reflect.Type;

/**
 * 幂等执行上下文，在 AOP → Executor → Store 之间传递。
 */
@Getter
@Builder
public class IdempotentContext {

    /**
     * 完整的幂等 key（已含前缀）
     */
    private final String idempotentKey;

    /**
     * TTL（毫秒）
     */
    private final long ttlMs;

    /**
     * 场景标识，用于区分不同业务（记录在存储结构中，便于排查）
     */
    private final String scene;

    /**
     * 业务执行成功后是否立即删除幂等 key（适合一次性操作，如发短信）
     */
    private final boolean deleteOnSuccess;

    /**
     * 业务执行失败后是否删除幂等 key（true=允许重试，false=直接返回失败）
     */
    private final boolean deleteOnFailure;

    /**
     * 并发冲突（PROCESSING 状态）时的处理策略
     */
    private final ConflictStrategy conflictStrategy;

    /**
     * 并发等待的最大时间（毫秒），仅 WAIT 策略有效
     */
    private final long waitTimeoutMs;

    /**
     * 目标方法（用于反序列化返回值类型）
     */
    private final Method targetMethod;

    /**
     * 目标方法的泛型返回类型
     */
    private final Type returnType;
}