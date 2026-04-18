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

import java.util.ArrayList;
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

        boolean waitForCompletion = properties.isWaitForInitialization();
        CompletableFuture<List<StartupTaskResult>> startupFuture = runStartupInitializationAsync(waitForCompletion);
        if (waitForCompletion) {
            runStartupSynchronously(startupFuture);
            return;
        }

        startupFuture.whenComplete((results, throwable) -> {
            if (throwable != null) {
                log.error("[cascade-bloom] Bloom filter startup initialization callback failed: {}",
                        throwable.getMessage(), throwable);
                return;
            }
            logSummary(results, false);
        });
        log.info("[cascade-bloom] Bloom filter startup initialization submitted asynchronously; startup thread will not wait.");
    }

    private CompletableFuture<List<StartupTaskResult>> runStartupInitializationAsync(boolean waitForCompletion) {
        return runPredefinedFilterInitializationAsync(waitForCompletion)
                .thenCompose(predefinedResults -> runCustomInitializerAsync(waitForCompletion)
                        .thenApply(initializerResults -> mergeResults(predefinedResults, initializerResults)));
    }

    private CompletableFuture<List<StartupTaskResult>> runPredefinedFilterInitializationAsync(boolean waitForCompletion) {
        List<BloomFilterProperties.BloomFilterDefinition> filters = properties.getFilters();
        if (CollectionUtils.isEmpty(filters)) {
            return CompletableFuture.completedFuture(List.of());
        }

        log.info("[cascade-bloom] Initializing {} predefined bloom filter(s) in parallel, waitForCompletion={}...",
                filters.size(), waitForCompletion);

        List<CompletableFuture<StartupTaskResult>> futures = filters.stream()
                .map(definition -> CompletableFuture.supplyAsync(
                        () -> executePredefinedFilterInitialization(definition),
                        initializationExecutor
                ))
                .toList();

        return collectResults(futures);
    }

    private CompletableFuture<List<StartupTaskResult>> runCustomInitializerAsync(boolean waitForCompletion) {
        if (CollectionUtils.isEmpty(initializers)) {
            return CompletableFuture.completedFuture(List.of());
        }

        log.info("[cascade-bloom] Running {} bloom filter initializer(s) in parallel, waitForCompletion={}...",
                initializers.size(), waitForCompletion);

        List<CompletableFuture<StartupTaskResult>> futures = initializers.stream()
                .map(initializer -> CompletableFuture.supplyAsync(
                        () -> executeInitializer(initializer),
                        initializationExecutor
                ))
                .toList();

        return collectResults(futures);
    }

    private StartupTaskResult executePredefinedFilterInitialization(BloomFilterProperties.BloomFilterDefinition definition) {
        String filterName = definition.getName();
        try {
            if (filterName == null || filterName.isBlank()) {
                throw new IllegalStateException("[cascade-bloom] predefined filter name must not be blank");
            }
            long expectedInsertions = definition.getExpectedInsertions() != null
                    ? definition.getExpectedInsertions()
                    : properties.getDefaultExpectedInsertions();
            double falseProbability = definition.getFalseProbability() != null
                    ? definition.getFalseProbability()
                    : properties.getDefaultFalseProbability();

            bloomFilterManager.getOrCreate(filterName, expectedInsertions, falseProbability);
            if (log.isDebugEnabled()) {
                log.debug("[cascade-bloom] Predefined bloom filter [{}] initialized. expectedInsertions={}, falseProbability={}",
                        filterName, expectedInsertions, falseProbability);
            }
            return StartupTaskResult.success(StartupTaskType.PREDEFINED_FILTER, filterName);
        } catch (Exception exception) {
            log.error("[cascade-bloom] Predefined bloom filter [{}] initialization failed: {}",
                    filterName, exception.getMessage(), exception);
            return StartupTaskResult.failure(StartupTaskType.PREDEFINED_FILTER, filterName, exception);
        }
    }

    private StartupTaskResult executeInitializer(BloomFilterInitializer initializer) {
        String filterName = initializer.filterName();
        try {
            log.info("[cascade-bloom] Starting initializer for filter [{}]", filterName);
            CascadeBloomFilter<String> filter = bloomFilterManager.getFilter(filterName);
            initializer.initialize(filter);
            log.info("[cascade-bloom] Initializer for filter [{}] completed. count={}", filterName, filter.count());
            return StartupTaskResult.success(StartupTaskType.INITIALIZER, filterName);
        } catch (Exception exception) {
            log.error("[cascade-bloom] Initializer for filter [{}] failed: {}",
                    filterName, exception.getMessage(), exception);
            return StartupTaskResult.failure(StartupTaskType.INITIALIZER, filterName, exception);
        }
    }

    private CompletableFuture<List<StartupTaskResult>> collectResults(List<CompletableFuture<StartupTaskResult>> futures) {
        if (futures.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(unused -> futures.stream().map(CompletableFuture::join).toList());
    }

    private List<StartupTaskResult> mergeResults(List<StartupTaskResult> predefinedResults,
                                                 List<StartupTaskResult> initializerResults) {
        List<StartupTaskResult> merged = new ArrayList<>(predefinedResults.size() + initializerResults.size());
        merged.addAll(predefinedResults);
        merged.addAll(initializerResults);
        return merged;
    }

    private void runStartupSynchronously(CompletableFuture<List<StartupTaskResult>> startupFuture) {
        List<StartupTaskResult> results = awaitResults(startupFuture);
        logSummary(results, true);

        List<StartupTaskResult> failedResults = results.stream()
                .filter(result -> !result.success())
                .toList();
        if (failedResults.isEmpty()) {
            return;
        }

        throw buildStartupFailure(failedResults);
    }

    private List<StartupTaskResult> awaitResults(CompletableFuture<List<StartupTaskResult>> startupFuture) {
        try {
            return startupFuture.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BloomFilterInitException("[cascade-bloom] Bloom filter initialization interrupted", e);
        } catch (ExecutionException e) {
            Throwable cause = unwrap(e.getCause());
            throw new BloomFilterInitException("[cascade-bloom] Bloom filter initialization failed unexpectedly", cause);
        }
    }

    private void logSummary(List<StartupTaskResult> results, boolean sync) {
        long success = results.stream().filter(StartupTaskResult::success).count();
        long failed = results.size() - success;
        long predefinedFilterTasks = results.stream().filter(result -> result.taskType() == StartupTaskType.PREDEFINED_FILTER).count();
        long customInitializerTasks = results.stream().filter(result -> result.taskType() == StartupTaskType.INITIALIZER).count();
        String mode = sync ? "synchronously" : "asynchronously";
        log.info("[cascade-bloom] Bloom filter startup initialization completed {}. predefinedFilters={}, initializers={}, success={}, failed={}",
                mode, predefinedFilterTasks, customInitializerTasks, success, failed);
    }

    private BloomFilterInitException buildStartupFailure(List<StartupTaskResult> failedResults) {
        StartupTaskResult firstFailure = failedResults.get(0);
        String failedTasks = failedResults.stream()
                .map(StartupTaskResult::describe)
                .toList()
                .toString();

        BloomFilterInitException exception = new BloomFilterInitException(
                "[cascade-bloom] Bloom filter startup initialization failed. failedTasks="
                        + failedResults.size() + ", tasks=" + failedTasks,
                firstFailure.cause()
        );
        for (int i = 1; i < failedResults.size(); i++) {
            StartupTaskResult failure = failedResults.get(i);
            exception.addSuppressed(new BloomFilterInitException(
                    "Startup task failed [" + failure.describe() + "]",
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

    private enum StartupTaskType {
        PREDEFINED_FILTER("predefined-filter"),
        INITIALIZER("initializer");

        private final String label;

        StartupTaskType(String label) {
            this.label = label;
        }
    }

    private static final class StartupTaskResult {
        private final StartupTaskType taskType;
        private final String taskName;
        private final Throwable cause;

        private StartupTaskResult(StartupTaskType taskType, String taskName, Throwable cause) {
            this.taskType = taskType;
            this.taskName = taskName;
            this.cause = cause;
        }

        private static StartupTaskResult success(StartupTaskType taskType, String taskName) {
            return new StartupTaskResult(taskType, taskName, null);
        }

        private static StartupTaskResult failure(StartupTaskType taskType, String taskName, Throwable cause) {
            return new StartupTaskResult(taskType, taskName, cause);
        }

        private boolean success() {
            return cause == null;
        }

        private StartupTaskType taskType() {
            return taskType;
        }

        private String describe() {
            return taskType.label + ":" + taskName;
        }

        private Throwable cause() {
            return cause;
        }
    }
}
