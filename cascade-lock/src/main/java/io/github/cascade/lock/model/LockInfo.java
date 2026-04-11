package io.github.cascade.lock.model;

import io.github.cascade.lock.enums.LockStrategy;
import io.github.cascade.lock.enums.LockType;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * @author lionel lionelk@163.com
 * =============================
 * Date: 2026/4/12
 * Time: 01:03
 * =============================
 */
@Data
@Builder
public class LockInfo {

    /**
     * 锁的 key 列表
     * <ul>
     *   <li>size == 1：单锁</li>
     *   <li>size > 1：联锁/红锁</li>
     * </ul>
     */
    private List<String> keys;

    private LockType lockType;
    private LockStrategy lockStrategy;

    /**
     * 等待时间，<= 0 不等待
     */
    private long waitTime;

    /**
     * 租约时间，-1 看门狗续期
     */
    private long leaseTime;

    private TimeUnit timeUnit;

    private String failMessage;

    /**
     * 用于日志、事件、异常信息中的展示 key
     */
    public String getDisplayKey() {
        if (keys.size() == 1) {
            return keys.getFirst();
        }
        return "multi[" + String.join(",", keys) + "]";
    }

    /**
     * 是否为多锁场景
     */
    public boolean isMultiKey() {
        return keys.size() > 1;
    }
}
