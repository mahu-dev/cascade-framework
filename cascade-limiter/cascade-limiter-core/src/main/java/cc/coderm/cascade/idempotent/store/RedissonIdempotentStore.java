package cc.coderm.cascade.idempotent.store;

import cc.coderm.cascade.idempotent.model.IdempotentRecord;
import cc.coderm.cascade.idempotent.model.IdempotentState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

/**
 * 基于 Redisson 高层能力的幂等存储实现。
 *
 * <p>设计说明：
 * <ul>
 *   <li>一致性：所有状态写入都在短临界区内完成，使用 owner+state 进行 CAS 校验</li>
 *   <li>占位语义：仅当 key 不存在时写入 PROCESSING，已存在记录一律返回冲突</li>
 *   <li>续租：由 Executor 调用 {@link #renewProcessing} 刷新状态 TTL</li>
 * </ul>
 */
@Slf4j
@RequiredArgsConstructor
public class RedissonIdempotentStore implements IdempotentStore {

    private static final String LOCK_SUFFIX = ":owner:lock";
    private static final String NULL_PLACEHOLDER = "__NULL__";
    private static final long STATE_CHANGE_LOCK_WAIT_MS = 1000;
    private static final long RENEW_LOCK_WAIT_MS = 50;
    private static final long OCCUPY_LOCK_WAIT_STEP_MS = 25;
    private static final long OCCUPY_RETRY_TIMEOUT_MS = 500;

    private final RedissonClient redissonClient;

    @Override
    public OccupyResult tryOccupy(String key, String scene, long ttlMs, String ownerToken) {
        RLock lock = lock(key);
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(OCCUPY_RETRY_TIMEOUT_MS);

        while (System.nanoTime() < deadlineNanos) {
            if (tryLockWithWait(lock, OCCUPY_LOCK_WAIT_STEP_MS)) {
                try {
                    RMap<String, String> recordMap = recordMap(key);
                    Map<String, String> existing = recordMap.readAllMap();
                    IdempotentRecord existingRecord = IdempotentRecord.fromMap(existing);
                    if (existingRecord != null) {
                        return OccupyResult.conflict(existingRecord);
                    }

                    long now = System.currentTimeMillis();
                    Map<String, String> values = new HashMap<>();
                    values.put("state", IdempotentState.PROCESSING.name());
                    values.put("scene", scene);
                    values.put("owner", ownerToken);
                    values.put("createdAt", String.valueOf(now));
                    values.put("updatedAt", String.valueOf(now));
                    recordMap.putAll(values);
                    recordMap.expire(Duration.ofMillis(ttlMs));
                    return OccupyResult.success();
                } finally {
                    safeUnlock(lock, key);
                }
            }

            Optional<IdempotentRecord> existing = load(key);
            if (existing.isPresent()) {
                return OccupyResult.conflict(existing.get());
            }

            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }

        Optional<IdempotentRecord> latest = load(key);
        if (latest.isPresent()) {
            return OccupyResult.conflict(latest.get());
        }

        log.warn("[IdempotentStore] key={} lock contention timeout without visible record, return CONTENDED.", key);
        return OccupyResult.contention();
    }

    @Override
    public boolean markSucceeded(String key, String ownerToken, String result, String resultType, long ttlMs) {
        return transitionState(key, ownerToken, ttlMs, recordMap -> {
            recordMap.put("state", IdempotentState.SUCCEEDED.name());
            recordMap.put("result", result != null ? result : NULL_PLACEHOLDER);
            recordMap.put("resultType", resultType != null ? resultType : "");
            recordMap.remove("error");
        });
    }

    @Override
    public boolean markFailed(String key, String ownerToken, long ttlMs) {
        return transitionState(key, ownerToken, ttlMs, recordMap -> {
            recordMap.put("state", IdempotentState.FAILED.name());
            recordMap.remove("error");
            recordMap.remove("result");
            recordMap.remove("resultType");
        });
    }

    @Override
    public boolean markUncertain(String key, String ownerToken, String errorReason, long ttlMs) {
        return transitionState(key, ownerToken, ttlMs, recordMap -> {
            recordMap.put("state", IdempotentState.UNCERTAIN.name());
            recordMap.put("error", errorReason != null ? errorReason : "");
            recordMap.remove("result");
            recordMap.remove("resultType");
        });
    }

    @Override
    public boolean deleteIfOwner(String key, String ownerToken) {
        RLock lock = lock(key);
        if (!tryLockWithWait(lock, STATE_CHANGE_LOCK_WAIT_MS)) {
            return false;
        }

        try {
            RMap<String, String> recordMap = recordMap(key);
            if (!isOwnerProcessingRecord(recordMap, ownerToken)) {
                return false;
            }
            return redissonClient.getKeys().delete(key) > 0;
        } finally {
            safeUnlock(lock, key);
        }
    }

    @Override
    public RenewResult renewProcessing(String key, String ownerToken, long ttlMs) {
        RLock lock = lock(key);
        if (!tryLockWithWait(lock, RENEW_LOCK_WAIT_MS)) {
            return RenewResult.CONTENDED;
        }

        try {
            RMap<String, String> recordMap = recordMap(key);
            if (!isOwnerProcessingRecord(recordMap, ownerToken)) {
                return RenewResult.OWNERSHIP_LOST;
            }
            recordMap.put("updatedAt", String.valueOf(System.currentTimeMillis()));
            boolean ttlRefreshed = recordMap.expire(Duration.ofMillis(ttlMs));
            return ttlRefreshed ? RenewResult.RENEWED : RenewResult.CONTENDED;
        } finally {
            safeUnlock(lock, key);
        }
    }

    @Override
    public Optional<IdempotentRecord> load(String key) {
        Map<String, String> all = recordMap(key).readAllMap();
        return Optional.ofNullable(IdempotentRecord.fromMap(all));
    }

    private boolean transitionState(String key,
                                    String ownerToken,
                                    long ttlMs,
                                    Consumer<RMap<String, String>> mutator) {
        RLock lock = lock(key);
        if (!tryLockWithWait(lock, STATE_CHANGE_LOCK_WAIT_MS)) {
            return false;
        }

        try {
            RMap<String, String> recordMap = recordMap(key);
            if (!isOwnerProcessingRecord(recordMap, ownerToken)) {
                return false;
            }
            mutator.accept(recordMap);
            recordMap.put("updatedAt", String.valueOf(System.currentTimeMillis()));
            recordMap.remove("owner");
            recordMap.expire(Duration.ofMillis(ttlMs));
            return true;
        } finally {
            safeUnlock(lock, key);
        }
    }

    private RLock lock(String key) {
        return redissonClient.getLock(key + LOCK_SUFFIX);
    }

    private RMap<String, String> recordMap(String key) {
        return redissonClient.getMap(key, StringCodec.INSTANCE);
    }

    private static boolean isOwnerProcessingRecord(RMap<String, String> recordMap, String ownerToken) {
        String owner = recordMap.get("owner");
        String state = recordMap.get("state");
        return ownerToken.equals(owner) && IdempotentState.PROCESSING.name().equals(state);
    }

    private static void safeUnlock(RLock lock, String key) {
        try {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        } catch (IllegalMonitorStateException ex) {
            log.warn("[IdempotentStore] unlock skipped due ownership loss, key={}", key);
        }
    }

    private static boolean tryLockWithWait(RLock lock, long waitMs) {
        try {
            return lock.tryLock(waitMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
