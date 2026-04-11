package io.github.cascade.lock.model;

import lombok.Data;

/**
 * @author lionel lionelk@163.com
 * =============================
 * Date: 2026/4/12
 * Time: 01:03
 * =============================
 */
@Data
public class LockResult<T> {

    private boolean acquired;
    private T result;
    private String lockKey;
    private long acquireCostMs;

    public static <T> LockResult<T> success(T result, String lockKey, long costMs) {
        LockResult<T> r = new LockResult<>();
        r.acquired = true;
        r.result = result;
        r.lockKey = lockKey;
        r.acquireCostMs = costMs;
        return r;
    }

    public static <T> LockResult<T> failed(String lockKey) {
        LockResult<T> r = new LockResult<>();
        r.acquired = false;
        r.lockKey = lockKey;
        return r;
    }
}
