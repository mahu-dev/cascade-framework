package cc.coderm.cascade.idempotent.model;

/**
 * 幂等请求的生命周期状态。
 *
 * <p>状态流转：
 * <pre>
 *   （无 key）─── 首次请求 ──→ PROCESSING ─── 业务成功 ──→ SUCCEEDED
 *                                         └─── 业务异常 ──→ FAILED
 *                                         └─── 提交异常 ──→ UNCERTAIN
 *
 *   PROCESSING  ← 并发请求到达，按策略等待或快速失败
 *   SUCCEEDED   ← 重复请求直接重放缓存结果
 *   FAILED      ← 上次失败，按 deleteOnFailure 决定是否允许重试
 *   UNCERTAIN   ← 业务可能已成功但提交失败，重复请求必须 fail-closed
 * </pre>
 */
public enum IdempotentState {

    /**
     * 正在执行，其他并发请求需等待或快速失败
     */
    PROCESSING,

    /**
     * 已成功执行，后续重复请求直接返回缓存结果
     */
    SUCCEEDED,

    /**
     * 执行失败，由 deleteOnFailure 决定是否清除允许重试
     */
    FAILED,

    /**
     * 业务执行结果提交失败（例如序列化/存储异常），处于不确定态。
     */
    UNCERTAIN
}
