package cc.coderm.cascade.bloom.initializer;

import cc.coderm.cascade.bloom.config.BloomFilterProperties;
import cc.coderm.cascade.bloom.core.BloomFilterManager;
import cc.coderm.cascade.bloom.core.CascadeBloomFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * BloomFilterStartupInitializer 初始化行为测试
 *
 * @author lionel lionelk@163.com
 * =============================
 * Date: 2026-04-10
 * Time: 23:45:00
 * =============================
 */
@ExtendWith(MockitoExtension.class)
class BloomFilterStartupInitializerTest {

    @Mock
    private BloomFilterManager bloomFilterManager;

    @Mock
    private CascadeBloomFilter<String> bloomFilter;

    @Test
    void testWaitForInitializationEnabled_ShouldBlockUntilComplete() {
        // 准备测试数据
        BloomFilterProperties properties = new BloomFilterProperties();
        properties.setEnabled(true);
        properties.setWaitForInitialization(true);

        AtomicBoolean initializationCompleted = new AtomicBoolean(false);

        List<BloomFilterInitializer> initializers = new ArrayList<>();
        initializers.add(new BloomFilterInitializer() {
            @Override
            public String filterName() {
                return "test-filter";
            }

            @Override
            public void initialize(CascadeBloomFilter<String> filter) {
                try {
                    // 模拟耗时初始化
                    Thread.sleep(100);
                    initializationCompleted.set(true);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });

        when(bloomFilterManager.getFilter(anyString())).thenAnswer(invocation -> {
            return bloomFilter;
        });

        // 执行测试
        BloomFilterStartupInitializer initializer =
            new BloomFilterStartupInitializer(bloomFilterManager, properties, initializers);

        long startTime = System.currentTimeMillis();
        initializer.onApplicationEvent(null);
        long elapsedTime = System.currentTimeMillis() - startTime;

        // 验证结果
        assertTrue(initializationCompleted.get(), "初始化应该已完成");
        assertTrue(elapsedTime >= 100, "应该等待初始化完成，耗时至少100ms");
        verify(bloomFilterManager, times(1)).getFilter("test-filter");
    }

    @Test
    void testWaitForInitializationDisabled_ShouldNotBlock() throws Exception {
        // 准备测试数据
        BloomFilterProperties properties = new BloomFilterProperties();
        properties.setEnabled(true);
        properties.setWaitForInitialization(false);

        AtomicBoolean initializationStarted = new AtomicBoolean(false);

        List<BloomFilterInitializer> initializers = new ArrayList<>();
        initializers.add(new BloomFilterInitializer() {
            @Override
            public String filterName() {
                return "test-filter";
            }

            @Override
            public void initialize(CascadeBloomFilter<String> filter) {
                initializationStarted.set(true);
                try {
                    // 模拟耗时初始化
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });

        when(bloomFilterManager.getFilter(anyString())).thenAnswer(invocation -> {
            return bloomFilter;
        });

        // 执行测试
        BloomFilterStartupInitializer initializer =
            new BloomFilterStartupInitializer(bloomFilterManager, properties, initializers);

        long startTime = System.currentTimeMillis();
        initializer.onApplicationEvent(null);
        long elapsedTime = System.currentTimeMillis() - startTime;

        // 验证结果：主线程应该快速返回
        assertTrue(elapsedTime < 200, "不应该等待初始化完成，应该快速返回");

        // 等待确认初始化已启动（异步执行）
        Thread.sleep(100);
        assertTrue(initializationStarted.get(), "初始化应该已启动（异步）");
    }

    @Test
    void testDisabledModule_ShouldSkipInitialization() {
        // 准备测试数据
        BloomFilterProperties properties = new BloomFilterProperties();
        properties.setEnabled(false);

        List<BloomFilterInitializer> initializers = new ArrayList<>();
        initializers.add(new BloomFilterInitializer() {
            @Override
            public String filterName() {
                return "error-filter";
            }

            @Override
            public void initialize(CascadeBloomFilter<String> filter) {
                throw new RuntimeException("不应该执行初始化");
            }
        });

        // 执行测试
        BloomFilterStartupInitializer initializer =
            new BloomFilterStartupInitializer(bloomFilterManager, properties, initializers);

        initializer.onApplicationEvent(null);

        // 验证结果：不应该调用 bloomFilterManager
        verify(bloomFilterManager, never()).getFilter(anyString());
    }

    @Test
    void testEmptyInitializers_ShouldHandleGracefully() {
        // 准备测试数据
        BloomFilterProperties properties = new BloomFilterProperties();
        properties.setEnabled(true);
        properties.setWaitForInitialization(true);

        List<BloomFilterInitializer> initializers = new ArrayList<>();

        // 执行测试：不应该抛出异常
        BloomFilterStartupInitializer initializer =
            new BloomFilterStartupInitializer(bloomFilterManager, properties, initializers);

        assertDoesNotThrow(() -> initializer.onApplicationEvent(null));
    }
}