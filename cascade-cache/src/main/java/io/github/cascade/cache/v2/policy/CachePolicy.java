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
    private final SyncMode syncMode;
    private final boolean singleFlightEnabled;
    private final boolean distributedLockEnabled;
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
        this.syncMode = builder.syncMode;
        this.singleFlightEnabled = builder.singleFlightEnabled;
        this.distributedLockEnabled = builder.distributedLockEnabled;
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

    public SyncMode getSyncMode() {
        return syncMode;
    }

    public boolean isSingleFlightEnabled() {
        return singleFlightEnabled;
    }

    public boolean isDistributedLockEnabled() {
        return distributedLockEnabled;
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
        private SyncMode syncMode = SyncMode.INVALIDATE;
        private boolean singleFlightEnabled = true;
        private boolean distributedLockEnabled = true;
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

        public Builder syncMode(SyncMode syncMode) {
            this.syncMode = syncMode;
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
            if (syncMode == null) {
                syncMode = SyncMode.INVALIDATE;
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
