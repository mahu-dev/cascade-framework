package io.github.cascade.cache.v2.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cascade.cache.v2.consistency.InvalidationBus;
import io.github.cascade.cache.v2.consistency.InvalidationEvent;
import io.github.cascade.cache.v2.observability.CacheMetricsCollector;
import io.github.cascade.cache.v2.policy.CachePolicy;
import io.github.cascade.cache.v2.policy.SyncMode;
import io.github.cascade.cache.v2.store.l1.L1CacheStore;
import io.github.cascade.cache.v2.store.model.CacheRecord;
import org.slf4j.Logger;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * EngineBackedCache 同步发布/消费逻辑。
 */
final class EngineBackedCacheSync<K, V> {

    private static final String UPDATE_PAYLOAD_CODEC = "jackson-json-v1";

    private final Logger logger;
    private final String cacheName;
    private final CachePolicy policy;
    private final InvalidationBus<K> invalidationBus;
    private final String nodeId;
    private final Class<V> valueType;
    private final ObjectMapper objectMapper;
    private final L1CacheStore<K, V> l1Store;
    private final AtomicLong clearVersion;
    private final Set<K> trackedKeys;
    private final com.github.benmanes.caffeine.cache.Cache<K, AtomicLong> readCounter;
    private final com.github.benmanes.caffeine.cache.Cache<K, Long> localVersion;
    private final AtomicLong eventLagMs;
    private final AtomicLong droppedEvents;
    private final CacheMetricsCollector metricsCollector;
    private final Function<K, Optional<CacheRecord<V>>> readFromL1;
    private final BiConsumer<K, Boolean> pruneTrackingState;
    private final Runnable markSyncPublish;
    private final Runnable markSyncConsume;
    private final Runnable markSyncUpdateFallback;
    private final Runnable markBackfillL1;

    EngineBackedCacheSync(Logger logger,
                          String cacheName,
                          CachePolicy policy,
                          InvalidationBus<K> invalidationBus,
                          String nodeId,
                          Class<V> valueType,
                          ObjectMapper objectMapper,
                          L1CacheStore<K, V> l1Store,
                          AtomicLong clearVersion,
                          Set<K> trackedKeys,
                          com.github.benmanes.caffeine.cache.Cache<K, AtomicLong> readCounter,
                          com.github.benmanes.caffeine.cache.Cache<K, Long> localVersion,
                          AtomicLong eventLagMs,
                          AtomicLong droppedEvents,
                          CacheMetricsCollector metricsCollector,
                          Function<K, Optional<CacheRecord<V>>> readFromL1,
                          BiConsumer<K, Boolean> pruneTrackingState,
                          Runnable markSyncPublish,
                          Runnable markSyncConsume,
                          Runnable markSyncUpdateFallback,
                          Runnable markBackfillL1) {
        this.logger = logger;
        this.cacheName = cacheName;
        this.policy = policy;
        this.invalidationBus = invalidationBus;
        this.nodeId = nodeId;
        this.valueType = valueType;
        this.objectMapper = objectMapper;
        this.l1Store = l1Store;
        this.clearVersion = clearVersion;
        this.trackedKeys = trackedKeys;
        this.readCounter = readCounter;
        this.localVersion = localVersion;
        this.eventLagMs = eventLagMs;
        this.droppedEvents = droppedEvents;
        this.metricsCollector = metricsCollector;
        this.readFromL1 = readFromL1;
        this.pruneTrackingState = pruneTrackingState;
        this.markSyncPublish = markSyncPublish;
        this.markSyncConsume = markSyncConsume;
        this.markSyncUpdateFallback = markSyncUpdateFallback;
        this.markBackfillL1 = markBackfillL1;
    }

    void startSyncIfNeeded(AtomicBoolean subscribed) {
        if (policy.getSyncMode() == SyncMode.NONE) {
            return;
        }
        try {
            invalidationBus.start();
            invalidationBus.subscribe(cacheName, this::handleInvalidationEvent);
            subscribed.set(true);
        } catch (Exception e) {
            logger.warn("启动同步总线失败，降级为本地模式: cache={}, error={}", cacheName, e.getMessage());
        }
    }

    void publishInvalidation(K key, long version) {
        if (policy.getSyncMode() == SyncMode.NONE) {
            return;
        }
        markSyncPublish.run();
        invalidationBus.publishInvalidation(cacheName, key, version, nodeId)
                .exceptionally(throwable -> {
                    logger.debug("发布失效事件失败: cache={}, key={}, error={}", cacheName, key, throwable.getMessage());
                    return null;
                });
    }

    void publishWriteEvent(K key, CacheRecord<V> record) {
        if (shouldPublishUpdate(record)) {
            publishUpdate(key, record);
        } else {
            publishInvalidation(key, record != null ? record.getVersion() : 0L);
        }
    }

    void publishClear(long version) {
        if (policy.getSyncMode() == SyncMode.NONE) {
            return;
        }
        markSyncPublish.run();
        invalidationBus.publishClear(cacheName, version, nodeId)
                .exceptionally(throwable -> {
                    logger.debug("发布清空事件失败: cache={}, error={}", cacheName, throwable.getMessage());
                    return null;
                });
    }

    private void publishUpdate(K key, CacheRecord<V> record) {
        if (policy.getSyncMode() == SyncMode.NONE || record == null) {
            return;
        }
        markSyncPublish.run();
        invalidationBus.publishUpdate(cacheName, key, record, valueType.getName(), nodeId)
                .exceptionally(throwable -> {
                    logger.debug("发布更新事件失败: cache={}, key={}, error={}", cacheName, key, throwable.getMessage());
                    return null;
                });
    }

