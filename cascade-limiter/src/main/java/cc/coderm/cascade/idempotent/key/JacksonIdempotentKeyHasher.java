package cc.coderm.cascade.idempotent.key;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.util.DigestUtils;

import java.lang.reflect.Method;

/**
 * 基于 Jackson 的稳定参数哈希实现。
 */
public class JacksonIdempotentKeyHasher implements IdempotentKeyHasher {

    private final ObjectMapper canonicalMapper;

    public JacksonIdempotentKeyHasher() {
        this(new ObjectMapper());
    }

    public JacksonIdempotentKeyHasher(ObjectMapper objectMapper) {
        this.canonicalMapper = objectMapper.copy()
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    @Override
    public String hash(Method method, Object[] args) {
        try {
            byte[] canonicalArgs = canonicalMapper.writeValueAsBytes(args);
            return DigestUtils.md5DigestAsHex(canonicalArgs);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException(
                    "Failed to generate deterministic default idempotent key for method '"
                            + method.toGenericString()
                            + "'. Please provide explicit @Idempotent(key=...) expression.", ex);
        }
    }
}
