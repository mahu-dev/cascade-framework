package io.github.cascade.cache.v2.policy;

/**
 * V2 缓存策略。
 * <p>
 * 该对象是统一引擎的只读运行策略，负责把配置中心、注解参数、Builder 参数
 * 收敛为一份可直接执行的缓存语义。
 */
public final class CachePolicy {
    /**
     * 是否启用本地 L1 命中层。
     */
    private final boolean l1Enabled;
    /**
     * 是否启用共享 L2 命中层。
     */
    private final boolean l2Enabled;
    /**
     * 硬过期时间，超过后必须重新加载。
     */
    private final long hardTtlSeconds;
    /**
     * 软过期时间，超过后允许旧值兜底并触发刷新。
     */
    private final long softTtlSeconds;
    /**
     * 定时扫描热点 key 的刷新周期。
     */
    private final long refreshIntervalSeconds;
    /**
     * 是否启用自动刷新链路。
     */
    private final boolean autoRefreshEnabled;
    /**
     * 刷新线程池、超时、重试等执行细节。
     */
    private final RefreshExecutionOptions refreshExecutionOptions;
    /**
     * 多节点同步模式。
     */
    private final SyncMode syncMode;
    /**
     * 是否允许以 UPDATE 事件直接下发新值。
     */
    private final boolean syncUpdateEnabled;
    /**
     * UPDATE 事件可携带的最大 payload 大小。
     */
    private final int syncUpdateMaxPayloadBytes;
    /**
     * 是否合并同 key 并发加载。
     */
    private final boolean singleFlightEnabled;
    /**
     * 是否启用分布式锁，约束跨节点并发回源。
     */
    private final boolean distributedLockEnabled;
    /**
     * 分布式锁失败时的降级策略。
     */
    private final LockFailureStrategy lockFailureStrategy;
    /**
     * 分布式锁获取等待时间。
     */
    private final long distributedLockWaitMs;
    /**
     * 分布式锁租期。
     */
    private final long distributedLockLeaseMs;
    /**
     * key 被视为热点前需要累计的访问次数。
     */
    private final int hotKeyAccessThreshold;
    /**
     * 最多追踪多少个热点 key 参与自动刷新。
     */
    private final int maxTrackedKeys;

