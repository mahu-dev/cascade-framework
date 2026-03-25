package io.github.cascade.cache.v2.consistency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 基于 Redis 的版本管理器。
 */
public class RedisVersionManager<K> implements VersionManager<K> {

    private final RedissonClient redissonClient;
    private final String keyPrefix;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RedisVersionManager(String cacheName, RedissonClient redissonClient, String prefix) {
        this.redissonClient = redissonClient;
        String finalPrefix = prefix == null ? "" : prefix;
        if (!finalPrefix.isEmpty() && !finalPrefix.endsWith(":")) {
            finalPrefix += ":";
        }
        this.keyPrefix = finalPrefix + cacheName + ":";
    }

    @Override
    public long nextVersion(K key) {
        return redissonClient.getAtomicLong(versionKey(key)).incrementAndGet();
    }

    @Override
    public long currentVersion(K key) {
        return redissonClient.getAtomicLong(versionKey(key)).get();
    }

    @Override
    public long nextClearVersion() {
        return redissonClient.getAtomicLong(clearVersionKey()).incrementAndGet();
    }

    @Override
    public long currentClearVersion() {
        return redissonClient.getAtomicLong(clearVersionKey()).get();
    }

    private String versionKey(K key) {
        return keyPrefix + "ver:" + encodeKey(key);
    }

    private String clearVersionKey() {
        return keyPrefix + "ver:clear";
    }

    private String encodeKey(K key) {
        if (key == null) {
            return "null";
        }
        try {
            String json = objectMapper.writeValueAsString(key);
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(json.getBytes(StandardCharsets.UTF_8));
        } catch (JsonProcessingException e) {
            return String.valueOf(key);
        }
    }
}
