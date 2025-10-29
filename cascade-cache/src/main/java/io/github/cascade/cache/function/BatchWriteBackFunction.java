package io.github.cascade.cache.function;

import io.github.cascade.cache.core.Cache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 批量写回Function实现
 * <p>
 * 将多个缓存写操作收集到一个批次中，
 * 定时或达到阈值时统一写入，提高写入效率。
 * <p>
 * 该类从CacheFunctions的内部类提取而来，
 * 解决SonarQube关于内部类过长的警告。
 *
 * @param <K> 键类型
 * @param <V> 值类型
 * @author cascade
 */
final class BatchWriteBackFunction<K, V> implements Function<K, V> {

    private static final Logger LOGGER = LoggerFactory.getLogger(BatchWriteBackFunction.class);

    private final Cache<K, V> cache;
    private final Function<K, V> sourceFunction;
    private final int batchSize;
    private final long delayMs;
    private final Map<K, V> pendingWrites = new ConcurrentHashMap<>();

    public BatchWriteBackFunction(Cache<K, V> cache, Function<K, V> sourceFunction,
                                  int batchSize, long delayMs) {
        this.cache = cache;
        this.sourceFunction = sourceFunction;
        this.batchSize = batchSize;
        this.delayMs = delayMs;

        // 启动定时刷新任务
        startFlushTask();
    }

    @Override
    public V apply(K key) {
        V value = sourceFunction.apply(key);
        if (value != null) {
            pendingWrites.put(key, value);

            // 检查是否需要立即刷新
            if (pendingWrites.size() >= batchSize) {
                flushPendingWrites();
            }
        }
        return value;
    }

    private void startFlushTask() {
        CompletableFuture.runAsync(() -> {
            while (true) {
                try {
                    Thread.sleep(delayMs);
                    if (!pendingWrites.isEmpty()) {
                        flushPendingWrites();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
    }

    private void flushPendingWrites() {
        if (pendingWrites.isEmpty()) {
            return;
        }

        Map<K, V> toFlush = Map.copyOf(pendingWrites);
        pendingWrites.clear();

        try {
            cache.putAll(toFlush);
            LOGGER.debug("批量写回完成: cache={}, size={}", cache.getName(), toFlush.size());
        } catch (Exception e) {
            LOGGER.warn("批量写回失败: cache={}, size={}, error={}",
                    cache.getName(), toFlush.size(), e.getMessage());
            // 失败的数据放回pending队列
            pendingWrites.putAll(toFlush);
        }
    }
}