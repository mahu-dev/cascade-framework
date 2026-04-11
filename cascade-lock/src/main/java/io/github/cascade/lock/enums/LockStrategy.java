package io.github.cascade.lock.enums;

/**
 * 获取锁失败策略
 *
 * @author lionel lionelk@163.com
 * =============================
 * Date: 2026/4/12
 * Time: 01:02
 * =============================
 */
public enum LockStrategy {
    /**
     * 获取失败立即抛出异常
     */
    FAIL_FAST,
    /**
     * 等待直到获取锁（使用 waitTime）
     */
    KEEP_TRYING,
    /**
     * 获取失败直接跳过，不执行业务逻辑
     */
    SKIP
}
