package io.github.cascade.cache.v2.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cascade.cache.v2.common.exception.CacheConfigurationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 统一的缓存 key 编码器。
 */
public final class CacheKeyEncoder {

    private static final Logger LOGGER = LoggerFactory.getLogger(CacheKeyEncoder.class);

    private CacheKeyEncoder() {
    }

    /**
     * String-only 约束：缓存业务键必须是 String。
     * <p>
     * 为避免 Redis key 中出现不可见控制字符，对控制字符和反斜杠做可逆转义。
     */
    public static String encodeKey(ObjectMapper mapper, Object key) {
        if (!(key instanceof String rawKey)) {
            String actualType = key == null ? "null" : key.getClass().getName();
            throw new CacheConfigurationException(
                    "key",
                    key,
                    "缓存键必须是String类型: actualType=" + actualType,
                    null
            );
        }
        return escape(rawKey);
    }

    public static String decodeKey(String encodedKey) {
        if (encodedKey == null || encodedKey.isEmpty()) {
            return encodedKey;
        }
        StringBuilder decoded = new StringBuilder(encodedKey.length());
        for (int i = 0; i < encodedKey.length(); i++) {
            char current = encodedKey.charAt(i);
            if (current != '\\') {
                decoded.append(current);
                continue;
            }
            if (i + 1 >= encodedKey.length()) {
                decoded.append('\\');
                continue;
            }
            char marker = encodedKey.charAt(i + 1);
            if (marker == '\\') {
                decoded.append('\\');
                i++;
                continue;
            }
            if (marker == 'u' && i + 5 < encodedKey.length()) {
                String hex = encodedKey.substring(i + 2, i + 6);
                try {
                    decoded.append((char) Integer.parseInt(hex, 16));
                    i += 5;
                    continue;
                } catch (NumberFormatException ignored) {
                }
            }
            decoded.append('\\');
        }
        return decoded.toString();
    }

    private static String escape(String rawKey) {
        if (rawKey.isEmpty()) {
            return rawKey;
        }
        StringBuilder escaped = new StringBuilder(rawKey.length());
        for (int i = 0; i < rawKey.length(); i++) {
            char ch = rawKey.charAt(i);
            if (ch == '\\') {
                escaped.append("\\\\");
                continue;
            }
            if (Character.isISOControl(ch)) {
                escaped.append("\\u");
                String hex = Integer.toHexString(ch).toUpperCase();
                for (int pad = hex.length(); pad < 4; pad++) {
                    escaped.append('0');
                }
                escaped.append(hex);
                continue;
            }
            escaped.append(ch);
        }
        if (LOGGER.isTraceEnabled() && !rawKey.equals(escaped.toString())) {
            LOGGER.trace("缓存键已执行可逆转义: rawKey={}, encodedKey={}", rawKey, escaped);
        }
        return escaped.toString();
    }
}
