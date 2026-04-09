package io.github.cascade.cache.configuration;

import io.github.cascade.cache.v2.policy.LockFailureStrategy;
import io.github.cascade.cache.v2.policy.SyncMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CascadeCachePropertiesTest {

    @Test
    void shouldDeepCopyAllNestedConfigurationAndKeepIsolation() {
        CascadeCacheProperties source = CascadeCacheProperties.defaults();
        source.setEnabled(false);
        source.setDefaultCacheName("orders");

        source.getL1().setEnabled(false);
        source.getL1().setMaximumSize(2048L);
        source.getL1().setExpireAfterWriteSeconds(99L);
        source.getL1().setExpireAfterAccessSeconds(77L);
        source.getL1().setRecordStats(false);
        source.getL1().setInitialCapacity(128);

        source.getL2().setEnabled(true);
        source.getL2().setKeyPrefix("biz:");
        source.getL2().setDefaultTtlSeconds(1234L);
        source.getL2().setEnableBatch(false);
        source.getL2().setBatchSize(42);
        source.getL2().setTimeoutSeconds(9L);

        source.getSync().setEnabled(true);
        source.getSync().setMode(SyncMode.UPDATE);
        source.getSync().setType(SyncProperties.SyncType.CUSTOM);
        source.getSync().setTopicPrefix("sync:test:");
        source.getSync().setAsyncPublish(false);
        source.getSync().setTimeoutMs(333L);
        source.getSync().setBatchSize(55);
        source.getSync().setPublishThreadPoolSize(7);
        source.getSync().setPublishQueueCapacity(888);
        source.getSync().setPublishMaxRetries(6);
        source.getSync().setPublishRetryBackoffMs(123L);
        source.getSync().setUpdateEnabled(true);
        source.getSync().setUpdateMaxPayloadBytes(4096);

        source.getRefresh().setEnabled(false);
        source.getRefresh().setDefaultRefreshIntervalSeconds(45L);
        source.getRefresh().setMinRefreshIntervalSeconds(12L);
        source.getRefresh().setMaxRefreshIntervalSeconds(360L);
        source.getRefresh().setDistributedRefresh(true);
        source.getRefresh().setThreadPoolSize(5);
        source.getRefresh().setQueueCapacity(321);
        source.getRefresh().setAllowConcurrentRefresh(true);
        source.getRefresh().setRefreshTimeoutSeconds(18L);
        source.getRefresh().setMaxRetries(4);
        source.getRefresh().setRetryIntervalSeconds(8L);
        source.getRefresh().setStartOnInit(false);
        source.getRefresh().setShutdownTimeoutSeconds(22L);

        source.getLoader().setAutoDiscover(false);
        source.getLoader().setEnableStats(false);
        source.getLoader().setTimeoutSeconds(66L);

        source.getProtection().setSingleFlightEnabled(false);
        source.getProtection().setDistributedLockEnabled(false);
        source.getProtection().setDistributedLockWaitMs(444L);
        source.getProtection().setDistributedLockLeaseMs(555L);
        source.getProtection().setLockFailureStrategy(LockFailureStrategy.STRICT);
        source.getProtection().setHotKeyAccessThreshold(10);
        source.getProtection().setMaxTrackedKeys(999);

        CascadeCacheProperties copied = source.deepCopy();

        assertEquals(source, copied, "深拷贝后配置值应完全一致");
        assertNotSame(source, copied);
        assertNotSame(source.getL1(), copied.getL1());
        assertNotSame(source.getL2(), copied.getL2());
        assertNotSame(source.getSync(), copied.getSync());
        assertNotSame(source.getRefresh(), copied.getRefresh());
        assertNotSame(source.getLoader(), copied.getLoader());
        assertNotSame(source.getProtection(), copied.getProtection());

        copied.setDefaultCacheName("mutated");
        copied.getL1().setMaximumSize(1L);
        copied.getL2().setKeyPrefix("mutated:");
        copied.getSync().setTopicPrefix("mutated:topic:");
        copied.getRefresh().setDefaultRefreshIntervalSeconds(1L);
        copied.getLoader().setTimeoutSeconds(1L);
        copied.getProtection().setMaxTrackedKeys(1);

        assertEquals("orders", source.getDefaultCacheName());
        assertEquals(2048L, source.getL1().getMaximumSize());
        assertEquals("biz:", source.getL2().getKeyPrefix());
        assertEquals("sync:test:", source.getSync().getTopicPrefix());
        assertEquals(45L, source.getRefresh().getDefaultRefreshIntervalSeconds());
        assertEquals(66L, source.getLoader().getTimeoutSeconds());
        assertEquals(999, source.getProtection().getMaxTrackedKeys());
        assertTrue(source.getSync().isUpdateEnabled(), "源对象不应被深拷贝结果反向污染");
    }
}
