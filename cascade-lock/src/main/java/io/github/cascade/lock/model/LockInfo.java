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
     * 最终生效的锁 key
     */
    private String lockKey;

    /**
     * 多锁场景下的多个 key
     */
    private List<String> lockKeys;

    private LockType lockType;
    private LockStrategy lockStrategy;

    /**
     * 等待时间，-1 不等待
     */
    private long waitTime;

    /**
     * 租约时间，-1 看门狗续期
     */
    private long leaseTime;

    private TimeUnit timeUnit;

    private String failMessage;
}
