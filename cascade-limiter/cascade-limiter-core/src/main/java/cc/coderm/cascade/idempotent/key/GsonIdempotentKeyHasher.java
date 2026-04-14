package cc.coderm.cascade.idempotent.key;

import com.google.gson.Gson;
import org.springframework.util.DigestUtils;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

/**
 * 基于 Gson 的默认幂等参数哈希实现。
 */
public class GsonIdempotentKeyHasher implements IdempotentKeyHasher {

    private final Gson gson;

    public GsonIdempotentKeyHasher() {
        this(new Gson());
    }

    public GsonIdempotentKeyHasher(Gson gson) {
        this.gson = gson;
    }

    @Override
    public String hash(Method method, Object[] args) {
        try {
            Object normalized = gson.fromJson(gson.toJson(args), Object.class);
            String canonical = CanonicalJsonSupport.canonicalize(normalized);
            return DigestUtils.md5DigestAsHex(canonical.getBytes(StandardCharsets.UTF_8));
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException(
                    "Failed to generate deterministic default idempotent key for method '"
                            + method.toGenericString()
                            + "'. Please provide explicit @Idempotent(key=...) expression.", ex);
        }
    }
}
