package io.github.cascade.cache.v2.engine;

import io.github.cascade.cache.v2.common.exception.CacheConfigurationException;
import io.github.cascade.cache.v2.loader.DistLockCoordinator;
import io.github.cascade.cache.v2.loader.SingleFlight;
import io.github.cascade.cache.v2.policy.CachePolicy;
import io.github.cascade.cache.v2.policy.LockFailureStrategy;
import io.github.cascade.cache.v2.store.l2.L2CacheStore;
import io.github.cascade.cache.v2.store.model.CacheRecord;
import org.slf4j.Logger;

import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * EngineBackedCache 写入与回源链路逻辑。
 */
final class EngineBackedCacheWrite<K, V> {

    private final Logger logger;
    private final String cacheName;
    private final String nodeId;
    private final CachePolicy policy;
    private final L2CacheStore<K, V> l2Store;
    private final DistLockCoordinator<K> lockCoordinator;
    private final SingleFlight<K, V> singleFlight;
    private final WritePipeline<V> writePipeline;
    private final Supplier<Function<K, V>> loaderSupplier;
    private final Function<K, Long> nextVersion;
    private final Function<K, Optional<CacheRecord<V>>> readFromL2;
    private final Predicate<CacheRecord<V>> isHardExpired;
    private final BiConsumer<K, CacheRecord<V>> publishWriteEvent;
    private final BiConsumer<K, CacheRecord<V>> writeBackL1;
    private final Runnable markBackfillL2;
    private final Runnable markSingleFlightJoin;
    private final Runnable markDistLockDegrade;

    EngineBackedCacheWrite(Logger logger,
                           String cacheName,
                           String nodeId,
                           CachePolicy policy,
                           L2CacheStore<K, V> l2Store,
                           DistLockCoordinator<K> lockCoordinator,
                           SingleFlight<K, V> singleFlight,
                           WritePipeline<V> writePipeline,
                           Supplier<Function<K, V>> loaderSupplier,
                           Function<K, Long> nextVersion,
                           Function<K, Optional<CacheRecord<V>>> readFromL2,
                           Predicate<CacheRecord<V>> isHardExpired,
                           BiConsumer<K, CacheRecord<V>> publishWriteEvent,
                           BiConsumer<K, CacheRecord<V>> writeBackL1,
                           Runnable markBackfillL2,
                           Runnable markSingleFlightJoin,
                           Runnable markDistLockDegrade) {
        this.logger = logger;
        this.cacheName = cacheName;
        this.nodeId = nodeId;
        this.policy = policy;
        this.l2Store = l2Store;
        this.lockCoordinator = lockCoordinator;
        this.singleFlight = singleFlight;
        this.writePipeline = writePipeline;
        this.loaderSupplier = loaderSupplier;
        this.nextVersion = nextVersion;
        this.readFromL2 = readFromL2;
        this.isHardExpired = isHardExpired;
        this.publishWriteEvent = publishWriteEvent;
        this.writeBackL1 = writeBackL1;
        this.markBackfillL2 = markBackfillL2;
        this.markSingleFlightJoin = markSingleFlightJoin;
        this.markDistLockDegrade = markDistLockDegrade;
    }

    void writeThrough(K key, V value, long ttlSeconds) {
        long now = System.currentTimeMillis();
        long hardTtl = ttlSeconds > 0 ? ttlSeconds : policy.getHardTtlSeconds();
        long softTtl = policy.getSoftTtlSeconds() > 0 ? policy.getSoftTtlSeconds() : Math.max(1, hardTtl / 3);
        long version = nextVersion.apply(key);

        CacheRecord<V> record = writePipeline.createRecord(value, version, now, hardTtl, softTtl, nodeId);
        if (l2Store != null) {
            l2Store.put(key, record, hardTtl);
            markBackfillL2.run();
        }
        publishWriteEvent.accept(key, record);
        writeBackL1.accept(key, record);
    }

    V loadAndWriteBack(K key, long ttlSeconds) {
        if (loaderSupplier.get() == null) {
            return null;
        }

        if (policy.isSingleFlightEnabled()) {
            return singleFlight.execute(key, () -> {
                markSingleFlightJoin.run();
                return loadWithProtection(key, ttlSeconds);
            });
        }
        return loadWithProtection(key, ttlSeconds);
    }

    V invokeLoaderAndWrite(K key, Long ttlSeconds) {
        Function<K, V> loadFunction = loaderSupplier.get();
        if (loadFunction == null) {
            return null;
        }
        long effectiveTtl = ttlSeconds != null ? ttlSeconds : policy.getHardTtlSeconds();
        try {
            V loaded = loadFunction.apply(key);
            if (loaded != null) {
                writeThrough(key, loaded, effectiveTtl);
            }
            return loaded;
        } catch (CacheConfigurationException e) {
            throw e;
        } catch (Exception e) {
            logger.warn("加载器执行失败: cache={}, key={}, error={}", cacheName, key, e.getMessage());
            return null;
        }
    }

    private V loadWithProtection(K key, long ttlSeconds) {
        if (!policy.isDistributedLockEnabled()) {
            return invokeLoaderAndWrite(key, ttlSeconds);
        }
        DistLockCoordinator.LockResult<V> result = lockCoordinator.withLock(
                cacheName,
                key,
                policy.getDistributedLockWaitMs(),
                policy.getDistributedLockLeaseMs(),
                () -> loadWithL2RecheckThenSource(key, ttlSeconds)
        );
        if (result.outcome() == DistLockCoordinator.Outcome.ACQUIRED) {
            return result.value();
        }

        markDistLockDegrade.run();
        if (policy.getLockFailureStrategy() == LockFailureStrategy.STRICT) {
            if (result.error() != null) {
                logger.warn("分布式锁异常且采用STRICT策略，放弃加载: cache={}, key={}, error={}",
                        cacheName, key, result.error().getMessage());
            }
            return null;
        }
        return loadWithL2RecheckThenSource(key, ttlSeconds);
    }

    private V loadWithL2RecheckThenSource(K key, long ttlSeconds) {
        Optional<CacheRecord<V>> latest = readFromL2.apply(key);
        if (latest.isPresent() && !isHardExpired.test(latest.get())) {
            writeBackL1.accept(key, latest.get());
            return latest.get().getValue();
        }
        return invokeLoaderAndWrite(key, ttlSeconds);
    }
}
