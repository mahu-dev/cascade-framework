package io.github.cascade.lock.key;

import io.github.cascade.lock.exception.LockException;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 锁 key 规范化与校验工具，供编程式与注解式共用。
 */
public final class LockKeyNormalizer {

    private LockKeyNormalizer() {
    }

    public static String normalizeSingleKey(String key, String source) {
        if (key == null) {
            throw new LockException(source + " 不能为空", "null");
        }
        String normalized = key.trim();
        if (!StringUtils.hasText(normalized)) {
            throw new LockException(source + " 不能为空白", key);
        }
        return normalized;
    }

    public static List<String> normalizeKeyList(List<String> keys, String source) {
        if (CollectionUtils.isEmpty(keys)) {
            throw new LockException(source + " 至少需要一个 key", "[]");
        }
        List<String> normalized = keys.stream()
                .map(k -> normalizeSingleKey(k, source + " 的元素 key"))
                .toList();
        if (normalized.stream().distinct().count() != normalized.size()) {
            throw new LockException(source + " 不允许重复 key", normalized.toString());
        }
        return normalized;
    }

    public static String applyPrefix(String prefix, String key) {
        String normalizedPrefix = prefix == null ? null : prefix.trim();
        return StringUtils.hasText(normalizedPrefix) ? normalizedPrefix + ":" + key : key;
    }
}
