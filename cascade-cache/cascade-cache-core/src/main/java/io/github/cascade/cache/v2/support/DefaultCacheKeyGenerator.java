package io.github.cascade.cache.v2.support;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.aspectj.lang.JoinPoint;

import java.util.Arrays;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 注解路径默认 key 生成器。
 * <p>
 * 仅基于方法参数生成 key，不包含方法签名，
 * 以保证同一 cacheName 下不同注解入口可互通。
 * 使用稳定序列化 + SHA-256，避免 32 位哈希碰撞导致的串值风险。
 */
public final class DefaultCacheKeyGenerator {

    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final ObjectMapper KEY_MAPPER = JsonMapper.builder()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .build();
    private static final ThreadLocal<MessageDigest> SHA_256 = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM不支持SHA-256", e);
        }
    });

    private DefaultCacheKeyGenerator() {
    }

    public static String generate(JoinPoint joinPoint) {
        Object[] args = joinPoint != null && joinPoint.getArgs() != null
                ? joinPoint.getArgs()
                : new Object[0];
        return "args#sha256:" + toHex(sha256(canonicalBytes(args)));
    }

    private static byte[] canonicalBytes(Object[] args) {
        Object[] values = Arrays.copyOf(args, args.length);
        ArgDescriptor[] descriptors = new ArgDescriptor[values.length];
        for (int i = 0; i < values.length; i++) {
            Object value = values[i];
            descriptors[i] = new ArgDescriptor(value == null ? "null" : value.getClass().getName(), value);
        }
        try {
            return KEY_MAPPER.writeValueAsBytes(descriptors);
        } catch (Exception ignored) {
            // 极端场景（无法JSON序列化）退化为深度字符串表示，但仍走SHA-256避免32位碰撞。
            return Arrays.deepToString(descriptors).getBytes(StandardCharsets.UTF_8);
        }
    }

    private static byte[] sha256(byte[] payload) {
        MessageDigest digest = SHA_256.get();
        digest.reset();
        return digest.digest(payload);
    }

    private static String toHex(byte[] bytes) {
        char[] chars = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xFF;
            chars[i * 2] = HEX[value >>> 4];
            chars[i * 2 + 1] = HEX[value & 0x0F];
        }
        return new String(chars);
    }

    private record ArgDescriptor(String type, Object value) {
    }
}
