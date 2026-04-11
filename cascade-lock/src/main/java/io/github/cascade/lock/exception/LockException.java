package io.github.cascade.lock.exception;

/**
 * @author lionel lionelk@163.com
 * =============================
 * Date: 2026/4/12
 * Time: 01:02
 * =============================
 */
public class LockException extends RuntimeException {

    private final String lockKey;

    public LockException(String message, String lockKey) {
        super(message);
        this.lockKey = lockKey;
    }

    public LockException(String message, String lockKey, Throwable cause) {
        super(message, cause);
        this.lockKey = lockKey;
    }

    public String getLockKey() {
        return lockKey;
    }
}