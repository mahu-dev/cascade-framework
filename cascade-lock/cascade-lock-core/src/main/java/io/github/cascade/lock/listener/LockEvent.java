package io.github.cascade.lock.listener;

import lombok.Getter;

import java.time.LocalDateTime;

/**
 * @author lionel lionelk@163.com
 * =============================
 * Date: 2026/4/12
 * Time: 01:03
 * =============================
 */
@Getter
public class LockEvent {

    public enum Type {ACQUIRED, RELEASED, FAILED}

    private final Type type;
    private final String lockKey;
    private final LocalDateTime time;
    private final long threadId;

    private LockEvent(Type type, String lockKey) {
        this.type = type;
        this.lockKey = lockKey;
        this.time = LocalDateTime.now();
        this.threadId = Thread.currentThread().getId();
    }

    public static LockEvent acquired(String key) {
        return new LockEvent(Type.ACQUIRED, key);
    }

    public static LockEvent released(String key) {
        return new LockEvent(Type.RELEASED, key);
    }

    public static LockEvent failed(String key) {
        return new LockEvent(Type.FAILED, key);
    }
}
