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
     * 原子占位：若 key 不存在，则写入 PROCESSING 状态并返回 true（抢占成功）；
     * 若 key 已存在，则返回 false 并将现有记录填充到 existing 中。
     *
     * @param key   幂等 key
     * @param scene 场景标识
     * @param ttlMs TTL（毫秒）
     * @param ownerToken 本次执行实例的所有权令牌
     * @return 抢占结果，包含是否成功 + 已有记录（抢占失败时有值）
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

    record OccupyResult(boolean occupied, IdempotentRecord existingRecord) {
        public static OccupyResult success() {
            return new OccupyResult(true, null);
        }

        public static OccupyResult conflict(IdempotentRecord existingRecord) {
            return new OccupyResult(false, existingRecord);
        }
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
