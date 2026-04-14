package cc.coderm.cascade.limiter.support;

/**
 * 限流 key 准入守卫。
 *
 * <p>用于在算法执行前阻断异常 key 扩散（DoS 场景下大量随机 key）。
 */
public interface KeyAdmissionGuard {

    KeyAdmissionDecision admit(String fullKey);

    static KeyAdmissionGuard allowAll() {
        return KeyAdmissionDecision::allowed;
    }
}

