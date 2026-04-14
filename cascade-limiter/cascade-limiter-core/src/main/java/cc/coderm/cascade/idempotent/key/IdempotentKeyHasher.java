package cc.coderm.cascade.idempotent.key;

import java.lang.reflect.Method;

/**
 * 默认幂等 key 参数哈希策略。
 */
public interface IdempotentKeyHasher {

    /**
     * 为方法参数生成稳定哈希。
     *
     * @param method 目标方法
     * @param args   方法参数
     * @return 哈希值（不含前缀）
     */
    String hash(Method method, Object[] args);
}
