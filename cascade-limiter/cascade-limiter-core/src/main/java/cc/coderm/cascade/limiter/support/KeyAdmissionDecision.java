package cc.coderm.cascade.limiter.support;

/**
 * key 准入判断结果。
 */
public record KeyAdmissionDecision(
        boolean allowed,
        String effectiveKey,
        long waitMillis,
        boolean coalesced
) {
    public static KeyAdmissionDecision allowed(String key) {
        return new KeyAdmissionDecision(true, key, 0L, false);
    }

    public static KeyAdmissionDecision coalesced(String key) {
        return new KeyAdmissionDecision(true, key, 0L, true);
    }

    public static KeyAdmissionDecision rejected(long waitMillis) {
        return new KeyAdmissionDecision(false, null, Math.max(0L, waitMillis), false);
    }
}

