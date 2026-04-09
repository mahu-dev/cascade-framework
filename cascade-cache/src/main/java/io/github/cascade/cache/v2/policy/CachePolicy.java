package io.github.cascade.cache.v2.policy;

/**
 * V2 缓存策略。
 */
public final class CachePolicy {

    private final boolean l1Enabled;
    private final boolean l2Enabled;
    private final long hardTtlSeconds;
    private final long softTtlSeconds;
    private final long refreshIntervalSeconds;
    private final boolean autoRefreshEnabled;
    private final RefreshExecutionOptions refreshExecutionOptions;
    private final SyncMode syncMode;
    private final boolean syncUpdateEnabled;
    private final int syncUpdateMaxPayloadBytes;
    private final boolean singleFlightEnabled;
    private final boolean distributedLockEnabled;
    private final LockFailureStrategy lockFailureStrategy;
    private final long distributedLockWaitMs;
    private final long distributedLockLeaseMs;
    private final int hotKeyAccessThreshold;
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
        private boolean l1Enabled = true;
        private boolean l2Enabled = true;
        private long hardTtlSeconds = 1800;
        private long softTtlSeconds = 300;
        private long refreshIntervalSeconds = 300;
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
                hotKeyAccessThreshold = 1;
            }
            if (maxTrackedKeys <= 0) {
                maxTrackedKeys = 10_000;
            }
            return new CachePolicy(this);
        }
    }
}
