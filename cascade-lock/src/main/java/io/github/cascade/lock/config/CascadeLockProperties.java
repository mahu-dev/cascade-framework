package io.github.cascade.lock.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @author lionel lionelk@163.com
 * =============================
 * Date: 2026/4/12
 * Time: 01:01
 * =============================
 */
@Data
@ConfigurationProperties(prefix = "cascade.lock")
public class CascadeLockProperties {

    /**
     * 全局 key 前缀
     */
    private String keyPrefix = "cascade:lock";

    /**
     * 全局默认等待时间（秒），-1 不等待
     */
    private long waitTime = -1;

    /**
     * 全局默认租约时间（秒），-1 看门狗续期
     */
    private long leaseTime = -1;

    /**
     * 是否启用锁
     */
    private boolean enabled = true;
}
