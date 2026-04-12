package cc.coderm.cascade.idempotent.key;

import java.lang.reflect.Method;

/**
 * 在缺少默认哈希实现时提供明确失败提示。
 */
public class UnsupportedDefaultIdempotentKeyHasher implements IdempotentKeyHasher {

    @Override
    public String hash(Method method, Object[] args) {
        throw new IllegalStateException(
                "No default idempotent key hasher available for method '"
                        + method.toGenericString()
                        + "'. Add Jackson to classpath, provide a custom IdempotentKeyHasher bean, "
                        + "or set explicit @Idempotent(key=...) expression.");
    }
}
