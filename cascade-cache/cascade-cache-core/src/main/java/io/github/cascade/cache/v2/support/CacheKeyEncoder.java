package io.github.cascade.cache.v2.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 统一的缓存 key 编码器。
 * <p>
 * 注意：保持与历史实现兼容，仍使用 JSON + Base64URL（无填充）格式，
 * 避免升级后读不到旧版本写入的 Redis 键。
 */
public final class CacheKeyEncoder {

    private static final Logger LOGGER = LoggerFactory.getLogger(CacheKeyEncoder.class);
    private static final Base64.Encoder BASE64_URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private CacheKeyEncoder() {
    }

    public static String encodeKey(ObjectMapper mapper, Object key) {
        if (key == null) {
            return "null";
        }
        ObjectMapper effectiveMapper = mapper != null ? mapper : ObjectMapperHolder.getInstance();
        try {
            String json = effectiveMapper.writeValueAsString(key);
            return BASE64_URL_ENCODER.encodeToString(json.getBytes(StandardCharsets.UTF_8));
        } catch (JsonProcessingException e) {
            LOGGER.debug("缓存key序列化失败，降级为toString: type={}, error={}",
                    key.getClass().getName(), e.getMessage());
            return String.valueOf(key);
        }
    }
}
