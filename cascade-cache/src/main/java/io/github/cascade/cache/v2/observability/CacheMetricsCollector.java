package io.github.cascade.cache.v2.observability;

import io.micrometer.core.instrument.MeterRegistry;

import java.util.concurrent.TimeUnit;

/**
 * V2 指标收集器（无注册器时自动降级为 no-op）。
 */
public class CacheMetricsCollector {

    private final MeterRegistry registry;
    private final String cacheName;

    private CacheMetricsCollector(String cacheName, MeterRegistry registry) {
        this.cacheName = cacheName;
        this.registry = registry;
    }

    public static CacheMetricsCollector create(String cacheName, MeterRegistry registry) {
        return new CacheMetricsCollector(cacheName, registry);
    }

    public void incL1Hit() {
        increment("cascade.cache.l1.hit");
    }

    public void incL2Hit() {
        increment("cascade.cache.l2.hit");
    }

    public void incMiss() {
        increment("cascade.cache.miss");
    }

    public void incBackfillL1() {
        increment("cascade.cache.backfill.l1");
    }

    public void incBackfillL2() {
        increment("cascade.cache.backfill.l2");
    }

    public void incRefreshSuccess() {
        increment("cascade.cache.refresh.success");
    }

    public void incRefreshFail() {
        increment("cascade.cache.refresh.fail");
    }

    public void recordRefreshLatency(long latencyMs) {
        if (registry == null || latencyMs < 0) {
            return;
        }
        registry.timer("cascade.cache.refresh.latency", "cache", cacheName)
                .record(latencyMs, TimeUnit.MILLISECONDS);
    }

    public void incSyncPublish() {
        increment("cascade.cache.sync.publish");
    }

    public void incSyncConsume() {
        increment("cascade.cache.sync.consume");
    }

    public void incSyncUpdateFallback() {
        increment("cascade.cache.sync.update.fallback");
    }

    public void recordEventLag(long lagMs) {
        if (registry == null || lagMs < 0) {
            return;
        }
        registry.summary("cascade.cache.sync.event.lag", "cache", cacheName)
                .record(lagMs);
    }

    public void incSingleFlightJoin() {
        increment("cascade.cache.singleflight.join");
    }

    public void incDistLockDegrade() {
        increment("cascade.cache.distlock.degrade");
    }

    private void increment(String metricName) {
        if (registry == null) {
            return;
        }
        registry.counter(metricName, "cache", cacheName).increment();
    }
}
