package cc.coderm.cascade.bloom.initializer;

import cc.coderm.cascade.bloom.config.BloomFilterProperties;
import cc.coderm.cascade.bloom.core.BloomFilterManager;
import cc.coderm.cascade.bloom.core.CascadeBloomFilter;
import cc.coderm.cascade.bloom.exception.BloomFilterInitException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.util.CollectionUtils;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutionException;

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
    private final Executor initializationExecutor;

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
            String filterName = def.getName();
            if (filterName == null || filterName.isBlank()) {
                throw new IllegalStateException(
                        "[cascade-bloom] Invalid filter definition encountered at runtime: name must not be blank"
                );
            }
            long expectedInsertions = def.getExpectedInsertions() != null
                    ? def.getExpectedInsertions()
                    : properties.getDefaultExpectedInsertions();
            double falseProbability = def.getFalseProbability() != null
                    ? def.getFalseProbability()
                    : properties.getDefaultFalseProbability();

            bloomFilterManager.getOrCreate(filterName, expectedInsertions, falseProbability);
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

        List<CompletableFuture<InitializerResult>> futures = initializers.stream()
                .map(initializer -> CompletableFuture.supplyAsync(
                        () -> executeInitializer(initializer),
                        initializationExecutor
                ))
                .toList();

        // 根据配置决定是否等待初始化完成
        if (waitForCompletion) {
            runInitializersSynchronously(futures);
        } else {
            // 异步非阻塞：异步汇总初始化结果，不阻塞启动线程
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .thenApply(unused -> futures.stream().map(CompletableFuture::join).toList())
                    .whenComplete((results, throwable) -> {
                        if (throwable != null) {
                            log.error("[cascade-bloom] Bloom filter initializer completion callback failed: {}",
                                    throwable.getMessage(), throwable);
                            return;
                        }
                        logSummary(results, false);
                    });

            log.info("[cascade-bloom] Bloom filter initializers submitted asynchronously; startup thread will not wait.");
        }
    }

    private InitializerResult executeInitializer(BloomFilterInitializer initializer) {
        String filterName = initializer.filterName();
        try {
            log.info("[cascade-bloom] Starting initializer for filter [{}]", filterName);
            CascadeBloomFilter<String> filter = bloomFilterManager.getFilter(filterName);
            initializer.initialize(filter);
            log.info("[cascade-bloom] Initializer for filter [{}] completed. count={}", filterName, filter.count());
            return InitializerResult.success(filterName);
        } catch (Exception exception) {
            log.error("[cascade-bloom] Initializer for filter [{}] failed: {}",
                    filterName, exception.getMessage(), exception);
            return InitializerResult.failure(filterName, exception);
        }
    }

    private void runInitializersSynchronously(List<CompletableFuture<InitializerResult>> futures) {
        List<InitializerResult> results = awaitResults(futures);
        logSummary(results, true);

        List<InitializerResult> failedResults = results.stream()
                .filter(result -> !result.success())
                .toList();
        if (failedResults.isEmpty()) {
            return;
        }

        throw buildStartupFailure(failedResults);
    }

    private List<InitializerResult> awaitResults(List<CompletableFuture<InitializerResult>> futures) {
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get();
            return futures.stream()
                    .map(CompletableFuture::join)
                    .toList();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BloomFilterInitException("[cascade-bloom] Bloom filter initialization interrupted", e);
        } catch (ExecutionException e) {
            Throwable cause = unwrap(e.getCause());
            throw new BloomFilterInitException("[cascade-bloom] Bloom filter initialization failed unexpectedly", cause);
        }
    }

    private void logSummary(List<InitializerResult> results, boolean sync) {
        long success = results.stream().filter(InitializerResult::success).count();
        long failed = results.size() - success;
        String mode = sync ? "synchronously" : "asynchronously";
        log.info("[cascade-bloom] All bloom filter initializers completed {}. success={}, failed={}",
                mode, success, failed);
    }

    private BloomFilterInitException buildStartupFailure(List<InitializerResult> failedResults) {
        InitializerResult firstFailure = failedResults.get(0);
        String failedFilters = failedResults.stream()
                .map(InitializerResult::filterName)
                .toList()
                .toString();

        BloomFilterInitException exception = new BloomFilterInitException(
                "[cascade-bloom] Bloom filter startup initialization failed. failedInitializers="
                        + failedResults.size() + ", filters=" + failedFilters,
                firstFailure.cause()
        );
        for (int i = 1; i < failedResults.size(); i++) {
            InitializerResult failure = failedResults.get(i);
            exception.addSuppressed(new BloomFilterInitException(
                    "Initializer failed for filter [" + failure.filterName() + "]",
                    failure.cause()
            ));
        }
        return exception;
    }

    private Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static final class InitializerResult {
        private final String filterName;
        private final Throwable cause;

        private InitializerResult(String filterName, Throwable cause) {
            this.filterName = filterName;
            this.cause = cause;
        }

        private static InitializerResult success(String filterName) {
            return new InitializerResult(filterName, null);
        }

        private static InitializerResult failure(String filterName, Throwable cause) {
            return new InitializerResult(filterName, cause);
        }

        private boolean success() {
            return cause == null;
        }

        private String filterName() {
            return filterName;
        }

        private Throwable cause() {
            return cause;
        }
    }
}
