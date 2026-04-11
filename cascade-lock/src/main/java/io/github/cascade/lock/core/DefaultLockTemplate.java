package io.github.cascade.lock.core;

import io.github.cascade.lock.config.CascadeLockProperties;
import io.github.cascade.lock.enums.LockStrategy;
import io.github.cascade.lock.enums.LockType;
import io.github.cascade.lock.exception.LockException;
import io.github.cascade.lock.model.LockInfo;
import io.github.cascade.lock.model.LockResult;
import lombok.RequiredArgsConstructor;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

@RequiredArgsConstructor
public class DefaultLockTemplate implements LockTemplate {

    private static final TimeUnit DEFAULT_TIME_UNIT = TimeUnit.SECONDS;

    private final LockExecutor lockExecutor;
    private final CascadeLockProperties properties;

    @Override
    public <T> T lock(String key, Callable<T> action) {
        return lock(key, LockType.REENTRANT, action);
    }

    @Override
    public <T> T lock(String key, LockType lockType, Callable<T> action) {
        validateSingleKeyType(lockType, key);
        LockResult<T> result = lock(
                key,
                lockType,
                defaultWaitTime(),
                defaultLeaseTime(),
                DEFAULT_TIME_UNIT,
                LockStrategy.FAIL_FAST,
                action
        );
        return result.getResult();
    }

    @Override
    public <T> LockResult<T> lock(String key, LockType lockType, long waitTime, long leaseTime,
                                  TimeUnit timeUnit, LockStrategy strategy, Callable<T> action) {
        validateSingleKeyType(lockType, key);
        validateCommonArgs(timeUnit, strategy, action);
        LockInfo lockInfo = buildSingleKeyLockInfo(key, lockType, waitTime, leaseTime, timeUnit, strategy);
        return lockExecutor.execute(lockInfo, action);
    }

    @Override
    public <T> T lock(List<String> keys, LockType lockType, Callable<T> action) {
        LockResult<T> result = lock(
                keys,
                lockType,
                defaultWaitTime(),
                defaultLeaseTime(),
                DEFAULT_TIME_UNIT,
                LockStrategy.FAIL_FAST,
                action
        );
        return result.getResult();
    }

    @Override
    public <T> LockResult<T> lock(List<String> keys, LockType lockType, long waitTime, long leaseTime,
                                  TimeUnit timeUnit, LockStrategy strategy, Callable<T> action) {
        validateCommonArgs(timeUnit, strategy, action);
        List<String> normalizedKeys = normalizeAndValidateKeys(keys);
        validateMultiKeyType(lockType, normalizedKeys);
        LockInfo lockInfo = buildMultiKeyLockInfo(normalizedKeys, lockType, waitTime, leaseTime, timeUnit, strategy);
        return lockExecutor.execute(lockInfo, action);
    }

    @Override
    public <T> T readLock(String key, Callable<T> action) {
        return lock(key, LockType.READ, action);
    }

    @Override
    public <T> T writeLock(String key, Callable<T> action) {
        return lock(key, LockType.WRITE, action);
    }

    @Override
    public <T> T tryLock(String key, Callable<T> action) {
        LockResult<T> result = lock(key, LockType.REENTRANT, -1, -1,
                DEFAULT_TIME_UNIT, LockStrategy.SKIP, action);
        return result.getResult();
    }

    private static void validateSingleKeyType(LockType lockType, String key) {
        if (lockType == null) {
            throw new LockException("单 key API 的 lockType 不能为空", key);
        }
        if (lockType == LockType.RED || lockType == LockType.MULTI) {
            throw new LockException("单 key API 不支持 " + lockType + "，请使用多 key lock(keys, ...)", key);
        }
    }

    private static void validateMultiKeyType(LockType lockType, List<String> keys) {
        if (lockType == null) {
            throw new LockException("多 key API 的 lockType 不能为空", keys.toString());
        }
        if (lockType != LockType.RED && lockType != LockType.MULTI) {
            throw new LockException("多 key API 仅支持 RED / MULTI，当前为 " + lockType, keys.toString());
        }
    }

    /**
     * 规范化并验证多 key 参数
     * <p>
     * 处理逻辑：
     * 1. 校验 key 列表不为空
     * 2. 去除每个 key 的首尾空格（保留 null 以便后续统一校验）
     * 3. 校验不存在空白或 null 的 key
     *
     * @param keys 原始 key 列表
     * @return 规范化后的 key 列表
     * @throws LockException 如果 key 列表为空或包含空白 key
     */
    private static List<String> normalizeAndValidateKeys(List<String> keys) {
        if (CollectionUtils.isEmpty(keys)) {
            throw new LockException("多 key API 至少需要一个 key", "[]");
        }

        List<String> normalized = keys.stream()
                .map(k -> k == null ? null : k.trim())
                .toList();
        if (normalized.stream().anyMatch(k -> !StringUtils.hasText(k))) {
            throw new LockException("多 key API 不允许空白 key", keys.toString());
        }
        return normalized;
    }

    private static <T> void validateCommonArgs(TimeUnit timeUnit, LockStrategy strategy, Callable<T> action) {
        Objects.requireNonNull(timeUnit, "timeUnit 不能为空");
        Objects.requireNonNull(strategy, "strategy 不能为空");
        Objects.requireNonNull(action, "action 不能为空");
    }

    private long defaultWaitTime() {
        return properties.getWaitTime();
    }

    private long defaultLeaseTime() {
        return properties.getLeaseTime();
    }

    private String prefixKey(String key) {
        String prefix = properties.getKeyPrefix();
        return StringUtils.hasText(prefix) ? prefix + ":" + key : key;
    }

    private LockInfo buildSingleKeyLockInfo(String key, LockType lockType,
                                            long waitTime, long leaseTime,
                                            TimeUnit timeUnit, LockStrategy strategy) {
        String finalKey = prefixKey(key);
        return LockInfo.builder()
                .keys(List.of(finalKey))
                .lockType(lockType)
                .waitTime(waitTime)
                .leaseTime(leaseTime)
                .timeUnit(timeUnit)
                .lockStrategy(strategy)
                .failMessage("获取分布式锁失败: " + finalKey)
                .build();
    }

    private LockInfo buildMultiKeyLockInfo(List<String> keys, LockType lockType,
                                           long waitTime, long leaseTime,
                                           TimeUnit timeUnit, LockStrategy strategy) {
        List<String> prefixedKeys = keys.stream().map(this::prefixKey).toList();
        return LockInfo.builder()
                .keys(prefixedKeys)
                .lockType(lockType)
                .waitTime(waitTime)
                .leaseTime(leaseTime)
                .timeUnit(timeUnit)
                .lockStrategy(strategy)
                .failMessage("获取分布式锁失败: " + String.join(",", prefixedKeys))
                .build();
    }
}
