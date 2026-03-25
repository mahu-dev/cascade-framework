package io.github.cascade.cache.v2.policy;

/**
 * V2 同步模式。
 */
public enum SyncMode {
    /**
     * 不做跨节点同步。
     */
    NONE,
    /**
     * 失效同步（推荐）：仅广播 key + version，远端淘汰本地 L1。
     */
    INVALIDATE,
    /**
     * 值更新同步：广播 value，远端直接更新本地缓存。
     * 当前版本保留枚举，后续按需扩展。
     */
    UPDATE
}

