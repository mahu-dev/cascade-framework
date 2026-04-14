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
    private String displayKey;
    private long acquireCostMs;

    public static <T> LockResult<T> success(T result, String displayKey, long costMs) {
        LockResult<T> r = new LockResult<>();
        r.acquired = true;
        r.result = result;
        r.displayKey = displayKey;
        r.acquireCostMs = costMs;
        return r;
    }

    public static <T> LockResult<T> failed(String displayKey) {
        LockResult<T> r = new LockResult<>();
        r.acquired = false;
        r.displayKey = displayKey;
        return r;
    }
}
