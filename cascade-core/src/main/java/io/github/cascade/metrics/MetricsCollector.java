package io.github.cascade.metrics;

import java.util.concurrent.TimeUnit;

/**
 * 指标收集器
 */
public interface MetricsCollector {
    
    /**
     * 记录计数
     */
    void recordCount(String name, long value, String... tags);
    
    /**
     * 记录耗时
     */
    void recordTime(String name, long duration, TimeUnit unit, String... tags);
    
    /**
     * 记录仪表值
     */
    void recordGauge(String name, double value, String... tags);
    
    /**
     * 增加计数
     */
    default void increment(String name, String... tags) {
        recordCount(name, 1, tags);
    }
    
    /**
     * 获取快照
     */
    MetricsSnapshot snapshot();
}