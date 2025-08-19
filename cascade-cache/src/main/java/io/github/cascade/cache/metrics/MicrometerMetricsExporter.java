package io.github.cascade.cache.metrics;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Micrometer指标导出器 预留功能
 * 将缓存指标导出到Micrometer监控系统
 *
 * @author Cascade Framework
 */
public class MicrometerMetricsExporter implements CacheMetricsCollector.MetricsListener {

    private final MeterRegistry meterRegistry;
    private final Map<String, CacheMetricsCollector> collectors;
    private final Map<String, MeterSet> meterSets;
    private final ScheduledExecutorService scheduler;
    private final Duration updateInterval;
    private final Set<String> tags;
    private final boolean enableDetailedMetrics;
    private final boolean enableTimeWindowMetrics;
    private final boolean enableTierMetrics;

    public MicrometerMetricsExporter(MeterRegistry meterRegistry) {
        this(meterRegistry, new Builder());
    }

    private MicrometerMetricsExporter(MeterRegistry meterRegistry, Builder builder) {
        this.meterRegistry = meterRegistry;
        this.collectors = new ConcurrentHashMap<>();
        this.meterSets = new ConcurrentHashMap<>();
        this.scheduler = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "micrometer-cache-metrics");
            t.setDaemon(true);
            return t;
        });
        this.updateInterval = builder.updateInterval;
        this.tags = new CopyOnWriteArraySet<>(builder.tags);
        this.enableDetailedMetrics = builder.enableDetailedMetrics;
        this.enableTimeWindowMetrics = builder.enableTimeWindowMetrics;
        this.enableTierMetrics = builder.enableTierMetrics;

        // 启动定期更新任务
        scheduler.scheduleAtFixedRate(
                this::updateMetrics,
                updateInterval.toMillis(),
                updateInterval.toMillis(),
                TimeUnit.MILLISECONDS
        );
    }

    /**
     * 注册缓存指标收集器
     *
     * @param collector 指标收集器
     */
    public void registerCollector(CacheMetricsCollector collector) {
        String cacheName = collector.getStats().getCacheName();
        collectors.put(cacheName, collector);
        collector.addListener(this);

        // 创建指标集合
        MeterSet meterSet = createMeterSet(cacheName, collector);
        meterSets.put(cacheName, meterSet);
    }

    /**
     * 注销缓存指标收集器
     *
     * @param cacheName 缓存名称
     */
    public void unregisterCollector(String cacheName) {
        CacheMetricsCollector collector = collectors.remove(cacheName);
        if (collector != null) {
            collector.removeListener(this);
        }

        MeterSet meterSet = meterSets.remove(cacheName);
        if (meterSet != null) {
            meterSet.close();
        }
    }

    /**
     * 关闭导出器
     */
    public void close() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }

        meterSets.values().forEach(MeterSet::close);
        meterSets.clear();
        collectors.clear();
    }

    @Override
    public void onMetricsEvent(String cacheName, CacheMetricsCollector.MetricsEvent event) {
        // 实时事件处理（如果需要）
    }

    /**
     * 更新所有指标
     */
    private void updateMetrics() {
        for (Map.Entry<String, CacheMetricsCollector> entry : collectors.entrySet()) {
            String cacheName = entry.getKey();
            CacheMetricsCollector collector = entry.getValue();
            MeterSet meterSet = meterSets.get(cacheName);

            if (meterSet != null) {
                DetailedCacheMetrics stats = collector.getStats();
                meterSet.update(stats);
            }
        }
    }

    /**
     * 创建指标集合
     */
    private MeterSet createMeterSet(String cacheName, CacheMetricsCollector collector) {
        return new MeterSet(cacheName, meterRegistry, tags,
                enableDetailedMetrics, enableTimeWindowMetrics, enableTierMetrics);
    }

    /**
     * 指标集合
     */
    private static class MeterSet {
        private final String cacheName;
        private final MeterRegistry registry;
        private final Set<String> tags;
        private final boolean enableDetailedMetrics;
        private final boolean enableTimeWindowMetrics;
        private final boolean enableTierMetrics;

        // 基础指标
        private final AtomicReference<Double> hitRate;
        private final AtomicReference<Double> missRate;
        private final AtomicReference<Long> hitCount;
        private final AtomicReference<Long> missCount;
        private final AtomicReference<Long> loadCount;
        private final AtomicReference<Double> loadSuccessRate;
        private final AtomicReference<Double> averageLoadTime;
        private final AtomicReference<Long> evictionCount;
        private final AtomicReference<Long> currentSize;

        // 详细指标
        private final AtomicReference<Long> putCount;
        private final AtomicReference<Long> removeCount;
        private final AtomicReference<Long> maxLoadTime;
        private final AtomicReference<Long> minLoadTime;
        private final AtomicReference<Long> maxSize;
        private final AtomicReference<Double> throughput;
        private final AtomicReference<Double> loadThroughput;
        private final AtomicReference<Double> evictionRate;

        public MeterSet(String cacheName, MeterRegistry registry, Set<String> globalTags,
                        boolean enableDetailedMetrics, boolean enableTimeWindowMetrics,
                        boolean enableTierMetrics) {
            this.cacheName = cacheName;
            this.registry = registry;
            this.tags = globalTags;
            this.enableDetailedMetrics = enableDetailedMetrics;
            this.enableTimeWindowMetrics = enableTimeWindowMetrics;
            this.enableTierMetrics = enableTierMetrics;

            // 初始化基础指标
            this.hitRate = new AtomicReference<>(0.0);
            this.missRate = new AtomicReference<>(0.0);
            this.hitCount = new AtomicReference<>(0L);
            this.missCount = new AtomicReference<>(0L);
            this.loadCount = new AtomicReference<>(0L);
            this.loadSuccessRate = new AtomicReference<>(0.0);
            this.averageLoadTime = new AtomicReference<>(0.0);
            this.evictionCount = new AtomicReference<>(0L);
            this.currentSize = new AtomicReference<>(0L);

            // 初始化详细指标
            this.putCount = new AtomicReference<>(0L);
            this.removeCount = new AtomicReference<>(0L);
            this.maxLoadTime = new AtomicReference<>(0L);
            this.minLoadTime = new AtomicReference<>(0L);
            this.maxSize = new AtomicReference<>(0L);
            this.throughput = new AtomicReference<>(0.0);
            this.loadThroughput = new AtomicReference<>(0.0);
            this.evictionRate = new AtomicReference<>(0.0);

            registerMeters();
        }

        /**
         * 注册指标
         */
        private void registerMeters() {
            String[] tagArray = createTagArray();

            // 基础指标
            createGauge("cache.hit.rate", "Cache hit rate", () -> hitRate.get(), tagArray);
            createGauge("cache.miss.rate", "Cache miss rate", () -> missRate.get(), tagArray);
            createGauge("cache.requests", "Cache hit count", () -> hitCount.get().doubleValue(), tagArray, "result", "hit");
            createGauge("cache.requests", "Cache miss count", () -> missCount.get().doubleValue(), tagArray, "result", "miss");
            createGauge("cache.loads", "Cache load count", () -> loadCount.get().doubleValue(), tagArray);
            createGauge("cache.load.success.rate", "Cache load success rate", () -> loadSuccessRate.get(), tagArray);
            createGauge("cache.load.time.average", "Average cache load time", () -> averageLoadTime.get(), tagArray);
            createGauge("cache.evictions", "Cache eviction count", () -> evictionCount.get().doubleValue(), tagArray);
            createGauge("cache.size", "Current cache size", () -> currentSize.get().doubleValue(), tagArray);

            if (enableDetailedMetrics) {
                createGauge("cache.puts", "Cache put count", () -> putCount.get().doubleValue(), tagArray);
                createGauge("cache.removals", "Cache removal count", () -> removeCount.get().doubleValue(), tagArray);
                createGauge("cache.load.time.max", "Max cache load time", () -> maxLoadTime.get().doubleValue(), tagArray);
                createGauge("cache.load.time.min", "Min cache load time", () -> minLoadTime.get().doubleValue(), tagArray);
                createGauge("cache.size.max", "Max cache size", () -> maxSize.get().doubleValue(), tagArray);
                createGauge("cache.throughput", "Cache throughput", () -> throughput.get(), tagArray);
                createGauge("cache.load.throughput", "Cache load throughput", () -> loadThroughput.get(), tagArray);
                createGauge("cache.eviction.rate", "Cache eviction rate", () -> evictionRate.get(), tagArray);
            }
        }

        /**
         * 创建标签数组
         */
        private String[] createTagArray(String... additionalTags) {
            String[] baseTagArray = {"cache", cacheName};
            String[] allTags = new String[baseTagArray.length + tags.size() * 2 + additionalTags.length];

            System.arraycopy(baseTagArray, 0, allTags, 0, baseTagArray.length);

            int index = baseTagArray.length;
            for (String tag : tags) {
                String[] parts = tag.split("=", 2);
                if (parts.length == 2) {
                    allTags[index++] = parts[0];
                    allTags[index++] = parts[1];
                }
            }

            System.arraycopy(additionalTags, 0, allTags, index, additionalTags.length);

            return allTags;
        }

        /**
         * 创建仪表指标
         */
        private void createGauge(String name, String description, Supplier<Double> valueFunction, String[] tags, String... additionalTags) {
            String[] allTags = new String[tags.length + additionalTags.length];
            System.arraycopy(tags, 0, allTags, 0, tags.length);
            System.arraycopy(additionalTags, 0, allTags, tags.length, additionalTags.length);

            // 使用反射创建Gauge，避免直接依赖Micrometer
            try {
                Class<?> gaugeClass = Class.forName("io.micrometer.core.instrument.Gauge");
                Class<?> builderClass = Class.forName("io.micrometer.core.instrument.Gauge$Builder");

                Object builder = gaugeClass.getMethod("builder", String.class).invoke(null, name);
                builder = builderClass.getMethod("description", String.class).invoke(builder, description);
                builder = builderClass.getMethod("tags", String[].class).invoke(builder, (Object) allTags);
                builderClass.getMethod("register", Class.forName("io.micrometer.core.instrument.MeterRegistry"))
                        .invoke(builder, registry);
            } catch (Exception e) {
                // 如果Micrometer不可用，忽略错误
            }
        }

        /**
         * 更新指标
         */
        public void update(DetailedCacheMetrics stats) {
            // 更新基础指标
            hitRate.set(stats.getHitRate());
            missRate.set(stats.getMissRate());
            hitCount.set(stats.getHitCount());
            missCount.set(stats.getMissCount());
            loadCount.set(stats.getLoadCount());
            loadSuccessRate.set(stats.getLoadSuccessRate());
            averageLoadTime.set(stats.getAverageLoadTimeMillis());
            evictionCount.set(stats.getEvictionCount());
            currentSize.set(stats.getCurrentSize());

            if (enableDetailedMetrics) {
                putCount.set(stats.getPutCount());
                removeCount.set(stats.getRemoveCount());
                maxLoadTime.set((long) stats.getMaxLoadTimeMillis());
                minLoadTime.set((long) stats.getMinLoadTimeMillis());
                maxSize.set(stats.getMaxSize());
                throughput.set(stats.getThroughput());
                loadThroughput.set(stats.getLoadThroughput());
                evictionRate.set(stats.getEvictionRate());
            }

            // 更新时间窗口指标
            if (enableTimeWindowMetrics) {
                updateTimeWindowMetrics(stats);
            }

            // 更新分层指标
            if (enableTierMetrics) {
                updateTierMetrics(stats);
            }
        }

        /**
         * 更新时间窗口指标
         */
        private void updateTimeWindowMetrics(DetailedCacheMetrics stats) {
            Map<String, CacheMetricsCollector.WindowStats> windowMetrics = stats.getTimeWindowMetrics();
            for (Map.Entry<String, CacheMetricsCollector.WindowStats> entry : windowMetrics.entrySet()) {
                String window = entry.getKey();
                CacheMetricsCollector.WindowStats windowStats = entry.getValue();

                String[] tags = createTagArray("window", window);
                // 这里可以创建时间窗口相关的指标
            }
        }

        /**
         * 更新分层指标
         */
        private void updateTierMetrics(DetailedCacheMetrics stats) {
            Map<String, CacheMetricsCollector.TierMetrics> tierMetrics = stats.getTierMetrics();
            for (Map.Entry<String, CacheMetricsCollector.TierMetrics> entry : tierMetrics.entrySet()) {
                String tier = entry.getKey();
                CacheMetricsCollector.TierMetrics tierStats = entry.getValue();

                String[] tags = createTagArray("tier", tier);
                // 这里可以创建分层相关的指标
            }
        }

        /**
         * 关闭指标集合
         */
        public void close() {
            // 移除注册的指标
            try {
                Class<?> meterRegistryClass = Class.forName("io.micrometer.core.instrument.MeterRegistry");
                meterRegistryClass.getMethod("clear").invoke(registry);
            } catch (Exception e) {
                // 忽略错误
            }
        }
    }

    /**
     * MeterRegistry接口（避免直接依赖Micrometer）
     */
    public interface MeterRegistry {
        // 占位符接口
    }

    /**
     * 构建器
     */
    public static class Builder {
        private Duration updateInterval = Duration.ofSeconds(30);
        private Set<String> tags = new CopyOnWriteArraySet<>();
        private boolean enableDetailedMetrics = true;
        private boolean enableTimeWindowMetrics = false;
        private boolean enableTierMetrics = true;

        public Builder updateInterval(Duration updateInterval) {
            this.updateInterval = updateInterval;
            return this;
        }

        public Builder addTag(String key, String value) {
            this.tags.add(key + "=" + value);
            return this;
        }

        public Builder enableDetailedMetrics(boolean enable) {
            this.enableDetailedMetrics = enable;
            return this;
        }

        public Builder enableTimeWindowMetrics(boolean enable) {
            this.enableTimeWindowMetrics = enable;
            return this;
        }

        public Builder enableTierMetrics(boolean enable) {
            this.enableTierMetrics = enable;
            return this;
        }

        public MicrometerMetricsExporter build(MeterRegistry meterRegistry) {
            return new MicrometerMetricsExporter(meterRegistry, this);
        }
    }

    /**
     * 创建构建器
     */
    public static Builder builder() {
        return new Builder();
    }
}