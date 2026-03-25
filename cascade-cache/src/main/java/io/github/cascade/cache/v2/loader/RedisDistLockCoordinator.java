package io.github.cascade.cache.v2.loader;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 基于 Redisson 的分布式锁协调器。
 */
public class RedisDistLockCoordinator<K> implements DistLockCoordinator<K> {

    private static final Logger LOGGER = LoggerFactory.getLogger(RedisDistLockCoordinator.class);

    private final RedissonClient redissonClient;
    private final String lockPrefix;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RedisDistLockCoordinator(String cacheName, RedissonClient redissonClient, String keyPrefix) {
        this.redissonClient = redissonClient;
        String prefix = keyPrefix == null ? "" : keyPrefix;
        if (!prefix.isEmpty() && !prefix.endsWith(":")) {
            prefix += ":";
        }
        this.lockPrefix = prefix + cacheName + ":lock:";
    }

    @Override
    public <T> T withLock(String cacheName,
                          K key,
                          long waitMs,
                          long leaseMs,
                          Supplier<T> supplier,
                          Runnable onDegrade) {
        if (key == null) {
            return supplier.get();
        }
        RLock lock = redissonClient.getLock(lockPrefix + encodeKey(key));
        boolean locked = false;
        try {
            locked = lock.tryLock(Math.max(0L, waitMs), Math.max(1L, leaseMs), TimeUnit.MILLISECONDS);
            if (!locked) {
                LOGGER.debug("分布式锁获取超时，降级继续执行: cache={}, key={}", cacheName, key);
                if (onDegrade != null) {
                    onDegrade.run();
                }
                return supplier.get();
            }
            return supplier.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.debug("分布式锁等待中断，降级继续执行: cache={}, key={}", cacheName, key);
            if (onDegrade != null) {
                onDegrade.run();
            }
            return supplier.get();
        } catch (Exception e) {
            LOGGER.debug("分布式锁执行失败，降级继续执行: cache={}, key={}, error={}",
                    cacheName, key, e.getMessage());
            if (onDegrade != null) {
                onDegrade.run();
            }
            return supplier.get();
        } finally {
            if (locked) {
                try {
                    if (lock.isHeldByCurrentThread()) {
                        lock.unlock();
                    }
                } catch (Exception ignored) {
                }
            }
        }
    }

    private String encodeKey(K key) {
        try {
            String json = objectMapper.writeValueAsString(key);
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(json.getBytes(StandardCharsets.UTF_8));
        } catch (JsonProcessingException e) {
            return String.valueOf(key);
        }
    }
}
