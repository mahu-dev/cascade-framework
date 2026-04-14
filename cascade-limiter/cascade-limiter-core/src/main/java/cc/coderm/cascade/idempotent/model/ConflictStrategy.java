package cc.coderm.cascade.idempotent.model;

/**
 * 并发冲突策略：当检测到同一幂等 key 处于 PROCESSING 状态时的行为。
 */
public enum ConflictStrategy {

    /**
     * 轮询等待，直到前一个请求完成或超时。
     * 适合幂等操作执行时间较短（< 1s）、调用方可接受等待的场景。
     */
    WAIT,

    /**
     * 立即抛出 {@link cc.coderm.cascade.idempotent.exception.IdempotentConflictException}。
     * 适合调用方有自己的重试机制，或不希望线程被阻塞的场景。
     */
    FAIL_FAST,

    /**
     * 执行 fallback 方法（必须显式配置 {@code fallbackMethod}）。
     * 适合对并发冲突有本地降级处理逻辑的场景。
     */
    FALLBACK
}
