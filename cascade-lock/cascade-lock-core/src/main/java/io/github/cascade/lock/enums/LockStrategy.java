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
     * waitTime > 0 时在窗口内持续等待；waitTime <= 0 时无限阻塞直到获取锁
     */
    KEEP_TRYING,
    /**
     * 获取失败直接跳过，不执行业务逻辑
     */
    SKIP
}
