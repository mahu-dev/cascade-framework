package cc.coderm.cascade.idempotent.key;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONException;
import org.springframework.util.DigestUtils;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

/**
 * 基于 FastJson2 的默认幂等参数哈希实现。
 */
public class FastJson2IdempotentKeyHasher implements IdempotentKeyHasher {

    @Override
    public String hash(Method method, Object[] args) {
        try {
            String canonical = CanonicalJsonSupport.canonicalize(JSON.toJSON(args));
            return DigestUtils.md5DigestAsHex(canonical.getBytes(StandardCharsets.UTF_8));
        } catch (JSONException ex) {
            throw new IllegalArgumentException(
                    "Failed to generate deterministic default idempotent key for method '"
                            + method.toGenericString()
                            + "'. Please provide explicit @Idempotent(key=...) expression.", ex);
        }
    }
}
