package io.github.cascade.cache.v2.policy;

/**
 * 分布式锁失败策略。
 */
public enum LockFailureStrategy {
    /**
     * 默认降级策略：锁失败后继续本地加载流程。
     */
    DEGRADE,
    /**
     * 严格策略：锁失败后不再执行加载。
     */
    STRICT
}

