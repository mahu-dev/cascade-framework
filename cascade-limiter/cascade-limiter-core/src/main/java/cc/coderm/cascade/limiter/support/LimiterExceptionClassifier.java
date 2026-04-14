package cc.coderm.cascade.limiter.support;

import org.redisson.client.RedisException;

/**
 * 分类限流执行链路中的异常来源。
 *
 * <p>仅将底层限流后端（Redis/Redisson）异常纳入 fail-open/fail-closed 策略；
 * 本地配置错误、参数错误、算法映射错误应直接抛出，避免静默绕过限流。
 */
public final class LimiterExceptionClassifier {

    private static final int MAX_CAUSE_DEPTH = 16;

    private LimiterExceptionClassifier() {
    }

    public static boolean isBackendException(Throwable error) {
        Throwable current = error;
        int depth = 0;
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            if (current instanceof RedisException) {
                return true;
            }
            current = current.getCause();
            depth++;
        }
        return false;
    }
}
