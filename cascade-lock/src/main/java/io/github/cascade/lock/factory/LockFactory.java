package io.github.cascade.lock.factory;

import io.github.cascade.lock.enums.LockType;
import io.github.cascade.lock.exception.LockException;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.List;

/**
 * Redisson 锁工厂，负责根据类型创建对应 RLock
 *
 * @author lionel lionelk@163.com
 * =============================
 * Date: 2026/4/12
 * Time: 01:03
 * =============================
 */
@RequiredArgsConstructor
public class LockFactory {

    private final RedissonClient redissonClient;

    public RLock getLock(LockType lockType, String key) {
        if (lockType == null) {
            throw new LockException("lockType 不能为空", key);
        }
        return switch (lockType) {
            case REENTRANT -> redissonClient.getLock(key);
            case FAIR -> redissonClient.getFairLock(key);
            case READ -> redissonClient.getReadWriteLock(key).readLock();
            case WRITE -> redissonClient.getReadWriteLock(key).writeLock();
            default -> throw new LockException("单 key 不支持 " + lockType + " 类型", key);
        };
    }

    public RLock getMultiLock(LockType lockType, List<String> keys) {
        if (lockType == null) {
            throw new LockException("lockType 不能为空", String.valueOf(keys));
        }
        RLock[] locks = keys.stream()
                .map(redissonClient::getLock)
                .toArray(RLock[]::new);

        return switch (lockType) {
            // RED 原使用已弃用的 getRedLock()，Redisson 3.17.6+ 推荐统一使用 getMultiLock()
            case RED, MULTI -> redissonClient.getMultiLock(locks);
            default -> throw new LockException("多 key 仅支持 RED / MULTI 类型", keys.toString());
        };
    }
}
