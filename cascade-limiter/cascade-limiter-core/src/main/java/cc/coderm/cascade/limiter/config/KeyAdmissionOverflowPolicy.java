package cc.coderm.cascade.limiter.config;

/**
 * 新 key 准入超过上限时的处理策略。
 */
public enum KeyAdmissionOverflowPolicy {
    /**
     * 直接拒绝请求（返回限流拒绝结果）。
     */
    REJECT,
    /**
     * 将新 key 汇聚到固定兜底 key，避免无限制创建新 key。
     */
    COALESCE
}

