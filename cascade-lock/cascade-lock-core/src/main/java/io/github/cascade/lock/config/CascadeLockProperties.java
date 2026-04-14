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
     * 全局默认等待时间（单位由注解 timeUnit 指定，默认秒）。
     * <= 0：表示不等待，立即尝试获取锁一次
     * > 0：等待指定时长
     */
    private long waitTime = -1;

    /**
     * 全局默认租约时间（单位由注解 timeUnit 指定，默认秒）。
     * <= 0：使用看门狗自动续期（推荐）
     * > 0：指定持有时间，到期自动释放
     */
    private long leaseTime = -1;

    /**
     * 是否启用锁
     */
    private boolean enabled = true;
}