    private CachePolicy(Builder builder) {
        this.l1Enabled = builder.l1Enabled;
        this.l2Enabled = builder.l2Enabled;
        this.hardTtlSeconds = builder.hardTtlSeconds;
        this.softTtlSeconds = builder.softTtlSeconds;
        this.refreshIntervalSeconds = builder.refreshIntervalSeconds;
        this.autoRefreshEnabled = builder.autoRefreshEnabled;
        this.refreshExecutionOptions = builder.refreshExecutionOptions;
        this.syncMode = builder.syncMode;
        this.syncUpdateEnabled = builder.syncUpdateEnabled;
        this.syncUpdateMaxPayloadBytes = builder.syncUpdateMaxPayloadBytes;
        this.singleFlightEnabled = builder.singleFlightEnabled;
        this.distributedLockEnabled = builder.distributedLockEnabled;
        this.lockFailureStrategy = builder.lockFailureStrategy;
        this.distributedLockWaitMs = builder.distributedLockWaitMs;
        this.distributedLockLeaseMs = builder.distributedLockLeaseMs;
        this.hotKeyAccessThreshold = builder.hotKeyAccessThreshold;
        this.maxTrackedKeys = builder.maxTrackedKeys;
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean isL1Enabled() {
        return l1Enabled;
    }

    public boolean isL2Enabled() {
        return l2Enabled;
    }

    public long getHardTtlSeconds() {
        return hardTtlSeconds;
    }

    public long getSoftTtlSeconds() {
        return softTtlSeconds;
    }

    public long getRefreshIntervalSeconds() {
        return refreshIntervalSeconds;
    }

    public boolean isAutoRefreshEnabled() {
        return autoRefreshEnabled;
    }

    public RefreshExecutionOptions getRefreshExecutionOptions() {
        return refreshExecutionOptions;
    }

    public SyncMode getSyncMode() {
        return syncMode;
    }

    public boolean isSyncUpdateEnabled() {
        return syncUpdateEnabled;
    }

    public int getSyncUpdateMaxPayloadBytes() {
        return syncUpdateMaxPayloadBytes;
    }

    public boolean isSingleFlightEnabled() {
        return singleFlightEnabled;
    }

    public boolean isDistributedLockEnabled() {
        return distributedLockEnabled;
    }

    public LockFailureStrategy getLockFailureStrategy() {
        return lockFailureStrategy;
    }

    public long getDistributedLockWaitMs() {
        return distributedLockWaitMs;
    }

    public long getDistributedLockLeaseMs() {
        return distributedLockLeaseMs;
    }

    public int getHotKeyAccessThreshold() {
        return hotKeyAccessThreshold;
    }

    public int getMaxTrackedKeys() {
        return maxTrackedKeys;
    }

    public static final class Builder {
        /**
         * Builder 默认即启用 L1，本地命中优先。
         */
        private boolean l1Enabled = true;
        /**
         * Builder 默认启用 L2，用于共享与回填。
         */
        private boolean l2Enabled = true;
        /**
         * 默认硬 TTL。
         */
        private long hardTtlSeconds = 1800;
        /**
         * 默认软 TTL。
         */
        private long softTtlSeconds = 300;
        /**
         * 默认刷新扫描周期。
         */
        private long refreshIntervalSeconds = 300;
        /**
         * 默认开启自动刷新。
         */
        private boolean autoRefreshEnabled = true;
        private RefreshExecutionOptions refreshExecutionOptions = RefreshExecutionOptions.defaults();
        private SyncMode syncMode = SyncMode.INVALIDATE;
        private boolean syncUpdateEnabled = false;
        private int syncUpdateMaxPayloadBytes = 16 * 1024;
        private boolean singleFlightEnabled = true;
        private boolean distributedLockEnabled = true;
        private LockFailureStrategy lockFailureStrategy = LockFailureStrategy.DEGRADE;
        private long distributedLockWaitMs = 200;
        private long distributedLockLeaseMs = 3000;
        private int hotKeyAccessThreshold = 3;
        private int maxTrackedKeys = 10_000;

        public Builder l1Enabled(boolean l1Enabled) {
            this.l1Enabled = l1Enabled;
            return this;
        }

        public Builder l2Enabled(boolean l2Enabled) {
            this.l2Enabled = l2Enabled;
            return this;
        }

        public Builder hardTtlSeconds(long hardTtlSeconds) {
            this.hardTtlSeconds = hardTtlSeconds;
            return this;
        }

        public Builder softTtlSeconds(long softTtlSeconds) {
            this.softTtlSeconds = softTtlSeconds;
            return this;
        }

        public Builder refreshIntervalSeconds(long refreshIntervalSeconds) {
            this.refreshIntervalSeconds = refreshIntervalSeconds;
            return this;
        }

        public Builder autoRefreshEnabled(boolean autoRefreshEnabled) {
            this.autoRefreshEnabled = autoRefreshEnabled;
            return this;
        }

        public Builder refreshExecutionOptions(RefreshExecutionOptions refreshExecutionOptions) {
            this.refreshExecutionOptions = refreshExecutionOptions;
            return this;
        }

        public Builder syncMode(SyncMode syncMode) {
            this.syncMode = syncMode;
            return this;
        }

        public Builder syncUpdateEnabled(boolean syncUpdateEnabled) {
            this.syncUpdateEnabled = syncUpdateEnabled;
            return this;
        }

        public Builder syncUpdateMaxPayloadBytes(int syncUpdateMaxPayloadBytes) {
            this.syncUpdateMaxPayloadBytes = syncUpdateMaxPayloadBytes;
            return this;
        }

        public Builder singleFlightEnabled(boolean singleFlightEnabled) {
            this.singleFlightEnabled = singleFlightEnabled;
            return this;
        }

        public Builder distributedLockEnabled(boolean distributedLockEnabled) {
            this.distributedLockEnabled = distributedLockEnabled;
            return this;
        }

        public Builder lockFailureStrategy(LockFailureStrategy lockFailureStrategy) {
            this.lockFailureStrategy = lockFailureStrategy;
            return this;
        }

        public Builder distributedLockWaitMs(long distributedLockWaitMs) {
            this.distributedLockWaitMs = distributedLockWaitMs;
            return this;
        }

        public Builder distributedLockLeaseMs(long distributedLockLeaseMs) {
            this.distributedLockLeaseMs = distributedLockLeaseMs;
            return this;
        }

        public Builder hotKeyAccessThreshold(int hotKeyAccessThreshold) {
            this.hotKeyAccessThreshold = hotKeyAccessThreshold;
            return this;
        }

        public Builder maxTrackedKeys(int maxTrackedKeys) {
            this.maxTrackedKeys = maxTrackedKeys;
            return this;
        }

        /**
         * 规整配置并构造只读策略对象。
         * <p>
         * 这里会补齐默认值，并修正软 TTL、刷新周期、锁参数之间的边界关系。
         */
        public CachePolicy build() {
            if (hardTtlSeconds == 0) {
                hardTtlSeconds = 1800;
            }
            if (softTtlSeconds <= 0) {
                softTtlSeconds = Math.max(1, hardTtlSeconds / 3);
            }
            if (hardTtlSeconds > 0 && softTtlSeconds > hardTtlSeconds) {
                softTtlSeconds = hardTtlSeconds;
            }
            if (refreshIntervalSeconds <= 0) {
                refreshIntervalSeconds = Math.max(1, softTtlSeconds);
            }
            if (refreshExecutionOptions == null) {
                refreshExecutionOptions = RefreshExecutionOptions.defaults();
            }
            if (syncMode == null) {
                syncMode = SyncMode.INVALIDATE;
            }
            if (syncUpdateMaxPayloadBytes <= 0) {
                syncUpdateMaxPayloadBytes = 16 * 1024;
            }
            if (lockFailureStrategy == null) {
                lockFailureStrategy = LockFailureStrategy.DEGRADE;
            }
            if (distributedLockWaitMs < 0) {
                distributedLockWaitMs = 0;
            }
            if (distributedLockLeaseMs <= 0) {
                distributedLockLeaseMs = 3000;
            }
            if (hotKeyAccessThreshold <= 0) {
                hotKeyAccessThreshold = 30;
            }
            if (maxTrackedKeys <= 0) {
                maxTrackedKeys = 10_000;
            }
            return new CachePolicy(this);
        }
    }
}
