package io.github.cascade.lock.enums;

/**
 * 分布式锁类型
 *
 * @author lionel lionelk@163.com
 * =============================
 * Date: 2026/4/12
 * Time: 01:02
 * =============================
 */
public enum LockType {
    /**
     * 可重入锁（默认）
     */
    REENTRANT,
    /**
     * 公平锁（按请求顺序获取）
     */
    FAIR,
    /**
     * 读锁（共享）
     */
    READ,
    /**
     * 写锁（独占）
     */
    WRITE,
    /**
     * 红锁（多Redis实例，强一致）
     */
    RED,
    /**
     * 联锁（同时持有多把锁）
     */
    MULTI
}
