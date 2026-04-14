package cc.coderm.cascade.idempotent.store;

import cc.coderm.cascade.idempotent.model.IdempotentRecord;

import java.util.Optional;

/**
 * 幂等存储接口，抽象 Redis 读写细节。
 *
 * <p>所有写操作都必须满足原子语义，防止并发竞态。
 */
public interface IdempotentStore {

    /**
     * 原子占位。
     *
     * @param key   幂等 key
     * @param scene 场景标识
     * @param ttlMs TTL（毫秒）
     * @param ownerToken 本次执行实例的所有权令牌
     * @return 三态占位结果：
     * OCCUPIED（抢占成功）、
     * CONFLICT（真实冲突，existingRecord 有值）、
     * CONTENDED（瞬时争用/无法确认，建议重试）
     */
    OccupyResult tryOccupy(String key, String scene, long ttlMs, String ownerToken);

    /**
     * 将状态推进为 SUCCEEDED，同时存储序列化结果。
     * 仅当 key 仍处于 PROCESSING 且 ownerToken 匹配时写入成功。
     *
     * @param key        幂等 key
     * @param ownerToken 本次执行实例的所有权令牌
     * @param result     序列化后的业务结果
     * @param resultType 结果类型名
     * @param ttlMs      TTL（毫秒）
     * @return true=状态推进成功；false=所有权丢失或状态不匹配
     */
    boolean markSucceeded(String key, String ownerToken, String result, String resultType, long ttlMs);

    /**
     * 将状态推进为 FAILED。
     *
     * @param key   幂等 key
     * @param ownerToken 本次执行实例的所有权令牌
     * @param ttlMs TTL（毫秒）
     * @return true=状态推进成功；false=所有权丢失或状态不匹配
     */
    boolean markFailed(String key, String ownerToken, long ttlMs);

    /**
     * 将状态推进为 UNCERTAIN（业务已执行但结果提交失败，需 fail-closed）。
     *
     * @param key         幂等 key
     * @param ownerToken  本次执行实例的所有权令牌
     * @param errorReason 提交失败原因
     * @param ttlMs       TTL（毫秒）
     * @return true=状态推进成功；false=所有权丢失或状态不匹配
     */
    boolean markUncertain(String key, String ownerToken, String errorReason, long ttlMs);

    /**
     * 仅当 ownerToken 匹配时删除幂等 key（用于 deleteOnSuccess / deleteOnFailure）。
     */
    boolean deleteIfOwner(String key, String ownerToken);

    /**
     * 对 PROCESSING 状态做续租（自动刷新 TTL）。
     *
     * @return 三态结果：
     * {@link RenewResult#RENEWED}（已确认刷新 TTL）、
     * {@link RenewResult#OWNERSHIP_LOST}（所有权已丢失）、
     * {@link RenewResult#CONTENDED}（瞬时争用/无法确认，建议重试）
     */
    RenewResult renewProcessing(String key, String ownerToken, long ttlMs);

    /**
     * 查询当前幂等记录，不存在时返回 empty。
     */
    Optional<IdempotentRecord> load(String key);

    // ── 内部结果对象 ──

    record OccupyResult(OccupyState state, IdempotentRecord existingRecord) {
        public static OccupyResult success() {
            return new OccupyResult(OccupyState.OCCUPIED, null);
        }

        public static OccupyResult conflict(IdempotentRecord existingRecord) {
            return new OccupyResult(OccupyState.CONFLICT, existingRecord);
        }

        public static OccupyResult contention() {
            return new OccupyResult(OccupyState.CONTENDED, null);
        }

        public boolean occupied() {
            return state == OccupyState.OCCUPIED;
        }

        public boolean conflicted() {
            return state == OccupyState.CONFLICT;
        }

        public boolean contended() {
            return state == OccupyState.CONTENDED;
        }
    }

    enum OccupyState {
        /** 抢占成功，当前请求持有执行所有权。 */
        OCCUPIED,
        /** 存在真实冲突记录（PROCESSING/SUCCEEDED/FAILED/UNCERTAIN）。 */
        CONFLICT,
        /** 存储层瞬时争用/异常，无法确认是否冲突。 */
        CONTENDED
    }

    enum RenewResult {
        /** 已确认刷新 TTL 成功。 */
        RENEWED,
        /** key 不存在 / owner 不匹配 / 状态非 PROCESSING。 */
        OWNERSHIP_LOST,
        /** 短暂锁争用或瞬时异常，无法确认续租结果。 */
        CONTENDED
    }
}
