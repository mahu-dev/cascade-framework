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

    private CachePolicy(Builder builder) {
        this.l1Enabled = builder.l1Enabled;
        this.l2Enabled = builder.l2Enabled;
        this.hardTtlSeconds = builder.hardTtlSeconds;
        this.softTtlSeconds = builder.softTtlSeconds;
        this.refreshIntervalSeconds = builder.refreshIntervalSeconds;
        this.autoRefreshEnabled = builder.autoRefreshEnabled;
        this.syncMode = builder.syncMode;
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

    public static final class Builder {
        private boolean l1Enabled = true;
        private boolean l2Enabled = true;
        private long hardTtlSeconds = 1800;
        private long softTtlSeconds = 300;
        private long refreshIntervalSeconds = 300;
        private boolean autoRefreshEnabled = true;
        private SyncMode syncMode = SyncMode.INVALIDATE;

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
            return new CachePolicy(this);
        }
    }
}

