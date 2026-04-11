package cc.coderm.cascade.bloom.initializer;

import cc.coderm.cascade.bloom.config.BloomFilterProperties;
import cc.coderm.cascade.bloom.core.BloomFilterManager;
import cc.coderm.cascade.bloom.core.CascadeBloomFilter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.util.CollectionUtils;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 布隆过滤器启动初始化处理器
 * <p>
 * 监听 {@link ApplicationReadyEvent}，在 Spring 容器完全就绪后执行：
 * <ol>
 *   <li>根据配置文件中 {@code cascade.bloom.filters} 自动创建预定义过滤器</li>
 *   <li>并行调用所有 {@link BloomFilterInitializer} Bean 完成数据预热</li>
 * </ol>
 *
 * @author lionel lionelk@163.com
 * =============================
 * Date: 2025-01-10
 * Time: 16:00:00
 * =============================
 */
@Slf4j
@RequiredArgsConstructor
public class BloomFilterStartupInitializer implements ApplicationListener<ApplicationReadyEvent> {

    private final BloomFilterManager bloomFilterManager;
    private final BloomFilterProperties properties;
    private final List<BloomFilterInitializer> initializers;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (!properties.isEnabled()) {
            log.info("[cascade-bloom] BloomFilter module is disabled, skipping initialization.");
            return;
        }

        // Step 1: 根据配置创建预定义过滤器
        initPredefinedFilters();

        // Step 2: 并行执行所有自定义初始化器
        runInitializers();
    }

    /**
     * 根据 cascade.bloom.filters 配置批量创建过滤器
     */
    private void initPredefinedFilters() {
        List<BloomFilterProperties.BloomFilterDefinition> filters = properties.getFilters();
        if (CollectionUtils.isEmpty(filters)) {
            return;
        }

        log.info("[cascade-bloom] Initializing {} predefined bloom filter(s)...", filters.size());
        for (BloomFilterProperties.BloomFilterDefinition def : filters) {
            if (def.getName() == null || def.getName().isBlank()) {
                log.warn("[cascade-bloom] Skipped a filter definition with blank name.");
                continue;
            }
            long expectedInsertions = def.getExpectedInsertions() != null
                    ? def.getExpectedInsertions()
                    : properties.getDefaultExpectedInsertions();
            double falseProbability = def.getFalseProbability() != null
                    ? def.getFalseProbability()
                    : properties.getDefaultFalseProbability();

            bloomFilterManager.getOrCreate(def.getName(), expectedInsertions, falseProbability);
        }
        log.info("[cascade-bloom] Predefined bloom filter(s) initialized.");
    }

    /**
     * 并行执行所有 BloomFilterInitializer
     */
    private void runInitializers() {
        if (CollectionUtils.isEmpty(initializers)) {
            return;
        }

        boolean waitForCompletion = properties.isWaitForInitialization();
        log.info("[cascade-bloom] Running {} bloom filter initializer(s) in parallel, waitForCompletion={}...",
                initializers.size(), waitForCompletion);

        Executor executor = ForkJoinPool.commonPool();
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failedCount = new AtomicInteger();

        List<CompletableFuture<Void>> futures = initializers.stream()
                .map(initializer -> CompletableFuture.runAsync(() -> {
                    String filterName = initializer.filterName();
                    try {
                        log.info("[cascade-bloom] Starting initializer for filter [{}]", filterName);
                        CascadeBloomFilter<String> filter = bloomFilterManager.getFilter(filterName);
                        initializer.initialize(filter);
                        successCount.incrementAndGet();
                        log.info("[cascade-bloom] Initializer for filter [{}] completed. count={}",
                                filterName, filter.count());
                    } catch (Exception e) {
                        failedCount.incrementAndGet();
                        log.error("[cascade-bloom] Initializer for filter [{}] failed: {}",
                                filterName, e.getMessage(), e);
                    }
                }, executor))
                .toList();

        // 根据配置决定是否等待初始化完成
        if (waitForCompletion) {
            // 阻塞等待所有初始化器完成，确保数据一致性
            try {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get();
                log.info("[cascade-bloom] All bloom filter initializers completed synchronously. success={}, failed={}",
                        successCount.get(), failedCount.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("[cascade-bloom] Bloom filter initialization interrupted", e);
            } catch (Exception e) {
                log.error("[cascade-bloom] Bloom filter initialization failed with exception", e);
            }
        } else {
            // 异步非阻塞：异步汇总初始化结果，不阻塞启动线程
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .whenComplete((unused, throwable) -> {
                        if (throwable != null) {
                            log.error("[cascade-bloom] Bloom filter initializer completion callback failed: {}",
                                    throwable.getMessage(), throwable);
                        }
                        log.info("[cascade-bloom] All bloom filter initializers completed asynchronously. success={}, failed={}",
                                successCount.get(), failedCount.get());
                    });

            log.info("[cascade-bloom] Bloom filter initializers submitted asynchronously; startup thread will not wait.");
        }
    }
}