    private boolean shouldPublishUpdate(CacheRecord<V> record) {
        if (policy.getSyncMode() != SyncMode.UPDATE || !policy.isSyncUpdateEnabled() || record == null) {
            return false;
        }
        Object value = record.getValue();
        if (value == null) {
            markSyncUpdateFallback.run();
            return false;
        }
        try {
            int payloadBytes = objectMapper.writeValueAsBytes(value).length;
            if (payloadBytes > policy.getSyncUpdateMaxPayloadBytes()) {
                markSyncUpdateFallback.run();
                logger.debug("UPDATE事件payload超限，降级为INVALIDATE: cache={}, keyPayloadBytes={}, limit={}",
                        cacheName, payloadBytes, policy.getSyncUpdateMaxPayloadBytes());
                return false;
            }
            return true;
        } catch (Exception e) {
            markSyncUpdateFallback.run();
            logger.debug("UPDATE事件payload序列化失败，降级为INVALIDATE: cache={}, error={}", cacheName, e.getMessage());
            return false;
        }
    }

    void handleInvalidationEvent(InvalidationEvent<K> event) {
        if (event == null || event.getNodeId() == null || event.getNodeId().equals(nodeId)) {
            return;
        }
        markSyncConsume.run();
        if (event.getTimestamp() > 0) {
            long lag = Math.max(0L, System.currentTimeMillis() - event.getTimestamp());
            eventLagMs.set(lag);
            metricsCollector.recordEventLag(lag);
        }
        if (event.getOperation() == InvalidationEvent.Operation.CLEAR) {
            long incomingClearVersion = event.getVersion();
            long currentClearVersion = clearVersion.get();
            if (incomingClearVersion <= currentClearVersion) {
                droppedEvents.incrementAndGet();
                return;
            }
            clearVersion.set(incomingClearVersion);
            if (l1Store != null) {
                l1Store.clear();
            }
            trackedKeys.clear();
            readCounter.invalidateAll();
            localVersion.invalidateAll();
            return;
        }
        K key = event.getKey();
        if (key == null) {
            return;
        }

        long incoming = event.getVersion();
        long current = readFromL1.apply(key).map(CacheRecord::getVersion)
                .orElseGet(() -> Optional.ofNullable(localVersion.getIfPresent(key)).orElse(0L));
        if (incoming <= current) {
            droppedEvents.incrementAndGet();
            return;
        }

        localVersion.put(key, incoming);
        if (event.getOperation() == InvalidationEvent.Operation.UPDATE) {
            applyUpdateOrInvalidate(key, event);
            return;
        }

        if (l1Store != null) {
            l1Store.evict(key);
        }
        pruneTrackingState.accept(key, false);
    }

    private void applyUpdateOrInvalidate(K key, InvalidationEvent<K> event) {
        InvalidationEvent.UpdatePayload payload = event.getUpdatePayload();
        if (payload == null || l1Store == null) {
            fallbackInvalidateOnUpdate(key, "payload为空或L1不可用");
            return;
        }
        if (payload.getCodec() == null || !UPDATE_PAYLOAD_CODEC.equals(payload.getCodec())) {
            fallbackInvalidateOnUpdate(key, "payload codec不支持: " + payload.getCodec());
            return;
        }
        String eventType = event.getValueTypeName();
        if (eventType == null || eventType.isBlank()) {
            fallbackInvalidateOnUpdate(key, "valueType缺失");
            return;
        }
        if (valueType != Object.class
                && !valueType.getName().equals(eventType)) {
            fallbackInvalidateOnUpdate(key, "valueType不匹配: event=" + eventType + ", local=" + valueType.getName());
            return;
        }
        try {
            String valuePayload = payload.getValuePayload();
            if (valuePayload == null || valuePayload.isBlank()) {
                fallbackInvalidateOnUpdate(key, "payload.value为空");
                return;
            }
            V typedValue = decodeUpdateValue(valuePayload, eventType);
            if (typedValue == null) {
                fallbackInvalidateOnUpdate(key, "payload.value为空");
                return;
            }
            CacheRecord<V> casted = new CacheRecord<>(
                    typedValue,
                    event.getVersion(),
                    payload.getWriteTimeMs(),
                    payload.getSoftExpireAtMs(),
                    payload.getHardExpireAtMs(),
                    payload.getSourceNodeId()
            );
            l1Store.put(key, casted);
            markBackfillL1.run();
        } catch (Exception e) {
            fallbackInvalidateOnUpdate(key, "payload转换失败: " + e.getMessage());
        }
    }

    private void fallbackInvalidateOnUpdate(K key, String reason) {
        markSyncUpdateFallback.run();
        if (l1Store != null) {
            l1Store.evict(key);
        }
        logger.debug("UPDATE事件降级为INVALIDATE: cache={}, key={}, reason={}", cacheName, key, reason);
    }

    @SuppressWarnings("unchecked")
    private V decodeUpdateValue(String valuePayload, String eventTypeName) throws Exception {
        if (valueType == Object.class) {
            if (eventTypeName != null && !eventTypeName.isBlank()) {
                try {
                    Class<?> declaredType = Class.forName(eventTypeName);
                    Object value = objectMapper.readValue(valuePayload, declaredType);
                    return (V) value;
                } catch (ClassNotFoundException ignored) {
                    // 保底按Object反序列化，避免类型缺失导致整个更新失败
                }
            }
            return (V) objectMapper.readValue(valuePayload, Object.class);
        }
        Object raw = objectMapper.readValue(valuePayload, Object.class);
        if (valueType.isInstance(raw)) {
            return valueType.cast(raw);
        }
        return objectMapper.convertValue(raw, valueType);
    }
}
